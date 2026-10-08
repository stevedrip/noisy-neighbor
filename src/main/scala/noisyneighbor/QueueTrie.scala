package noisyneighbor

import scala.annotation.tailrec
import scala.collection.immutable.TreeMap
import scodec.bits.BitVector

// Future structure to explore:
//
// When enqueue is None, the queue is draining.
// When dequeue is None, the queue is empty.
//
// final case class QueueTrie[A](
//   children: TreeMap[BitVector, QueueTrie[A]].
//   enqueue: Option[BatchWriter[QueueTrie.Item[A]]],
//   dequeue: Option[BatchReader[QueueTrie.Item[A]]]
// )
//
// This is probably equivalent to
//
// final case class QueueTrie[A](
//   children: TreeMap[BitVector, QueueTrie[A]],
//   queue: Fifo[QueueTrie.Item[A]],
//   draining: Boolean
//   empty: Boolean
// )
//
// I wonder what the fs2 analogues are.

/** A tree of queues. Each queue owns a key prefix, and the children of a queue
  * own longer prefixes. Producers add messages at the root only. A queue reads
  * messages from its front and either forwards them to a child or processes
  * them itself, so the order messages leave a queue in is the order they
  * entered it, and ordering between conflicting messages comes from the shape
  * of the tree rather than from looking at messages.
  *
  * A message is forwarded when its key starts with the prefix of a child that
  * is not draining: it is added to the back of that child and acknowledged in
  * this queue. Otherwise it is one of the queue's own, and it starts once no
  * child that could hold a conflicting message has anything in it. Everything
  * behind an own message that is waiting or has been handed out stays in the
  * queue, so nothing overtakes it.
  *
  * The tree is a pure value, and it works by being asked. Each queue has one
  * consumer at a time, and only that consumer moves the front of its queue:
  * when it asks ([[takeFrom]]), the queue forwards what is at its front into
  * its children, up to its first own message, and hands out a batch of its own
  * messages. A batch is a run of the queue's own messages at its front that can
  * all start together, with no forward between them. Its messages are applied
  * together, in the order they arrived. The consumer acknowledges by offset
  * ([[acknowledge]]), as many messages as it likes, in order.
  *
  * A batch always starts at the front, so asking again gives back whatever has
  * not been acknowledged, and may extend it with messages that have become
  * startable. A consumer that asks again has stopped working on what it was
  * given, so that is also when a message that a split has since given to a
  * child is forwarded rather than handed back. The tree has no notion of
  * failure or time: when to ask again is the consumer's business, and it trusts
  * the offsets it is given.
  *
  * Walks of the tree recurse once per level, and the depth is the number of
  * nested splits, not the length of a key.
  *
  * @param queue
  *   The messages of this queue. The only way to use it is to add to the back,
  *   read from the front, and acknowledge what was read.
  * @param children
  *   Child queues by prefix. No prefix is a prefix of another, so at most one
  *   child can be a prefix of a key. Each prefix extends this queue's own.
  * @param draining
  *   The queue accepts nothing new, from its parent or, for the root, from
  *   producers. A draining child is removed when it has emptied. A draining
  *   queue still processes and forwards what it has.
  */
final case class QueueTrie[A](
  queue: Fifo[QueueTrie.Item[A]],
  children: TreeMap[BitVector, QueueTrie[A]],
  draining: Boolean
):
  import QueueTrie.*

  /** Adds a message at the root. A draining root accepts nothing, which is the
    * one refusal a producer has to hear about, so there is no tree then.
    */
  def enqueue(key: Key, value: A): Option[QueueTrie[A]] =
    Option.unless(draining)(copy(queue = queue.add(Item(key, value))))

  /** Whether this queue and every queue below it hold nothing. A message that
    * is handed out and not acknowledged is still held.
    */
  def isEmpty: Boolean =
    queue.isEmpty && children.forall((_, child) => child.isEmpty)

  /** The prefix of this queue and of every queue below it, given that this
    * queue is `prefix`.
    */
  def queues(prefix: Key = Key.root): List[Key] =
    prefix :: children.toList.flatMap((p, child) => child.queues(Key(p)))

  /** Makes sure there is a queue for `prefix` that accepts messages. If there
    * is none, an empty one is created beneath the deepest queue that owns the
    * prefix. Queues that were children of that one and extend `prefix` become
    * children of the new queue. The new queue gets messages only as its parent
    * reads them from its front, so it does not claim any that are already
    * waiting there. If there is one, nothing changes, so splitting is
    * idempotent. A queue on the way down from the root that is draining, the
    * one for `prefix` included, stops draining: messages for `prefix` can only
    * reach its queue through them. The root is the queue for the empty prefix.
    */
  def split(prefix: Key): QueueTrie[A] =
    val target = prefix.bits
    def insert(node: QueueTrie[A], address: BitVector): QueueTrie[A] =
      if address == target then node.copy(draining = false)
      else
        owner(node, target) match
          case Some((p, child)) =>
            node.copy(
              draining = false,
              children = node.children.updated(p, insert(child, p))
            )
          case None =>
            val adopted = node.children.filter((p, _) => p.startsWith(target))
            val rest = node.children.removedAll(adopted.keys)
            val fresh =
              QueueTrie(Fifo.empty[Item[A]], adopted, draining = false)
            node.copy(draining = false, children = rest.updated(target, fresh))
    insert(this, Key.root.bits)

  /** Starts draining the queue for `prefix`: it accepts nothing new, and
    * finishes what it has. Draining a child is how it is merged into its
    * parent: the parent stops forwarding to it, and keeps the messages of that
    * range as its own, each waiting for the child to empty, and the child is
    * removed once it has. Draining the root stops it accepting messages, and it
    * stays, empty, when it is done. There is nothing to drain where there is no
    * queue for `prefix`, so that changes nothing.
    */
  def drain(prefix: Key): QueueTrie[A] =
    modify(this, prefix)(_.copy(draining = true))

  /** Acknowledges every message of the queue for `queue` with an offset up to
    * and including `through`. Acknowledging what is already acknowledged
    * changes nothing, and neither does acknowledging for a queue that is gone,
    * since it was removed only when it was empty.
    */
  def acknowledge(queue: Key, through: Long): QueueTrie[A] =
    pruned(
      modify(this, queue)(node =>
        node.copy(queue = node.queue.acknowledge(through))
      )
    )

  /** Called by the consumer of `queue`: forwards what is at the front of the
    * queue into its children, up to its first own message, and hands out a
    * batch of at most `maxBatch` of its own messages from the front. That is
    * what has been handed out and not acknowledged, then as many more as can
    * start, or nothing if none can. Only this queue is touched. Queues that are
    * draining and empty are removed.
    *
    * Nothing starts unless someone asks. A queue's own message at its front is
    * started only when its queue is asked, so every queue has to be asked
    * eventually, and a child sees a message only after its parent has been
    * asked about it ([[advance]] asks every queue, parents first). Asking for
    * nothing, or for a queue that is not there, gives nothing.
    */
  def takeFrom(
    queue: Key,
    maxBatch: Int
  ): (QueueTrie[A], Option[Run[A]]) =
    /** The child a message is forwarded to, unless it is one of the node's own.
      */
    def forwardTo(node: QueueTrie[A], key: BitVector): Option[BitVector] =
      owner(node, key).collect { case (p, child) if !child.draining => p }

    /** Whether a message of `key` that is the node's own could conflict with
      * what a child holds.
      */
    def blocked(node: QueueTrie[A], key: BitVector): Boolean =
      node.children.exists((p, child) =>
        (key.startsWith(p) || p.startsWith(key)) && !child.isEmpty
      )

    /** Forwards what is at the front, up to the first of the node's own. */
    @tailrec def forward(current: QueueTrie[A]): QueueTrie[A] =
      current.queue.read(1) match
        case Nil => current
        case item :: _ =>
          forwardTo(current, item.key.bits) match
            case None => current
            case Some(p) =>
              val child = current.children(p)
              forward(
                current.copy(
                  queue = current.queue.acknowledge(current.queue.start),
                  children = current.children
                    .updated(p, child.copy(queue = child.queue.add(item)))
                )
              )

    /** Hands out a batch of the node's own messages at its front, if it can. */
    def start(
      node: QueueTrie[A],
      address: Key
    ): Option[Run[A]] =
      val batch = node.queue
        .read(maxBatch)
        .takeWhile(i =>
          forwardTo(node, i.key.bits).isEmpty && !blocked(node, i.key.bits)
        )
      Option.when(batch.nonEmpty)(Run(address, node.queue.start, batch))

    along(this, queue)((node, address) =>
      val forwarded = forward(node)
      (forwarded, start(forwarded, address))
    ).fold((this, None))((updated, run) => (pruned(updated), run))

  /** Asks the queues in turn, parents before children, and returns the first
    * batch of at most `maxBatch` messages that one hands out. There is none
    * when nothing can start.
    */
  def take(maxBatch: Int): (QueueTrie[A], Option[Run[A]]) =
    @tailrec def loop(
      current: QueueTrie[A],
      remaining: List[Key]
    ): (QueueTrie[A], Option[Run[A]]) =
      remaining match
        case Nil => (current, None)
        case q :: rest =>
          current.takeFrom(q, maxBatch) match
            case (next, Some(run)) => (next, Some(run))
            case (next, None) => loop(next, rest)
    loop(this, queues())

  /** Asks every queue once, parents before children, so what a parent forwards
    * can be handed out in the same pass. Nothing is acknowledged, so asking
    * again gives the same batches back.
    */
  def advance(maxBatch: Int = Int.MaxValue): (QueueTrie[A], List[Run[A]]) =
    queues().foldLeft((this, List.empty[Run[A]])):
      case ((current, runs), q) =>
        val (next, run) = current.takeFrom(q, maxBatch)
        (next, runs ++ run.toList)

object QueueTrie:
  /** A message and its key, which is how the tree routes it. */
  final case class Item[+A](key: Key, value: A)

  /** A batch handed out by `queue`. Its messages have consecutive offsets
    * starting at `first`, and acknowledging through [[last]] acknowledges all
    * of them.
    */
  final case class Run[A](queue: Key, first: Long, items: List[Item[A]]):
    def last: Long = first + items.size - 1

  /** Applies `f` to the queue for `prefix`, which is given its own prefix, and
    * rebuilds the tree around what it returns along with whatever else it
    * returns. There is nothing if there is no such queue. Every walk down to a
    * queue is this one, and the root is only where it starts.
    */
  def along[A, B](root: QueueTrie[A], prefix: Key)(
    f: (QueueTrie[A], Key) => (QueueTrie[A], B)
  ): Option[(QueueTrie[A], B)] =
    def descend(
      node: QueueTrie[A],
      address: BitVector
    ): Option[(QueueTrie[A], B)] =
      if address == prefix.bits then Some(f(node, Key(address)))
      else
        owner(node, prefix.bits).flatMap: (p, child) =>
          descend(child, p).map: (updated, b) =>
            (node.copy(children = node.children.updated(p, updated)), b)
    descend(root, Key.root.bits)

  /** The tree with `f` applied to the queue for `prefix`, or the tree as it is
    * if there is no such queue.
    */
  def modify[A](root: QueueTrie[A], prefix: Key)(
    f: QueueTrie[A] => QueueTrie[A]
  ): QueueTrie[A] =
    along(root, prefix)((node, _) => (f(node), ()))
      .fold(root)((updated, _) => updated)

  /** The tree without the queues that are draining and empty. */
  def pruned[A](node: QueueTrie[A]): QueueTrie[A] =
    node.copy(children = node.children.flatMap { (p, child) =>
      val kept = pruned(child)
      if kept.draining && kept.isEmpty then None else Some(p -> kept)
    })

  def empty[A]: QueueTrie[A] =
    QueueTrie(Fifo.empty[Item[A]], TreeMap.empty, draining = false)

  /** The child of `node` whose prefix is a prefix of `key`, if any. Children
    * are an antichain of prefixes, so it is the greatest one at or before
    * `key`: everything between a prefix and a key that extends it extends the
    * prefix too.
    */
  def owner[A](
    node: QueueTrie[A],
    key: BitVector
  ): Option[(BitVector, QueueTrie[A])] =
    node.children.rangeTo(key).lastOption.filter((p, _) => key.startsWith(p))

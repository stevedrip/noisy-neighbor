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
// I wonder what the fs2 analogues are for these types of sources and sinks.
//
// Consider case class QueueTrie[A] extends Trie[(BatchWriter[Producer[A]], BatchReader[Consumer[A]])]
//
// Writing at the root could delegate to the appropriate child for enqueuing, but it should also be possible, once you have access to the child, to enqueue directly.

/** A tree of queues. Each queue owns a key prefix, and the children of a queue
  * own longer prefixes. Producers add messages at the root only. A queue reads
  * messages from its front and either forwards them to a child or processes
  * them itself, so the order messages leave a queue in is the order they
  * entered it, and ordering between conflicting messages comes from the shape
  * of the tree rather than from looking at messages.
  *
  * It is a [[Trie]] of [[Fifo]]s: a node for every queue that exists, with the
  * labels of the edges relative to the node above. All the walks down to a
  * queue, creating one, and removing the ones that have drained are the trie's.
  * A queue that does not exist is a key with no node, not an empty queue. The
  * operations are extension methods in [[FifoTree]], and use none of the trie's
  * algebra.
  *
  * A message is forwarded when its key starts with the prefix of a child that
  * accepts it, which a draining one does not: it is added to the back of that
  * child and acknowledged in this queue. Otherwise it is one of the queue's
  * own, and it starts once no child that could hold a conflicting message has
  * anything in it. Everything behind an own message that is waiting or has been
  * handed out stays in the queue, so nothing overtakes it.
  *
  * The tree is a pure value, and it works by being asked. Each queue has one
  * consumer at a time, and only that consumer moves the front of its queue:
  * when it asks ([[FifoTree.takeFrom]]), the queue forwards what is at its
  * front into its children, up to its first own message, and hands out a batch
  * of its own messages. A batch is a run of the queue's own messages at its
  * front that can all start together, with no forward between them. Its
  * messages are applied together, in the order they arrived. The consumer
  * acknowledges by offset ([[FifoTree.acknowledge]]), as many messages as it
  * likes, in order.
  *
  * A batch always starts at the front, so asking again gives back whatever has
  * not been acknowledged, and may extend it with messages that have become
  * startable. A consumer that asks again has stopped working on what it was
  * given, so that is also when a message that a split has since given to a
  * child is forwarded rather than handed back. The tree has no notion of
  * failure or time: when to ask again is the consumer's business, and it trusts
  * the offsets it is given.
  *
  * A draining queue accepts nothing new, from its parent or, for the root, from
  * producers. A draining child is removed when it has emptied. A draining queue
  * still processes and forwards what it has.
  *
  * Walks of the tree recurse once per level, and the depth is the number of
  * nested splits, not the length of a key.
  */
type FifoTree[A] = Trie[Fifo[FifoTree.Item[A]]]

object FifoTree:
  /** A message and its key, which is how the tree routes it. */
  final case class Item[+A](key: BitVector, value: A)

  /** A batch handed out by `queue`. Its messages have consecutive offsets
    * starting at `first`, and acknowledging through [[last]] acknowledges all
    * of them.
    */
  final case class Run[A](queue: BitVector, first: Long, items: List[Item[A]]):
    def last: Long = first + items.size - 1

  def empty[A]: FifoTree[A] = Trie(TreeMap.empty, Fifo.empty[Item[A]])

  extension [A](tree: FifoTree[A])

    /** Adds a message at the root. A draining root accepts nothing, which is
      * the one refusal a producer has to hear about, so there is no tree then.
      */
    def enqueue(key: BitVector, value: A): Option[FifoTree[A]] =
      tree.value.add(Item(key, value)).map(queue => tree.copy(value = queue))

    /** Whether this queue and every queue below it hold nothing. A message that
      * is handed out and not acknowledged is still held.
      */
    def isEmpty: Boolean =
      tree.value.isEmpty && tree.children.values.forall(_.isEmpty)

    /** The queues without those that are draining and empty. */
    def pruned: FifoTree[A] =
      tree.prune(q => q.value.draining && q.isEmpty)

    /** The key of every queue, parents before children. */
    def queues: List[BitVector] = tree.nodes.map(_._1)

    /** Makes sure there is a queue for `prefix` that accepts messages. If there
      * is none, an empty one is created beneath the deepest queue that owns the
      * prefix. Queues that were children of that one and extend `prefix` become
      * children of the new queue. The new queue gets messages only as its
      * parent reads them from its front, so it does not claim any that are
      * already waiting there. If there is one, nothing changes, so splitting is
      * idempotent. A queue on the way down from the root that is draining, the
      * one for `prefix` included, stops draining: messages for `prefix` can
      * only reach its queue through them. The root is the queue for the empty
      * prefix.
      */
    def split(prefix: BitVector): FifoTree[A] =
      tree.upsert(prefix, Fifo.empty[Item[A]])(_.resume, _.resume)

    /** Starts draining the queue for `prefix`: it accepts nothing new, and
      * finishes what it has. Draining a child is how it is merged into its
      * parent: the parent stops forwarding to it, and keeps the messages of
      * that range as its own, each waiting for the child to empty, and the
      * child is removed once it has. Draining the root stops it accepting
      * messages, and it stays, empty, when it is done. There is nothing to
      * drain where there is no queue for `prefix`, so that changes nothing.
      */
    def drain(prefix: BitVector): FifoTree[A] =
      tree.modify(prefix)(t => t.copy(value = t.value.drain))

    /** Acknowledges every message of the queue for `queue` with an offset up to
      * and including `through`. Acknowledging what is already acknowledged
      * changes nothing, and neither does acknowledging for a queue that is
      * gone, since it was removed only when it was empty.
      */
    def acknowledge(queue: BitVector, through: Long): FifoTree[A] =
      tree
        .modify(queue)(t => t.copy(value = t.value.acknowledge(through)))
        .pruned

    /** Called by the consumer of `queue`: forwards what is at the front of the
      * queue into its children, up to its first own message, and hands out a
      * batch of at most `maxBatch` of its own messages from the front. That is
      * what has been handed out and not acknowledged, then as many more as can
      * start, or nothing if none can. Only this queue is touched. Queues that
      * are draining and empty are removed.
      *
      * Nothing starts unless someone asks. A queue's own message at its front
      * is started only when its queue is asked, so every queue has to be asked
      * eventually, and a child sees a message only after its parent has been
      * asked about it ([[advance]] asks every queue, parents first). Asking for
      * nothing, or for a queue that is not there, gives nothing.
      */
    def takeFrom(
      queue: BitVector,
      maxBatch: Int
    ): (FifoTree[A], Option[Run[A]]) =

      /** What forwarding a message from the node for `address` would give: the
        * label of the child it goes to, and that child's queue with the message
        * added. There is nothing if it is one of the node's own, because no
        * child's label is a prefix of what is left of its key, or the child
        * accepts nothing. The message's key starts with `address`, so what is
        * left of the key is what the labels are relative to.
        */
      def forwarded(
        node: FifoTree[A],
        address: BitVector,
        item: Item[A]
      ): Option[(BitVector, Fifo[Item[A]])] =
        node.child(item.key.drop(address.size)).flatMap { (label, child) =>
          child.value.add(item).map(label -> _)
        }

      /** Whether a message of `key` that is the node's own could conflict with
        * what a child holds.
        */
      def blocked(node: FifoTree[A], address: BitVector, key: BitVector) =
        val rest = key.drop(address.size)
        node.children.exists((label, child) =>
          (rest.startsWith(label) || label.startsWith(rest)) && !child.isEmpty
        )

      /** Forwards what is at the front, up to the first of the node's own. */
      @tailrec def forward(
        current: FifoTree[A],
        address: BitVector
      ): FifoTree[A] =
        current.value.read(1) match
          case Nil => current
          case item :: _ =>
            forwarded(current, address, item) match
              case None => current
              case Some((label, added)) =>
                val here = current.value
                forward(
                  current.copy(
                    value = here.acknowledge(here.start),
                    children = current.children.updated(
                      label,
                      current.children(label).copy(value = added)
                    )
                  ),
                  address
                )

      /** Hands out a batch of the node's own messages at its front, if it can.
        */
      def start(node: FifoTree[A], address: BitVector): Option[Run[A]] =
        val messages = node.value
        val batch = messages
          .read(maxBatch)
          .takeWhile(i =>
            forwarded(node, address, i).isEmpty &&
              !blocked(node, address, i.key)
          )
        Option.when(batch.nonEmpty)(Run(address, messages.start, batch))

      tree
        .along(queue)((node, address) =>
          val moved = forward(node, address)
          (moved, start(moved, address))
        )
        .fold((tree, None))((updated, run) => (updated.pruned, run))

    /** Asks the queues in turn, parents before children, and returns the first
      * batch of at most `maxBatch` messages that one hands out. There is none
      * when nothing can start.
      */
    def take(maxBatch: Int): (FifoTree[A], Option[Run[A]]) =
      @tailrec def loop(
        current: FifoTree[A],
        remaining: List[BitVector]
      ): (FifoTree[A], Option[Run[A]]) =
        remaining match
          case Nil => (current, None)
          case q :: rest =>
            current.takeFrom(q, maxBatch) match
              case (next, Some(run)) => (next, Some(run))
              case (next, None) => loop(next, rest)
      loop(tree, tree.queues)

    /** Asks every queue once, parents before children, so what a parent
      * forwards can be handed out in the same pass. Nothing is acknowledged, so
      * asking again gives the same batches back.
      */
    def advance(maxBatch: Int = Int.MaxValue): (FifoTree[A], List[Run[A]]) =
      tree.queues.foldLeft((tree, List.empty[Run[A]])):
        case ((current, runs), q) =>
          val (next, run) = current.takeFrom(q, maxBatch)
          (next, runs ++ run.toList)

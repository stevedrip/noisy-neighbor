package noisyneighbor

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import fs2.Chunk
import scala.collection.immutable.TreeMap
import scodec.bits.BitVector

import FifoTrie.Item

/** A trie of queues, written in terms of [[Fifo]]: a [[Trie]] with a node for
  * every queue that exists, and the node is the queue, some `F <: Fifo`. The
  * labels of the edges are relative to the node above. Each queue owns a key
  * prefix, and the children of a queue own longer prefixes. Producers add
  * messages at the root only. A queue forwards a message to a child when its
  * key starts with the child's prefix, and otherwise the message is the queue's
  * own, so the order messages leave a queue in is the order they entered it,
  * and ordering between conflicting messages comes from the shape of the trie.
  *
  * The trie is shared by fibers that take turns, and each queue has one fiber
  * serving it, [[run]]. A fiber waits by suspending until something changes,
  * and every change wakes the ones that are waiting, so the queues are never
  * locked, and the shape of the trie is guarded by a mutex only when it
  * changes. The trie is the one that does the waiting that [[Fifo]] leaves to
  * whoever drives it:
  *
  *   - A queue is served with [[consume]]: one take, whose work forwards each
  *     message that belongs to a child and collects the queue's own messages,
  *     in order, into batches of the work the caller gives.
  *   - A message to forward waits for room in the child, and a batch of own
  *     messages waits until no queue below holds anything, since it cannot see
  *     inside them and has to start after everything that was forwarded before
  *     it. The queue still holds the chunk while it waits, and removes it once
  *     it has all been dealt with.
  *   - A queue is draining when its back is closed, `offering` is false. It is
  *     not forwarded to, and it is taken from until it is empty. It is removed
  *     when it, and everything below it, has emptied, and the queue is closed
  *     at both ends as it is removed.
  */
final class FifoTrie[A, F <: Fifo[Item[A]]] private (
  shape: Ref[IO, Trie[F]],
  gate: Ref[IO, Deferred[IO, Unit]],
  structure: Mutex[IO]
):
  /** Lets everything that is waiting for a change look again. */
  private def changed: IO[Unit] =
    Deferred[IO, Unit].flatMap(next =>
      gate.getAndSet(next).flatMap(_.complete(())).void
    )

  /** Looks at `condition`, and waits for a change and looks again until it
    * holds. The gate is taken before the condition is looked at, so a change in
    * between is not missed.
    */
  private def waitUntil(condition: IO[Boolean]): IO[Unit] =
    gate.get.flatMap: waiting =>
      condition.flatMap:
        case true => IO.unit
        case false => waiting.get >> waitUntil(condition)

  /** Whether a queue and every queue below it hold nothing. */
  private def holdsNothing(node: Trie[F]): IO[Boolean] =
    node.value.isEmpty.flatMap: empty =>
      if !empty then IO.pure(false)
      else node.children.values.toList.forallM(holdsNothing)

  /** The node for the queue at `address`. */
  private def at(address: BitVector): IO[Trie[F]] =
    shape.get.map(_.owner(address)._1)

  /** Offers one message to a queue, and says whether it took it. */
  private def offerOne(fifo: F, item: Item[A]): IO[Boolean] =
    Ref
      .of[IO, Int](0)
      .flatMap: added =>
        fifo.offer(Chunk.singleton(item), added.set) >> added.get.map(_ == 1)

  /** The child a message of the queue at `address` goes to, if there is one
    * that takes it, which a draining one does not.
    */
  private def target(address: BitVector, item: Item[A]): IO[Option[F]] =
    at(address).flatMap: node =>
      node.child(item.key.drop(address.size)) match
        case Some((_, child)) =>
          child.value.offering.get.map(open => Option.when(open)(child.value))
        case None => IO.pure(None)

  /** Puts a message in the child it goes to, waiting for room. It is false if
    * the child is draining by the time there is room, and the message is the
    * queue's own after all.
    */
  private def deliver(address: BitVector, item: Item[A]): IO[Boolean] =
    gate.get.flatMap: waiting =>
      target(address, item).flatMap:
        case None => IO.pure(false)
        case Some(child) =>
          offerOne(child, item).flatMap:
            case true => changed.as(true)
            case false => waiting.get >> deliver(address, item)

  /** Deals with a chunk from the queue at `address`, in order: its own messages
    * are gathered into a batch, and a message to forward ends the batch, which
    * is given to `work` once nothing below holds anything, before it is
    * forwarded.
    */
  private def process(
    address: BitVector,
    items: List[Item[A]],
    work: (BitVector, List[Item[A]]) => IO[Unit]
  ): IO[Unit] =
    def flush(run: Vector[Item[A]]): IO[Unit] =
      if run.isEmpty then IO.unit
      else
        val childrenEmpty =
          at(address).flatMap(_.children.values.toList.forallM(holdsNothing))
        waitUntil(childrenEmpty) >> work(address, run.toList)

    def go(rest: List[Item[A]], run: Vector[Item[A]]): IO[Unit] =
      rest match
        case Nil => flush(run)
        case item :: tail =>
          target(address, item).flatMap:
            case None => go(tail, run :+ item)
            case Some(_) =>
              flush(run) >> deliver(address, item).flatMap:
                case true => go(tail, Vector.empty)
                case false => go(tail, Vector(item))

    go(items, Vector.empty)

  /** Adds a message at the root. It is refused, and this is false, if the root
    * takes nothing: it is full, or draining, or closed.
    */
  def enqueue(key: BitVector, value: A): IO[Boolean] =
    shape.get.flatMap: current =>
      offerOne(current.value, Item(key, value)).flatTap(ok => changed.whenA(ok))

  /** Adds a message at the root, waiting while the root's queue is full. It is
    * false, and nothing is added, only if the root is draining or closed.
    */
  def submit(key: BitVector, value: A): IO[Boolean] =
    gate.get.flatMap: waiting =>
      shape.get.flatMap: current =>
        val root = current.value
        (root.offering.get, root.taking.get)
          .mapN((offering, taking) => !offering || !taking)
          .flatMap:
            case true => IO.pure(false)
            case false =>
              offerOne(root, Item(key, value)).flatMap:
                case true => changed.as(true)
                case false => waiting.get >> submit(key, value)

  /** Whether the trie holds nothing. A message that is being worked on is still
    * held.
    */
  def isEmpty: IO[Boolean] = shape.get.flatMap(holdsNothing)

  /** Completes once the trie holds nothing. */
  def untilEmpty: IO[Unit] = waitUntil(isEmpty)

  /** The key of every queue, parents before children. */
  def queues: IO[List[BitVector]] = shape.get.map(_.nodes.map(_._1))

  /** Makes sure there is a queue for `prefix` that is not draining. If there is
    * none, `fifo` is made its queue, beneath the deepest queue that owns the
    * prefix, and queues that were children of that one and extend `prefix`
    * become its children. It is only evaluated then. If there is one, it is
    * left as it is, so splitting is idempotent. A queue on the way down from
    * the root that is draining, the one for `prefix` included, stops draining:
    * messages for `prefix` can only reach its queue through them. The root is
    * the queue for the empty prefix.
    */
  def split(prefix: BitVector, fifo: => F): IO[Unit] =
    structure.lock.surround:
      for
        _ <- shape.update(_.upsert(prefix, fifo)(identity, identity))
        current <- shape.get
        _ <- current.nodes
          .filter((address, _) => prefix.startsWith(address))
          .traverse_((_, queue) => queue.offering.set(true))
        _ <- changed
      yield ()

  /** Starts draining the queue for `prefix`, which is closing its back: nothing
    * is forwarded to it, and what it holds is finished, and it is removed once
    * it is empty. Draining a child is how it is merged into its parent, which
    * keeps the messages of that range as its own, each waiting for the child to
    * empty. Draining the root refuses what producers enqueue, and it stays when
    * it is empty. There is nothing to drain where there is no queue for
    * `prefix`.
    */
  def drain(prefix: BitVector): IO[Unit] =
    structure.lock.surround:
      for
        current <- shape.get
        (node, _, rest) = current.owner(prefix)
        _ <- node.value.offering.set(false).whenA(rest.isEmpty)
        _ <- changed
      yield ()

  /** Removes the queues that are draining and have emptied, with everything
    * below them, and closes them at both ends, which they can be: they hold
    * nothing.
    */
  private def prune: IO[Unit] =
    structure.lock.surround:
      shape.get.flatMap: current =>
        def subtries(t: Trie[F]): List[Trie[F]] =
          t :: t.children.values.toList.flatMap(subtries)
        subtries(current)
          .drop(1)
          .filterA(t =>
            (t.value.offering.get.map(!_), holdsNothing(t)).mapN(_ && _)
          )
          .flatMap: gone =>
            val removed = gone.flatMap(subtries).map(_.value)
            if removed.isEmpty then IO.unit
            else
              removed.traverse_(queue =>
                queue.taking.set(false) >> queue.offering.set(false)
              ) >>
                shape.update(_.prune(t => removed.exists(_ eq t.value))) >>
                changed

  /** Serves the queue for `queue` once, and says whether there was anything to
    * take. It takes up to `maxBatch` messages from the front. Its own messages
    * are given to `work` in batches, each with the queue and the messages in
    * order, and each after nothing below holds anything. Messages for a child
    * are put in the child, waiting for room, before the messages behind them
    * are looked at. The queue removes what it took once all of it is dealt
    * with. If the work fails, they stay, and the next ask gives them again.
    * Queues that are draining and empty are removed. Serving a queue that is
    * not there takes nothing.
    */
  def consume(queue: BitVector, maxBatch: Int)(
    work: (BitVector, List[Item[A]]) => IO[Unit]
  ): IO[Boolean] =
    shape.get.flatMap: current =>
      val (node, address, rest) = current.owner(queue)
      if rest.nonEmpty then IO.pure(false)
      else
        Ref
          .of[IO, Int](0)
          .flatMap: taken =>
            node.value.take(
              maxBatch,
              chunk =>
                if chunk.isEmpty then IO.unit
                else
                  taken.set(chunk.size) >> process(address, chunk.toList, work)
            ) >>
              taken.get.flatMap: n =>
                if n == 0 then IO.pure(false) else changed >> prune.as(true)

  /** Serves the queue for `queue` for as long as it is there: takes what there
    * is, and when there is nothing, waits for a change and tries again. It ends
    * once the queue has been removed.
    */
  def run(queue: BitVector, maxBatch: Int)(
    work: (BitVector, List[Item[A]]) => IO[Unit]
  ): IO[Unit] =
    gate.get.flatMap: waiting =>
      consume(queue, maxBatch)(work).flatMap:
        case true => run(queue, maxBatch)(work)
        case false =>
          shape.get
            .map(_.owner(queue)._3.isEmpty)
            .flatMap: exists =>
              if exists then waiting.get >> run(queue, maxBatch)(work)
              else IO.unit

object FifoTrie:
  /** A message and its key, which is how the trie routes it. */
  final case class Item[+A](key: BitVector, value: A)

  /** A trie with only a root, which is the queue `root`. */
  def of[A, F <: Fifo[Item[A]]](root: F): IO[FifoTrie[A, F]] =
    for
      first <- Deferred[IO, Unit]
      gate <- Ref.of[IO, Deferred[IO, Unit]](first)
      structure <- Mutex[IO]
      shape <- Ref.of[IO, Trie[F]](Trie(TreeMap.empty, root))
    yield new FifoTrie(shape, gate, structure)

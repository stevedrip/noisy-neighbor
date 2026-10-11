package noisyneighbor

import cats.effect.{IO, Ref}
import fs2.Chunk

/** A first-in, first-out queue that a producer and a consumer meet at. It has
  * no offsets: each end is one call that answers at once, hands what crossed to
  * the caller's own work, and is done when it returns. The two calls cross,
  * `take` is asked for a count and gives a chunk, and `offer` is given a chunk
  * and gives a count.
  *
  * A queue does not wait, and it does not look after who calls it. Waiting for
  * messages or for room, and deciding when an end may close, are for whoever
  * drives the queues, which is the trie. The callers are fibers that take
  * turns, one at a time on each end, and share the queue without a lock. What a
  * caller commits to wherever it reads from or writes to, usually an offset, it
  * does once its call has finished.
  *
  * The two booleans, `taking` and `offering`, are how either end closes the
  * queue, so there is no separate close. Both are true to begin with. A queue
  * whose back is closed, `offering` false, is draining: it takes nothing new
  * and is taken from until it is empty.
  */
trait Fifo[A]:
  /** The consumer asks for up to `count` messages (at least one). The queue
    * decides how many to give, at most `count` and no more than it has, which
    * can be none, and `f` is given those from the front, in order. The consumer
    * must consume all of what it is given, there is no partial consumption: the
    * queue removes them once `f` has finished, so if `f` cannot finish and
    * fails, or is cancelled, they stay, and the next take gives them again, and
    * the queue commits its own state after the call completes.
    *
    * `f` is given an empty chunk if there is nothing to give now, or if the
    * consumer's end is closed.
    */
  def take(count: Int, f: Chunk[A] => IO[Unit]): IO[Unit]

  /** The producer offers `chunk`. The queue adds as many as there is room for,
    * `n`, which can be fewer than the chunk holds, and `f` is run with `n`,
    * which is the producer's own work, such as noting how far it has got. The
    * producer commits after the call has finished, and offers `chunk.drop(n)`
    * again if it has more.
    *
    * If either end is closed, nothing is added, and `f` is run with 0.
    */
  def offer(chunk: Chunk[A], f: Int => IO[Unit]): IO[Unit]

  /** Whether the queue holds nothing. A message a take is working on is still
    * held, until the take has finished, so a message that has been given out is
    * never in no queue at all.
    */
  def isEmpty: IO[Boolean]

  /** Whether the consumer's end is open, which it is to begin with, whatever
    * the implementation. Setting it to false closes the queue from that end:
    * nobody will take what is offered, so offers are refused with 0, and a take
    * gets an empty chunk.
    */
  val taking: Ref[IO, Boolean] = Ref.unsafe[IO, Boolean](true)

  /** Whether the producer's end is open, which it is to begin with, whatever
    * the implementation. Setting it to false closes the queue from that end,
    * which is draining it: offers are refused with 0. What is in the queue can
    * still be taken.
    */
  val offering: Ref[IO, Boolean] = Ref.unsafe[IO, Boolean](true)

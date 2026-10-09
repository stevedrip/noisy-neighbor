package noisyneighbor

import scala.annotation.tailrec
import scala.collection.immutable.Queue

/** A first-in, first-out queue. Messages are added at the back and read from
  * the front, in order, and reading does not remove them: the same messages are
  * there to read again until they are acknowledged. Each message has an offset,
  * numbered from 0 in the order added, which never changes. It cannot be
  * searched, indexed or counted.
  *
  * A queue can be draining. A draining queue accepts nothing new, and still
  * reads and acknowledges what it has until it is empty.
  *
  * @param start
  *   The offset of the message at the front, or of the next one added if it is
  *   empty.
  * @param draining
  *   Whether the queue accepts nothing new.
  */
final class Fifo[A] private (
  items: Queue[A],
  val start: Long,
  val draining: Boolean
):
  /** Adds a message at the back. A draining queue accepts nothing, which is
    * something the one adding has to hear about, so there is no queue then.
    */
  def add(a: A): Option[Fifo[A]] =
    Option.unless(draining)(new Fifo(items.enqueue(a), start, draining))

  /** Up to `n` messages from the front, in order. The first has offset `start`.
    */
  def read(n: Int): List[A] = items.iterator.take(n).toList

  /** Removes every message with an offset up to and including `through`. An
    * offset that has already been removed changes nothing.
    */
  def acknowledge(through: Long): Fifo[A] =
    @tailrec def drop(
      remaining: Long,
      current: Queue[A],
      dropped: Long
    ): (Queue[A], Long) =
      if remaining <= 0 || current.isEmpty then (current, dropped)
      else drop(remaining - 1, current.tail, dropped + 1)
    val (kept, dropped) = drop(through - start + 1, items, 0L)
    new Fifo(kept, start + dropped, draining)

  /** The queue, accepting nothing new. */
  def drain: Fifo[A] = new Fifo(items, start, draining = true)

  /** The queue, accepting messages again. */
  def resume: Fifo[A] = new Fifo(items, start, draining = false)

  def isEmpty: Boolean = items.isEmpty

object Fifo:
  def empty[A]: Fifo[A] = new Fifo(Queue.empty, 0L, draining = false)

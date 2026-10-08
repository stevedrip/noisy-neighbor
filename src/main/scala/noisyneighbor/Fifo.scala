package noisyneighbor

import scala.annotation.tailrec
import scala.collection.immutable.Queue

/** A first-in, first-out queue. Messages are added at the back and read from
  * the front, in order, and reading does not remove them: the same messages are
  * there to read again until they are acknowledged. Each message has an offset,
  * numbered from 0 in the order added, which never changes. It cannot be
  * searched, indexed or counted.
  *
  * @param start
  *   The offset of the message at the front, or of the next one added if it is
  *   empty.
  */
final class Fifo[A] private (items: Queue[A], val start: Long):
  def add(a: A): Fifo[A] = new Fifo(items.enqueue(a), start)

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
    new Fifo(kept, start + dropped)

  def isEmpty: Boolean = items.isEmpty

object Fifo:
  def empty[A]: Fifo[A] = new Fifo(Queue.empty, 0L)

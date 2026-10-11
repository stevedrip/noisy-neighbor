package noisyneighbor

import cats.effect.{IO, Ref}
import fs2.Chunk
import scala.collection.immutable.Queue

/** A [[Fifo]] in memory that holds at most `capacity` messages, those a take
  * has been given and not finished with included.
  *
  * It is a cell holding a collection and nothing more. It does not wait, and
  * what it holds changes in one step at a time, so fibers on any number of
  * threads can share it.
  */
final class MemoryFifo[A](capacity: Int) extends Fifo[A]:
  require(capacity > 0, "a queue holds at least one message")

  private val items = Ref.unsafe[IO, Queue[A]](Queue.empty)

  def isEmpty: IO[Boolean] = items.get.map(_.isEmpty)

  def take(count: Int, f: Chunk[A] => IO[Unit]): IO[Unit] =
    for
      open <- taking.get
      chunk <-
        if open then items.get.map(q => Chunk.from(q.take(count.max(1))))
        else IO.pure(Chunk.empty[A])
      _ <- f(chunk)
      _ <- if chunk.nonEmpty then items.update(_.drop(chunk.size)) else IO.unit
    yield ()

  def offer(chunk: Chunk[A], f: Int => IO[Unit]): IO[Unit] =
    for
      producing <- offering.get
      consuming <- taking.get
      accepted <-
        if producing && consuming then
          items.modify: q =>
            val n = (capacity - q.size).min(chunk.size)
            (q ++ chunk.take(n).iterator, n)
        else IO.pure(0)
      _ <- f(accepted)
    yield ()

object MemoryFifo:
  def apply[A](capacity: Int): MemoryFifo[A] = new MemoryFifo(capacity)

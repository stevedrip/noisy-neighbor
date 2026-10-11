package noisyneighbor

import cats.effect.{IO, Ref}
import fs2.Chunk
import munit.CatsEffectSuite

class MemoryFifoSpec extends CatsEffectSuite:
  /** The messages one take was given. */
  def took(fifo: Fifo[Int], count: Int): IO[List[Int]] =
    Ref
      .of[IO, List[Int]](Nil)
      .flatMap(seen =>
        fifo.take(count, chunk => seen.set(chunk.toList)) >> seen.get
      )

  /** How many of the messages one offer was told were added. */
  def offered(fifo: Fifo[Int], items: List[Int]): IO[Int] =
    Ref
      .of[IO, Int](-1)
      .flatMap(added => fifo.offer(Chunk.from(items), added.set) >> added.get)

  test("a take is given up to count messages, in order, and removes them"):
    val fifo = MemoryFifo[Int](10)
    for
      added <- offered(fifo, List(1, 2, 3))
      first <- took(fifo, 2)
      second <- took(fifo, 2)
    yield assertEquals((added, first, second), (3, List(1, 2), List(3)))

  test("a take from an empty queue is given nothing, at once"):
    val fifo = MemoryFifo[Int](10)
    for
      none <- took(fifo, 3)
      added <- offered(fifo, List(1))
      some <- took(fifo, 3)
    yield assertEquals((none, added, some), (Nil, 1, List(1)))

  test("a take is given at least one message, whatever the count"):
    val fifo = MemoryFifo[Int](10)
    for
      _ <- offered(fifo, List(1, 2))
      got <- took(fifo, 0)
    yield assertEquals(got, List(1))

  test("a take whose work fails leaves the messages, to be given again"):
    val fifo = MemoryFifo[Int](10)
    for
      _ <- offered(fifo, List(1, 2, 3))
      failed <- fifo
        .take(2, _ => IO.raiseError(new IllegalStateException("boom")))
        .attempt
      again <- took(fifo, 3)
    yield
      assertEquals(failed.left.map(_.getMessage), Left("boom"))
      assertEquals(again, List(1, 2, 3))

  test("a take that is cancelled leaves the messages, to be given again"):
    val fifo = MemoryFifo[Int](10)
    for
      _ <- offered(fifo, List(1, 2, 3))
      working <- fifo.take(2, _ => IO.never).start
      _ <- IO.cede
      _ <- working.cancel
      again <- took(fifo, 3)
    yield assertEquals(again, List(1, 2, 3))

  test("a message a take is working on still takes up room until it is done"):
    val fifo = MemoryFifo[Int](1)
    for
      _ <- offered(fifo, List(1))
      during <- Ref.of[IO, Int](-1)
      _ <- fifo.take(1, _ => offered(fifo, List(2)).flatMap(during.set))
      before <- during.get
      after <- offered(fifo, List(2))
    yield assertEquals((before, after), (0, 1))

  test("an offer is only partly added when there is not room for all of it"):
    val fifo = MemoryFifo[Int](2)
    for
      first <- offered(fifo, List(1, 2, 3, 4, 5))
      full <- offered(fifo, List(3))
      taken <- took(fifo, 5)
      second <- offered(fifo, List(3, 4, 5))
      rest <- took(fifo, 5)
    yield assertEquals(
      (first, full, taken, second, rest),
      (2, 0, List(1, 2), 2, List(3, 4))
    )

  test("an offer of nothing adds nothing"):
    offered(MemoryFifo[Int](2), Nil).map(assertEquals(_, 0))

  test("isEmpty says whether it holds anything, a message being worked on too"):
    val fifo = MemoryFifo[Int](10)
    for
      before <- fifo.isEmpty
      _ <- offered(fifo, List(1))
      held <- fifo.isEmpty
      during <- Ref.of[IO, Boolean](true)
      _ <- fifo.take(1, _ => fifo.isEmpty.flatMap(during.set))
      working <- during.get
      after <- fifo.isEmpty
    yield assertEquals(
      (before, held, working, after),
      (true, false, false, true)
    )

  test("both ends are open to begin with"):
    val fifo = MemoryFifo[Int](10)
    for
      taking <- fifo.taking.get
      offering <- fifo.offering.get
    yield assert(taking && offering)

  test("closed from the producer's end, offers get 0 and takes still give"):
    val fifo = MemoryFifo[Int](10)
    for
      _ <- offered(fifo, List(1, 2))
      _ <- fifo.offering.set(false)
      refused <- offered(fifo, List(3, 4))
      taken <- took(fifo, 5)
      none <- took(fifo, 5)
    yield assertEquals((refused, taken, none), (0, List(1, 2), Nil))

  test("closed from the consumer's end, offers get 0 and takes get nothing"):
    val fifo = MemoryFifo[Int](10)
    for
      _ <- offered(fifo, List(1))
      _ <- fifo.taking.set(false)
      refused <- offered(fifo, List(2, 3))
      none <- took(fifo, 5)
    yield assertEquals((refused, none), (0, Nil))

  test("an end that is set to true again works again"):
    val fifo = MemoryFifo[Int](10)
    for
      _ <- offered(fifo, List(1, 2))
      _ <- fifo.taking.set(false)
      _ <- fifo.offering.set(false)
      refused <- offered(fifo, List(3))
      none <- took(fifo, 5)
      _ <- fifo.taking.set(true)
      _ <- fifo.offering.set(true)
      added <- offered(fifo, List(3))
      taken <- took(fifo, 5)
    yield assertEquals(
      (refused, none, added, taken),
      (0, Nil, 1, List(1, 2, 3))
    )

package noisyneighbor

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scodec.bits.BitVector

import FifoTrie.Item

// Runs on the virtual clock of TestControl where fibers wait for one another,
// so the times are exact.
class FifoTrieSpec extends CatsEffectSuite:
  type Q = MemoryFifo[Item[Int]]
  type Fifos = FifoTrie[Int, Q]

  def bits(s: String): BitVector = BitVector.fromValidBin(s)

  def queue(capacity: Int = 100): Q = MemoryFifo[Item[Int]](capacity)

  def virtual[A](io: IO[A]): IO[A] = TestControl.executeEmbed(io)

  /** An empty root with the given queues split off, in order. */
  def trie(prefixes: String*): IO[Fifos] =
    FifoTrie
      .of[Int, Q](queue())
      .flatTap(t => prefixes.toList.traverse_(p => t.split(bits(p), queue())))

  /** Enqueues `(key, value)` pairs at the root. */
  def enqueue(t: Fifos, messages: (String, Int)*): IO[Unit] =
    messages.toList.traverse_((key, value) =>
      t.enqueue(bits(key), value).map(ok => assert(ok, s"'$key' was refused"))
    )

  /** Serves one queue once, and gives whether it took anything, and the batches
    * its work was given, as values.
    */
  def serve(
    t: Fifos,
    q: String,
    maxBatch: Int = 100
  ): IO[(Boolean, List[List[Int]])] =
    Ref
      .of[IO, List[List[Int]]](Nil)
      .flatMap(seen =>
        t.consume(bits(q), maxBatch)((_, items) =>
          seen.update(_ :+ items.map(_.value))
        ).flatMap(took => seen.get.map(took -> _))
      )

  /** Serves every queue with a fiber of its own, enqueues the messages, waits
    * until they are all dealt with, and gives the batches as the queue and the
    * values.
    */
  def served(
    t: Fifos,
    maxBatch: Int,
    messages: (String, Int)*
  ): IO[List[(String, List[Int])]] =
    for
      seen <- Ref.of[IO, List[(String, List[Int])]](Nil)
      work = (q: BitVector, items: List[Item[Int]]) =>
        seen.update(_ :+ (q.toBin -> items.map(_.value)))
      fibers <- t.queues.flatMap(
        _.traverse(q => t.run(q, maxBatch)(work).start)
      )
      _ <- enqueue(t, messages*)
      _ <- t.untilEmpty
      _ <- fibers.traverse_(_.cancel)
      batches <- seen.get
    yield batches

  test("a message for the root is handed out by the root"):
    for
      t <- trie()
      _ <- enqueue(t, "" -> 1)
      handed <- serve(t, "")
      empty <- t.isEmpty
    yield assertEquals((handed, empty), ((true, List(List(1))), true))

  test("a message for a child is forwarded, and handed out by the child"):
    for
      t <- trie("0")
      _ <- enqueue(t, "01" -> 1)
      forwarded <- serve(t, "")
      held <- t.isEmpty
      handed <- serve(t, "0")
      empty <- t.isEmpty
    yield assertEquals(
      (forwarded, held, handed, empty),
      ((true, Nil), false, (true, List(List(1))), true)
    )

  test("a message that nobody has asked about is held, wherever it is"):
    for
      t <- trie("0")
      _ <- enqueue(t, "01" -> 1)
      before <- t.isEmpty
      _ <- serve(t, "")
      during <- t.isEmpty
    yield assertEquals((before, during), (false, false))

  test("a batch of own messages is up to the maximum"):
    for
      t <- trie()
      _ <- enqueue(t, "" -> 0, "" -> 1, "" -> 2)
      first <- serve(t, "", maxBatch = 2)
      second <- serve(t, "", maxBatch = 2)
    yield assertEquals(
      (first, second),
      ((true, List(List(0, 1))), (true, List(List(2))))
    )

  test("a message to forward ends the batch, and what is behind waits for it"):
    virtual:
      for
        t <- trie("0")
        batches <- served(t, 5, "1" -> 0, "01" -> 1, "1" -> 2)
      yield assertEquals(
        batches,
        List("" -> List(0), "0" -> List(1), "" -> List(2))
      )

  test("a message for the root waits for earlier ones in the children"):
    virtual:
      for
        t <- trie("0", "1")
        batches <- served(t, 5, "01" -> 0, "" -> 1, "10" -> 2)
      yield assertEquals(
        batches,
        List("0" -> List(0), "" -> List(1), "1" -> List(2))
      )

  test("work that fails leaves the messages, to be given again"):
    for
      t <- trie()
      _ <- enqueue(t, "" -> 1, "" -> 2)
      failed <- t
        .consume(BitVector.empty, 5)((_, _) =>
          IO.raiseError(new IllegalStateException("boom"))
        )
        .attempt
      held <- t.isEmpty
      again <- serve(t, "")
    yield
      assertEquals(failed.left.map(_.getMessage), Left("boom"))
      assert(!held)
      assertEquals(again, (true, List(List(1, 2))))

  test("a message for a child that has no room waits for it"):
    virtual:
      for
        t <- FifoTrie.of[Int, Q](queue(10))
        _ <- t.split(bits("0"), queue(1))
        batches <- served(t, 5, "00" -> 0, "01" -> 1, "" -> 2)
      yield assertEquals(
        batches,
        List("0" -> List(0), "0" -> List(1), "" -> List(2))
      )

  test("asking for a queue that is not there takes nothing"):
    for
      t <- trie("0")
      _ <- enqueue(t, "" -> 1)
      missing <- serve(t, "1")
      queues <- t.queues
    yield assertEquals(
      (missing, queues.map(_.toBin)),
      ((false, Nil), List("", "0"))
    )

  test("submit waits while the root is full, until it has taken a message"):
    virtual:
      for
        t <- FifoTrie.of[Int, Q](queue(1))
        serving <- t.run(BitVector.empty, 1)((_, _) => IO.sleep(1.second)).start
        first <- t.submit(bits(""), 1)
        second <- (t.submit(bits(""), 2) *> IO.monotonic).start
        accepted <- second.joinWithNever
        _ <- t.untilEmpty
        _ <- serving.cancel
      yield assertEquals((first, accepted), (true, 1.second))

  test("submit is refused, and does not wait, by a root that is draining"):
    for
      t <- trie()
      _ <- t.drain(BitVector.empty)
      refused <- t.submit(bits("1"), 1)
    yield assert(!refused)

  /** Every message numbered from 1 in the order of `keys`, handled for 100 ms,
    * with a fiber for each queue: when each finished, in milliseconds.
    */
  def finished(
    labels: List[String],
    keys: List[String]
  ): IO[Map[Int, Long]] =
    virtual:
      for
        t <- trie(labels*)
        times <- Ref.of[IO, Map[Int, Long]](Map.empty)
        work = (_: BitVector, items: List[Item[Int]]) =>
          items.traverse_(item =>
            IO.sleep(100.millis) >>
              IO.monotonic.flatMap(now =>
                times.update(_.updated(item.value, now.toMillis))
              )
          )
        fibers <- t.queues.flatMap(_.traverse(q => t.run(q, 1)(work).start))
        _ <- keys.zipWithIndex.traverse_((k, i) => enqueue(t, k -> (i + 1)))
        _ <- t.untilEmpty
        _ <- fibers.traverse_(_.cancel)
        result <- times.get
      yield result

  val behavior = List("", "0", "00", "01", "10", "11", "1", "")

  test("minimally concurrent: a root alone handles one message after another"):
    finished(Nil, behavior).map: times =>
      assertEquals(times, (1 to 8).map(n => n -> n * 100L).toMap)

  test("messages for different queues are handled at the same time"):
    finished(List("0", "1"), List("00", "10", "01", "11")).map: times =>
      assertEquals(times, Map(1 -> 100L, 2 -> 100L, 3 -> 200L, 4 -> 200L))

  test("maximally concurrent: the messages are handled in four rounds"):
    finished(List("0", "00", "01", "1", "10", "11"), behavior).map: times =>
      assertEquals(
        times,
        Map(
          1 -> 100L,
          2 -> 200L,
          5 -> 200L,
          6 -> 200L,
          3 -> 300L,
          4 -> 300L,
          7 -> 300L,
          8 -> 400L
        )
      )

  test(
    "mixed concurrent: '11' waits for '10', which it does not conflict with"
  ):
    finished(List("01", "1", "10"), behavior).map: times =>
      assertEquals(
        times,
        Map(
          1 -> 100L,
          2 -> 200L,
          3 -> 300L,
          4 -> 400L,
          5 -> 400L,
          6 -> 500L,
          7 -> 600L,
          8 -> 700L
        )
      )

  test("queues are listed with parents before children"):
    trie("01", "0", "1")
      .flatMap(_.queues)
      .map: queues =>
        assertEquals(queues.map(_.toBin), List("", "0", "01", "1"))

  test("split is idempotent, and makes no queue for what exists"):
    var made = 0
    for
      t <- trie("0")
      _ <- t.split(bits("0"), { made += 1; queue() })
      queues <- t.queues
    yield assertEquals((made, queues.map(_.toBin)), (0, List("", "0")))

  test("draining closes the back of the queue, and splitting opens it again"):
    val child = queue()
    for
      t <- FifoTrie.of[Int, Q](queue())
      _ <- t.split(bits("0"), child)
      before <- child.offering.get
      _ <- t.drain(bits("0"))
      draining <- (child.offering.get, child.taking.get).tupled
      _ <- t.split(bits("0"), queue())
      after <- child.offering.get
    yield assertEquals(
      (before, draining, after),
      (true, (false, true), true)
    )

  test("splitting a queue that is draining stops it draining"):
    for
      t <- trie("0")
      _ <- t.drain(bits("0"))
      _ <- t.split(bits("0"), queue())
      _ <- enqueue(t, "01" -> 1)
      forwarded <- serve(t, "")
      handed <- serve(t, "0")
    yield assertEquals(
      (forwarded, handed),
      ((true, Nil), (true, List(List(1))))
    )

  test("a draining child is not forwarded to, and is removed and closed"):
    val child = queue()
    virtual:
      for
        t <- FifoTrie.of[Int, Q](queue())
        _ <- t.split(bits("0"), child)
        _ <- enqueue(t, "01" -> 1)
        // The message goes into the child, which has not been asked for it yet.
        _ <- serve(t, "")
        _ <- t.drain(bits("0"))
        // This is the root's own now, and waits for the child to empty.
        _ <- enqueue(t, "00" -> 2)
        root <- serve(t, "").start
        _ <- IO.sleep(1.second)
        handed <- serve(t, "0")
        rootBatch <- root.joinWithNever
        queues <- t.queues
        taking <- child.taking.get
        offering <- child.offering.get
      yield
        assertEquals(handed, (true, List(List(1))))
        assertEquals(rootBatch, (true, List(List(2))))
        assertEquals(queues.map(_.toBin), List(""))
        assertEquals((taking, offering), (false, false))

  test("draining the root refuses what is enqueued, and it stays when empty"):
    for
      t <- trie()
      _ <- enqueue(t, "" -> 1)
      _ <- t.drain(BitVector.empty)
      refused <- t.enqueue(bits("1"), 2)
      handed <- serve(t, "")
      queues <- t.queues
    yield assertEquals(
      (refused, handed, queues.map(_.toBin)),
      (false, (true, List(List(1))), List(""))
    )

  test("draining a queue that is not there changes nothing"):
    for
      t <- trie("0")
      _ <- t.drain(bits("1"))
      queues <- t.queues
    yield assertEquals(queues.map(_.toBin), List("", "0"))

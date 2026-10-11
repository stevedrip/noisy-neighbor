package noisyneighbor

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.concurrent.duration.*
import scodec.bits.BitVector

import FifoTrie.Item
import TrieTesting.genKey

class FifoTrieSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(300)

  /** Two keys conflict when one is a prefix of the other. */
  def conflict(a: BitVector, b: BitVector): Boolean =
    a.startsWith(b) || b.startsWith(a)

  property(
    "conflicting messages are handled in arrival order, and all are handled"
  ):
    forAll(
      Gen.listOf(genKey).map(_.toSet),
      Gen
        .listOfN(12, genKey)
        .flatMap(keys => Gen.choose(0, keys.size).map(keys.take)),
      Gen.choose(1, 3),
      Gen.listOfN(12, Gen.choose(0, 30))
    ): (labels, keys, maxBatch, durations) =>
      type Q = MemoryFifo[Item[Int]]
      val program =
        for
          t <- FifoTrie.of[Int, Q](MemoryFifo(100))
          _ <- labels.toList.traverse_(l => t.split(l, MemoryFifo(100)))
          times <- Ref.of[IO, Map[Int, (FiniteDuration, FiniteDuration)]](
            Map.empty
          )
          work = (_: BitVector, items: List[Item[Int]]) =>
            items.traverse_ : item =>
              for
                start <- IO.monotonic
                _ <- IO.sleep(durations(item.value).millis)
                end <- IO.monotonic
                _ <- times.update(_.updated(item.value, (start, end)))
              yield ()
          fibers <- t.queues.flatMap(
            _.traverse(q => t.run(q, maxBatch)(work).start)
          )
          _ <- keys.zipWithIndex.traverse_((key, i) => t.enqueue(key, i))
          _ <- t.untilEmpty
          _ <- fibers.traverse_(_.cancel)
          result <- times.get
        yield result
      val result = TestControl.executeEmbed(program).unsafeRunSync()
      val numbered = keys.zipWithIndex
      result.keySet == numbered.map(_._2).toSet &&
      (for
        (a, i) <- numbered
        (b, j) <- numbered
        if i < j && conflict(a, b)
      yield result(i)._2 <= result(j)._1).forall(identity)

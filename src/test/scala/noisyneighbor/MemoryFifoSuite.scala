package noisyneighbor

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Chunk
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class MemoryFifoSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(300)

  /** What is done to the queue, one call at a time. */
  enum Op:
    case Offer(items: List[Int])
    case Take(count: Int)
    case FailingTake(count: Int)
    case IsEmpty

  val genOp: Gen[Op] = Gen.oneOf(
    Gen.listOf(Gen.choose(0, 99)).map(Op.Offer(_)),
    Gen.choose(1, 6).map(Op.Take(_)),
    Gen.choose(1, 6).map(Op.FailingTake(_)),
    Gen.const(Op.IsEmpty)
  )

  property(
    "it is a plain queue of limited size, and a failing take changes nothing"
  ):
    forAll(Gen.choose(1, 6), Gen.listOf(genOp)): (capacity, ops) =>
      val fifo = MemoryFifo[Int](capacity)
      val program =
        for
          // The oracle: what is in the queue, in order.
          held <- Ref.of[IO, Vector[Int]](Vector.empty)
          agreed <- ops.traverse:
            case Op.Offer(items) =>
              for
                added <- Ref.of[IO, Int](-1)
                _ <- fifo.offer(Chunk.from(items), added.set)
                n <- added.get
                before <- held.get
                expected = (capacity - before.size).min(items.size)
                _ <- held.set(before ++ items.take(expected))
              yield n == expected
            case Op.Take(count) =>
              for
                seen <- Ref.of[IO, List[Int]](Nil)
                _ <- fifo.take(count, chunk => seen.set(chunk.toList))
                got <- seen.get
                before <- held.get
                expected = before.take(count).toList
                _ <- held.set(before.drop(expected.size))
              yield got == expected
            case Op.FailingTake(count) =>
              for
                handed <- Ref.of[IO, List[Int]](Nil)
                _ <- fifo
                  .take(
                    count,
                    chunk =>
                      handed.set(chunk.toList) >>
                        IO.raiseError(new IllegalStateException("boom"))
                  )
                  .attempt
                got <- handed.get
                before <- held.get
              // It was given what a take would give, and nothing was removed.
              yield got == before.take(count).toList
            case Op.IsEmpty =>
              for
                empty <- fifo.isEmpty
                before <- held.get
              yield empty == before.isEmpty
        yield agreed.forall(identity)
      program.unsafeRunSync()

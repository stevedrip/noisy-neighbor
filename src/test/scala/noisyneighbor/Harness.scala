package noisyneighbor

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream

import scala.concurrent.duration.*

/** Runs a scenario through the real [[Runner]] on a virtual clock and records a [[Trace]] like the simulator's. */
object Harness:
  /** Runs `io` with `IO.sleep` and friends on a simulated clock. Fails if the program never terminates. */
  def virtual[A](io: IO[A]): A = TestControl.executeEmbed(io).unsafeRunSync()

  /** A message with value n takes n virtual seconds. Messages arrive at their `arrival` time, in `chunk`-sized batches
    * as far as the stream is concerned, and attempts listed in `failures` raise an error.
    */
  def execute(
      settings: Runner.Settings,
      inputs: Vector[Input],
      failures: Set[(MessageId, Int)] = Set.empty,
      chunk: Int = 1
  ): Trace = virtual(program(settings, inputs, failures, chunk))

  private def program(
      settings: Runner.Settings,
      inputs: Vector[Input],
      failures: Set[(MessageId, Int)],
      chunk: Int
  ): IO[Trace] =
    for
      t0 <- IO.monotonic
      elapsed = IO.monotonic.map(t => (t - t0).toSeconds)
      attempts <- Ref.of[IO, Map[MessageId, Int]](Map.empty)
      runs <- Ref.of[IO, Vector[Run]](Vector.empty)
      dead <- Ref.of[IO, Set[MessageId]](Set.empty)
      delivered <- Ref.of[IO, Map[MessageId, Long]](Map.empty)
      // pulled, finished, and the largest pulled - finished seen. The scheduler may know of fewer finished messages
      // than this counts, so the observed maximum can only understate the real buffer size.
      counts <- Ref.of[IO, (Int, Int, Int)]((0, 0, 0))
      finish = counts.update((p, f, m) => (p, f + 1, m))
      source = Stream
        .emits(inputs)
        .chunkLimit(chunk)
        .flatMap(Stream.chunk)
        .evalMap: input =>
          for
            now <- elapsed
            _ <- IO.sleep(math.max(0L, input.arrival - now).seconds)
            t <- elapsed
            _ <- delivered.update(_.updated(input.message.id, t))
            _ <- counts.update((p, f, m) => (p + 1, f, math.max(m, p + 1 - f)))
          yield input.message
      nextAttempt = (id: MessageId) =>
        attempts.modify: a =>
          val n = a.getOrElse(id, 0) + 1
          (a.updated(id, n), n)
      handler = (m: Message) =>
        for
          attempt <- nextAttempt(m.id)
          start <- elapsed
          _ <- IO.sleep(m.value.seconds)
          end <- elapsed
          ok = !failures((m.id, attempt))
          _ <- runs.update(_ :+ Run(m.id, attempt, start, end, ok))
          _ <- if ok then finish else IO.raiseError(new RuntimeException("injected failure"))
        yield ()
      _ <- Runner.run(settings, source, handler, m => dead.update(_ + m.id) *> finish)
      trace <- (runs.get, dead.get, counts.get, delivered.get).mapN: (r, d, c, del) =>
        Trace(r, d, maxUnfinished = c._3, deliveredAt = del, deadlocked = false)
    yield trace

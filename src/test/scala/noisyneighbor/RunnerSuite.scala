package noisyneighbor

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import scala.concurrent.duration.*

import Checks.check
import Fixtures.{genScenario, msg}
import Harness.virtual

class RunnerSuite extends ScalaCheckSuite:
  private def at(arrival: Long, m: Message): Input = Input(m, arrival)

  private def settings(workers: Int, config: Config = Config(10, 3)) = Runner.Settings(config, workers)

  // --- properties over random scenarios, checked with the same oracle as the pure simulator ---

  property("conflicting messages run in arrival order, whatever the workers and capacity"):
    forAll(genScenario, Gen.choose(1, 8)): (sc, chunk) =>
      val trace = Harness.execute(Runner.Settings(sc.config, sc.workers), sc.inputs, sc.failures, chunk)
      check(Checks.orderingViolations(sc.inputs, trace))

  property("every message finishes, and retries stop at maxAttempts"):
    forAll(genScenario, Gen.choose(1, 8)): (sc, chunk) =>
      val trace = Harness.execute(Runner.Settings(sc.config, sc.workers), sc.inputs, sc.failures, chunk)
      check(Checks.completionViolations(sc.inputs, sc.config.maxAttempts, trace))

  property("the buffer never exceeds capacity"):
    forAll(genScenario): sc =>
      val trace = Harness.execute(Runner.Settings(sc.config, sc.workers), sc.inputs, sc.failures)
      check(Option.when(trace.maxUnfinished > sc.config.capacity)(s"${trace.maxUnfinished} > ${sc.config.capacity}").toList)

  property("with enough workers, each message starts at the earliest allowed time"):
    forAll(genScenario, Gen.choose(1, 8)): (sc, chunk) =>
      val config = sc.config.copy(capacity = sc.inputs.size + 1)
      val trace = Harness.execute(Runner.Settings(config, sc.inputs.size), sc.inputs, sc.failures, chunk)
      check(Checks.earliestStartViolations(sc.inputs, trace))

  // --- scenarios ---

  test("README worked example, timed"):
    val a = msg(0, "01", 2)
    val b = msg(1, "", 1)
    val c = msg(2, "10", 3)
    val trace = Harness.execute(settings(workers = 3), Vector(at(0, a), at(0, b), at(0, c)))
    assertEquals(
      trace.runs.sortBy(_.start).map(r => (r.id, r.start, r.end)),
      Vector((a.id, 0L, 2L), (b.id, 2L, 3L), (c.id, 3L, 6L))
    )

  test("a full buffer pauses reads until a message finishes"):
    val ms = Vector(msg(0, "00", 5), msg(1, "01", 5), msg(2, "1", 5))
    val trace = Harness.execute(settings(workers = 3, Config(capacity = 2, maxAttempts = 3)), ms.map(at(0, _)))
    assertEquals(trace.deliveredAt(ms(2).id), 5L)
    assert(trace.maxUnfinished <= 2, trace.maxUnfinished)

  test("a retry waits out the backoff, and keeps its subtree waiting"):
    val a = msg(0, "0", 2)
    val b = msg(1, "00", 1)
    val trace = Harness.execute(
      Runner.Settings(Config(10, 3), workers = 2, backoff = retry => (retry * 3).seconds),
      Vector(at(0, a), at(0, b)),
      failures = Set((a.id, 1))
    )
    assertEquals(trace.runsOf(a.id).map(r => (r.start, r.end, r.succeeded)), Vector((0L, 2L, false), (5L, 7L, true)))
    assertEquals(trace.firstStart(b.id), Some(7L))

  test("chunking of the source does not change the schedule"):
    val ms = Vector(msg(0, "0", 2), msg(1, "", 1), msg(2, "10", 3), msg(3, "01", 1), msg(4, "11", 2))
    val traces = List(1, 2, 3, 10).map(c => Harness.execute(settings(workers = 5), ms.map(at(0, _)), chunk = c))
    val starts = traces.map(t => ms.map(m => t.firstStart(m.id)))
    assertEquals(starts.distinct.size, 1, starts)

  test("a hung handler times out, counts as failed, and is dead-lettered"):
    val a = msg(0, "0", 1)
    val b = msg(1, "00", 1)
    val result = virtual:
      for
        t0 <- IO.monotonic
        dead <- Ref.of[IO, List[(MessageId, Long)]](Nil)
        started <- Ref.of[IO, Map[MessageId, Long]](Map.empty)
        run <- Runner.run(
          Runner.Settings(Config(10, maxAttempts = 1), workers = 2, handlerTimeout = Some(2.seconds)),
          Stream.emits(Vector(a, b)).covary[IO],
          m =>
            IO.monotonic.flatMap(t => started.update(_.updated(m.id, (t - t0).toSeconds))) *>
              (if m.id == a.id then IO.never else IO.unit),
          m => IO.monotonic.flatMap(t => dead.update(_ :+ ((m.id, (t - t0).toSeconds))))
        )
        d <- dead.get
        s <- started.get
      yield (d, s)
    assertEquals(result._1, List((a.id, 2L)))
    assertEquals(result._2.get(b.id), Some(2L)) // released only once a was dead-lettered

  test("an empty source finishes immediately"):
    virtual(Runner.run(settings(workers = 2), Stream.empty.covaryAll[IO, Message], _ => IO.unit))

  test("a failing source fails the run"):
    val boom = new RuntimeException("boom")
    val result = virtual:
      Runner
        .run(settings(workers = 2), Stream.emit(msg(0, "0")).covary[IO] ++ Stream.raiseError[IO](boom), _ => IO.unit)
        .attempt
    assertEquals(result, Left(boom))

  test("a failing dead-letter sink fails the run"):
    val boom = new RuntimeException("dlq down")
    val result = virtual:
      Runner
        .run(
          Runner.Settings(Config(10, maxAttempts = 1), workers = 1),
          Stream.emit(msg(0, "0")).covary[IO],
          _ => IO.raiseError(new RuntimeException("handler failed")),
          _ => IO.raiseError(boom)
        )
        .attempt
    assertEquals(result, Left(boom))

package noisyneighbor

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

import Checks.check
import Fixtures.{genScenario, msg}

class SimulationSuite extends ScalaCheckSuite:
  private def at(arrival: Long, m: Message): Input = Input(m, arrival)

  // --- properties over random scenarios ---

  property("conflicting messages run in arrival order, whatever the workers and capacity"):
    forAll(genScenario): sc =>
      val trace = Simulator.run(sc.config, sc.workers, sc.inputs, sc.failures)
      check(Checks.orderingViolations(sc.inputs, trace))

  property("every message finishes: no deadlock, and retries stop at maxAttempts"):
    forAll(genScenario): sc =>
      val trace = Simulator.run(sc.config, sc.workers, sc.inputs, sc.failures)
      check(Checks.completionViolations(sc.inputs, sc.config.maxAttempts, trace))

  property("the buffer never exceeds capacity"):
    forAll(genScenario): sc =>
      val trace = Simulator.run(sc.config, sc.workers, sc.inputs, sc.failures)
      check(Option.when(trace.maxUnfinished > sc.config.capacity)(s"${trace.maxUnfinished} > ${sc.config.capacity}").toList)

  property("with unlimited workers, each message starts at the earliest allowed time"):
    forAll(genScenario): sc =>
      val config = sc.config.copy(capacity = sc.inputs.size + 1)
      val trace = Simulator.run(config, Int.MaxValue, sc.inputs, sc.failures)
      check(Checks.earliestStartViolations(sc.inputs, trace))

  // --- scenarios ---

  test("README worked example, timed"):
    val a = msg(0, "01", 2)
    val b = msg(1, "", 1)
    val c = msg(2, "10", 3)
    val trace = Simulator.run(Config(10, 3), Int.MaxValue, Vector(at(0, a), at(0, b), at(0, c)))
    assertEquals(trace.runs.map(r => (r.id, r.start, r.end)), Vector((a.id, 0L, 2L), (b.id, 2L, 3L), (c.id, 3L, 6L)))

  test("later leaf messages cannot starve a waiting barrier"):
    val busy = msg(0, "00", 4)
    val barrier = msg(1, "", 1)
    val leaf = msg(2, "01", 1) // arrives while the barrier waits, and conflicts with nothing in flight
    val trace = Simulator.run(Config(10, 3), Int.MaxValue, Vector(at(0, busy), at(1, barrier), at(2, leaf)))
    assertEquals(trace.firstStart(barrier.id), Some(4L))
    assertEquals(trace.firstStart(leaf.id), Some(5L)) // not 2: it must not overtake the barrier

  test("a retried message keeps its subtree waiting until it is dead-lettered"):
    val a = msg(0, "0", 2)
    val b = msg(1, "00", 1)
    val trace = Simulator.run(
      Config(10, maxAttempts = 2),
      Int.MaxValue,
      Vector(at(0, a), at(0, b)),
      failures = Set((a.id, 1), (a.id, 2))
    )
    assertEquals(trace.runsOf(a.id).map(r => (r.start, r.end, r.succeeded)), Vector((0L, 2L, false), (2L, 4L, false)))
    assertEquals(trace.deadLettered, Set(a.id))
    assertEquals(trace.firstStart(b.id), Some(4L))

  test("a full buffer pauses reads until a message finishes"):
    val ms = Vector(msg(0, "00", 5), msg(1, "01", 5), msg(2, "1", 5))
    val trace = Simulator.run(Config(capacity = 2, maxAttempts = 3), Int.MaxValue, ms.map(at(0, _)))
    assertEquals(trace.deliveredAt(ms(0).id), 0L)
    assertEquals(trace.deliveredAt(ms(1).id), 0L)
    assertEquals(trace.deliveredAt(ms(2).id), 5L)
    assertEquals(trace.maxUnfinished, 2)
    assert(!trace.deadlocked)

  test("one worker still honors order and finishes"):
    val ms = Vector(msg(0, "0", 1), msg(1, "1", 1), msg(2, "", 1), msg(3, "01", 1))
    val trace = Simulator.run(Config(10, 3), workers = 1, ms.map(at(0, _)))
    assertEquals(trace.runs.map(_.id), ms.map(_.id))
    assert(!trace.deadlocked)

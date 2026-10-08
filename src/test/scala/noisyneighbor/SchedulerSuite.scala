package noisyneighbor

import munit.FunSuite

import Fixtures.msg

class SchedulerSuite extends FunSuite:
  private val config = Config(capacity = 10, maxAttempts = 3)

  /** Feeds events in order, returning the final state and the commands each event produced. */
  private def feed(config: Config, events: Event*): (Scheduler.State, List[List[Command]]) =
    events.foldLeft((Scheduler.init(config), List.empty[List[Command]])):
      case ((state, out), event) =>
        val (next, commands) = Scheduler.step(state, event)
        (next, out :+ commands)

  test("README worked example: a barrier waits for earlier messages and holds back later ones"):
    val a = msg(0, "01", 2)
    val b = msg(1, "", 1)
    val c = msg(2, "10", 3)
    val (_, out) = feed(
      config,
      Event.Arrived(a),
      Event.Arrived(b),
      Event.Arrived(c), // does not conflict with a, but must not overtake the waiting barrier
      Event.Completed(a.id),
      Event.Completed(b.id)
    )
    assertEquals(
      out,
      List(
        List(Command.Dispatch(List(a))),
        Nil,
        Nil,
        List(Command.Dispatch(List(b))),
        List(Command.Dispatch(List(c)))
      )
    )

  test("non-conflicting messages are dispatched together"):
    val a = msg(0, "00")
    val b = msg(1, "01")
    val c = msg(2, "1")
    val (_, out) = feed(config, Event.Arrived(a), Event.Arrived(b), Event.Arrived(c))
    assertEquals(out.flatten, List(Command.Dispatch(List(a)), Command.Dispatch(List(b)), Command.Dispatch(List(c))))

  test("equal keys run one at a time, in arrival order"):
    val a = msg(0, "01")
    val b = msg(1, "01")
    val (_, out) = feed(config, Event.Arrived(a), Event.Arrived(b), Event.Completed(a.id))
    assertEquals(out, List(List(Command.Dispatch(List(a))), Nil, List(Command.Dispatch(List(b)))))

  test("a failed message is redispatched while its subtree keeps waiting"):
    val a = msg(0, "0")
    val b = msg(1, "00")
    val (_, out) = feed(config, Event.Arrived(a), Event.Arrived(b), Event.Failed(a.id))
    assertEquals(out, List(List(Command.Dispatch(List(a))), Nil, List(Command.Dispatch(List(a)))))

  test("a dead-lettered message counts as finished and releases its subtree"):
    val a = msg(0, "0")
    val b = msg(1, "00")
    val (_, out) = feed(
      Config(capacity = 10, maxAttempts = 2),
      Event.Arrived(a),
      Event.Arrived(b),
      Event.Failed(a.id),
      Event.Failed(a.id)
    )
    assertEquals(out.last, List(Command.DeadLetter(a), Command.Dispatch(List(b))))

  test("stale arrivals and duplicate acks are ignored"):
    val a = msg(0, "0")
    val (state, out) = feed(
      config,
      Event.Arrived(a),
      Event.Arrived(a),
      Event.Completed(a.id),
      Event.Completed(a.id),
      Event.Failed(a.id),
      Event.Arrived(a) // replayed after it finished
    )
    assertEquals(out, List(List(Command.Dispatch(List(a))), Nil, Nil, Nil, Nil, Nil))
    assertEquals(state.unfinished, 0)

  test("reads pause at capacity and resume as messages finish"):
    val a = msg(0, "00")
    val b = msg(1, "01")
    val (_, out) = feed(
      Config(capacity = 2, maxAttempts = 3),
      Event.Arrived(a),
      Event.Arrived(b),
      Event.Completed(a.id)
    )
    assertEquals(
      out,
      List(
        List(Command.Dispatch(List(a))),
        List(Command.Dispatch(List(b)), Command.PauseReads),
        List(Command.ResumeReads)
      )
    )

  test("resumeFrom is the oldest unfinished message, or the next sequence number"):
    val a = msg(0, "00")
    val b = msg(1, "01")
    val fresh = Scheduler.init(config)
    assertEquals(fresh.resumeFrom, 0L)
    val (state, _) = feed(config, Event.Arrived(a), Event.Arrived(b), Event.Completed(b.id))
    assertEquals(state.resumeFrom, 0L) // a is still unfinished, so b's completion does not move the checkpoint
    val (done, _) = feed(config, Event.Arrived(a), Event.Arrived(b), Event.Completed(b.id), Event.Completed(a.id))
    assertEquals(done.resumeFrom, 2L)

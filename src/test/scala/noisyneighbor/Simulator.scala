package noisyneighbor

import scala.annotation.tailrec
import scala.collection.immutable.Queue

/** A message and the time it becomes available on the stream. Arrival times must not decrease in `seq` order. */
final case class Input(message: Message, arrival: Long)

/** One execution of a message by a worker. */
final case class Run(id: MessageId, attempt: Int, start: Long, end: Long, succeeded: Boolean)

final case class Trace(
    runs: Vector[Run],
    deadLettered: Set[MessageId],
    maxUnfinished: Int,
    deliveredAt: Map[MessageId, Long],
    deadlocked: Boolean
):
  def runsOf(id: MessageId): Vector[Run] = runs.filter(_.id == id)
  def firstStart(id: MessageId): Option[Long] = runsOf(id).map(_.start).minOption
  def lastEnd(id: MessageId): Option[Long] = runsOf(id).map(_.end).maxOption

/** Drives the scheduler's pure core on a virtual clock. A message with value n runs for n seconds.
  *
  * The reader delivers messages at their arrival time unless the scheduler has paused reads. Dispatched messages queue
  * for a fixed number of workers in dispatch order. When a completion and an arrival coincide, the completion goes
  * first.
  */
object Simulator:
  private final case class Running(run: Run, order: Int)

  private final case class Sim(
      clock: Long,
      scheduler: Scheduler.State,
      next: Int,
      paused: Boolean,
      ready: Queue[Message],
      running: Vector[Running],
      started: Map[MessageId, Int],
      runs: Vector[Run],
      dead: Set[MessageId],
      maxUnfinished: Int,
      delivered: Map[MessageId, Long]
  )

  def run(
      config: Config,
      workers: Int,
      inputs: Vector[Input],
      failures: Set[(MessageId, Int)] = Set.empty
  ): Trace =
    def feed(sim: Sim, event: Event): Sim =
      val (nextState, commands) = Scheduler.step(sim.scheduler, event)
      val applied = commands.foldLeft(sim.copy(scheduler = nextState)):
        case (s, Command.Dispatch(ms))   => s.copy(ready = s.ready ++ ms)
        case (s, Command.DeadLetter(m))  => s.copy(dead = s.dead + m.id)
        case (s, Command.PauseReads)     => s.copy(paused = true)
        case (s, Command.ResumeReads)    => s.copy(paused = false)
      applied.copy(maxUnfinished = math.max(applied.maxUnfinished, nextState.unfinished))

    @tailrec def startWorkers(sim: Sim): Sim =
      if sim.running.size >= workers || sim.ready.isEmpty then sim
      else
        val (m, rest) = sim.ready.dequeue
        val attempt = sim.started.getOrElse(m.id, 0) + 1
        val r = Run(m.id, attempt, sim.clock, sim.clock + m.value, succeeded = !failures((m.id, attempt)))
        startWorkers(
          sim.copy(
            ready = rest,
            running = sim.running :+ Running(r, sim.runs.size),
            started = sim.started.updated(m.id, attempt),
            runs = sim.runs :+ r
          )
        )

    @tailrec def loop(sim: Sim): Sim =
      val nextArrival =
        Option.when(!sim.paused && sim.next < inputs.size)(math.max(sim.clock, inputs(sim.next).arrival))
      val nextDone = sim.running.minByOption(r => (r.run.end, r.order))
      nextDone match
        case Some(done) if nextArrival.forall(done.run.end <= _) =>
          val event = if done.run.succeeded then Event.Completed(done.run.id) else Event.Failed(done.run.id)
          val s = feed(sim.copy(clock = done.run.end, running = sim.running.filterNot(_.order == done.order)), event)
          loop(startWorkers(s))
        case _ =>
          nextArrival match
            case Some(t) =>
              val input = inputs(sim.next)
              val s = feed(
                sim.copy(
                  clock = t,
                  next = sim.next + 1,
                  delivered = sim.delivered.updated(input.message.id, t)
                ),
                Event.Arrived(input.message)
              )
              loop(startWorkers(s))
            case None => sim

    val start = Sim(
      clock = 0L,
      scheduler = Scheduler.init(config),
      next = 0,
      paused = false,
      ready = Queue.empty,
      running = Vector.empty,
      started = Map.empty,
      runs = Vector.empty,
      dead = Set.empty,
      maxUnfinished = 0,
      delivered = Map.empty
    )
    val end = loop(start)
    Trace(
      runs = end.runs,
      deadLettered = end.dead,
      maxUnfinished = end.maxUnfinished,
      deliveredAt = end.delivered,
      deadlocked = end.scheduler.unfinished > 0 || end.next < inputs.size
    )

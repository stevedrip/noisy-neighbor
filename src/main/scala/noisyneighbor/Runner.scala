package noisyneighbor

import cats.effect.{Deferred, IO}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.{Pull, Stream}
import fs2.concurrent.SignallingRef

import scala.concurrent.duration.*

/** The effectful shell around the pure scheduling core.
  *
  * One fiber (the loop) owns the scheduler state and is the only place `Scheduler.step` is called. Everything else
  * talks to it through a queue:
  *   - the reader pulls messages from the source and hands them to the loop one at a time, waiting for the loop to
  *     process each before it checks whether reads are paused, so the buffer never exceeds `capacity`;
  *   - workers run the handler for dispatched messages and report back `Completed` or `Failed`.
  *
  * The source is a stream of messages in arrival order. How it is chunked (the batch size of whatever produced or
  * fetched them) does not matter: messages are scheduled one at a time.
  */
object Runner:
  /** @param workers
    *   Number of concurrent handler invocations.
    * @param handlerTimeout
    *   A handler still running after this long counts as failed.
    * @param backoff
    *   Delay before a retry, given the retry number (1 for the first retry). The delay occupies a worker.
    */
  final case class Settings(
      config: Config,
      workers: Int,
      handlerTimeout: Option[FiniteDuration] = None,
      backoff: Int => FiniteDuration = _ => Duration.Zero
  ):
    require(workers >= 1, "workers must be at least 1")

  private enum Input:
    /** `processed` is completed once the loop has handled the arrival, including any pause it caused. */
    case Arrival(message: Message, processed: Deferred[IO, Unit])
    case Ack(event: Event)
    case EndOfInput
    case SourceFailed(error: Throwable)

  private final case class Loop(
      scheduler: Scheduler.State,
      inputDone: Boolean,
      dispatches: Map[MessageId, Int]
  )

  /** Runs until the source is exhausted and every message has finished (completed or dead-lettered).
    *
    * A handler that raises an error, or times out, counts as a failed attempt. `deadLetter` is called, in order with
    * the scheduling of later messages, when a message exhausts its attempts. If `deadLetter` or the source fails, the
    * whole run fails.
    */
  def run(
      settings: Settings,
      source: Stream[IO, Message],
      handler: Message => IO[Unit],
      deadLetter: Message => IO[Unit] = _ => IO.unit
  ): IO[Unit] =
    for
      inputs <- Queue.unbounded[IO, Input]
      work <- Queue.unbounded[IO, (Message, Int)]
      paused <- SignallingRef[IO, Boolean](false)
      _ <- {
        val workers = List.fill(settings.workers)(worker(settings, work, inputs, handler).background).sequence_
        (workers *> reader(source, inputs, paused).background).use: _ =>
          loop(inputs, work, paused, deadLetter)(
            Loop(Scheduler.init(settings.config), inputDone = false, dispatches = Map.empty)
          )
      }
    yield ()

  private def worker(
      settings: Settings,
      work: Queue[IO, (Message, Int)],
      inputs: Queue[IO, Input],
      handler: Message => IO[Unit]
  ): IO[Nothing] =
    work.take
      .flatMap: (message, attempt) =>
        val delay = if attempt > 1 then settings.backoff(attempt - 1) else Duration.Zero
        val run = settings.handlerTimeout.fold(handler(message))(handler(message).timeout(_))
        (IO.sleep(delay).whenA(delay > Duration.Zero) *> run).attempt.flatMap: result =>
          inputs.offer(Input.Ack(result.fold(_ => Event.Failed(message.id), _ => Event.Completed(message.id))))
      .foreverM

  /** Checks the pause signal before pulling each message, so a paused reader does not read ahead. */
  private def reader(
      source: Stream[IO, Message],
      inputs: Queue[IO, Input],
      paused: SignallingRef[IO, Boolean]
  ): IO[Unit] =
    def deliver(message: Message): IO[Unit] =
      Deferred[IO, Unit].flatMap(processed => inputs.offer(Input.Arrival(message, processed)) *> processed.get)

    def go(rest: Stream[IO, Message]): Pull[IO, Nothing, Unit] =
      Pull.eval(paused.waitUntil(!_)) >> rest.pull.uncons1.flatMap:
        case None                => Pull.done
        case Some((message, tl)) => Pull.eval(deliver(message)) >> go(tl)

    go(source).stream.compile.drain.attempt.flatMap:
      case Right(_) => inputs.offer(Input.EndOfInput)
      case Left(e)  => inputs.offer(Input.SourceFailed(e))

  private def loop(
      inputs: Queue[IO, Input],
      work: Queue[IO, (Message, Int)],
      paused: SignallingRef[IO, Boolean],
      deadLetter: Message => IO[Unit]
  )(state: Loop): IO[Unit] =
    def apply(current: Loop, event: Event): IO[Loop] =
      val (next, commands) = Scheduler.step(current.scheduler, event)
      val settled = event match
        case Event.Completed(id) => current.dispatches - id
        case _                   => current.dispatches
      commands.foldLeftM(Loop(next, current.inputDone, settled)):
        case (acc, Command.Dispatch(messages)) =>
          val dispatches = messages.foldLeft(acc.dispatches)((d, m) => d.updated(m.id, d.getOrElse(m.id, 0) + 1))
          messages.traverse_(m => work.offer((m, dispatches(m.id)))).as(acc.copy(dispatches = dispatches))
        case (acc, Command.DeadLetter(message)) =>
          deadLetter(message).as(acc.copy(dispatches = acc.dispatches - message.id))
        case (acc, Command.PauseReads)  => paused.set(true).as(acc)
        case (acc, Command.ResumeReads) => paused.set(false).as(acc)

    def handle(current: Loop, input: Input): IO[Loop] =
      input match
        case Input.Arrival(message, processed) => apply(current, Event.Arrived(message)) <* processed.complete(())
        case Input.Ack(event)                  => apply(current, event)
        case Input.EndOfInput                  => IO.pure(current.copy(inputDone = true))
        case Input.SourceFailed(error)         => IO.raiseError(error)

    if state.inputDone && state.scheduler.unfinished == 0 then IO.unit
    else inputs.take.flatMap(handle(state, _)).flatMap(loop(inputs, work, paused, deadLetter))

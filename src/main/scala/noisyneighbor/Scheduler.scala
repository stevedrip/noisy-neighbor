package noisyneighbor

import scala.collection.immutable.TreeMap

import Trie.given

/** @param capacity
  *   Bound on unfinished messages (waiting plus in flight). Reads pause when it is reached and resume when it is not.
  * @param maxAttempts
  *   Total number of times a message may run. After that many failures it is dead-lettered, which counts as finished.
  */
final case class Config(capacity: Int, maxAttempts: Int):
  require(capacity >= 1, "capacity must be at least 1")
  require(maxAttempts >= 1, "maxAttempts must be at least 1")

/** The pure scheduling core: `step(state, event) => (state, commands)`.
  *
  * A message is admitted, and dispatched, once no earlier unfinished message conflicts with it. Waiting messages stay
  * in the trie, so a waiting barrier holds back later conflicting messages. Admitted messages are therefore pairwise
  * non-conflicting.
  *
  * Preconditions on the event stream: messages arrive in increasing `seq` order. Arrivals with a `seq` at or below
  * the highest seen, and acks for messages that are not in flight, are ignored, which makes replay after a restart
  * and duplicate acks harmless.
  */
object Scheduler:
  final case class Entry(message: Message, failures: Int, inFlight: Boolean)

  final case class State(
      config: Config,
      trie: Trie[Long],
      entries: Map[MessageId, Entry],
      waiting: TreeMap[Long, MessageId],
      lastSeq: Option[Long],
      paused: Boolean
  ):
    /** Messages that have arrived and not finished. */
    def unfinished: Int = entries.size

    /** The sequence number to replay from after a restart: everything below it is finished. */
    def resumeFrom: Long = trie.min.getOrElse(lastSeq.fold(0L)(_ + 1))

  def init(config: Config): State =
    State(config, Trie.empty[Long], Map.empty, TreeMap.empty, lastSeq = None, paused = false)

  def step(state: State, event: Event): (State, List[Command]) =
    val (afterEvent, eventCommands) = event match
      case Event.Arrived(message) => (arrive(state, message), Nil)
      case Event.Completed(id)    => (complete(state, id), Nil)
      case Event.Failed(id)       => fail(state, id)
    val (afterAdmit, dispatched) = admit(afterEvent)
    val (afterFlow, flow) = controlFlow(afterAdmit)
    (afterFlow, eventCommands ++ dispatched ++ flow)

  private def arrive(state: State, message: Message): State =
    val seen = state.lastSeq.exists(message.seq <= _) || state.entries.contains(message.id)
    if seen then state
    else
      state.copy(
        trie = state.trie.insert(message.key.bits, message.seq),
        entries = state.entries.updated(message.id, Entry(message, failures = 0, inFlight = false)),
        waiting = state.waiting.updated(message.seq, message.id),
        lastSeq = Some(message.seq)
      )

  private def remove(state: State, entry: Entry): State =
    state.copy(
      trie = state.trie.remove(entry.message.key.bits, entry.message.seq),
      entries = state.entries - entry.message.id
    )

  private def complete(state: State, id: MessageId): State =
    state.entries.get(id) match
      case Some(entry) if entry.inFlight => remove(state, entry)
      case _                             => state

  private def fail(state: State, id: MessageId): (State, List[Command]) =
    state.entries.get(id) match
      case Some(entry) if entry.inFlight =>
        val failures = entry.failures + 1
        if failures >= state.config.maxAttempts then (remove(state, entry), List(Command.DeadLetter(entry.message)))
        else
          // Still the earliest unfinished conflicting message, so `admit` will dispatch it again.
          val retry = entry.copy(failures = failures, inFlight = false)
          (
            state.copy(
              entries = state.entries.updated(id, retry),
              waiting = state.waiting.updated(entry.message.seq, id)
            ),
            Nil
          )
      case _ => (state, Nil)

  private def admit(state: State): (State, List[Command]) =
    val admitted = state.waiting.valuesIterator
      .map(state.entries)
      .filter(e => state.trie.minConflicting(e.message.key.bits).contains(e.message.seq))
      .toList
    if admitted.isEmpty then (state, Nil)
    else
      val next = state.copy(
        entries = admitted.foldLeft(state.entries)((es, e) => es.updated(e.message.id, e.copy(inFlight = true))),
        waiting = state.waiting -- admitted.map(_.message.seq)
      )
      (next, List(Command.Dispatch(admitted.map(_.message))))

  private def controlFlow(state: State): (State, List[Command]) =
    val full = state.unfinished >= state.config.capacity
    if full && !state.paused then (state.copy(paused = true), List(Command.PauseReads))
    else if !full && state.paused then (state.copy(paused = false), List(Command.ResumeReads))
    else (state, Nil)

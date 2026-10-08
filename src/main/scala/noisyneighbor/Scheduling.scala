package noisyneighbor

opaque type MessageId = String

object MessageId:
  def apply(s: String): MessageId = s

  extension (id: MessageId) def value: String = id

/** @param seq
  *   Arrival order on the root topic. Total across all shards.
  * @param value
  *   Processing time in seconds (test workload).
  */
final case class Message(id: MessageId, seq: Long, key: Key, value: Int)

/** Inputs to the scheduler's pure core. An ack names the whole message, because
  * its key is how the scheduler finds it.
  */
enum Event:
  case Arrived(message: Message)
  case Completed(message: Message)

/** Outputs of the scheduler's pure core, interpreted by the effectful shell. */
enum Command:
  case Dispatch(messages: List[Message])
  case PauseReads
  case ResumeReads

package noisyneighbor.example

import cats.effect.IO
import cats.syntax.all.*
import noisyneighbor.{FifoTrie, MemoryFifo}
import scodec.bits.BitVector

/** Parsing what the examples are given on the command line and on stdin. */
object Input:
  /** A key, the seconds it takes to produce, and the seconds it takes to
    * consume.
    */
  type Message = (BitVector, Int, Int)

  /** A string of 0 and 1. The empty string is the empty key, the root's. */
  def bits(s: String): Either[String, BitVector] =
    if s.forall(c => c == '0' || c == '1') then Right(BitVector.fromValidBin(s))
    else Left(s"\"$s\" is not a string of 0 and 1")

  /** Absolute labels separated by commas, such as `01,1,10`. The root exists
    * without being listed, so an empty label is left out.
    */
  def labels(csv: String): Either[String, List[BitVector]] =
    csv.split(",", -1).toList.map(_.trim).filter(_.nonEmpty).traverse(bits)

  /** The trie with a queue for each of the absolute labels, each in memory and
    * holding up to `capacity` messages.
    */
  def trie[A](
    labels: List[BitVector],
    capacity: Int = 1000
  ): IO[FifoTrie[A, MemoryFifo[FifoTrie.Item[A]]]] =
    FifoTrie
      .of[A, MemoryFifo[FifoTrie.Item[A]]](MemoryFifo(capacity))
      .flatTap(t => labels.traverse_(l => t.split(l, MemoryFifo(capacity))))

  /** A line of `key,produceSeconds,consumeSeconds`, such as `01,1,8`. The key
    * may be empty, as in `,1,8`. A blank line or one starting with `#` is
    * nothing.
    */
  def message(line: String): Either[String, Option[Message]] =
    val text = line.trim
    if text.isEmpty || text.startsWith("#") then Right(None)
    else
      text.split(",", -1).toList.map(_.trim) match
        case List(key, produce, consume) =>
          for
            k <- bits(key)
            p <- seconds(produce)
            c <- seconds(consume)
          yield Some((k, p, c))
        case fields =>
          Left(
            s"expected key,produceSeconds,consumeSeconds but found ${fields.size} fields"
          )

  /** The messages of a file, in order, with the number of the line that is
    * wrong.
    */
  def messages(lines: List[String]): Either[String, List[Message]] =
    lines.zipWithIndex
      .traverse((line, i) => message(line).left.map(e => s"line ${i + 1}: $e"))
      .map(_.flatten)

  private def seconds(s: String): Either[String, Int] =
    s.toIntOption
      .filter(_ >= 0)
      .toRight(s"\"$s\" is not a number of seconds")

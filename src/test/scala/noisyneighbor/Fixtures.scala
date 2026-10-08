package noisyneighbor

import org.scalacheck.Gen
import scodec.bits.BitVector

object Fixtures:
  def key(s: String): Key = Key.parse(s).fold(sys.error, identity)

  def msg(seq: Long, k: String, value: Int = 1): Message =
    Message(MessageId(s"m$seq"), seq, key(k), value)

  /** Short keys, so random messages conflict often. */
  val genKey: Gen[Key] =
    for
      length <- Gen.choose(0, 4)
      bits <- Gen.listOfN(length, Gen.oneOf(true, false))
    yield Key(BitVector.bits(bits))

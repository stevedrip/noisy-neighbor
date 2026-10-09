package noisyneighbor

import scodec.bits.BitVector

/** A bit string, most significant bit first. The empty key is the root.
  *
  * Two keys conflict when one is a prefix of the other, or they are equal.
  * Length is part of a key's identity: "0" and "00" are different keys.
  *
  * This is an opaque type, so a `Key` is a `BitVector` at runtime with no
  * wrapper object. The type keeps keys apart from other bit vectors.
  */
opaque type Key = BitVector

object Key:
  def apply(bits: BitVector): Key = bits

  val root: Key = BitVector.empty

  extension (key: Key)
    def bits: BitVector = key

    def isPrefixOf(that: Key): Boolean = that.startsWith(key)

    def conflictsWith(that: Key): Boolean =
      key.isPrefixOf(that) || that.isPrefixOf(key)

    /** The key as a string of '0' and '1' characters. */
    def show: String = key.toBin

  /** Parses a string of '0' and '1' characters, e.g. "010". */
  def parse(s: String): Either[String, Key] =
    s.find(c => c != '0' && c != '1') match
      case Some(c) => Left(s"Not a bit: '$c'")
      case None => Right(BitVector.fromValidBin(s))

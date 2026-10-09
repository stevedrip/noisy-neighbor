package noisyneighbor

/** A bit string, most significant bit first. The empty key is the root. */
final case class Key(bits: Vector[Boolean]):
  def isPrefixOf(that: Key): Boolean =
    bits.length <= that.bits.length && that.bits.startsWith(bits)

  /** Equal keys, or one key a prefix of the other. */
  def conflictsWith(that: Key): Boolean =
    isPrefixOf(that) || that.isPrefixOf(this)

object Key:
  val root: Key = Key(Vector.empty)

  /** Parses a string of '0' and '1' characters, e.g. "010". */
  def parse(s: String): Either[String, Key] =
    s.foldLeft[Either[String, Vector[Boolean]]](Right(Vector.empty)) {
      case (acc, '0') => acc.map(_ :+ false)
      case (acc, '1') => acc.map(_ :+ true)
      case (_, c)     => Left(s"Not a bit: '$c'")
    }.map(Key(_))

package noisyneighbor

import algebra.lattice.GenBool
import cats.kernel.Eq
import org.scalacheck.{Arbitrary, Gen}
import scodec.bits.BitVector

/** What the trie suites draw from. */
object TrieTesting:
  /** Short keys, so random keys overlap often. */
  val genKey: Gen[BitVector] =
    for
      length <- Gen.choose(0, 4)
      bits <- Gen.listOfN(length, Gen.oneOf(true, false))
    yield BitVector.bits(bits)

  /** Every key of up to four bits: the keys the generators draw from. */
  val universe: List[BitVector] =
    (0 to 4).toList.flatMap: n =>
      (0 until (1 << n)).toList.map: i =>
        BitVector.fromLong(i.toLong, n)

  def genEntries[A: Arbitrary]: Gen[List[(BitVector, A)]] =
    Gen
      .choose(0, 10)
      .flatMap(Gen.listOfN(_, Gen.zip(genKey, Arbitrary.arbitrary[A])))

  /** Built with the trie's own union. */
  def build[A](entries: List[(BitVector, A)])(using
    GenBool[A],
    Eq[A]
  ): Trie[A] =
    val trieBool = summon[GenBool[Trie[A]]]
    entries.foldLeft(Trie.empty[A])((t, e) =>
      trieBool.or(t, Trie.single(e._1, e._2))
    )

  given arbitraryTrie[A](using
    GenBool[A],
    Eq[A],
    Arbitrary[A]
  ): Arbitrary[Trie[A]] =
    Arbitrary(genEntries[A].map(build[A]))

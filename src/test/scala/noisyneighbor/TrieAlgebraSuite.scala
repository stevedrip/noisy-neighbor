package noisyneighbor

import algebra.instances.boolean.given
import algebra.instances.set.setLattice
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Gen}

class TrieAlgebraSuite extends DisciplineSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(200)

  /** Sets of a few distinct integers, so that two sets often share some. */
  given Arbitrary[Set[Int]] =
    Arbitrary(Gen.listOf(Gen.choose(0, 3)).map(_.toSet))

  checkAll("Trie[Boolean]", TrieTests[Boolean].trie)
  checkAll("Trie[Set[Int]]", TrieTests[Set[Int]].trie)

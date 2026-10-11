package noisyneighbor

import algebra.lattice.GenBool
import algebra.laws.LogicLaws
import cats.kernel.Eq
import cats.laws.discipline.FunctorTests
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.Prop.forAll
import org.typelevel.discipline.Laws
import scala.collection.immutable.TreeMap
import scodec.bits.BitVector

import TrieTesting.{*, given}

/** The laws and properties of a [[Trie]] whose values are a [[GenBool]], for
  * one value type: the laws of the algebra and of the functor, that the trie
  * agrees with a plain map from keys to values, and examples of its layout for
  * any value other than zero. The values are drawn from the `Arbitrary` of the
  * value type.
  */
final class TrieTests[A](using
  genBool: GenBool[A],
  eqA: Eq[A],
  cogen: Cogen[A],
  arbitraryA: Arbitrary[A]
) extends Laws:
  private type T = Trie[A]
  private val trieBool = summon[GenBool[T]]

  extension (t: T)
    /** The value at `key`, or the zero of `A`: the owner, if nothing is left
      * over.
      */
    private def get(key: BitVector): A =
      val (node, _, rest) = t.owner(key)
      if rest.isEmpty then node.value else genBool.zero

    /** The nodes with a value other than zero. */
    private def entries: List[(BitVector, A)] =
      t.nodes.filterNot((_, v) => genBool.isZero(v))

  /** No node that could be left out or merged into its edge, no empty or
    * overlapping labels, anywhere: the one form of a trie.
    */
  private def normalized(t: T): Boolean =
    val labels = t.children.keys.toList
    labels.forall(!_.isEmpty) &&
    labels.zip(labels.drop(1)).forall((a, b) => !b.startsWith(a)) &&
    t.children.values.forall(child =>
      !genBool.isZero(child.value) && normalized(child)
    )

  /** The oracle: the map from key to value, a key given twice `or`ed. */
  private def model(entries: List[(BitVector, A)]): Map[String, A] =
    entries.foldLeft(Map.empty[String, A]): (m, e) =>
      m.updated(
        e._1.toBin,
        genBool.or(m.getOrElse(e._1.toBin, genBool.zero), e._2)
      )

  /** The values that have a node: the ones other than zero. */
  private val genNonZero: Gen[A] =
    Arbitrary.arbitrary[A].suchThat(!genBool.isZero(_))

  private val genTrieEntries: Gen[List[(BitVector, A)]] = genEntries[A]

  def trie: RuleSet =
    // The children of a trie are an algebra of their own, with their own laws.
    type Children = TreeMap[BitVector, Trie[A]]
    given GenBool[Children] = Trie.children[A](using trieBool, genBool, eqA)
    given Eq[Children] = Eq.fromUniversalEquals
    given Arbitrary[Children] =
      Arbitrary(genTrieEntries.map(build[A](_).children))

    new RuleSet:
      val name = "trie"
      val parents = Nil
      val bases = Seq(
        "algebra" -> LogicLaws[T].generalizedBool,
        "children" -> LogicLaws[Children].generalizedBool,
        "functor" -> FunctorTests[Trie].functor[A, Int, String]
      )
      val props = Seq(
        "get agrees with the map the entries make" ->
          forAll(genTrieEntries): entries =>
            val (t, expected) = (build[A](entries), model(entries))
            universe.forall(k =>
              eqA.eqv(t.get(k), expected.getOrElse(k.toBin, genBool.zero))
            )
        ,
        "entries are the keys with a value, once each" ->
          forAll(genTrieEntries): entries =>
            val listed = build[A](entries).entries.map((k, v) => (k.toBin, v))
            val expected = model(entries).filterNot((_, v) => genBool.isZero(v))
            listed.toMap == expected && listed.size == expected.size
        ,
        "union, intersection and difference are pointwise" ->
          forAll(genTrieEntries, genTrieEntries): (xs, ys) =>
            val (a, b) = (build[A](xs), build[A](ys))
            universe.forall: k =>
              eqA.eqv(
                trieBool.or(a, b).get(k),
                genBool.or(a.get(k), b.get(k))
              ) &&
                eqA
                  .eqv(
                    trieBool.and(a, b).get(k),
                    genBool.and(a.get(k), b.get(k))
                  ) &&
                eqA.eqv(
                  trieBool.without(a, b).get(k),
                  genBool.without(a.get(k), b.get(k))
                )
        ,
        "every trie that is built has the one form" ->
          forAll(genTrieEntries, genTrieEntries): (xs, ys) =>
            val (a, b) = (build[A](xs), build[A](ys))
            List(
              a,
              trieBool.or(a, b),
              trieBool.and(a, b),
              trieBool.without(a, b),
              trieBool.without(a, a)
            ).forall(normalized)
        ,
        "upsert builds the same trie as the algebra does, key by key" ->
          forAll(Gen.listOf(genKey), genNonZero): (keys, value) =>
            val viaWalks = keys.foldLeft(Trie.empty[A])((t, k) =>
              t.upsert(k, value)(identity, _ => value)
            )
            val viaAlgebra = keys.foldLeft(Trie.empty[A])((t, k) =>
              trieBool.or(t, Trie.single(k, value))
            )
            viaWalks == viaAlgebra && normalized(viaWalks)
      )

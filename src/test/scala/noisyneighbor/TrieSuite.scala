package noisyneighbor

import algebra.laws.{LatticeLaws, LogicLaws}
import algebra.lattice.{BoundedMeetSemilattice, GenBool}
import cats.kernel.Eq
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

import Fixtures.{genEntries, genKey, genTrie, trieOf}
import Trie.given

class TrieSuite extends DisciplineSuite:
  private def indexed(keys: List[Key]): List[(Key, Long)] =
    keys.zipWithIndex.map((k, i) => (k, i.toLong))

  // --- conflict queries ---

  property("minConflicting matches a brute-force scan"):
    forAll(Gen.listOf(genKey), genKey): (keys, query) =>
      val entries = indexed(keys)
      val expected = entries.collect { case (k, s) if k.conflictsWith(query) => s }.minOption
      trieOf(entries).minConflicting(query.bits) == expected

  property("minConflicting still matches after removals"):
    forAll(Gen.listOf(genKey), genKey): (keys, query) =>
      val entries = indexed(keys)
      val (removed, kept) = entries.partition(_._2 % 2 == 0)
      val trie = removed.foldLeft(trieOf(entries))((t, e) => t.remove(e._1.bits, e._2))
      val expected = kept.collect { case (k, s) if k.conflictsWith(query) => s }.minOption
      trie.minConflicting(query.bits) == expected

  property("the root's summary is the meet of everything stored"):
    forAll(Gen.listOf(genKey)): keys =>
      val entries = indexed(keys)
      trieOf(entries).min == entries.map(_._2).minOption

  property("removing everything leaves an empty trie"):
    forAll(Gen.listOf(genKey)): keys =>
      val entries = indexed(keys)
      sets.isZero(entries.foldLeft(trieOf(entries))((t, e) => t.remove(e._1.bits, e._2)))

  test("same-key entries are kept in order"):
    val k = Fixtures.key("01")
    val trie = trieOf(List((k, 3L), (k, 1L), (k, 2L)))
    assertEquals(trie.minConflicting(k.bits), Some(1L))
    assertEquals(trie.remove(k.bits, 1L).minConflicting(k.bits), Some(2L))

  // --- the summary lattice: Option[Long] under min, with None as the top ---

  checkAll("Option[Long] min meet", LatticeLaws[Option[Long]].boundedMeetSemilattice)

  // --- the trie as a generalized Boolean algebra of entry sets ---

  private given Arbitrary[Trie[Long]] = Arbitrary(genTrie)
  private given Eq[Trie[Long]] = Eq.fromUniversalEquals

  private val sets = summon[GenBool[Trie[Long]]]
  private val summary = summon[BoundedMeetSemilattice[Option[Long]]]

  checkAll("Trie entry sets", LogicLaws[Trie[Long]].generalizedBool(sets))

  property("a trie holds exactly the entries inserted into it"):
    forAll(genEntries)(es => trieOf(es).entries.toSet == es.toSet)

  property("or, and and without are union, intersection and difference of the entries, and stay canonical"):
    forAll(genEntries, genEntries): (xs, ys) =>
      val (x, y) = (trieOf(xs), trieOf(ys))
      val (sx, sy) = (xs.toSet, ys.toSet)
      sets.or(x, y) == trieOf((sx ++ sy).toList) &&
        sets.and(x, y) == trieOf((sx & sy).toList) &&
        sets.without(x, y) == trieOf((sx -- sy).toList)

  property("insert is union with a singleton, and remove is difference with one"):
    forAll(genTrie, genKey, Gen.choose(0L, 3L)): (t, k, a) =>
      val single = Trie.singleton(k.bits, a)
      t.insert(k.bits, a) == sets.or(t, single) && t.remove(k.bits, a) == sets.without(t, single)

  property("zero is the empty trie"):
    forAll(genTrie)(t => sets.isZero(t) == t.entries.isEmpty && (t.min == None) == sets.isZero(t))

  property("zero is skipped, not walked: x | 0, 0 | x and x - 0 return x itself, sharing it"):
    forAll(genTrie): t =>
      (sets.or(t, sets.zero) eq t) && (sets.or(sets.zero, t) eq t) && (sets.without(t, sets.zero) eq t)

  property("conflict queries distribute over union: minConflicting is a homomorphism into the min meet"):
    forAll(genTrie, genTrie, genKey): (x, y, q) =>
      sets.or(x, y).minConflicting(q.bits) ==
        summary.meet(x.minConflicting(q.bits), y.minConflicting(q.bits))

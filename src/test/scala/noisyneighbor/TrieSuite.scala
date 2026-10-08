package noisyneighbor

import algebra.lattice.GenBool
import algebra.laws.LogicLaws
import cats.kernel.Eq
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import scodec.bits.BitVector

import Fixtures.{genKey, key}

class TrieSuite extends DisciplineSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(200)

  /** Every key of up to four bits: the keys the generators draw from. */
  val universe: List[Key] =
    (0 to 4).toList.flatMap: n =>
      (0 until (1 << n)).toList.map: i =>
        Key(BitVector.fromLong(i.toLong, n))

  /** Sets of integers, to have a value type richer than `Boolean`. */
  given setGenBool: GenBool[Set[Int]] = new GenBool[Set[Int]]:
    def zero: Set[Int] = Set.empty
    def or(a: Set[Int], b: Set[Int]): Set[Int] = a | b
    def and(a: Set[Int], b: Set[Int]): Set[Int] = a & b
    def without(a: Set[Int], b: Set[Int]): Set[Int] = a -- b

  val genBoolValue: Gen[Boolean] = Gen.oneOf(true, false)
  val genSetValue: Gen[Set[Int]] = Gen.listOf(Gen.choose(0, 3)).map(_.toSet)

  /** No node that could be left out or merged into its edge, no empty or
    * overlapping labels, anywhere: the one form of a trie.
    */
  def normalized[A](trie: Trie[A])(using ev: GenBool[A], eq: Eq[A]): Boolean =
    val labels = trie.children.keys.toList
    labels.forall(!_.isEmpty) &&
    labels.zip(labels.drop(1)).forall((a, b) => !b.startsWith(a)) &&
    trie.children.values.forall(child =>
      !ev.isZero(child.value) && normalized(child)
    )

  /** The same checks for each value type. */
  def checks[A](name: String, genValue: Gen[A])(using
    ev: GenBool[A],
    eq: Eq[A]
  ): Unit =
    type T = Trie[A]
    val trieBool = summon[GenBool[T]]

    val genEntries: Gen[List[(Key, A)]] =
      Gen.choose(0, 10).flatMap(Gen.listOfN(_, Gen.zip(genKey, genValue)))

    /** Built with the trie's own union. */
    def build(entries: List[(Key, A)]): T =
      entries.foldLeft(Trie.empty[A])((t, e) =>
        trieBool.or(t, Trie.single(e._1, e._2))
      )

    /** The oracle: the map from key to value, a key given twice `or`ed. */
    def model(entries: List[(Key, A)]): Map[String, A] =
      entries.foldLeft(Map.empty[String, A]): (m, e) =>
        m.updated(e._1.show, ev.or(m.getOrElse(e._1.show, ev.zero), e._2))

    given Arbitrary[T] = Arbitrary(genEntries.map(build))

    checkAll(s"Trie[$name]", LogicLaws[T].generalizedBool)

    property(s"$name: get agrees with the map the entries make"):
      forAll(genEntries): entries =>
        val (trie, expected) = (build(entries), model(entries))
        universe.forall(k =>
          eq.eqv(trie.get(k), expected.getOrElse(k.show, ev.zero))
        )

    property(s"$name: entries are the keys with a value, once each"):
      forAll(genEntries): entries =>
        val listed = build(entries).entries.map((k, v) => (k.show, v))
        val expected = model(entries).filterNot((_, v) => ev.isZero(v))
        listed.toMap == expected && listed.size == expected.size

    property(s"$name: union, intersection and difference are pointwise"):
      forAll(genEntries, genEntries): (xs, ys) =>
        val (a, b) = (build(xs), build(ys))
        universe.forall: k =>
          eq.eqv(trieBool.or(a, b).get(k), ev.or(a.get(k), b.get(k))) &&
            eq.eqv(trieBool.and(a, b).get(k), ev.and(a.get(k), b.get(k))) &&
            eq.eqv(
              trieBool.without(a, b).get(k),
              ev.without(a.get(k), b.get(k))
            )

    property(s"$name: equal maps are equal tries, whatever the order"):
      forAll(genEntries): entries =>
        build(entries) == build(entries.reverse) &&
          build(entries) == build(entries ++ entries)

    property(s"$name: every trie that is built has the one form"):
      forAll(genEntries, genEntries): (xs, ys) =>
        val (a, b) = (build(xs), build(ys))
        List(
          a,
          trieBool.or(a, b),
          trieBool.and(a, b),
          trieBool.without(a, b),
          trieBool.without(a, a)
        ).forall(normalized) &&
        trieBool.without(a, a) == Trie.empty[A] &&
        trieBool.and(a, Trie.empty[A]) == Trie.empty[A]

  checks("Boolean", genBoolValue)(using
    algebra.instances.boolean.booleanAlgebra,
    summon[Eq[Boolean]]
  )
  checks("Set[Int]", genSetValue)

  given GenBool[Boolean] = algebra.instances.boolean.booleanAlgebra

  def or[A: GenBool: Eq](a: Trie[A], b: Trie[A]): Trie[A] =
    summon[GenBool[Trie[A]]].or(a, b)

  def without[A: GenBool: Eq](a: Trie[A], b: Trie[A]): Trie[A] =
    summon[GenBool[Trie[A]]].without(a, b)

  test("a key is one edge however long it is: nodes are keys and divergences"):
    val long = Key(BitVector.fill(10000)(true))
    val trie = Trie.single(long, true)
    assertEquals(trie.children.size, 1)
    assertEquals(trie.children.head._1.size, 10000L)
    assert(trie.get(long))
    assert(!trie.get(Key(BitVector.fill(9999)(true))))

  test(
    "keys that diverge are siblings, and a key between them is their parent"
  ):
    val a = Trie.single(key("0110"), true)
    val b = Trie.single(key("0111"), true)
    val both = or(a, b)
    // There is no node where they merely diverge.
    assertEquals(both.children.keys.map(_.toBin).toList, List("0110", "0111"))
    // With a key at the point where they diverge, they hang from it, with labels
    // relative to it.
    val parent = or(both, Trie.single(key("011"), true))
    assertEquals(parent.children.keys.map(_.toBin).toList, List("011"))
    assertEquals(
      parent.children.head._2.children.keys.map(_.toBin).toList,
      List("0", "1")
    )
    assertEquals(parent.entries.map(_._1.show), List("011", "0110", "0111"))

  test("a key and the keys that extend it, and the empty key at the root"):
    val trie = List("", "0", "01", "011")
      .map(k => Trie.single(key(k), true))
      .reduce(or(_, _))
    assertEquals(trie.entries.map(_._1.show), List("", "0", "01", "011"))
    assert(trie.value)
    assertEquals(trie.children.keys.map(_.toBin).toList, List("0"))

  test("deep nesting: a chain of keys, each extending the last"):
    val keys = (1 to 400).map(n => Key(BitVector.fill(n.toLong)(false)))
    val chain = keys.map(k => Trie.single(k, true)).reduce(or(_, _))
    assertEquals(chain.entries.size, 400)
    val removed = without(chain, Trie.single(keys(199), true))
    assertEquals(removed.entries.size, 399)
    assert(!removed.get(keys(199)) && removed.get(keys(200)))

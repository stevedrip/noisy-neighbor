package noisyneighbor

import algebra.lattice.GenBool
import algebra.laws.LogicLaws
import cats.kernel.Eq
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import scala.collection.immutable.TreeMap
import scodec.bits.BitVector

import Fixtures.{genKey as genKeyOf, key as keyOf}

class TrieSuite extends DisciplineSuite:
  /** Keys are plain bit vectors here. */
  def key(s: String): BitVector = keyOf(s).bits
  val genKey: Gen[BitVector] = genKeyOf.map(_.bits)

  extension [A](trie: Trie[A])
    /** The value at `key`, or the zero of `A`: the owner, if nothing is left
      * over.
      */
    def get(key: BitVector)(using ev: GenBool[A]): A =
      val (node, _, rest) = trie.owner(key)
      if rest.isEmpty then node.value else ev.zero

    /** The nodes with a value other than zero. */
    def entries(using ev: GenBool[A], eq: Eq[A]): List[(BitVector, A)] =
      trie.nodes.filterNot((_, v) => ev.isZero(v))

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(200)

  /** Every key of up to four bits: the keys the generators draw from. */
  val universe: List[BitVector] =
    (0 to 4).toList.flatMap: n =>
      (0 until (1 << n)).toList.map: i =>
        BitVector.fromLong(i.toLong, n)

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

    val genEntries: Gen[List[(BitVector, A)]] =
      Gen.choose(0, 10).flatMap(Gen.listOfN(_, Gen.zip(genKey, genValue)))

    /** Built with the trie's own union. */
    def build(entries: List[(BitVector, A)]): T =
      entries.foldLeft(Trie.empty[A])((t, e) =>
        trieBool.or(t, Trie.single(e._1, e._2))
      )

    /** The oracle: the map from key to value, a key given twice `or`ed. */
    def model(entries: List[(BitVector, A)]): Map[String, A] =
      entries.foldLeft(Map.empty[String, A]): (m, e) =>
        m.updated(e._1.toBin, ev.or(m.getOrElse(e._1.toBin, ev.zero), e._2))

    given Arbitrary[T] = Arbitrary(genEntries.map(build))

    checkAll(s"Trie[$name]", LogicLaws[T].generalizedBool)

    // The children of a trie are an algebra of their own, with their own laws.
    type Children = TreeMap[BitVector, Trie[A]]
    given GenBool[Children] = Trie.children[A](using trieBool, ev, eq)
    given Eq[Children] = Eq.fromUniversalEquals
    given Arbitrary[Children] = Arbitrary(genEntries.map(build(_).children))

    checkAll(s"Children of Trie[$name]", LogicLaws[Children].generalizedBool)

    property(s"$name: a trie combines its value and its children separately"):
      forAll(genEntries, genEntries): (xs, ys) =>
        val (a, b) = (build(xs), build(ys))
        val kids = summon[GenBool[Children]]
        trieBool.or(a, b) ==
          Trie(kids.or(a.children, b.children), ev.or(a.value, b.value)) &&
          trieBool.and(a, b) ==
          Trie(kids.and(a.children, b.children), ev.and(a.value, b.value)) &&
          trieBool.without(a, b) ==
          Trie(
            kids.without(a.children, b.children),
            ev.without(a.value, b.value)
          )

    property(s"$name: get agrees with the map the entries make"):
      forAll(genEntries): entries =>
        val (trie, expected) = (build(entries), model(entries))
        universe.forall(k =>
          eq.eqv(trie.get(k), expected.getOrElse(k.toBin, ev.zero))
        )

    property(s"$name: entries are the keys with a value, once each"):
      forAll(genEntries): entries =>
        val listed = build(entries).entries.map((k, v) => (k.toBin, v))
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
    val long = BitVector.fill(10000)(true)
    val trie = Trie.single(long, true)
    assertEquals(trie.children.size, 1)
    assertEquals(trie.children.head._1.size, 10000L)
    assert(trie.get(long))
    assert(!trie.get(BitVector.fill(9999)(true)))

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
    assertEquals(parent.entries.map(_._1.toBin), List("011", "0110", "0111"))

  test("a key and the keys that extend it, and the empty key at the root"):
    val trie = List("", "0", "01", "011")
      .map(k => Trie.single(key(k), true))
      .reduce(or(_, _))
    assertEquals(trie.entries.map(_._1.toBin), List("", "0", "01", "011"))
    assert(trie.value)
    assertEquals(trie.children.keys.map(_.toBin).toList, List("0"))

  test("deep nesting: a chain of keys, each extending the last"):
    val keys = (1 to 400).map(n => BitVector.fill(n.toLong)(false))
    val chain = keys.map(k => Trie.single(k, true)).reduce(or(_, _))
    assertEquals(chain.entries.size, 400)
    val removed = without(chain, Trie.single(keys(199), true))
    assertEquals(removed.entries.size, 399)
    assert(!removed.get(keys(199)) && removed.get(keys(200)))

  // The walks, which need nothing of the value type: these tries hold strings,
  // which are not an algebra here, and are built with `upsert` alone. The value
  // of a node is its own key, and the root's is "root".

  def built(keys: String*): Trie[String] =
    keys.foldLeft(Trie[String](TreeMap.empty, "root"))((t, k) =>
      t.upsert(key(k), k)(identity, identity)
    )

  def labels[A](trie: Trie[A]): List[String] =
    trie.children.keys.map(_.toBin).toList

  test("child is the one step: the child whose label is a prefix of the rest"):
    val trie = built("0110", "1")
    assertEquals(trie.child(key("0110")).map(_._1.toBin), Some("0110"))
    assertEquals(trie.child(key("01101")).map(_._1.toBin), Some("0110"))
    assertEquals(trie.child(key("1")).map(_._1.toBin), Some("1"))
    assertEquals(trie.child(key("011")), None) // part-way along an edge
    assertEquals(trie.child(key("00")), None)
    assertEquals(trie.child(BitVector.empty), None)

  /** The node's key and value, and the bits of the key beyond it. */
  def ownerOf(trie: Trie[String], k: String): (String, String, String) =
    val (node, address, rest) = trie.owner(key(k))
    (address.toBin, node.value, rest.toBin)

  test("owner is the deepest node that owns the key, and falls back to itself"):
    val trie = built("01", "0110")
    // A node: it owns itself, with nothing left over.
    assertEquals(ownerOf(trie, "0110"), ("0110", "0110", ""))
    assertEquals(ownerOf(trie, "01"), ("01", "01", ""))
    // A key beyond a node: the node that owns it, and the bits that are left.
    assertEquals(ownerOf(trie, "01101"), ("0110", "0110", "1"))
    assertEquals(ownerOf(trie, "010"), ("01", "01", "0"))
    // Part-way along the edge from "01" to "0110": the node above it.
    assertEquals(ownerOf(trie, "011"), ("01", "01", "1"))
    // No child's label is a prefix: the node itself, which is the root here.
    assertEquals(ownerOf(trie, "1"), ("", "root", "1"))
    assertEquals(ownerOf(trie, "00"), ("", "root", "00"))
    assertEquals(ownerOf(trie, ""), ("", "root", ""))

  test("owner of a subtrie starts from that node, with keys relative to it"):
    val inner = built("01", "0110").children.head._2
    val (node, address, rest) = inner.owner(key("10"))
    assertEquals((node.value, address.toBin, rest.toBin), ("0110", "10", ""))

  property("owner is the longest node key that is a prefix, with the rest"):
    forAll(Gen.listOf(genKey), genKey): (keys, k) =>
      val trie = keys.foldLeft(Trie.empty[Boolean])((t, key) =>
        t.upsert(key, true)(identity, _ => true)
      )
      val present = keys.map(_.toBin).toSet + ""
      val expected = present.filter(k.toBin.startsWith).maxBy(_.length)
      val (node, address, rest) = trie.owner(k)
      address.toBin == expected && rest.toBin == k.toBin.drop(
        expected.length
      ) &&
      trie.along(address)((n, _) => (n, ())).isDefined && node.value == (
        keys.map(_.toBin).contains(expected)
      )

  test("along finds the node for a key, with its key, and rebuilds around it"):
    val trie = built("01", "0110")
    // The edge to "0110" is labelled "10", relative to "01".
    assertEquals(labels(trie), List("01"))
    assertEquals(labels(trie.children.head._2), List("10"))
    assertEquals(
      trie.along(key("0110"))((n, k) => (n, (k.toBin, n.value))).map(_._2),
      Some(("0110", "0110"))
    )
    assertEquals(
      trie.along(BitVector.empty)((n, k) => (n, (k.toBin, n.value))).map(_._2),
      Some(("", "root"))
    )
    assertEquals(trie.along(key("011"))((n, _) => (n, ())), None) // mid-edge
    assertEquals(trie.along(key("1"))((n, _) => (n, ())), None)
    val renamed = trie.along(key("0110"))((n, _) => (n.copy(value = "x"), ()))
    assertEquals(
      renamed.map(_._1.along(key("0110"))((n, _) => (n, n.value)).map(_._2)),
      Some(Some("x"))
    )

  test("modify changes one node, and leaves the trie alone if there is none"):
    val trie = built("0", "01")
    val changed = trie.modify(key("01"))(_.copy(value = "changed"))
    assertEquals(
      changed.nodes.map((k, v) => (k.toBin, v)),
      List(("", "root"), ("0", "0"), ("01", "changed"))
    )
    assertEquals(trie.modify(key("1"))(_.copy(value = "x")), trie)
    assertEquals(trie.modify(key("00"))(_.copy(value = "x")), trie)

  test("upsert creates a node beneath its owner, and adopts what extends it"):
    val both = built("0110", "0111")
    assertEquals(
      labels(both),
      List("0110", "0111")
    ) // no node where they diverge
    val parent = both.upsert(key("011"), "011")(identity, identity)
    assertEquals(labels(parent), List("011"))
    assertEquals(labels(parent.children.head._2), List("0", "1"))
    assertEquals(
      parent.nodes.map(_._1.toBin),
      List("", "011", "0110", "0111")
    )

  test("upsert applies onPath to the nodes above and onTarget to the node"):
    val start = Trie[Int](TreeMap.empty, 0)
    val once = start
      .upsert(key("0"), 0)(_ + 1, _ + 10)
      .upsert(key("01"), 0)(_ + 1, _ + 10)
    // "0" was created holding 0, then it was on the path to "01", and the root
    // was on the path to both.
    assertEquals(
      once.nodes.map((k, v) => (k.toBin, v)),
      List(("", 2), ("0", 1), ("01", 0))
    )
    val again = once.upsert(key("0"), 0)(_ + 1, _ + 10)
    assertEquals(
      again.nodes.map((k, v) => (k.toBin, v)),
      List(("", 3), ("0", 11), ("01", 0))
    )
    // The root is the target of the empty key and nothing is above it.
    assertEquals(again.upsert(BitVector.empty, 0)(_ + 1, _ + 10).value, 13)

  test("prune removes a node and everything below it, never the root"):
    val trie = built("0", "01", "011", "1")
    val pruned = trie.prune(_.value == "01")
    assertEquals(pruned.nodes.map(_._1.toBin), List("", "0", "1"))
    assertEquals(trie.prune(_ => true).nodes.map(_._1.toBin), List(""))
    assertEquals(trie.prune(_ => false), trie)

  test("nodes lists every node, a root that holds zero included"):
    val zero = Trie.empty[Boolean]
    assertEquals(zero.nodes.map((k, v) => (k.toBin, v)), List(("", false)))

  property("upsert builds the same trie as the algebra does, key by key"):
    forAll(Gen.listOf(genKey)): keys =>
      val viaWalks = keys.foldLeft(Trie.empty[Boolean])((t, k) =>
        t.upsert(k, true)(identity, _ => true)
      )
      val viaAlgebra = keys.foldLeft(Trie.empty[Boolean])((t, k) =>
        or(t, Trie.single(k, true))
      )
      viaWalks == viaAlgebra && normalized(viaWalks)

  property("along reaches exactly the nodes there are"):
    forAll(Gen.listOf(genKey)): keys =>
      val trie = keys.foldLeft(Trie.empty[Boolean])((t, k) =>
        t.upsert(k, true)(identity, _ => true)
      )
      val present = keys.map(_.toBin).toSet + ""
      universe.forall(k =>
        trie.along(k)((n, found) => (n, found)).map(_._2.toBin) ==
          Option.when(present(k.toBin))(k.toBin)
      )

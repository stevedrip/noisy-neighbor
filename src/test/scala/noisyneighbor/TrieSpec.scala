package noisyneighbor

import munit.FunSuite
import scala.collection.immutable.TreeMap
import scodec.bits.BitVector

class TrieSpec extends FunSuite:

  // The value of a node is its own key, and the root's is "root".

  def built(keys: String*): Trie[String] =
    keys.foldLeft(Trie[String](TreeMap.empty, "root"))((t, k) =>
      t.upsert(BitVector.fromValidBin(k), k)(identity, identity)
    )

  def labels[A](trie: Trie[A]): List[String] =
    trie.children.keys.map(_.toBin).toList

  test("child is the one step: the child whose label is a prefix of the rest"):
    val trie = built("0110", "1")
    assertEquals(
      trie.child(BitVector.fromValidBin("0110")).map(_._1.toBin),
      Some("0110")
    )
    assertEquals(
      trie.child(BitVector.fromValidBin("01101")).map(_._1.toBin),
      Some("0110")
    )
    assertEquals(
      trie.child(BitVector.fromValidBin("1")).map(_._1.toBin),
      Some("1")
    )
    assertEquals(
      trie.child(BitVector.fromValidBin("011")),
      None
    ) // part-way along an edge
    assertEquals(trie.child(BitVector.fromValidBin("00")), None)
    assertEquals(trie.child(BitVector.empty), None)

  /** The node's key and value, and the bits of the key beyond it. */
  def ownerOf(trie: Trie[String], k: String): (String, String, String) =
    val (node, address, rest) = trie.owner(BitVector.fromValidBin(k))
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
    val (node, address, rest) = inner.owner(BitVector.fromValidBin("10"))
    assertEquals((node.value, address.toBin, rest.toBin), ("0110", "10", ""))

  test("along finds the node for a key, with its key, and rebuilds around it"):
    val trie = built("01", "0110")
    // The edge to "0110" is labelled "10", relative to "01".
    assertEquals(labels(trie), List("01"))
    assertEquals(labels(trie.children.head._2), List("10"))
    assertEquals(
      trie
        .along(BitVector.fromValidBin("0110"))((n, k) =>
          (n, (k.toBin, n.value))
        )
        .map(_._2),
      Some(("0110", "0110"))
    )
    assertEquals(
      trie.along(BitVector.empty)((n, k) => (n, (k.toBin, n.value))).map(_._2),
      Some(("", "root"))
    )
    assertEquals(
      trie.along(BitVector.fromValidBin("011"))((n, _) => (n, ())),
      None
    ) // mid-edge
    assertEquals(
      trie.along(BitVector.fromValidBin("1"))((n, _) => (n, ())),
      None
    )
    val renamed = trie.along(BitVector.fromValidBin("0110"))((n, _) =>
      (n.copy(value = "x"), ())
    )
    assertEquals(
      renamed.map(
        _._1
          .along(BitVector.fromValidBin("0110"))((n, _) => (n, n.value))
          .map(_._2)
      ),
      Some(Some("x"))
    )

  test("modify changes one node, and leaves the trie alone if there is none"):
    val trie = built("0", "01")
    val changed =
      trie.modify(BitVector.fromValidBin("01"))(_.copy(value = "changed"))
    assertEquals(
      changed.nodes.map((k, v) => (k.toBin, v)),
      List(("", "root"), ("0", "0"), ("01", "changed"))
    )
    assertEquals(
      trie.modify(BitVector.fromValidBin("1"))(_.copy(value = "x")),
      trie
    )
    assertEquals(
      trie.modify(BitVector.fromValidBin("00"))(_.copy(value = "x")),
      trie
    )

  test("upsert creates a node beneath its owner, and adopts what extends it"):
    val both = built("0110", "0111")
    assertEquals(
      labels(both),
      List("0110", "0111")
    ) // no node where they diverge
    val parent =
      both.upsert(BitVector.fromValidBin("011"), "011")(identity, identity)
    assertEquals(labels(parent), List("011"))
    assertEquals(labels(parent.children.head._2), List("0", "1"))
    assertEquals(
      parent.nodes.map(_._1.toBin),
      List("", "011", "0110", "0111")
    )

  test("upsert applies onPath to the nodes above and onTarget to the node"):
    val start = Trie[Int](TreeMap.empty, 0)
    val once = start
      .upsert(BitVector.fromValidBin("0"), 0)(_ + 1, _ + 10)
      .upsert(BitVector.fromValidBin("01"), 0)(_ + 1, _ + 10)
    // "0" was created holding 0, then it was on the path to "01", and the root
    // was on the path to both.
    assertEquals(
      once.nodes.map((k, v) => (k.toBin, v)),
      List(("", 2), ("0", 1), ("01", 0))
    )
    val again = once.upsert(BitVector.fromValidBin("0"), 0)(_ + 1, _ + 10)
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

  test("nodes lists every node, a root that holds nothing included"):
    val root = Trie[Boolean](TreeMap.empty, false)
    assertEquals(root.nodes.map((k, v) => (k.toBin, v)), List(("", false)))

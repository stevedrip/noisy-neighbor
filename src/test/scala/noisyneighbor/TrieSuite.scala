package noisyneighbor

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.collection.immutable.TreeMap
import scodec.bits.BitVector

import TrieTesting.{genKey, universe}

class TrieSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(200)

  /** A trie of the keys, where a node holds whether it is one of them. */
  def present(keys: List[BitVector]): Trie[Boolean] =
    keys.foldLeft(Trie[Boolean](TreeMap.empty, false))((t, key) =>
      t.upsert(key, true)(identity, _ => true)
    )

  property("owner is the longest node key that is a prefix, with the rest"):
    forAll(Gen.listOf(genKey), genKey): (keys, k) =>
      val trie = present(keys)
      val there = keys.map(_.toBin).toSet + ""
      val expected = there.filter(k.toBin.startsWith).maxBy(_.length)
      val (node, address, rest) = trie.owner(k)
      address.toBin == expected && rest.toBin == k.toBin.drop(
        expected.length
      ) &&
      trie.along(address)((n, _) => (n, ())).isDefined && node.value == (
        keys.map(_.toBin).contains(expected)
      )

  property("along reaches exactly the nodes there are"):
    forAll(Gen.listOf(genKey)): keys =>
      val trie = present(keys)
      val there = keys.map(_.toBin).toSet + ""
      universe.forall(k =>
        trie.along(k)((n, found) => (n, found)).map(_._2.toBin) ==
          Option.when(there(k.toBin))(k.toBin)
      )

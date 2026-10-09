package noisyneighbor

import munit.FunSuite
import scodec.bits.BitVector

import FifoTree.Item
import Fixtures.key as keyOf

// Contains explicit examples, no property tests.
class FifoTrieSpec extends FunSuite:
/*
This is an unused test. The expectations are nonsensical.

Write and read all of these with values to be asserted. These messages are defined like:
'0' -> [('0', 0)]
'101' -> [('101', 1), ('101', 0), ('101', 1)]
'11' -> [('11', 1), ('11', 1)]

Batch reads from '01' messages []
Batch reads from '10' messages []
Batch reads from ''   messages []

Batch writes to  ''   messages ['0']
Batch writes to  '10' messages ['101']
Batch writes to  ''   messages ['00', '010', '100', '11']
Batch writes to  '01' messages ['011']
Batch writes to  ''   messages ['1']

Batch reads from '01' messages []
Batch reads from '10' messages ['101']
Batch reads from ''   messages ['0', '00', '11']
Batch reads from '01' messages ['011', '010']
Batch reads from '10' messages ['100']
Batch reads from ''   messages ['1']

Batch reads from '01' messages []
Batch reads from '10' messages []
Batch reads from ''   messages []
*/
  type Tree = FifoTree[Int]

  def key(s: String): BitVector = keyOf(s).bits

  /** The messages derived from keys: one per bit of each key, holding that bit,
    * so '101' is ('101', 1), ('101', 0), ('101', 1).
    */
  def derived(keys: String*): List[Item[Int]] =
    keys.toList.flatMap(k => k.toList.map(bit => Item(key(k), bit.asDigit)))

  /** Writes a batch of messages derived from `keys` straight into the queue
    * `to`, which has to exist. The bitstrings are absolute, so every key has to
    * start with the queue's own.
    */
  def write(tree: Tree, to: String, keys: String*): Tree =
    assert(tree.queues.contains(key(to)), s"there is no queue '$to'")
    derived(keys*).foldLeft(tree): (t, m) =>
      assert(m.key.startsWith(key(to)), s"'${m.key.toBin}' is not under '$to'")
      t.modify(key(to))(queue =>
        queue.copy(value =
          queue.value.add(m).getOrElse(fail(s"'$to' is draining"))
        )
      )

  /** Reads a batch from the queue `from`, and acknowledges all of it. */
  def read(tree: Tree, from: String): (Tree, List[Item[Int]]) =
    val (asked, run) = tree.takeFrom(key(from), Int.MaxValue)
    val acknowledged = run.fold(asked)(r => asked.acknowledge(r.queue, r.last))
    (acknowledged, run.toList.flatMap(_.items))

  /** What the example does, in order. */
  enum Step:
    case Write(to: String, keys: List[String])
    case Read(from: String, returns: List[String])

  import Step.*

  test("a trie of three nodes, '', '01' and '10', written to and read from"):
    val start = FifoTree.empty[Int].split(key("01")).split(key("10"))
    assertEquals(start.queues.map(_.toBin), List("", "01", "10"))

    val steps = List(
      Read("01", Nil),
      Read("10", Nil),
      Read("", Nil),
      Write("", List("0")),
      Write("10", List("101")),
      Write("", List("00", "010", "100", "11")),
      Write("01", List("011")),
      Write("", List("1")),
      Read("01", Nil),
      Read("10", List("101")),
      Read("", List("0", "00", "11")),
      Read("01", List("011", "010")),
      Read("10", List("100")),
      Read("", List("1")),
      Read("01", Nil),
      Read("10", Nil),
      Read("", Nil)
    )

    // One line per read, as `queue -> key:value ...`, so a failure reads at a glance.
    def line(from: String, batch: List[Item[Int]]): String =
      s"'$from' -> " + batch.map(m => s"${m.key.toBin}:${m.value}").mkString(" ")

    val (_, observed, expected) = steps.foldLeft(
      (start, List.empty[String], List.empty[String])
    ):
      case ((tree, seen, wanted), Write(to, keys)) =>
        (write(tree, to, keys*), seen, wanted)
      case ((tree, seen, wanted), Read(from, returns)) =>
        val (next, batch) = read(tree, from)
        (
          next,
          seen :+ line(from, batch),
          wanted :+ line(from, derived(returns*))
        )

    assertEquals(observed, expected)

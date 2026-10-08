package noisyneighbor

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class FifoSuite extends ScalaCheckSuite:
  def of(as: Int*): Fifo[Int] = as.foldLeft(Fifo.empty[Int])(_.add(_))

  test("reading does not remove: the same messages are there to read again"):
    val fifo = of(1, 2, 3)
    assertEquals(fifo.read(2), List(1, 2))
    assertEquals(fifo.read(2), List(1, 2))
    assertEquals(fifo.read(10), List(1, 2, 3))

  test("acknowledging an offset removes everything up to and including it"):
    val fifo = of(1, 2, 3).acknowledge(1)
    assertEquals(fifo.read(10), List(3))
    assertEquals(fifo.start, 2L)
    assert(!fifo.isEmpty)
    assert(fifo.acknowledge(2).isEmpty)

  test("offsets are numbered from 0 and do not change as messages are removed"):
    val fifo = of(1, 2, 3)
    assertEquals(fifo.start, 0L)
    assertEquals(fifo.acknowledge(0).start, 1L)
    assertEquals(fifo.acknowledge(0).add(4).acknowledge(2).read(10), List(4))
    assertEquals(fifo.acknowledge(0).add(4).acknowledge(2).start, 3L)

  test("acknowledging an offset that is already gone changes nothing"):
    val once = of(1, 2, 3).acknowledge(1)
    val again = once.acknowledge(1).acknowledge(0).acknowledge(-1)
    assertEquals(again.read(10), once.read(10))
    assertEquals(again.start, once.start)

  test("acknowledging past the end empties it, and the next message follows"):
    val fifo = of(1).acknowledge(5)
    assert(fifo.isEmpty)
    assertEquals(fifo.start, 1L)
    assertEquals(fifo.add(2).read(10), List(2))
    assertEquals(Fifo.empty[Int].read(3), Nil)

  property("acknowledging an offset and reading is the list without its start"):
    forAll(Gen.listOf(Gen.choose(0, 9)), Gen.choose(-1, 12), Gen.choose(0, 12)):
      (xs, through, m) =>
        val fifo = of(xs*).acknowledge(through.toLong)
        val removed = (through + 1).min(xs.size).max(0)
        fifo.read(m) == xs.drop(removed).take(m) && fifo.start == removed.toLong

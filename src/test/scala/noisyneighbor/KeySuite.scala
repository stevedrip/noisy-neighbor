package noisyneighbor

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

class KeySuite extends ScalaCheckSuite:
  import Fixtures.{genKey, key}

  test("README conflict examples"):
    assert(key("").conflictsWith(key("0110")))
    assert(key("0").conflictsWith(key("0")))
    assert(key("0").conflictsWith(key("00")))
    assert(key("0").conflictsWith(key("01")))
    assert(!key("00").conflictsWith(key("01")))
    assert(!key("00").conflictsWith(key("1")))

  test("parse rejects non-bits"):
    assert(Key.parse("012").isLeft)

  test("length is part of a key's identity"):
    assertNotEquals(key("0"), key("00"))
    assertNotEquals(key(""), key("0"))
    assertEquals(key(""), Key.root)
    assertEquals(key("0110").show, "0110")
    assert(key("0").isPrefixOf(key("00")))
    assert(!key("00").isPrefixOf(key("0")))

  property("keys conflict exactly when one is a prefix of the other"):
    forAll(genKey, genKey): (a, b) =>
      val (xs, ys) = (a.bits.toIndexedSeq, b.bits.toIndexedSeq)
      a.conflictsWith(b) == (xs.startsWith(ys) || ys.startsWith(xs))

  property("conflict is symmetric"):
    forAll(genKey, genKey)((a, b) => a.conflictsWith(b) == b.conflictsWith(a))

  property("root conflicts with every key"):
    forAll(genKey)(k => Key.root.conflictsWith(k))

  property("show and parse round-trip"):
    forAll(genKey)(k => Key.parse(k.show) == Right(k))

package noisyneighbor

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class KeySuite extends ScalaCheckSuite:
  private def key(s: String): Key = Key.parse(s).fold(sys.error, identity)

  private val genKey: Gen[Key] =
    Gen.listOf(Gen.oneOf(true, false)).map(bs => Key(bs.toVector))

  test("README conflict examples"):
    assert(key("").conflictsWith(key("0110")))
    assert(key("0").conflictsWith(key("0")))
    assert(key("0").conflictsWith(key("00")))
    assert(key("0").conflictsWith(key("01")))
    assert(!key("00").conflictsWith(key("01")))
    assert(!key("00").conflictsWith(key("1")))

  test("parse rejects non-bits"):
    assert(Key.parse("012").isLeft)

  property("conflict is symmetric"):
    forAll(genKey, genKey)((a, b) => a.conflictsWith(b) == b.conflictsWith(a))

  property("root conflicts with every key"):
    forAll(genKey)(k => Key.root.conflictsWith(k))

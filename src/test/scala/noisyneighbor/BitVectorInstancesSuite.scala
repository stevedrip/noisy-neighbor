package noisyneighbor

import cats.kernel.laws.discipline.PartialOrderTests
import munit.DisciplineSuite
import noisyneighbor.instances.all.given
import org.scalacheck.{Arbitrary, Cogen}
import scodec.bits.BitVector

class BitVectorInstancesSuite extends DisciplineSuite:
  given Arbitrary[BitVector] = Arbitrary(TrieTesting.genKey)
  given Cogen[BitVector] = Cogen[String].contramap(_.toBin)

  checkAll("PartialOrder[BitVector]", PartialOrderTests[BitVector].partialOrder)

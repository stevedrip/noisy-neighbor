package noisyneighbor
package instances

import cats.PartialOrder
import scodec.bits.BitVector

object bitvector extends BitVectorInstances

trait BitVectorInstances:
  /** The order that decides whether two keys conflict: a key is less than every
    * key it is a proper prefix of, and two keys are incomparable when neither
    * is a prefix of the other.
    */
  given prefixOrder: PartialOrder[BitVector] with
    def partialCompare(a: BitVector, b: BitVector): Double =
      (a.startsWith(b), b.startsWith(a)) match
        case (true, true)  => 0.0
        case (true, false) => 1.0
        case (false, true) => -1.0
        case _             => Double.NaN

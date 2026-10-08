package noisyneighbor

import algebra.lattice.{BoundedMeetSemilattice, GenBool}
import cats.kernel.{Eq, Order}
import scodec.bits.BitVector

import scala.collection.immutable.SortedSet

/** A finite set of entries `(key, value)`, indexed by key so that conflicting entries can be found quickly.
  *
  * As an algebra, a trie is a generalized Boolean algebra (`GenBool`, see [[Trie.genBool]]): union, intersection and
  * difference of entry sets, with the empty trie as `zero`. There is no `one`, since there is no set of all entries,
  * just as `Set[A]` has none for an unbounded `A`. Adding and removing a message is union and difference with a
  * singleton.
  *
  * The structure is a product of `GenBool`s: a node pairs the values stored at exactly its key (`own`) with a trie
  * for each next bit, and the three operations act pointwise. The empty trie is the bound that ends every branch, and
  * it is its own children, so each operation's base cases are just the laws of `zero`. A node whose parts are all
  * empty is never built: the one smart constructor, `Trie.node`, returns the empty trie instead, so a trie has one
  * representation and equal entries mean equal tries.
  *
  * The values are ordered (for the scheduler, arrival sequence numbers). Each node summarizes its subtree with the
  * meet of a bounded meet-semilattice, so the summary is the smallest value, and `None` is the top: the summary of
  * nothing. A node's `own` set holds the values stored at exactly its key and does not include its children's values;
  * `min` is the meet over the whole subtree, cached so that a path is rebuilt in O(depth). The summary is a fold of
  * the entry set, not part of its identity.
  *
  * A message conflicts with the entries on a prefix of its key (itself included) and with everything at or below
  * its key. [[minConflicting]] is the meet over exactly those: the ancestors' own entries and the subtree's summary.
  * Siblings do not conflict.
  */
sealed trait Trie[A]:
  /** The smallest value anywhere in the trie. It is `None` exactly when the trie is empty, which is `zero`: ask
    * `GenBool.isZero` instead of testing for emptiness separately.
    */
  def min: Option[A]

  def insert(bits: BitVector, a: A)(using Order[A]): Trie[A] = Trie.insertFrom(this, bits, 0L, a)

  /** Removes `a` from the node at `bits`. */
  def remove(bits: BitVector, a: A)(using Order[A]): Trie[A] = Trie.removeFrom(this, bits, 0L, a)

  /** The smallest value among entries whose key conflicts with `bits`. */
  def minConflicting(bits: BitVector)(using Order[A]): Option[A] =
    Trie.minConflictingFrom(this, bits, 0L, Trie.optionMeet[A])

  /** Every entry, as a key and a value. */
  def entries: List[(Key, A)] = Trie.entriesFrom(this, BitVector.empty)

object Trie:
  private final case class Empty[A]() extends Trie[A]:
    def min: Option[A] = None

  private final case class Node[A](own: SortedSet[A], zero: Trie[A], one: Trie[A], min: Option[A]) extends Trie[A]

  // The empty trie holds no `A`, so one shared instance serves every `A`. Operations return it as is, which is what
  // lets `x | 0` hand back `x` itself.
  private val emptyTrie: Trie[Any] = Empty[Any]()

  def empty[A]: Trie[A] = emptyTrie.asInstanceOf[Trie[A]]

  def singleton[A](bits: BitVector, a: A)(using Order[A]): Trie[A] = empty[A].insert(bits, a)

  /** The only way to build a node. The empty trie is exactly the one whose summary is the top of the summary lattice
    * (`None`: nothing stored), so an all-empty node collapses to it and no other trie is ever empty.
    */
  private def node[A](own: SortedSet[A], zero: Trie[A], one: Trie[A])(using Order[A]): Trie[A] =
    val L = optionMeet[A]
    val summary = L.meetSemilattice.combineAll(List(own.headOption, zero.min, one.min))
    if L.isOne(summary) then empty else Node(own, zero, one, summary)

  /** A trie as a node: the empty trie is a node with no values whose children are again empty. */
  private def parts[A](t: Trie[A])(using ord: Order[A]): (SortedSet[A], Trie[A], Trie[A]) =
    t match
      case n: Node[A] => (n.own, n.zero, n.one)
      case _          => (SortedSet.empty(using ord.toOrdering), t, t)

  // `i` is the number of bits of `bits` already consumed on the way down to the trie `t`.

  private def insertFrom[A](t: Trie[A], bits: BitVector, i: Long, a: A)(using Order[A]): Trie[A] =
    val (own, zero, one) = parts(t)
    bits.lift(i) match
      case None        => node(own + a, zero, one)
      case Some(false) => node(own, insertFrom(zero, bits, i + 1, a), one)
      case Some(true)  => node(own, zero, insertFrom(one, bits, i + 1, a))

  private def removeFrom[A](t: Trie[A], bits: BitVector, i: Long, a: A)(using Order[A]): Trie[A] =
    t match
      case n: Node[A] =>
        bits.lift(i) match
          case None        => node(n.own - a, n.zero, n.one)
          case Some(false) => node(n.own, removeFrom(n.zero, bits, i + 1, a), n.one)
          case Some(true)  => node(n.own, n.zero, removeFrom(n.one, bits, i + 1, a))
      case _ => t

  private def minConflictingFrom[A](t: Trie[A], bits: BitVector, i: Long, L: BoundedMeetSemilattice[Option[A]]): Option[A] =
    t match
      case n: Node[A] =>
        bits.lift(i) match
          case None => n.min
          case Some(bit) =>
            L.meet(n.own.headOption, minConflictingFrom(if bit then n.one else n.zero, bits, i + 1, L))
      case _ => None

  private def entriesFrom[A](t: Trie[A], path: BitVector): List[(Key, A)] =
    t match
      case n: Node[A] =>
        n.own.toList.map(a => (Key(path), a)) ++
          entriesFrom(n.zero, path :+ false) ++
          entriesFrom(n.one, path :+ true)
      case _ => Nil

  /** Combines two tries node by node: `ownOp` on the values at a key, `childOp` on each pair of subtries. */
  private def zipNodes[A](x: Trie[A], y: Trie[A])(
      ownOp: (SortedSet[A], SortedSet[A]) => SortedSet[A],
      childOp: (Trie[A], Trie[A]) => Trie[A]
  )(using Order[A]): Trie[A] =
    val (ox, zx, nx) = parts(x)
    val (oy, zy, ny) = parts(y)
    node(ownOp(ox, oy), childOp(zx, zy), childOp(nx, ny))

  /** Union, intersection and difference of the entry sets, with the empty trie as `zero`.
    *
    * Each operation recurses pointwise through `zipNodes`. Its base cases are the laws of `zero`, asked of the algebra
    * itself with `isZero`: `x | 0 = x`, `x & 0 = 0`, `x - 0 = x`, `0 - x = 0`.
    */
  given genBool[A](using Order[A]): GenBool[Trie[A]] with
    // `isZero` compares with `zero`. Equality is structural and a trie has one representation, so this is cheap.
    private given Eq[Trie[A]] = Eq.fromUniversalEquals

    def zero: Trie[A] = empty[A]

    def or(x: Trie[A], y: Trie[A]): Trie[A] =
      if isZero(x) then y
      else if isZero(y) then x
      else zipNodes(x, y)(_ ++ _, or)

    def and(x: Trie[A], y: Trie[A]): Trie[A] =
      if isZero(x) || isZero(y) then zero
      else zipNodes(x, y)(_ & _, and)

    def without(x: Trie[A], y: Trie[A]): Trie[A] =
      if isZero(x) then zero
      else if isZero(y) then x
      else zipNodes(x, y)(_ diff _, without)

  /** The smaller of two optional values, with `None` as the top. For a chain this is the meet of its lattice. */
  given optionMeet[A](using ord: Order[A]): BoundedMeetSemilattice[Option[A]] with
    def one: Option[A] = None

    def meet(x: Option[A], y: Option[A]): Option[A] =
      (x, y) match
        case (Some(a), Some(b)) => Some(ord.min(a, b))
        case (None, _)          => y
        case (_, None)          => x

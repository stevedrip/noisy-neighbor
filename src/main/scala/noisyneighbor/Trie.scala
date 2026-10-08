package noisyneighbor

import algebra.lattice.GenBool
import cats.kernel.Eq
import scala.annotation.tailrec
import scala.collection.immutable.TreeMap
import scodec.bits.BitVector

/** A finite map from keys to `A`, where a key that is not in it has the zero of
  * `A`. There is a node for every key with a value other than zero, and the
  * root, which is the empty key and may hold zero. The parent of a node is the
  * node of the longest key that is a proper prefix of its own, and the edge
  * between them carries the bits in between, so the labels of the children of a
  * node are relative to it. No label is a prefix of another (the children are
  * an antichain), and none is empty. There are no nodes where keys merely
  * diverge, so there are as many nodes as keys.
  *
  * A trie has one form, so `==` is equality of the maps: the shape follows from
  * the keys that have a value and nothing else. No node other than the root
  * holds zero.
  *
  * When `A` is a [[GenBool]], so is `Trie[A]`, pointwise: at every key, the
  * union, intersection and difference of two tries have the `or`, `and` and
  * `without` of their values there. A key that only one trie has is combined
  * with the zero of `A` on the other side, so intersection drops it, and
  * difference drops it if it is the right trie's. `Trie[Boolean]` is a set of
  * keys. The walks recurse once per level of nesting, not once per bit.
  */
final case class Trie[A](
  children: TreeMap[BitVector, Trie[A]],
  value: A
):
  import Trie.*

  /** The value at `key`, or the zero of `A`. */
  def get(key: Key)(using ev: GenBool[A]): A =
    @tailrec def go(trie: Trie[A], rest: BitVector): A =
      if rest.isEmpty then trie.value
      else
        trie.children
          .rangeTo(rest)
          .lastOption
          .filter((l, _) => rest.startsWith(l)) match
          case Some((l, child)) => go(child, rest.drop(l.size))
          case None => ev.zero
    go(this, key.bits)

  /** The keys that have a value other than zero, with their values, in order: a
    * key before the keys that extend it.
    */
  def entries(using ev: GenBool[A], eq: Eq[A]): List[(Key, A)] =
    def go(trie: Trie[A], prefix: BitVector): List[(Key, A)] =
      Option.unless(ev.isZero(trie.value))((Key(prefix), trie.value)).toList ++
        trie.children.toList.flatMap((l, child) => go(child, prefix ++ l))
    go(this, BitVector.empty)

object Trie:
  def empty[A](using ev: GenBool[A]): Trie[A] = Trie(TreeMap.empty, ev.zero)

  /** The trie with `value` at `key` and nothing else. */
  def single[A](key: Key, value: A)(using
    ev: GenBool[A],
    eq: Eq[A]
  ): Trie[A] =
    if ev.isZero(value) then empty
    else if key.bits.isEmpty then Trie(TreeMap.empty, value)
    else Trie(TreeMap(key.bits -> Trie(TreeMap.empty, value)), ev.zero)

  given eq[A]: Eq[Trie[A]] = Eq.fromUniversalEquals

  /** The pointwise `GenBool`, for a value type that is one. */
  given genBool[A](using ev: GenBool[A], eq: Eq[A]): GenBool[Trie[A]] =
    new GenBool[Trie[A]]:
      def zero: Trie[A] = empty
      def or(a: Trie[A], b: Trie[A]): Trie[A] =
        combine(a, b, ev.or, keepLeft = true, keepRight = true)
      def and(a: Trie[A], b: Trie[A]): Trie[A] =
        combine(a, b, ev.and, keepLeft = false, keepRight = false)
      def without(a: Trie[A], b: Trie[A]): Trie[A] =
        combine(a, b, ev.without, keepLeft = true, keepRight = false)

  /** The trie whose value at every key is `op` of the two values there. What
    * only one side has is combined with the zero of `A` on the other, which
    * `keepLeft` and `keepRight` say gives back that value (`op(a, zero) = a`)
    * or zero (`op(a, zero) = zero`), for the left side and the right side
    * respectively. That is what lets a side's own entries be kept or dropped
    * without looking at them.
    */
  def combine[A](
    x: Trie[A],
    y: Trie[A],
    op: (A, A) => A,
    keepLeft: Boolean,
    keepRight: Boolean
  )(using ev: GenBool[A], eq: Eq[A]): Trie[A] =

    /** The edges that a combined child leaves. A child that holds zero is not a
      * node: what is below it hangs from the parent instead, with longer
      * labels, and nothing is left if there is nothing below it.
      */
    def edges(label: BitVector, t: Trie[A]): Vector[(BitVector, Trie[A])] =
      if !ev.isZero(t.value) then Vector((label, t))
      else t.children.toVector.map((l, c) => (label ++ l, c))

    /** The children of the combination. Both lists are antichains in order, so
      * what overlaps a label is next to it. The label that is a prefix of the
      * others it conflicts with is where they are combined: they are all that
      * the other side has below it, a trie of its own with nothing at the top.
      */
    @tailrec def go(
      xs: List[(BitVector, Trie[A])],
      ys: List[(BitVector, Trie[A])],
      out: Vector[(BitVector, Trie[A])]
    ): Vector[(BitVector, Trie[A])] =
      def below(
        label: BitVector,
        side: List[(BitVector, Trie[A])]
      ): (Trie[A], List[(BitVector, Trie[A])]) =
        val (inside, outside) = side.span((l, _) => l.startsWith(label))
        (
          Trie(
            TreeMap.from(inside.map((l, t) => (l.drop(label.size), t))),
            ev.zero
          ),
          outside
        )
      (xs, ys) match
        case (Nil, Nil) => out
        case (Nil, _) => if keepRight then out ++ ys else out
        case (_, Nil) => if keepLeft then out ++ xs else out
        case ((lx, tx) :: xt, (ly, ty) :: yt) =>
          if lx == ly then
            go(
              xt,
              yt,
              out ++ edges(lx, combine(tx, ty, op, keepLeft, keepRight))
            )
          else if ly.startsWith(lx) then
            val (v, rest) = below(lx, ys)
            go(
              xt,
              rest,
              out ++ edges(lx, combine(tx, v, op, keepLeft, keepRight))
            )
          else if lx.startsWith(ly) then
            val (v, rest) = below(ly, xs)
            go(
              rest,
              yt,
              out ++ edges(ly, combine(v, ty, op, keepLeft, keepRight))
            )
          else if Ordering[BitVector].gt(lx, ly) then
            go(xt, ys, if keepLeft then out :+ (lx, tx) else out)
          else go(xs, yt, if keepRight then out :+ (ly, ty) else out)

    Trie(
      TreeMap.from(go(x.children.toList, y.children.toList, Vector.empty)),
      op(x.value, y.value)
    )

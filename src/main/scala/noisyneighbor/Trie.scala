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
  * `without` of their values there. The algebra of a trie is the product of the
  * algebra of its value and the algebra of its children, which is a [[GenBool]]
  * in its own right: [[Trie.children]]. A key that only one trie has is
  * combined with the zero of `A` on the other side, so intersection drops it,
  * and difference drops it if it is the right trie's. `Trie[Boolean]` is a set
  * of keys. The walks recurse once per level of nesting, not once per bit.
  */
final case class Trie[A](
  children: TreeMap[BitVector, Trie[A]],
  value: A
):
  import Trie.*

  /** The child whose label is a prefix of `rest`, if there is one. The labels
    * are an antichain, so it is the greatest one at or before `rest`:
    * everything between a label and a key that extends it extends the label
    * too.
    */
  def child(rest: BitVector): Option[(BitVector, Trie[A])] =
    children.rangeTo(rest).lastOption.filter((l, _) => rest.startsWith(l))

  /** The deepest node whose key is a prefix of `key`: this node, if no child's
    * label is a prefix of it, and otherwise as far down as the labels go. It
    * comes with its own key and the bits of `key` beyond it, which are none if
    * `key` is the key of a node. Where a key ends part-way along an edge, the
    * node above the edge owns it, with the bits that are left.
    */
  def owner(key: BitVector): (Trie[A], BitVector, BitVector) =
    @tailrec def go(
      node: Trie[A],
      address: BitVector,
      rest: BitVector
    ): (Trie[A], BitVector, BitVector) =
      node.child(rest) match
        case Some((label, next)) =>
          go(next, address ++ label, rest.drop(label.size))
        case None => (node, address, rest)
    go(this, BitVector.empty, key)

  /** Applies `f` to the node for `prefix`, given its key, and rebuilds the trie
    * around what it returns along with whatever else it returns. There is
    * nothing if there is no node for `prefix`, which includes a key that ends
    * part-way along an edge. The root is the node for the empty key. Every walk
    * down to a node is this one.
    */
  def along[B](prefix: BitVector)(
    f: (Trie[A], BitVector) => (Trie[A], B)
  ): Option[(Trie[A], B)] =
    def descend(
      node: Trie[A],
      address: BitVector,
      rest: BitVector
    ): Option[(Trie[A], B)] =
      if rest.isEmpty then Some(f(node, address))
      else
        node
          .child(rest)
          .flatMap: (label, next) =>
            descend(next, address ++ label, rest.drop(label.size)).map:
              (updated, b) =>
                (node.copy(children = node.children.updated(label, updated)), b)
    descend(this, BitVector.empty, prefix)

  /** The trie with `f` applied to the node for `prefix`, or the trie as it is
    * if there is no such node.
    */
  def modify(prefix: BitVector)(f: Trie[A] => Trie[A]): Trie[A] =
    along(prefix)((node, _) => (f(node), ())).fold(this)((updated, _) =>
      updated
    )

  /** Makes sure there is a node for `prefix`. If there is not, one holding
    * `fresh` is created beneath the deepest node that owns the prefix, and the
    * children of that node that extend `prefix` become its children. If there
    * is, `onTarget` is applied to its value. `onPath` is applied to the value
    * of every node on the way down from the root, the target not included, and
    * the root is the target if the prefix is empty.
    */
  def upsert(prefix: BitVector, fresh: A)(
    onPath: A => A,
    onTarget: A => A
  ): Trie[A] =
    def go(node: Trie[A], rest: BitVector): Trie[A] =
      if rest.isEmpty then node.copy(value = onTarget(node.value))
      else
        val path = node.copy(value = onPath(node.value))
        node.child(rest) match
          case Some((label, next)) =>
            path.copy(children =
              node.children.updated(label, go(next, rest.drop(label.size)))
            )
          case None =>
            val adopted = node.children.filter((l, _) => l.startsWith(rest))
            val below =
              TreeMap.from(adopted.map((l, c) => (l.drop(rest.size), c)))
            path.copy(children =
              node.children
                .removedAll(adopted.keys)
                .updated(rest, Trie(below, fresh))
            )
    go(this, prefix)

  /** The trie without the nodes, and everything below them, that `drop` says to
    * remove, looking at each node after what is below it has been pruned. The
    * root is never removed.
    */
  def prune(drop: Trie[A] => Boolean): Trie[A] =
    copy(children = children.flatMap: (label, child) =>
      val kept = child.prune(drop)
      if drop(kept) then None else Some(label -> kept)
    )

  /** Every node with its key and value, whatever the value is, in order: a node
    * before the nodes below it.
    */
  def nodes: List[(BitVector, A)] =
    def go(trie: Trie[A], prefix: BitVector): List[(BitVector, A)] =
      (prefix, trie.value) ::
        trie.children.toList.flatMap((l, child) => go(child, prefix ++ l))
    go(this, BitVector.empty)

object Trie:
  def empty[A](using ev: GenBool[A]): Trie[A] = Trie(TreeMap.empty, ev.zero)

  /** The trie with `value` at `key` and nothing else. */
  def single[A](key: BitVector, value: A)(using
    ev: GenBool[A],
    eq: Eq[A]
  ): Trie[A] =
    if ev.isZero(value) then empty
    else if key.isEmpty then Trie(TreeMap.empty, value)
    else Trie(TreeMap(key -> Trie(TreeMap.empty, value)), ev.zero)

  given eq[A]: Eq[Trie[A]] = Eq.fromUniversalEquals

  /** The pointwise `GenBool`, for a value type that is one: the value is
    * combined by `A`'s algebra, and the children by their own.
    */
  given genBool[A](using ev: GenBool[A], eqA: Eq[A]): GenBool[Trie[A]] =
    new GenBool[Trie[A]]:
      private lazy val kids: GenBool[TreeMap[BitVector, Trie[A]]] =
        children(using this, ev, eqA)
      def zero: Trie[A] = empty
      def or(a: Trie[A], b: Trie[A]): Trie[A] =
        Trie(kids.or(a.children, b.children), ev.or(a.value, b.value))
      def and(a: Trie[A], b: Trie[A]): Trie[A] =
        Trie(kids.and(a.children, b.children), ev.and(a.value, b.value))
      def without(a: Trie[A], b: Trie[A]): Trie[A] =
        Trie(
          kids.without(a.children, b.children),
          ev.without(a.value, b.value)
        )

  /** The children of a trie, as an algebra of their own: maps from labels that
    * are an antichain to the tries below, where the subtries are combined by
    * `sub`. It is the algebra of the finite maps from non-empty keys to `A`
    * that they stand for, so the empty map is its zero. Where two maps have a
    * subtrie under the same label, or one has a label that is a prefix of
    * labels of the other, the subtries that overlap are combined by `sub`. A
    * subtrie that only one side has is what the operation gives for it against
    * nothing: itself, or nothing.
    */
  given children[A](using
    sub: GenBool[Trie[A]],
    ev: GenBool[A],
    eq: Eq[A]
  ): GenBool[TreeMap[BitVector, Trie[A]]] =
    new GenBool[TreeMap[BitVector, Trie[A]]]:
      type Children = TreeMap[BitVector, Trie[A]]
      type Edge = (BitVector, Trie[A])

      def zero: Children = TreeMap.empty
      def or(a: Children, b: Children): Children =
        merge(a, b, sub.or, identity, identity)
      def and(a: Children, b: Children): Children =
        merge(a, b, sub.and, _ => sub.zero, _ => sub.zero)
      def without(a: Children, b: Children): Children =
        merge(a, b, sub.without, identity, _ => sub.zero)

      /** The edges that a combined subtrie leaves. One that holds zero is not a
        * node: what is below it hangs from the parent instead, with longer
        * labels, and nothing is left if there is nothing below it.
        */
      def edges(label: BitVector, t: Trie[A]): Vector[Edge] =
        if !ev.isZero(t.value) then Vector((label, t))
        else t.children.toVector.map((l, c) => (label ++ l, c))

      /** What is under `label` on one side, as a trie of its own with nothing
        * at the top, and what is after it.
        */
      def below(label: BitVector, side: List[Edge]): (Trie[A], List[Edge]) =
        val (inside, outside) = side.span((l, _) => l.startsWith(label))
        (
          Trie(
            TreeMap.from(inside.map((l, t) => (l.drop(label.size), t))),
            ev.zero
          ),
          outside
        )

      /** Both sides are antichains in order, so what overlaps a label is next
        * to it. The label that is a prefix of the others it conflicts with is
        * where they are combined.
        */
      def merge(
        a: Children,
        b: Children,
        op: (Trie[A], Trie[A]) => Trie[A],
        onlyLeft: Trie[A] => Trie[A],
        onlyRight: Trie[A] => Trie[A]
      ): Children =
        @tailrec def go(
          xs: List[Edge],
          ys: List[Edge],
          out: Vector[Edge]
        ): Vector[Edge] =
          (xs, ys) match
            case (Nil, Nil) => out
            case ((lx, tx) :: xt, Nil) =>
              go(xt, Nil, out ++ edges(lx, onlyLeft(tx)))
            case (Nil, (ly, ty) :: yt) =>
              go(Nil, yt, out ++ edges(ly, onlyRight(ty)))
            case ((lx, tx) :: xt, (ly, ty) :: yt) =>
              if lx == ly then go(xt, yt, out ++ edges(lx, op(tx, ty)))
              else if ly.startsWith(lx) then
                val (v, rest) = below(lx, ys)
                go(xt, rest, out ++ edges(lx, op(tx, v)))
              else if lx.startsWith(ly) then
                val (v, rest) = below(ly, xs)
                go(rest, yt, out ++ edges(ly, op(v, ty)))
              else if Ordering[BitVector].lt(lx, ly) then
                go(xt, ys, out ++ edges(lx, onlyLeft(tx)))
              else go(xs, yt, out ++ edges(ly, onlyRight(ty)))
        TreeMap.from(go(a.toList, b.toList, Vector.empty))

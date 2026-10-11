// package noisyneighbor

// import cats.PartialOrder
// import cats.effect.IO
// import fs2.Chunk

// import FifoTrie.{Message, Node}

// class FifoTrie[K, V, F <: Fifo[Message[K, V]]] extends F with Trie[(K, F)] (
//   trie: Trie[F]
// )(
//   given partialOrder: PartialOrder[K]
// ) = {
//   // Redundant with the relative paths. Convenient for comparing against chunks.
//   def absolutePath = trie.value.path

//   // Pass a "Split" message down the queue with a key. Once that message is consumed, the node will be added.
//   def split(key: K): IO[Unit]

//   // Pass a "Merge" message down the queue with a key. Once that message is consumed, the node will be removed.
//   def merge(key: K): IO[Unit]

//   // Adds the messages at the root, which routes each one by its key.
//   def offer(chunk: Chunk[Message[K, V]], f: Int => IO[Unit]): IO[Unit]

//   // Takes up to `count` messages, children first and then this queue, and gives `f` each chunk in that order.
//   def take(count: Int, f: Chunk[Message[K, V]] => IO[Unit]): IO[Unit]

//   // Whether this queue and everything below it hold nothing.
//   def isEmpty: IO[Boolean]

//   // Takes up to `count` from the node's subtree, children first and concurrently, then its own queue, and gives how many were taken. Fewer than `count` means the subtree is drained.
//   private def takeFrom(
//     node: Trie[Node[K, F]],
//     count: Int,
//     f: Chunk[Message[K, V]] => IO[Unit]
//   ): IO[Int]

//   // The child whose path is a prefix of `key`, if there is one.
//   private def childFor(node: Trie[Node[K, F]], key: K): Option[Trie[Node[K, F]]]

//   // Deals with a chunk from a queue in order: own data goes to `f`, data for a child is forwarded, a control is applied, and the children are taken from again before an own message that follows a forward.
//   private def process(
//     node: Trie[Node[K, F]],
//     chunk: Chunk[Message[K, V]],
//     f: Chunk[Message[K, V]] => IO[Unit]
//   ): IO[Unit]

//   // Offers a message to the child it belongs to, which has room because the children were taken from first, so a refusal is an error.
//   private def forward(
//     node: Trie[Node[K, F]],
//     data: Message.Data[K, V]
//   ): IO[Unit]

//   // Adds the node for `key` beneath this one, adopting the children that extend it.
//   private def applySplit(node: Trie[Node[K, F]], key: K): IO[Unit]

//   // Removes the child at `key` with everything below it, which is empty because the children were taken from first.
//   private def applyMerge(node: Trie[Node[K, F]], key: K): IO[Unit]
// }

// object FifoTrie:
//   // What a queue holds: data for a consumer, or a change to the shape that is applied when it reaches the front.
//   enum Message[K, V]:
//     case Data(key: K, value: V)
//     case Split(key: K)
//     case Merge(key: K)

//   case class Node[K, F](path: K, fifo: F)

//   def empty[K, V, F <: Fifo[Message[K, V]]]: Node[K, F] = Node(K.empty, F.empty)

//   // A trie with only a root, which is the queue `root`.
//   def of[K: PartialOrder, V, F <: Fifo[Message[K, V]]](
//     root: F
//   ): IO[FifoTrie[K, V, F]]

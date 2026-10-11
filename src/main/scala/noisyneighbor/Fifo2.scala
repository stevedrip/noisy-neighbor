// package noisyneighbor

// import cats.effect.{IO, Ref}
// import fs2.Chunk

// /** A first-in, first-out queue that a producer and a consumer meet at. It has
//   * no offsets: each end is one call that answers at once, hands what crossed to
//   * the caller's own work, and is done when it returns. The two calls cross,
//   * `take` is asked for a count and gives a chunk, and `offer` is given a chunk
//   * and gives a count.
//   *
//   * A queue does not wait, and it does not look after who calls it. Waiting for
//   * messages or for room, and deciding when an end may close, are for whoever
//   * drives the queues, which is the trie. The callers are fibers that take
//   * turns, one at a time on each end, and share the queue without a lock. What a
//   * caller commits to wherever it reads from or writes to, usually an offset, it
//   * does once its call has finished.
//   *
//   * The two booleans, `taking` and `offering`, are how either end closes the
//   * queue, so there is no separate close. Both are true to begin with. A queue
//   * whose back is closed, `offering` false, is draining: it takes nothing new
//   * and is taken from until it is empty.
//   */
// trait Fifo[A]:
//   def consume(count: Int, f: Chunk[A] => IO[Int]): IO[Unit]
//   def produce(chunk: Chunk[A], f: Int => IO[Int]): IO[Unit]
//   val taking: Ref[IO, Boolean] = Ref.unsafe[IO, Boolean](true)
//   val offering: Ref[IO, Boolean] = Ref.unsafe[IO, Boolean](true)
//   val open: Ref[IO, Boolean] = Ref.unsafe[IO, Boolean](true) // Dead if draining
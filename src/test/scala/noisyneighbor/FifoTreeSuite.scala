package noisyneighbor

import munit.ScalaCheckSuite
import scala.annotation.tailrec
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

import scodec.bits.BitVector

import Fixtures.{genKey as genKeyOf, key as keyOf}
import FifoTree.Run

class FifoTreeSuite extends ScalaCheckSuite:
  /** Keys are plain bit vectors here. */
  def key(s: String): BitVector = keyOf(s).bits
  val genKey: Gen[BitVector] = genKeyOf.map(_.bits)

  /** Two keys conflict when one is a prefix of the other. */
  def conflict(a: BitVector, b: BitVector): Boolean =
    a.startsWith(b) || b.startsWith(a)

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(2000)

  type Tree = FifoTree[Int]

  /** An empty root with the given queues split off, in order. */
  def tree(prefixes: String*): Tree =
    prefixes.foldLeft(FifoTree.empty[Int])((t, p) => t.split(key(p)))

  /** Enqueues `(key, value)` pairs at the root. */
  def enqueued(t: Tree, messages: (String, Int)*): Tree =
    messages.foldLeft(t)((acc, m) =>
      acc.enqueue(key(m._1), m._2).getOrElse(fail("the root is draining"))
    )

  /** Each batch as its queue and the values in it. */
  def shape(runs: List[Run[Int]]): List[(String, List[Int])] =
    runs.map(r => (r.queue.toBin, r.items.map(_.value)))

  def values(run: Option[Run[Int]]): Option[(Long, List[Int])] =
    run.map(r => (r.first, r.items.map(_.value)))

  def prefixes(t: Tree): List[String] = t.queues.map(_.toBin)

  /** Acknowledges the whole of the batch that `runs` gave to the queue `q`. */
  def done(t: Tree, runs: List[Run[Int]], q: String): Tree =
    runs
      .find(_.queue == key(q))
      .fold(fail(s"no batch was handed out for '$q'"))(r =>
        t.acknowledge(r.queue, r.last)
      )

  /** The consumer of the queue `q` asks. */
  def ask(t: Tree, q: String, size: Int): (Tree, Option[Run[Int]]) =
    t.takeFrom(key(q), size)

  test(
    "README worked example: a message for '' waits for earlier ones in the children and holds back later ones"
  ):
    val (t1, r1) =
      enqueued(tree("0", "1"), "01" -> 0, "" -> 1, "10" -> 2).advance()
    // '01' goes to its queue and runs. '' conflicts with both children, so it waits, and '10' stays behind it.
    assertEquals(shape(r1), List(("0", List(0))))
    val (t2, r2) = done(t1, r1, "0").advance()
    assertEquals(shape(r2), List(("", List(1))))
    val (_, r3) = done(t2, r2, "").advance()
    assertEquals(shape(r3), List(("1", List(2))))

  test("README noisy neighbor: messages for '00' and '1' run while '01' does"):
    val (t1, r1) = enqueued(tree("01"), "010" -> 0, "00" -> 1, "1" -> 2)
      .advance(maxBatch = 1)
    assertEquals(shape(r1), List(("", List(1)), ("01", List(0))))
    // The root's next message does not conflict with '01', and goes as soon as the root is free.
    // '01' has not acknowledged, so asking it again gives its batch back.
    val (_, r2) = done(t1, r1, "").advance(maxBatch = 1)
    assertEquals(shape(r2), List(("", List(2)), ("01", List(0))))

  test(
    "a message for '0' waits for earlier messages in '01', and later ones behind it wait with it"
  ):
    val (t1, r1) =
      enqueued(tree("01"), "010" -> 0, "0" -> 1, "00" -> 2).advance()
    assertEquals(shape(r1), List(("01", List(0))))
    val (_, r2) = done(t1, r1, "01").advance()
    assertEquals(shape(r2), List(("", List(1, 2))))

  test("a message that would not conflict still waits behind a waiting one"):
    val (t1, r1) =
      enqueued(tree("01"), "010" -> 0, "0" -> 1, "1" -> 2).advance()
    assertEquals(shape(r1), List(("01", List(0))))
    // Only the batch that is out comes back. Message 2 is behind the waiting 1.
    assertEquals(shape(t1.advance()._2), List(("01", List(0))))

  test(
    "a forward after an own message ends the batch, and a conflicting own message waits for the forward"
  ):
    val (t1, r1) = enqueued(tree("0"), "1" -> 0, "00" -> 1, "1" -> 2).advance()
    // The forward to '0' must not start while 0 runs, so the root's batch is just message 0.
    assertEquals(shape(r1), List(("", List(0))))
    val (t2, r2) = done(t1, r1, "").advance()
    assertEquals(shape(r2), List(("", List(2)), ("0", List(1))))
    // Message 2 is acknowledged. What is left out is the batch at '0'.
    assertEquals(shape(done(t2, r2, "").advance()._2), List(("0", List(1))))

  test(
    "own messages are batched up to the maximum, and the rest wait for the batch to be acknowledged"
  ):
    val start = enqueued(tree(), "0" -> 0, "1" -> 1, "" -> 2, "11" -> 3)
    val (t1, r1) = start.advance(maxBatch = 3)
    assertEquals(shape(r1), List(("", List(0, 1, 2))))
    // Asked again, the same batch comes back, and message 3 is still not in it.
    val (t2, r2) = t1.advance(maxBatch = 3)
    assertEquals(shape(r2), List(("", List(0, 1, 2))))
    assertEquals(
      shape(done(t2, r2, "").advance(maxBatch = 3)._2),
      List(("", List(3)))
    )

  test("a queue with a batch out holds back what a child would be given"):
    val (t1, r1) = enqueued(tree("0"), "1" -> 0).advance()
    assertEquals(shape(r1), List(("", List(0))))
    val (t2, r2) = enqueued(t1, "00" -> 1).advance()
    // Message 1 is behind message 0, so it is not forwarded and '0' gets nothing.
    assertEquals(shape(r2), List(("", List(0))))
    assertEquals(
      shape(done(t2, r2, "").advance()._2),
      List(("0", List(1)))
    )

  test("asking again gives back what was not acknowledged, and may extend it"):
    val t0 = enqueued(tree(), "0" -> 0, "1" -> 1, "0" -> 2)
    val (t1, first) = ask(t0, "", 1)
    assertEquals(values(first), Some((0L, List(0))))
    // The same message is there again, and the others that can start join it.
    val (t2, second) = ask(t1, "", 3)
    assertEquals(values(second), Some((0L, List(0, 1, 2))))
    // A smaller request gets a prefix of what is out.
    val (_, smaller) = ask(t2, "", 2)
    assertEquals(values(smaller), Some((0L, List(0, 1))))

  test("acknowledging some of a batch leaves the rest to be handed out again"):
    val (t1, _) = ask(enqueued(tree(), "0" -> 0, "1" -> 1, "0" -> 2), "", 3)
    val t2 = t1.acknowledge(key(""), 1)
    val (_, rest) = ask(t2, "", 3)
    assertEquals(values(rest), Some((2L, List(2))))

  test("acknowledging what is already acknowledged changes nothing"):
    val (t1, _) = ask(enqueued(tree(), "0" -> 0, "1" -> 1), "", 2)
    val t2 = t1.acknowledge(key(""), 0)
    val t3 = t2.acknowledge(key(""), 0).acknowledge(key(""), -1)
    assertEquals(values(ask(t3, "", 5)._2), Some((1L, List(1))))

  test(
    "only a queue's own consumer moves its front: a child's ask touches nothing above"
  ):
    val t0 = enqueued(tree("0"), "00" -> 0)
    val (t1, child) = ask(t0, "0", 5)
    assertEquals(child, None) // the message is still at the root
    val (t2, root) = ask(t1, "", 5)
    assertEquals(root, None) // the root forwards it, and has none of its own
    assertEquals(values(ask(t2, "0", 5)._2), Some((0L, List(0))))

  test("asking again after a split forwards a message the new queue now owns"):
    val (t1, first) = ask(enqueued(tree(), "01" -> 0, "1" -> 1), "", 2)
    assertEquals(values(first), Some((0L, List(0, 1))))
    val split = t1.split(key("0"))
    // The consumer asked again, so it is no longer working on message 0, which goes to '0'.
    val (t2, again) = ask(split, "", 2)
    assertEquals(values(again), Some((1L, List(1))))
    assertEquals(values(ask(t2, "0", 2)._2), Some((0L, List(0))))

  test("what is behind a batch waits until it is acknowledged"):
    val (t1, r1) = enqueued(tree(), "0" -> 0, "1" -> 1).advance(maxBatch = 1)
    assertEquals(shape(r1), List(("", List(0))))
    assertEquals(shape(t1.advance(maxBatch = 1)._2), List(("", List(0))))
    val (_, r2) = done(t1, r1, "").advance(maxBatch = 1)
    assertEquals(shape(r2), List(("", List(1))))

  test(
    "take hands out the first batch from the queues in turn, parents first"
  ):
    val t0 = enqueued(tree("0", "1"), "10" -> 0, "01" -> 1)
    // The root has nothing of its own and forwards both, then '0' answers.
    val (t1, first) = t0.take(5)
    assertEquals(shape(first.toList), List(("0", List(1))))
    // Asked again, the same queue answers with the same batch.
    val (t2, again) = t1.take(5)
    assertEquals(shape(again.toList), List(("0", List(1))))
    // Once '0' has acknowledged, '1' is next.
    val (_, next) = done(t2, again.toList, "0").take(5)
    assertEquals(shape(next.toList), List(("1", List(0))))

  test(
    "split creates the queue at once, and a message already in the parent goes to it when it is read"
  ):
    val queued = enqueued(FifoTree.empty[Int], "01" -> 0)
    val (t1, r1) = queued.split(key("0")).advance()
    assertEquals(prefixes(t1), List("", "0"))
    assertEquals(shape(r1), List(("0", List(0))))

  test("split under an existing queue reparents the queues it covers"):
    val t = tree("11", "1", "10")
    assertEquals(prefixes(t), List("", "1", "10", "11"))
    assertEquals(prefixes(tree("1", "11")), List("", "1", "11"))
    assertEquals(prefixes(tree("11", "1")), List("", "1", "11"))
    // the root has one child, and it is '1'
    assertEquals(t.children.keys.map(_.toBin).toList, List("1"))

  test("split is idempotent: splitting what exists changes nothing"):
    val t = tree("0", "01", "1")
    assertEquals(prefixes(t.split(key("0"))), prefixes(t))
    assertEquals(prefixes(t.split(key("01"))), prefixes(t))
    assertEquals(prefixes(t.split(BitVector.empty)), prefixes(t))
    assertEquals(prefixes(t.split(key("0")).split(key("0"))), prefixes(t))

  test("splitting a queue that is draining stops it draining"):
    val drained = tree("0").drain(key("0"))
    // Draining, it takes nothing: the root keeps the message as its own.
    val (_, gone) = enqueued(drained, "00" -> 0).advance()
    assertEquals(shape(gone), List(("", List(0))))
    val back = drained.split(key("0"))
    val (t1, runs) = enqueued(back, "00" -> 0).advance()
    assertEquals(shape(runs), List(("0", List(0))))
    assertEquals(prefixes(t1), List("", "0"))

  test("splitting beneath a queue that is draining stops it draining too"):
    val drained = tree("0").drain(key("0"))
    val split = drained.split(key("01"))
    assertEquals(prefixes(split), List("", "0", "01"))
    val (_, runs) = enqueued(split, "011" -> 0).advance()
    assertEquals(shape(runs), List(("01", List(0))))

  test("splitting further down stops every draining queue on the way draining"):
    val drained = tree("0", "01").drain(key("0")).drain(key("01"))
    val split = drained.split(key("011"))
    assertEquals(prefixes(split), List("", "0", "01", "011"))
    // A message for the new queue goes through '0' and '01' to it in one pass.
    val (_, runs) = enqueued(split, "0110" -> 0).advance()
    assertEquals(shape(runs), List(("011", List(0))))

  test("splitting the root stops it draining"):
    val drained = tree("0").drain(BitVector.empty)
    assertEquals(drained.enqueue(key("1"), 0), None)
    assert(drained.split(BitVector.empty).enqueue(key("1"), 0).isDefined)

  test(
    "merge: the parent keeps the range's messages as its own, and each waits for the draining queue"
  ):
    val (t1, r1) = enqueued(tree("0"), "01" -> 0).advance()
    assertEquals(shape(r1), List(("0", List(0))))
    val merged = t1.drain(key("0"))
    val (t2, r2) = enqueued(merged, "00" -> 1, "1" -> 2).advance()
    // The draining queue still has its batch out, and the root waits behind it.
    assertEquals(shape(r2), List(("0", List(0))))
    assertEquals(prefixes(t2), List("", "0"))
    val t3 = done(t2, r2, "0")
    assertEquals(prefixes(t3), List("")) // it is empty, so it is gone
    assertEquals(shape(t3.advance()._2), List(("", List(1, 2))))

  test(
    "merge: a draining queue finishes the messages it has, including those forwarded to its children"
  ):
    val (t1, r1) = enqueued(tree("0", "00"), "001" -> 0, "01" -> 1).advance()
    assertEquals(shape(r1), List(("0", List(1)), ("00", List(0))))
    val merged = t1.drain(key("0"))
    val (t2, r2) = merged.advance()
    assertEquals(prefixes(t2), List("", "0", "00"))
    val t3 = done(t2, r2, "0")
    assertEquals(prefixes(t3), List("", "0", "00")) // '00' still has its batch
    val t4 = done(t3, r2, "00")
    assertEquals(prefixes(t4), List(""))

  test("merge: an empty queue is removed at the next ask"):
    val (t, runs) = tree("0", "1").drain(key("0")).advance()
    assertEquals(runs, Nil)
    assertEquals(prefixes(t), List("", "1"))

  test(
    "asking about, draining or acknowledging a queue that is not there changes nothing"
  ):
    val t = enqueued(tree("0"), "01" -> 0)
    assertEquals(prefixes(t.drain(key("1"))), prefixes(t))
    assertEquals(shape(t.drain(key("1")).advance()._2), shape(t.advance()._2))
    assertEquals(prefixes(t.acknowledge(key("1"), 0)), prefixes(t))
    assertEquals(t.takeFrom(key("1"), 1)._2, None)
    assertEquals(prefixes(t.takeFrom(key("1"), 1)._1), prefixes(t))
    // Asking for nothing hands out nothing.
    assertEquals(t.takeFrom(key(""), 0)._2, None)

  test(
    "draining the root stops it accepting messages, and it finishes what it has"
  ):
    val (t1, r1) = enqueued(tree("0"), "01" -> 0, "1" -> 1).advance()
    assertEquals(shape(r1), List(("", List(1)), ("0", List(0))))
    val drained = t1.drain(BitVector.empty)
    assertEquals(drained.enqueue(key("1"), 0), None)
    val (t2, r2) = drained.advance()
    assertEquals(shape(r2), shape(r1)) // nothing new, only the batches out
    val done2 = done(done(t2, r2, ""), r2, "0")
    assert(done2.isEmpty)
    assertEquals(prefixes(done2.advance()._1), List("", "0")) // the root stays

  test("merging a queue that is already draining changes nothing"):
    val once = tree("0").drain(key("0"))
    assertEquals(prefixes(once.drain(key("0"))), prefixes(once))

  /** What the random run does. A pick selects among what exists at that point.
    * `Ask` is the consumer of one queue asking for a batch of up to `size`, and
    * `AskAny` asks the queues in turn. `Ack` acknowledges the first `count`
    * messages of what a queue handed out, as an offset, and `AckAll` all of
    * them. `Stale` acknowledges an offset that is long gone. A consumer that
    * asks again has stopped working on what it had.
    */
  enum Op:
    case Enqueue(key: BitVector)
    case Split(key: BitVector)
    case Drain(pick: Int)
    case Ack(pick: Int, count: Int)
    case AckAll(pick: Int)
    case Stale(pick: Int)
    case Ask(pick: Int, size: Int)
    case AskAny(size: Int)
    case Idle

  val genOp: Gen[Op] =
    val pick = Gen.choose(0, 7)
    val size = Gen.choose(1, 4)
    Gen.frequency(
      12 -> genKey.map(Op.Enqueue(_)),
      2 -> genKey.map(Op.Split(_)),
      4 -> pick.map(Op.Drain(_)),
      4 -> Gen.zip(pick, Gen.choose(0, 5)).map(Op.Ack(_, _)),
      3 -> pick.map(Op.AckAll(_)),
      1 -> pick.map(Op.Stale(_)),
      8 -> Gen.zip(pick, size).map(Op.Ask(_, _)),
      3 -> size.map(Op.AskAny(_)),
      1 -> Gen.const(Op.Idle)
    )

  /** The run so far, kept as facts the oracle can check afterwards. `clock`
    * orders every delivery and acknowledgment. `pending` is what each queue's
    * consumer holds: its last batch, less what it has acknowledged, with
    * offsets.
    */
  final case class Sim(
    trie: Tree,
    clock: Int,
    keys: Vector[BitVector],
    pending: Map[BitVector, Vector[(Long, Int)]],
    started: Map[Int, Int],
    firstDelivery: Map[Int, List[Int]],
    ended: Map[Int, Int],
    problems: Vector[String]
  ):
    def tick: (Sim, Int) = (copy(clock = clock + 1), clock)
    def problem(s: String): Sim = copy(problems = problems :+ s)

  def fresh: Sim = Sim(
    FifoTree.empty[Int],
    0,
    Vector.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Vector.empty
  )

  /** The consumer of `queue` asked and got `run`, of at most `size`. */
  def deliver(
    sim: Sim,
    queue: BitVector,
    run: Option[Run[Int]],
    size: Int
  ): Sim =
    val (ticked, at) = sim.tick
    run match
      case None => ticked.copy(pending = ticked.pending - queue)
      case Some(r) =>
        val vs = r.items.map(_.value)
        val before = ticked.pending.getOrElse(queue, Vector.empty)
        val fresh = vs.filter(!ticked.started.contains(_))
        val problems = List(
          Option.when(vs.size > size)(s"batch $vs is over $size"),
          Option.when(r.items.exists(i => i.key != ticked.keys(i.value)))(
            s"key mismatch in $vs"
          ),
          // It starts at the first message not acknowledged, or later if some were forwarded.
          Option.when(before.nonEmpty && r.first < before.head._1)(
            s"batch $vs starts at ${r.first}, before ${before.head._1}"
          ),
          // Where it starts at the same message, it repeats what was out.
          Option.when(
            before.nonEmpty && r.first == before.head._1 &&
              vs.take(before.size) != before.map(_._2).take(vs.size)
          )(s"batch $vs does not repeat what was out: ${before.map(_._2)}")
        ).flatten
        ticked.copy(
          pending = ticked.pending.updated(
            queue,
            vs.indices.map(i => (r.first + i, vs(i))).toVector
          ),
          started =
            fresh.foldLeft(ticked.started)((st, v) => st.updated(v, at)),
          firstDelivery = fresh
            .foldLeft(ticked.firstDelivery)((m, v) => m.updated(v, vs)),
          problems = ticked.problems ++ problems
        )

  /** The consumer of `queue` asks. */
  def askQueue(sim: Sim, queue: BitVector, size: Int): Sim =
    val (t, run) = sim.trie.takeFrom(queue, size)
    deliver(sim.copy(trie = t), queue, run, size)

  /** Acknowledges the first `count` messages a queue handed out. */
  def ack(sim: Sim, queue: BitVector, count: Int): Sim =
    val (acked, rest) = sim.pending(queue).splitAt(count)
    val (ticked, at) = sim.tick
    ticked.copy(
      trie = ticked.trie.acknowledge(queue, acked.last._1),
      pending =
        if rest.isEmpty then ticked.pending - queue
        else ticked.pending.updated(queue, rest),
      ended = ticked.ended ++ acked.map(_._2 -> at)
    )

  def perform(sim: Sim, op: Op): Sim =
    val held = sim.pending.filter(_._2.nonEmpty).keys.toVector.sortBy(_.toBin)
    op match
      case Op.Enqueue(k) =>
        sim.trie
          .enqueue(k, sim.keys.size)
          .fold(sim)(t => sim.copy(trie = t, keys = sim.keys :+ k))
      case Op.Split(k) =>
        sim.copy(trie = sim.trie.split(k))
      case Op.Drain(pick) =>
        val targets = sim.trie.queues
        sim.copy(trie = sim.trie.drain(targets(pick % targets.size)))
      case Op.Ack(pick, count) if held.nonEmpty =>
        val queue = held(pick % held.size)
        ack(sim, queue, 1 + count % sim.pending(queue).size)
      case Op.AckAll(pick) if held.nonEmpty =>
        val queue = held(pick % held.size)
        ack(sim, queue, sim.pending(queue).size)
      case Op.Stale(pick) =>
        val targets = sim.trie.queues
        sim.copy(trie = sim.trie.acknowledge(targets(pick % targets.size), -1L))
      case Op.Ask(pick, size) =>
        val targets = sim.trie.queues
        askQueue(sim, targets(pick % targets.size), size)
      case Op.AskAny(size) =>
        // Every queue before the one that answered was asked and had nothing.
        val before = sim.trie.queues
        val (t, run) = sim.trie.take(size)
        val asked = run.fold(before)(r => before.takeWhile(_ != r.queue))
        val cleared = asked.foldLeft(sim.copy(trie = t))((s, q) =>
          deliver(s, q, None, size)
        )
        run.fold(cleared)(r => deliver(cleared, r.queue, Some(r), size))
      case _ => sim

  /** Every queue is asked in turn, everything handed out is acknowledged, and
    * that repeats until nothing is handed out, which must leave nothing
    * waiting.
    */
  @tailrec final def settle(sim: Sim, size: Int, budget: Int = 10000): Sim =
    val asked = sim.trie.queues
      .foldLeft(sim)((s, q) =>
        if s.trie.queues.contains(q) then askQueue(s, q, size) else s
      )
    if asked.pending.isEmpty then
      if asked.trie.isEmpty then asked
      else asked.problem("messages are waiting and nothing is handed out")
    else if budget == 0 then asked.problem("did not settle")
    else settle(perform(asked, Op.AckAll(0)), size, budget - 1)

  /** The semantics, checked on the facts alone, without looking at how the tree
    * is built. A message may be handed out with an earlier conflicting one that
    * is not yet acknowledged only in the same batch, after it. Otherwise the
    * earlier one has to have been acknowledged first.
    */
  def violations(sim: Sim): List[String] =
    val n = sim.keys.size

    /** What is wrong with the way `a`, which arrived first, and `b` ran, if
      * they conflict.
      */
    def orderProblem(a: Int, b: Int): Option[String] =
      if !conflict(sim.keys(a), sim.keys(b)) then None
      else
        sim.started.get(b).flatMap { started =>
          val delivery = sim.firstDelivery(b)
          if delivery.contains(a) then
            Option.when(delivery.indexOf(a) > delivery.indexOf(b))(
              s"$a and $b conflict, and share a batch out of order"
            )
          else
            sim.ended.get(a) match
              case Some(ended) =>
                Option.when(started < ended)(
                  s"$b ('${sim.keys(b).toBin}') started at $started before $a ('${sim.keys(a).toBin}') was acknowledged at $ended"
                )
              case None => Some(s"$b started while $a was never acknowledged")
        }

    val unfinished = (0 until n)
      .filter(!sim.ended.contains(_))
      .map(v => s"message $v never acknowledged")
    val order = for
      a <- (0 until n).toList
      b <- (a + 1 until n).toList
      problem <- orderProblem(a, b)
    yield problem
    sim.problems.toList ++ unfinished ++ order

  property(
    "random operations: conflicting messages run in arrival order, everything finishes, nothing deadlocks"
  ):
    forAll(Gen.listOfN(150, genOp), Gen.choose(1, 4)): (ops, size) =>
      val end = settle(ops.foldLeft(fresh)(perform), size)
      val found = violations(end)
      Prop(found.isEmpty) :| found.mkString("; ")

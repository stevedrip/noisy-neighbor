package noisyneighbor

import org.scalacheck.Gen
import scodec.bits.BitVector

import Trie.given

object Fixtures:
  def key(s: String): Key = Key.parse(s).fold(sys.error, identity)

  def msg(seq: Long, k: String, value: Int = 1): Message =
    Message(MessageId(s"m$seq"), seq, key(k), value)

  /** Short keys, so random messages conflict often. */
  val genKey: Gen[Key] =
    for
      length <- Gen.choose(0, 4)
      bits <- Gen.listOfN(length, Gen.oneOf(true, false))
    yield Key(BitVector.bits(bits))

  /** Few distinct keys and values, so two random tries overlap often. */
  val genEntries: Gen[List[(Key, Long)]] = Gen.listOf(Gen.zip(genKey, Gen.choose(0L, 3L)))

  def trieOf(entries: List[(Key, Long)]): Trie[Long] =
    entries.foldLeft(Trie.empty[Long])((t, e) => t.insert(e._1.bits, e._2))

  val genTrie: Gen[Trie[Long]] = genEntries.map(trieOf)

  final case class Scenario(
      inputs: Vector[Input],
      failures: Set[(MessageId, Int)],
      workers: Int,
      config: Config
  )

  /** Random streams with nondecreasing arrival times, durations, injected failures, and resource limits. */
  val genScenario: Gen[Scenario] =
    for
      n <- Gen.choose(1, 25)
      keys <- Gen.listOfN(n, genKey)
      values <- Gen.listOfN(n, Gen.choose(0, 5))
      gaps <- Gen.listOfN(n, Gen.choose(0, 3))
      maxAttempts <- Gen.choose(1, 3)
      failing <- Gen.listOfN(n * maxAttempts, Gen.frequency(1 -> true, 5 -> false))
      workers <- Gen.choose(1, 4)
      capacity <- Gen.choose(1, n + 2)
    yield
      val arrivals = gaps.scanLeft(0L)(_ + _).tail
      val inputs = keys.indices.map { i =>
        Input(Message(MessageId(s"m$i"), i.toLong, keys(i), values(i)), arrivals(i))
      }.toVector
      val failures = failing.zipWithIndex.collect { case (true, i) => (MessageId(s"m${i / maxAttempts}"), i % maxAttempts + 1) }.toSet
      Scenario(inputs, failures, workers, Config(capacity, maxAttempts))

package noisyneighbor

import org.scalacheck.Prop

/** Properties of a [[Trace]], shared by the pure simulator and the cats-effect runner. */
object Checks:
  def check(violations: List[String]): Prop =
    Prop(violations.isEmpty) :| violations.mkString("; ")

  /** For every conflicting pair, the later message starts only after the earlier one has finished for good. */
  def orderingViolations(inputs: Vector[Input], trace: Trace): List[String] =
    val ms = inputs.map(_.message)
    for
      (a, i) <- ms.zipWithIndex.toList
      b <- ms.drop(i + 1)
      if a.key.conflictsWith(b.key)
      end <- trace.lastEnd(a.id)
      start <- trace.firstStart(b.id)
      if start < end
    yield s"${b.id} (seq ${b.seq}, '${b.key.show}') started at $start before ${a.id} ('${a.key.show}') ended at $end"

  /** Every message either succeeded on its last run, or failed `maxAttempts` times and was dead-lettered. */
  def completionViolations(inputs: Vector[Input], maxAttempts: Int, trace: Trace): List[String] =
    val unfinished = Option.when(trace.deadlocked)("deadlocked").toList
    unfinished ++ inputs.map(_.message).flatMap: m =>
      val runs = trace.runsOf(m.id)
      val finishedOk = runs.lastOption.exists(_.succeeded) && !trace.deadLettered(m.id)
      val finishedDead = runs.size == maxAttempts && runs.forall(!_.succeeded) && trace.deadLettered(m.id)
      Option.when(!(finishedOk || finishedDead))(s"${m.id} did not finish cleanly: $runs")

  /** With enough workers and no pausing, a message starts as soon as it has arrived and every earlier conflicting
    * message has finished. The oracle is a plain O(n^2) scan, independent of the trie.
    */
  def earliestStartViolations(inputs: Vector[Input], trace: Trace): List[String] =
    val ms = inputs.map(_.message)
    for
      (input, i) <- inputs.zipWithIndex.toList
      earlierEnds = ms.take(i).filter(_.key.conflictsWith(input.message.key)).flatMap(a => trace.lastEnd(a.id))
      expected = (earlierEnds :+ input.arrival).max
      actual = trace.firstStart(input.message.id)
      if !actual.contains(expected)
    yield s"${input.message.id}: expected start $expected, got $actual"

# Prototype plan (working notes, not committed)

## Decided

- **Architecture.** One scheduler holds the trie and barrier logic. Stateless, idempotent workers run the handler. See README.
- **Backpressure.** The scheduler has a bounded buffer (waiting + in-flight). Reading pauses when it is full and resumes as messages finish. Pausing is per shard where the reader allows it.
- **Quarantine is not a separate feature.** It falls out of splitting shards: once a noisy subtree has its own shard, pausing that shard's reads stalls nothing else. One bounded buffer, no parking, no per-subtree buffers.
- **Dead-lettered counts as finished** (from README). Revisit: one poison message releases its whole subtree.
- **Ordering unit is the message, never the batch.**

- **Language.** Scala on the Typelevel stack (cats-effect).

### Design shape

- Pure core: `step(state, event) => (state, commands)`. Events: `Arrived`, `Completed`, `Failed`. Commands: `Dispatch`, `DeadLetter`, `PauseReads`, `ResumeReads`. No effects, so property tests need no IO or clock.
- Thin cats-effect shell around the core: reader fiber, dispatch, completion channel, bounded buffer.
- Admission: keep every unfinished message (waiting and in-flight) in the trie with its arrival sequence number. A message is admissible iff no other earlier unfinished message is on its path or in its subtree. Waiting messages block later conflicting ones, which gives the no-overtaking rule for free.

## Open decisions

1. Failure policy: retry count, backoff. Default: 3 retries.
2. Message id carried for dedupe on redispatch (at-least-once).
3. Worker contract: one message or a batch per dispatch, ack shape, redispatch timeout. Default: the scheduler batches only already-admitted messages, and acks carry message ids.
4. Fairness among admitted messages when workers are scarce. Default: arrival order.

## Status

Prototype 1 is built: `Trie`, `Scheduler.step`, and a virtual-clock `Simulator` (test sources).

Lattices (typelevel `algebra`):
- The algebra lives on `Trie[A]`, not on `Key`. A trie is a finite set of entries `(key, value)`, and `GenBool[Trie[A]]` is union (`or`), intersection (`and`) and difference (`without`), with the empty trie as `zero`. It has one bound: there is no set of all entries, so no `one` (as with `Set[A]` over an unbounded `A`). Adding or removing a message is union or difference with a singleton; tests check `insert`/`remove` against those, and `or`/`and`/`without` against plain set operations on `entries`. Laws: `LogicLaws.generalizedBool` from `algebra-laws` + `discipline-munit`.
- Representation: `Trie` is `Empty | Node(own, zero, one, min)`. `Empty` is the `zero`, and is its own children, so the three operations are direct pointwise recursions whose base cases are the laws of `zero` (`x|0=x`, `x&0=0`, `x-0=x`, `0-x=0`). The single smart constructor `Trie.node` collapses an all-empty node to `Empty`, so there is one representation and no per-operation pruning. An earlier version used `Option[Trie]` children, which encoded the zero two ways (`None` and `Some(empty)`) and needed a `pruned` step after each recursion. There is no `isEmpty`: a trie is empty exactly when its summary `min` is the summary lattice's top (`L.isOne(summary)`, checked once in `node`), and callers ask `GenBool.isZero`. `Empty` is one shared instance, so `x | 0`, `0 | x` and `x - 0` return `x` itself. The checks are ours, not the library's: `algebra` never calls `isZero`/`isOne` for you (they are plain `eqv(a, zero)` queries, and `or`/`and`/`without` are abstract). The both-zero guard is needed for termination (`Empty` is its own children); the one-sided shortcuts are an optimization that makes `insert` (union with a singleton) O(depth) by sharing untouched subtrees, and `TrieSuite` pins them with reference equality.
- `Key` is an opaque type over `scodec.bits.BitVector` (no wrapper object at runtime) with no instances: only `isPrefixOf`, `conflictsWith` (equal, or one a prefix of the other), `show` and `parse`. We tried giving it structure and dropped it. The longest common prefix is a meet and the root is its bottom, but nothing needs it, and `BoundedMeetSemilattice` cannot apply: its `one` is the identity of meet (a key extending every key), while the root is the opposite, the absorbing element. An `Option[Key]` lattice would adjoin an artificial second bound. If the longest common prefix is needed later (e.g. a shard's covering prefix for Kinesis), add it as a plain function.
- Each trie node summarizes its subtree with `BoundedMeetSemilattice[Option[A]]` (min, `None` as top), checked with `LatticeLaws.boundedMeetSemilattice`. `minConflicting` is the meet over the entries on a prefix of the query key and at or below it; it is a homomorphism from union to that meet (tested).
- Removal needs the per-node sets because a semilattice has no inverse. A node with many same-key waiting messages stays cheap only because the values form a chain.

The cats-effect shell is built too: `Runner.run(settings, source, handler, deadLetter)`. One loop fiber owns the scheduler state; a reader fiber pulls from an fs2 `Stream[IO, Message]` and hands messages over one at a time (checking the pause signal before each pull); `workers` fibers run the handler and ack. Tests run it on `TestControl`'s virtual clock against the same oracle (`Checks`) as the simulator. 37 tests pass. Not built: Kinesis, remote workers.

Shell choices, to revisit:
- Handler errors and handler timeouts both count as a failed attempt. A `deadLetter` or source failure fails the whole run.
- Retry backoff sleeps inside the worker, so it occupies a worker slot.
- The reader waits for the loop to process each arrival before checking the pause flag, so the buffer bound is strict. This costs one handoff per message.
- Source chunking (batch size) is invisible to scheduling; a test checks it.
- `IO` is used directly, not `F[_]`.

Choices made in the core, to revisit:
- `Config.maxAttempts` is the total number of runs, not retries after the first.
- Retry backoff and ack timeouts are shell concerns, not in the core.
- `Arrived` events must come in increasing `seq`. Arrivals at or below the highest seq seen are ignored, which makes replay after restart harmless. Cross-shard arrival is deferred.
- Each `step` rescans all waiting messages for admissibility: O(waiting x key depth). Fine for a prototype; revisit if the waiting set gets large.
- `Dispatch` carries every message admitted by one event; they are pairwise non-conflicting.

## Prototype 1: scheduler library

Pure library, driven by an in-memory simulated stream, with a virtual clock (a message with value n takes n simulated seconds). No Kinesis, no real workers.

- Trie of unfinished messages keyed by bitstring.
- Admit a message once no earlier conflicting message is unfinished. A waiting barrier holds back later conflicting messages (no overtaking).
- Bounded buffer: stop reading when full.
- Dispatch, completion, retry, dead-letter (counts as finished).
- Per-shard low-watermark for checkpointing, even though the prototype has a single stream.

### Tests

Property test with random keys, durations and arrival orders. From the output trace, assert:
- Every conflicting pair ran in arrival order and did not overlap.
- Non-conflicting pairs overlapped when workers were available.
- No message started while an earlier conflicting message was unfinished.
- Barrier starvation case: `c` behind a waiting `a` (README's worked example).
- Bounded buffer never exceeded, and the scheduler never deadlocks on a single stream.

## Deferred

- **Scope the trie in an `Order` module (decided, not yet done).** Make the trie type a member of `class TrieOf[A](using Order[A])`, so path-dependent types (`SeqTrie.Trie`, with `object SeqTrie extends TrieOf[Long]`) make mixing tries built from different orders a compile error. Today a single `using Order[A]` is threaded through every operation and also embedded in each node's `SortedSet`, and nothing enforces that the instance passed at a call matches the one a trie was built with; we rely on `Order[A]` being canonical. Cost: a module value per element type, imports at use sites, and `Scheduler.State` referencing the module. Also consider renaming the `zero`/`one` child fields (they collide with the algebra names).
- **Multiple shards.** Cross-shard order via fence records written to every shard under a barrier's subtree. Never read past an unexecuted barrier's fence on any shard. That keeps the buffer closed under predecessors, which avoids a buffer-space deadlock (the order itself is acyclic; the cycle comes from the shared buffer).
- Parent-before-child ordering for split shards. Whether the scheduler reads a child before its parent is drained, using the trie's conflict rules instead of strict lineage order.
- Resharding workflow: two sequential splits per tenant, poll until `ACTIVE`, retry on `ResourceInUseException`. Verify limits against current docs.
- Key encoding in Kinesis (`ExplicitHashKey`, left-aligned into the 128-bit space).
- Scheduler failover and leader election. Replay from checkpoint on takeover.
- Automatic splitting.

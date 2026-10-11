# Prototype plan (working notes, not committed)

## Built

- `Trie` (`Trie.scala`): a finite map from bit-string keys to values, with relative edge labels, the walks (`child`, `owner`, `along`, `modify`, `upsert`, `prune`, `nodes`), and a `GenBool` and `Functor` instance. `FifoTrie` uses none of its algebra.
- `Fifo` (`Fifo.scala`), the queue as a trait, and `MemoryFifo`, the one in memory.
- `FifoTrie[A, F <: Fifo]` (`FifoTrie.scala`): a trie of queues, served by fibers.
- `example/`: an interactive example on `FifoTrie`, with `example/mixed.csv` as the reference input.

## Decided

- **Model.** A trie of FIFO queues. A queue owns a key prefix. Producers add messages at the root only. A queue forwards a message to the child whose prefix its key starts with, and otherwise the message is the queue's own. Two messages conflict when one key is a prefix of the other, or they are equal, and conflicting messages are handled in arrival order. Non-conflicting ones may be handled at the same time. behavior.md draws the partial order of three scenarios, and `FifoTrieSpec` and `FifoTrieSuite` check it.
- **An own message waits while any queue below holds anything,** conflicting or not, because a queue cannot see inside a child (behavior.md's Mixed scenario needs this: `"11"#6` waits for `"10"#5`). It waits for the whole subtree. A draining child counts whatever is in it.
- **A batch is a run of own messages with no forward in it,** because a forward starts running in the child at once and would overtake. A message to forward ends the batch.
- **Split.** The child is created at once, empty, and adopts the children that extend its prefix. It receives messages only as its parent forwards them. Splitting what exists changes nothing, and stops any draining queue on the way down, the target included, from draining.
- **Merge is draining.** A draining child is not forwarded to. The parent keeps the range's messages as its own, each waiting for the draining child to be empty, and the child is removed when it, and everything below it, is empty. The root can be drained: `enqueue` and `submit` then refuse, and the root stays when it is empty.
- **`Fifo` has no offsets.** Each end is one call that answers at once and hands what crossed to the caller's own work. `take(count, f)` is asked for a count and gives a chunk, and `offer(chunk, f)` is given a chunk and gives a count, so the two cross. The consumer must consume all it is given, there is no partial consumption, and the queue removes it once `f` has finished. If `f` fails or is cancelled the messages stay and are given again. What a caller commits to wherever it reads from or writes to, usually an offset, it does after its call has finished.
- **Either end closes the queue.** `taking` and `offering` are `Ref[IO, Boolean]`s on the trait, true to begin with, whatever the implementation. A closed end refuses: offers get 0 and takes get an empty chunk.
- **Draining is `offering` false.** A queue whose back is closed takes nothing new and is taken from until it is empty. The trie does not forward to it, and removes it when it, and everything below it, has emptied, closing both ends as it goes. A node of the trie is the queue itself, `Trie[F]`, with no state of the trie's own.
- **A queue does not wait and does not look after its callers.** Waiting for messages or for room, and when an end may close, are the trie's. `MemoryFifo` is a `Ref` holding a collection, so fibers on any number of threads can share it.
- **Concurrency, not parallelism, with `IO` fibers.** `IO` is used directly, not `F[_]`. Each queue has a fiber serving it (`FifoTrie.run`). A fiber waits by suspending until something changes, and every change wakes the ones that are waiting. A mutex guards the shape of the trie only when it changes (split, drain, prune).
- **A queue says whether it is empty.** `Fifo.isEmpty` is true when it holds nothing, a message a take is working on included, so a message that has been given out is never in no queue at all. The trie's gates (an own message waiting for the children, a draining queue being removed) read it.
- **Forwarding is in the work given to `take`.** A message for a child waits for room in it, and a batch of own messages waits until nothing below holds anything. The queue still holds the chunk while that happens.
- **Backpressure.** A queue has a capacity. `enqueue` refuses when the root is full, and `submit` waits for room.
- **No failure handling.** Retries, attempt limits and dead-lettering are the consumer's decision. Work that fails leaves the messages, so a cancel in the middle of a chunk can forward a message twice, which is at-least-once.
- **Ordering unit is the message, never the batch.**
- **Removed.** The conflict-index design, then the pure, offset-based trie (`PureFifo`, the old `FifoTrie`), the actor `Scheduler`, and the fs2 `BatchQueue`. They are in git history.

### Design shape

- Pure core, effects outside: `step(state, event) => (state, commands)`. Events: `Arrived`, `Completed`, plus split and merge. Commands: `Dispatch`, `PauseReads`, `ResumeReads`.
- `FifoTree[A]` is `Trie[Fifo[FifoTree.Item[A]]]`, a type alias with the operations as extension methods. A node is a `Fifo`: a queue that can be read without removing, acknowledged by offset, and drained (`draining`).
- Keep the router (which queue a message belongs to) separate from the queue itself.
- Thin cats-effect shell around the core: reader fiber, dispatch to workers, ack channel, pause signal.

## Open decisions

1. Command and event shapes for split and merge (who issues them, how they are sequenced against arrivals).
2. Exactly when a queue's own message counts as started versus read, and what "in flight" means for acks.
3. Whether a queue's own message waits only on conflicting children (current README rule) or on all children. Merge waits on the whole draining child regardless.
4. Redispatch: when a consumer asks again is its own business (no timeout in the tree). Fencing a replaced consumer is outside it.
5. Fairness among queues when workers are scarce. Default: arrival order.

## Status

`FifoTree` is built (`Fifo.scala`, `FifoTree.scala`, `Trie.scala`, and their suites) on top of `Trie`. `FifoTree[A]` is a type alias for `Trie[Fifo[FifoTree.Item[A]]]`: a node exists for every queue that exists (a queue that does not exist is a key with no node, not an empty queue), and a node is a `Fifo` (add, which a draining one refuses; read from the front without removing; acknowledge by offset; drain; resume). There is no separate node type. All the walks (`owner`, `along`, `modify`, `upsert` with adoption, `prune`) are `Trie`'s, with relative edge labels and `BitVector` keys. `FifoTree` uses none of `Trie`'s algebra. There is no per-queue state about what was handed out. The operations are extension methods: `enqueue` (root only), `split`, `drain`, `acknowledge`, `takeFrom`, `take`, `advance`. Choices in it, to revisit:
- A queue's own message waits only for children whose prefix is related to its key (a prefix of it, or extended by it). It waits for that child's whole subtree, since it cannot see inside. A draining child counts whatever is in it.
- A batch is a run of own messages with no forward between them, because a forward starts running in the child at once and would overtake. The first message that is a forward, is blocked, or would exceed `maxBatch` ends the batch, and stays at the front of the queue where it is looked at again next time.
- Offsets are absolute per queue (`Fifo.start` is the offset of the front). A re-ask is recomputed from the front, so what is out comes back because it is still startable. That holds because forwarding at a queue happens only on its own consumer's ask, and children only gain messages from that forwarding. The tree has no notion of failure, no attempt count and no retry.
- `drain(prefix)` (merge is draining a child) marks only that queue as draining, and the root can be drained: `enqueue` then fails and the root stays when empty. Its descendants keep receiving what it forwards, so they drain with it without a flag of their own.
- Operations are total: there are no error results. `split` makes sure an accepting queue exists for a prefix: it creates one (adopting the children that extend the prefix) or leaves the existing one, so it is idempotent, and it stops any draining queue on the way down from the root, the target included, from draining, since messages for the prefix reach its queue only through them. `drain`, `acknowledge` and `takeFrom` on a queue that is not there change nothing (acknowledging a removed queue is safe because it was removed only when empty), and asking for zero messages hands out none. The one refusal a producer must hear about is `enqueue` on a draining root, which returns no tree (`Option`).
- Tree walks recurse once per level, so depth is the number of nested splits.

Tests: unit tests for the README scenarios, and a property test that does random enqueues, splits, drains, asks (of one queue or any), acknowledgments (partial, full, stale and beyond what was handed out), then checks from the recorded deliveries and acknowledgments alone that every conflicting pair ran in order (the earlier message ended before the later one started, or they share a batch in order), that every message finished, and that nothing is left waiting when nothing is running. Mutation checks done, each makes a test fail: own messages never blocked, a forward in the middle of a batch, a running queue still taking messages, the draining relation, pruning a draining queue early, a split that does not adopt.

Parked, to be rebuilt on `FifoTree`: `Scheduler`, `Runner`, `Simulator`, `Harness`, `Checks` and their suites (`SchedulerSuite`, `SimulationSuite`, `RunnerSuite`). They are in git history, and were removed from the working tree so the project compiles. `Runner`'s structure still applies: one loop fiber owning state, a reader with pause gating and a per-arrival handshake, worker fibers acking. `Key` and `Scheduling` (`Message`, `Event`, `Command`, to be revised) are kept.

Shell choices, to revisit:
- A handler error or timeout is the consumer's concern. A source failure fails the whole run.
- The reader waits for the loop to process each arrival before checking the pause flag, so the buffer bound is strict. This costs one handoff per message.
- Source chunking (batch size) is invisible to scheduling; a test checks it.
- `IO` is used directly, not `F[_]`.
- `Arrived` events must come in increasing `seq`. Arrivals at or below the highest seq seen are ignored, which makes replay after restart harmless. Acks for messages not in flight are ignored.

## Tests

- `FifoTrieSpec`: examples of forwarding, batches, work that fails, a child with no room, split, drain and removal, and the three behavior.md scenarios with a fiber per queue and exact times.
- `FifoTrieSuite`: random keys, trie shapes, batch sizes and durations. Every conflicting pair is handled in arrival order, every message is handled, and nothing deadlocks.
- `MemoryFifoSpec` and `MemoryFifoSuite`: the contract of `take` and `offer`, and a property against a plain model queue.

## Direction (not built)

The queues become durable streams, and producers write to the deepest queue that accepts, not only at the root. This supersedes "producers add messages at the root only", and the README's "totally ordered by their arrival on the root queue".

- **Routing.** A write goes to the deepest queue on its key's path that exists and accepts. A missing queue, a draining queue and the descendants of a draining queue are skipped, and the write lands in the nearest accepting ancestor.
- **Ordering without an atomic step.** An ancestor write does two single-queue steps in a safe order: close the descendants it conflicts with, then append. A direct write that reached a descendant before the close is earlier in arrival, and the ancestor's own message waits for it as before. A write after the close is refused and re-routes upward, landing behind the append. A failure between the steps only leaves a spurious drain. Arrival order for conflicting messages is defined by when the close completes.
- **Split.** A new child stays closed until its parent has forwarded the backlog it holds for the range, and then opens, so a direct write cannot overtake that backlog.
- **The deep symmetry.** Each queue has two ends with one actor each. Blocking the front (an own message waiting for the children) and closing the back (a conflicting append closing the descendants) are one rule at either end. Forwarding down is promoting up. A closed back reopens once the message that closed it is done.
- **Kinesis (hypothesis, from memory and not checked).** Active shards partition the keyspace and never nest, so not every node of the trie has an active shard. A node whose descendants are active has none, and its own messages need it to: writing one closes the descendants, which is a shard merge into the node, and reopening is a split again once the message is done. A queue is then a lineage of segments, sealed ones read first in order, then the active one. To verify: how often Kinesis allows resharding, and how long a merge or split takes to become active.

## Open decisions

1. How a durable `Fifo` implements `take`, `offer` and `isEmpty`: what the checkpoint is, and what "removes once `f` has finished" means for a stream.
2. How the routing and the close-then-append above are driven: the commands for split and merge, and how they are sequenced against arrivals.
3. Whether `FifoTrie` should be able to peek at the front of a queue (it needs no `peek` today, because the work given to `take` decides as it goes).
4. Fairness among queues when workers are scarce. Default: arrival order.

## Deferred

- A Kinesis or other durable backend for the queues.
- Key encoding if a hash key is ever needed (`ExplicitHashKey`, left-aligned in the 128-bit space). Only relevant if shards are revisited.
- Failover and leader election. Replay from a checkpoint on takeover.
- Automatic splitting and merging.
- Remote workers.

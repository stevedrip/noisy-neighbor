# Prototype plan (working notes, not committed)

## Decided

- **Model.** A trie of FIFO queues. A queue owns a key prefix, and reading from it does not remove anything until the reader acknowledges: messages are added at the back, a reader reads from the front without removing them, and acknowledging removes them. Nothing else can look inside it (no search, index or count). See README ("How queues enforce it").
- **Producers enqueue only at the root queue.** One total arrival order. A queue forwards messages to the child whose prefix the key starts with, in order, when it reads them (acknowledging each in the parent).
- **One message at a time per queue.** A queue's own messages (key equals its prefix, or no child owns it) wait for earlier messages in the conflicting children and hold back the queue behind them. Parallelism comes from having several queues (and several workers).
- **Split.** The child is created at once, empty. It receives messages only as the parent reads and forwards them. The parent's existing backlog is not redistributed.
- **Merge = drain.** The child stops accepting new messages. The parent keeps the range's messages as its own, each waiting for the draining child to be empty (all of it; the parent cannot see inside). A draining queue's children drain with it. The child is removed when empty.
- **Kinesis is the source, not the queue.** Queues are in memory. Mapping queues to shards (or chains of shards) was explored and does not fit; reasons are in README ("Kinesis"). Checkpoint = oldest unfinished root message (`resumeFrom`).
- **Backpressure.** Pausing reads is the policy. Capacity (bound on unfinished messages) is configuration of the consumer, not a property of the trie. Pausing happens at the root, in the shell.
- **No failure handling.** Retries, attempt limits, poison messages and dead-lettering are the consumer's decision, not ours. A batch that was handed out and not acknowledged stays at the front of its queue, holding back what is behind it, and when the consumer asks again for a batch it gets it back. The consumer acknowledges once it is done with the messages, whatever it did with them.
- **Ordering unit is the message, never the batch.**
- **Pull model, one consumer per queue.** The consumer asks its queue for a batch of up to X messages and may get fewer, or none (`takeFrom(queue, X)`). Only a queue's own consumer moves its front: its ask forwards what is ahead of its first own message into the children, then hands out a batch of its own messages. A child sees a message only after its parent's consumer has asked, so every consumer keeps asking. A batch starts at the first message not acknowledged, so asking again gives back what is not acknowledged and may extend it; a consumer that asks again has stopped working on what it had, which is also when a message that a split gave to a child is forwarded. Consumers acknowledge by absolute offset, cumulatively and idempotently (`acknowledge(queue, through)`), as many messages as they like, and the tree trusts the offsets it is given. `take(X)` asks the queues in turn and returns the first batch; `advance(X)` asks each once, parents first. Replacing a consumer is done only once the old one is gone.
- **A batch comes from one queue.** The consumer asks for up to X messages and may get fewer. One batch at a time per queue. The handler applies a batch in arrival order, and a batch is acknowledged as a whole. Acks are by offset (`resumeFrom`).
- **Removed.** The conflict-index design (radix `Trie`, `AntichainTrie`, `GenBool`/gcd, `Bits`, typelevel `algebra`) is deleted. It tracked every unfinished message in a searchable set; the queue tree makes order structural, so none of it is needed. It is in the git history (the wip commits). Remove the `algebra`, `algebra-laws` and `discipline-munit` dependencies from `build.sbt` if nothing uses them after the rewrite.

### Design shape

- Pure core, effects outside: `step(state, event) => (state, commands)`. Events: `Arrived`, `Completed`, plus split and merge. Commands: `Dispatch`, `PauseReads`, `ResumeReads`.
- `QueueTrie[A]`: `queue` (opaque FIFO), `children: TreeMap[BitVector, QueueTrie[A]]`, `drain: Boolean`.
- Keep the router (which queue a message belongs to) separate from the queue itself.
- Thin cats-effect shell around the core: reader fiber, dispatch to workers, ack channel, pause signal.

## Open decisions

1. Command and event shapes for split and merge (who issues them, how they are sequenced against arrivals).
2. Exactly when a queue's own message counts as started versus read, and what "in flight" means for acks.
3. Whether a queue's own message waits only on conflicting children (current README rule) or on all children. Merge waits on the whole draining child regardless.
4. Redispatch: when a consumer asks again is its own business (no timeout in the tree). Fencing a replaced consumer is outside it.
5. Fairness among queues when workers are scarce. Default: arrival order.

## Status

`QueueTrie` is built (`Fifo.scala`, `QueueTrie.scala`, `FifoSuite.scala`, `QueueTrieSuite.scala`) and the project compiles with 40 tests passing. A node is a `Fifo` (add, read from the front without removing, acknowledge by offset), its children, and a `draining` flag. There is no per-queue state about what was handed out. The tree is a pure value: `enqueue` (root only), `split`, `drain`, `acknowledge`, `takeFrom`, `take`, `advance`. Choices in it, to revisit:
- A queue's own message waits only for children whose prefix is related to its key (a prefix of it, or extended by it). It waits for that child's whole subtree, since it cannot see inside. A draining child counts whatever is in it.
- A batch is a run of own messages with no forward between them, because a forward starts running in the child at once and would overtake. The first message that is a forward, is blocked, or would exceed `maxBatch` ends the batch, and stays at the front of the queue where it is looked at again next time.
- Offsets are absolute per queue (`Fifo.start` is the offset of the front). A re-ask is recomputed from the front, so what is out comes back because it is still startable. That holds because forwarding at a queue happens only on its own consumer's ask, and children only gain messages from that forwarding. The tree has no notion of failure, no attempt count and no retry.
- `drain(prefix)` (merge is draining a child) marks only that queue as draining, and the root can be drained: `enqueue` then fails and the root stays when empty. Its descendants keep receiving what it forwards, so they drain with it without a flag of their own.
- Operations are total: there are no error results. `split` makes sure an accepting queue exists for a prefix: it creates one (adopting the children that extend the prefix) or leaves the existing one, so it is idempotent, and it stops any draining queue on the way down from the root, the target included, from draining, since messages for the prefix reach its queue only through them. `drain`, `acknowledge` and `takeFrom` on a queue that is not there change nothing (acknowledging a removed queue is safe because it was removed only when empty), and asking for zero messages hands out none. The one refusal a producer must hear about is `enqueue` on a draining root, which returns no tree (`Option`).
- Tree walks recurse once per level, so depth is the number of nested splits.

Tests: unit tests for the README scenarios, and a property test that does random enqueues, splits, drains, asks (of one queue or any), acknowledgments (partial, full, stale and beyond what was handed out), then checks from the recorded deliveries and acknowledgments alone that every conflicting pair ran in order (the earlier message ended before the later one started, or they share a batch in order), that every message finished, and that nothing is left waiting when nothing is running. Mutation checks done, each makes a test fail: own messages never blocked, a forward in the middle of a batch, a running queue still taking messages, the draining relation, pruning a draining queue early, a split that does not adopt.

Parked, to be rebuilt on `QueueTrie`: `Scheduler`, `Runner`, `Simulator`, `Harness`, `Checks` and their suites (`SchedulerSuite`, `SimulationSuite`, `RunnerSuite`). They are in git history, and were removed from the working tree so the project compiles. `Runner`'s structure still applies: one loop fiber owning state, a reader with pause gating and a per-arrival handshake, worker fibers acking. `Key` and `Scheduling` (`Message`, `Event`, `Command`, to be revised) are kept.

Shell choices, to revisit:
- A handler error or timeout is the consumer's concern. A source failure fails the whole run.
- The reader waits for the loop to process each arrival before checking the pause flag, so the buffer bound is strict. This costs one handoff per message.
- Source chunking (batch size) is invisible to scheduling; a test checks it.
- `IO` is used directly, not `F[_]`.
- `Arrived` events must come in increasing `seq`. Arrivals at or below the highest seq seen are ignored, which makes replay after restart harmless. Acks for messages not in flight are ignored.

## Tests

Property tests with random keys, durations and arrival orders, with an independent oracle over the output trace:
- Every conflicting pair ran in arrival order and did not overlap.
- Non-conflicting pairs overlapped when workers were available.
- No message started while an earlier conflicting message was unfinished.
- A message waiting for earlier conflicting ones holds back later conflicting ones, even ones that conflict with nothing in flight (README's worked example).
- Splits and merges at random points do not change any of the above. After a merge, the child's messages all finish before the parent's own messages for that range start.
- Bounded buffer never exceeded; no deadlock.
- Mutation checks: break the code, confirm a failure, restore.

## Deferred

- Kinesis source for the root queue (reading, checkpointing by `resumeFrom`, backoff on throttling).
- Key encoding if a hash key is ever needed (`ExplicitHashKey`, left-aligned in the 128-bit space). Only relevant if shards are revisited.
- Scheduler failover and leader election. Replay from checkpoint on takeover.
- Automatic splitting and merging.
- Remote workers.

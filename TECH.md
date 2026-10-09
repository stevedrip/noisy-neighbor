# Technology fit for the queue trie

How well each candidate maps onto the model in README.md. Nothing here has been
built. The claims about each product come from memory, and the documentation
could not be fetched while this was written, so treat every specific limit as
something to verify before relying on it.

## What the model asks of a technology

1. **Queues are created and removed at runtime.** A split creates an empty child
   queue at once. A merge drains a child, then removes it. There are few queues
   (splits are manual, and only for noisy tenants), so tens or hundreds, not
   millions. A queue can be heavy as long as it is not slow to create.
2. **Strict FIFO per queue**, with one consumer at a time, and a queue has at
   most one batch in flight.
3. **Forwarding.** A queue moves a message to a child queue, in order, as it
   dequeues it. A crash must not duplicate it in a way that reorders. Idempotent
   workers make a duplicate harmless, but a reorder is not.
4. **Batches.** Enqueue in batches (idempotent on partial failure) and dequeue in
   batches, where a worker processes the whole batch at once. The two sides need
   not align.
5. **Offset acks.** Finishing a batch acks by offset or sequence. The checkpoint
   is the oldest unfinished message of the root queue.
6. **A single ordered root.** All messages are enqueued at the root queue, which
   gives the total arrival order the semantics depend on.

Queues are in memory in the proof of concept. This file is about what could
back them, or feed the root queue, later.

## Summary

| Technology | Queue per node | Atomic forward | Batches | Offset ack | Fit |
|---|---|---|---|---|---|
| Kafka (topic per queue) | Heavy but workable | Yes (transactions) | Native | Yes | Good |
| NATS JetStream | Consumer or stream | Idempotent (dedup id) | Fetch / async publish | `AckAll` | Good |
| Apache Pulsar | Topic, cheap | Yes (transactions) | Native | Cumulative ack | Good |
| Kinesis, stream per queue | Heavy, slow to create | No (idempotent only) | Native | Checkpoint | Workable, unwieldy |
| RabbitMQ | Queue or stream | Idempotent (confirms, dedup) | Prefetch / confirms | Multiple-ack | Workable |
| SQS FIFO | Queue, via API | Idempotent (dedup id) | Capped at 10 | None | Poor |
| Kinesis, shard per queue | No | No | Native | Checkpoint | Does not fit |
| Temporal | Workflow | In workflow | Via signals | n/a | Different shape |

## Kafka

- **Queue.** A topic with one partition. A partition is a single ordered log, so
  FIFO and offsets come for free.
- **Split.** Create the topic. Creating topics is slow and heavy compared with
  other systems here, and a topic cannot lose partitions or be merged, but the
  queue count is small, so this is acceptable.
- **Merge.** Stop producing to the draining topic, let it empty, delete it.
- **Forward.** A transactional consume, produce and offset commit makes
  forwarding atomic. This is the strongest answer to requirement 3 of any
  candidate.
- **Batches and acks.** Native on both sides, acked by offset.
- **Consumer assignment.** Consumer groups rebalance on their own. A queue wants
  exactly one consumer, so assign partitions manually (or use static
  assignment).
- **Cost.** Operating Kafka, if it is not already run.

## NATS JetStream

- **Queue.** Either a stream per node, or one stream holding the root queue with
  a consumer per node filtered to a subject prefix. Keys would be encoded in
  subjects (a token per bit, or per tenant level), and a prefix filter matches
  the conflict relation directly. In that form the single stream is the root
  queue with its total order, and a consumer is a view of it rather than an
  opaque queue, so parent and child consumers need coordinating.
- **Split.** Create the consumer, starting at the stream sequence where the
  parent currently is, so that it owns nothing before then.
- **Merge.** Delete the consumer once the child has drained.
- **Forward.** In the stream-per-node form, publish to the child with a message
  id header, which the server deduplicates inside a window. In the consumer form
  there is nothing to forward.
- **Batches and acks.** Pull consumers fetch batches. Publishing is not a batch
  but can be pipelined, and I believe newer servers add atomic batch publish.
  `AckAll` acks everything up to a sequence, which is the offset semantic. Set
  max in-flight to one batch for the one-at-a-time rule.
- **To verify.** Subject length and token limits at 64-bit keys, how many filter
  subjects a consumer may have, and the dedup window.

## Apache Pulsar

- **Queue.** A topic. Topics are cheap to create and delete, so this is the
  smoothest fit for requirement 1. An Exclusive or Failover subscription gives
  strict order with one active consumer.
- **Forward.** Transactions ack from the parent and publish to the child
  atomically.
- **Batches and acks.** Producers batch, and the consumer sees individual
  messages and can batch-receive them. Cumulative ack is the offset semantic and
  is only available on Exclusive and Failover subscriptions, which are the ones
  that give strict order anyway.
- **Cost.** It is a heavier system to run than the others, with BookKeeper behind
  it.

## Kinesis

Two different uses, and they behave differently.

**Shard per queue (does not fit).** The README's Kinesis section lists why: a
shard is one contiguous hash interval, valid shapes depend on split order,
order exists only within a shard, a prefix key does not hash to one shard,
records are not moved between shards, and a merge starts a third shard that
waits for both. A chain of shards was considered and has the same problems.

**Stream per queue (workable).** A shard here plays the role a partition plays
in Kafka. It is an ordered log, batch-native, acked by checkpoint, and neither
can stand in for an arbitrary prefix region. What works is creating a new
stream per queue, with one shard, the counterpart of a topic per queue. It is
unwieldy:
- Each stream costs per shard-hour and counts against an account limit (I recall
  500 per region by default).
- Creating one is an asynchronous call, and the stream is not usable until it is
  ACTIVE.
- There are no transactions, so a forward is a write to the child stream and then
  a checkpoint on the parent, and a crash between them replays the forward.
  Writes to the child need a stable position (`SequenceNumberForOrdering`) and
  idempotent workers for the duplicates, and a replayed forward must not land
  behind later ones.
- Per-shard read limits (I recall about five `GetRecords` calls a second) add
  latency to a queue that is one shard.

**As the source of the root queue (good).** One stream, or one ordered shard of
it, feeds the root queue. This is already what the plan assumes. Batches and
checkpoints by `resumeFrom` fit naturally, and a queue tree in memory behind it
needs none of the properties above.

## RabbitMQ

- **Queue.** A classic or quorum queue with single active consumer, or a stream.
  Queues are cheap and declared at runtime.
- **Forward.** Publisher confirms, plus stream deduplication or idempotent
  workers. Not atomic across the ack and the publish.
- **Batches and acks.** Prefetch gives batch dequeue, and acking with the
  multiple flag acks everything up to a delivery tag, which is an offset ack.
- **Merge.** Drain, then delete the queue.
- Nothing is wrong with it for this, and nothing makes it better than the three
  above.

## SQS FIFO

- **Queue.** A FIFO queue per node, created through the API, with a single
  message group so it is strictly ordered.
- **Forward.** Send to the child with a deduplication id equal to the message
  id. A replay inside the dedup window is dropped.
- **Batches.** Send, receive and delete are each capped at ten messages.
- **Acks.** There are no offsets: each message is deleted, in batches of ten.
- **Verdict.** It would work for small volumes. The caps and the lack of offsets
  are against the requirements you stated.

## Temporal

A different shape. A workflow per queue node holds the queue state durably.
Signals to a workflow are ordered, and a split starts a child workflow. The
workers run as activities, which are stateless and should be idempotent as you
require. It would replace the Runner more than it would back the queues, and
the per-workflow throughput and history size are the limits to check. Batching
sits at the signal edges only.

## Reading the table

- If an atomic forward matters, choose Kafka or Pulsar.
- If the prefix relation should be expressed by the broker, rather than by
  per-queue structures, look at JetStream.
- If the stack is already Kinesis, use it for the root queue and keep the queues
  in memory. A stream per queue is possible if the queues must be durable, with
  idempotent forwarding.
- The proof of concept does not depend on any of this. The `FifoTree` keeps the
  router (which queue a message goes to) separate from the queue, so a different
  backing queue can be swapped in later.

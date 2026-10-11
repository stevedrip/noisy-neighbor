This project intends to expand and contract Kinesis streams using a trie of queues to order work.

For example, given a schema of (key, value) where key is a bit string and value is an integer. Bit strings can be quite long, upwards of 64 bits. Conceptually a bit string may represent a composite key of nested tenants. Assume that processing a message with value n will take n seconds to process.

# Terms

- **Queue.** A first-in, first-out queue that owns a key prefix. Messages are *added* at the back. A reader *reads* the next messages from the front, in order, without removing them, and reading again gives the same messages until they are *acknowledged*, which removes them. Reading always starts at the front: a queue cannot be searched, indexed or counted. A queue's *own* messages are those whose key matches its prefix, or that no child queue owns.
- **Root queue.** The queue with prefix ''. Every message is enqueued here, and only here. It is fed by one ordered source; see "Kinesis".
- **Child queue.** A queue whose prefix extends its parent's, created by a split. Queues form a trie by prefix.
- **Shard.** Only ever means a Kinesis shard. Queues are not shards; see "Kinesis".

# Ordering semantics

Messages are totally ordered by their arrival on the root queue.

Two messages *conflict* if their keys are equal, or if one key is a prefix of
the other. For example, '' conflicts with everything, '0' conflicts with '0',
'00', and '01', and '00' does not conflict with '01' or '1'.

Conflicting messages are processed in arrival order: a message does not start
until every earlier conflicting message has finished, and it finishes before any
later conflicting message starts. Non-conflicting messages may be processed
concurrently, regardless of arrival order.

A message therefore waits only for earlier conflicting messages, and holds back
only later conflicting messages. Later messages must not overtake a waiting
message, even if they would not conflict with anything currently in flight.

A message is *finished* once the consumer has acknowledged it. A message that
has been handed out and not acknowledged is not finished, so later conflicting
messages keep waiting, and when the consumer asks again for a batch, it gets
the same one. There is no failure handling on our side: how many times to try,
and what to do about a message that keeps failing, is the consumer's decision.
The consumer acknowledges once it is done with the messages, whatever it did
with them.

Given, in arrival order: ('01',2), ('',1), ('10',3)
- ('',1) waits for ('01',2).
- ('10',3) waits for ('',1), even though ('01',2) and ('10',3) do not conflict with each other.

# How queues enforce it

Producers always add messages at the root queue. A queue reads messages from
its front, one at a time, and either forwards or processes each one:

- **Forward.** If the key starts with a child queue's prefix, the message is
  added to the back of that child queue, and acknowledged in this one. The child
  owns it from then on. A queue forwards in order and never reorders.
- **Process.** Otherwise the message is one of the queue's own. It starts only
  once every earlier message in the child queues has finished. A queue cannot
  see inside a child, so it waits for all of it, conflicting or not. It stays at
  the front, unacknowledged, until it finishes, so nothing behind it is read.
  That is how it holds back later conflicting messages.

A queue runs one of its own messages at a time. Concurrency comes from having
several queues. A trie that is not maximally concurrent can make a message wait
when waiting is not required for correctness (see behavior.md).

Queues are pull-based, and each queue has one consumer at a time. Only that
consumer moves the front of its queue. When it asks for a batch of up to some
size, the queue forwards what is ahead of its own messages into its children,
and hands out a batch of its own messages if one can start. A child sees a
message only once its parent's consumer has asked, so every queue's consumer has
to keep asking. A batch always starts at the first message not acknowledged, so
asking again gives back what has not been acknowledged, and may add messages that
have become startable. A consumer that asks again has stopped working on what it
was given.

# Batching

Messages may be produced and consumed in batches. Batch sizes may vary, and the
batches on the production side need not line up with the batches on the
consumption side. The ordering semantics above apply to individual messages,
never to batches: a batch is only a unit of transport or dispatch.

A batch handed to a worker comes from a single queue, so it is a run of that
queue's own messages. The consumer asks for up to some number of messages and
may be given fewer, or none. Because the messages of a batch are applied
together, the handler must apply them in arrival order (for instance, the later
row wins in a multi-row upsert). The consumer acknowledges by offset: everything up to an offset, in order, as many messages as it likes, and acknowledging what is already acknowledged does nothing.

# Scenarios

## Shared load

Start with the root queue ('').

Next, say we split off the child queues '0' and '1'. Whenever the root reads a message whose key starts with '0' or '1', it forwards it to the corresponding child.

A message with the exact bitstring '' is one of the root's own. It waits for
all earlier messages in the '0' and '1' queues to finish, and later messages
for those queues wait behind it in the root.

## Noisy neighbor

Start with the root queue ('').

Split off the child queue '01'. Whenever the root reads a message whose key starts with '01', it forwards it to the '01' queue.

A message with the exact bitstring '' or '0' is one of the root's own, and conflicts with the '01'
queue. It waits for all earlier '01' messages to finish, and later messages wait
behind it in the root. So do messages like those starting with '00' or '1',
which do not conflict with '01' but are also the root's own: the root cannot see
inside '01', so they wait for it too. A message forwarded to another child
queue is not affected.

# Dynamic configuration

New queues, like '01', should be declarable at runtime. Once a noisy neighbor is identified, we should be able to quarantine it. A quarantined key range is ordered like any other: it conflicts with the same messages it would have otherwise.

Eventually, it would be desirable to dynamically and automatically split and merge queues, but a manual configuration would be an excellent start.

## Splitting a queue

- The child queue is created immediately, and it is empty.
- Producers are unaffected: they still enqueue at the root.
- The child receives messages only when its parent reads and forwards them.
  Messages already waiting in the parent when the split happens stay there until
  their turn, then are forwarded like any other. The parent's backlog is not
  redistributed, so a split helps the messages behind that backlog, not the
  backlog itself.
- Splitting a prefix that already has a queue changes nothing, so splitting is
  idempotent. If that queue, or one above it on the way down from the root, is
  draining, it stops draining: messages for the prefix can only reach its queue
  through them.

## Merging a queue

Merging a child queue into its parent *drains* the child.

- The child stops accepting new messages. The parent stops forwarding to it, and
  keeps the messages for that range as its own.
- The parent does not take over the child's messages. They stay in the child,
  which keeps processing them as before, along with anything its own children
  hold. A draining queue's children drain with it.
- Each of the parent's own messages for the range waits for the draining child
  to be empty, and holds back later messages in the parent as usual. The parent
  cannot see inside the child, so it waits for all of it, not just the
  conflicting part. This is conservative, but it never reorders.
- When the child is empty it is removed, and the parent owns the range outright.
- Splitting the prefix of a draining queue cancels the merge. The queue accepts
  messages from its parent again, and the parent forwards its range to it.

The root can be drained too. It stops accepting messages from producers, and
everything already in the tree finishes. It has no parent, so it is not removed:
it stays, empty, when it is done. This is how a consumer winds down.

# Kinesis

The queues are in memory, in the consumer. Kinesis is the source that feeds the
root queue, and a checkpoint is the oldest message that has not finished.

We considered making each queue a Kinesis shard, or a chain of shards (when
`SplitShard` closes a shard, its successor continues the queue), so that
splitting a queue would be splitting a shard. It does not fit these semantics:

- **A shard is one contiguous hash interval.** A queue's own range is its prefix
  range minus its children's, which is one interval only if the children are
  carved from an end of it. Isolating `01` alone leaves `00` and `1` on either
  side, and one shard cannot hold both. Two live shards for one queue would lose
  its FIFO order.
- **Valid shapes depend on split order.** With the trie `''`, `01`, `1`, `11`,
  splitting at `.25` first leaves a right half (`01` and `1` together) that is
  not a prefix, so no queue owns it.
- **Order exists only within a shard.** Without a single root queue, "earlier"
  between messages in different shards has no answer, and the ordering
  semantics depend on one.
- **A prefix key does not hash to any one shard.** Messages like `''` or `'0'`
  would need a chosen shard.
- **Kinesis does not move records between shards**, so forwarding between queues has
  no counterpart, and a merge closes both shards and starts a third that must
  wait for both.

Shards map onto leaves only in a restricted form: a full binary trie where every
split makes two queues, which gives up single-child isolation, prefix keys, and
the global order across shards unless producers stamp a sequence. That is not
planned.

# Architecture

We support only one handler method, which must be able to scale horizontally and be dispatched concurrently. To allow this, deciding what may run is separated from running it.

- Queue tree. One logical component reads the root queue and owns the tree of queues. It answers consumers' requests for batches and their acknowledgments. It never runs the handler.
- Consumers. Each queue has exactly one consumer at a time. A consumer asks its queue for a batch, runs the handler on it, and acknowledges by offset. Consumers know nothing about ordering, and the handler is assumed to have idempotent semantics. The handler scales horizontally by having more queues, each with its own consumer, which is what splitting a queue does. Nothing stops two consumers asking the same queue, so replacing a consumer is done only once the old one is known to be gone.

# Technology

Kinesis is the source that feeds the root queue (see "Kinesis" above for why the queues are not shards). Background on how `SplitShard` behaves, for reference:

https://docs.aws.amazon.com/kinesis/latest/APIReference/API_SplitShard.html

## What a Kinesis split does to existing data

- SplitShard changes shard metadata, and consumers aren't involved. It takes effect whatever the backlog is.
- The parent shard closes. Its existing records stay in it and remain readable until retention expires. Nothing is moved or re-sharded.
- New writes go to the two child shards, according to their hash keys.
- A consumer must read the parent to its end before reading the children. Standard consumer libraries such as KCL enforce this, and the consumer here must preserve it too.

## Why splitting during a spike disappoints

The same holds for a split of a queue: the backlog already in the parent is not redistributed, so a split helps the messages behind that backlog, not the backlog itself. Splitting ahead of need is better.

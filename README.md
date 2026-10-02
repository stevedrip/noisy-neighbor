This project intends to expand and contract Kinesis streams using a trie to schedule work.

For example, given a schema of (key, value) where key is a bit string and value is an integer. Bit strings can be quite long, upwards of 64 bits. Conceptually a bit string may represent a composite key of nested tenants. Assume that processing a message with value n will take n seconds to process.

# Ordering semantics

Messages are totally ordered by their arrival on the root ('') topic.

Two messages *conflict* if their keys are equal, or if one key is a prefix of
the other. For example, '' conflicts with everything, '0' conflicts with '0',
'00', and '01', and '00' does not conflict with '01' or '1'.

Conflicting messages are processed in arrival order: a message does not start
until every earlier conflicting message has finished, and it finishes before any
later conflicting message starts. Non-conflicting messages may be processed
concurrently, regardless of arrival order.

A message therefore acts as a barrier for its whole subtree. It waits only for
earlier conflicting messages, and holds back only later conflicting messages.
Later messages must not overtake a waiting barrier, even if they would not
conflict with anything currently in flight.

A message is *finished* once its handler has returned successfully. A message
that fails and is retried is not finished, so later conflicting messages keep
waiting. (If a message is dead-lettered after exhausting retries, it counts as
finished.)

Given, in arrival order: ('01',2), ('',1), ('10',3)
- ('',1) waits for ('01',2).
- ('10',3) waits for ('',1), even though ('01',2) and ('10',3) do not conflict with each other.

# Batching

Messages may be produced and consumed in batches. Batch sizes may vary, and the
batches on the production side need not line up with the batches on the
consumption side. The ordering semantics above apply to individual messages,
never to batches: a batch is only a unit of transport or dispatch.

# Scenarios

## Shared load

Start with a single root subtree (''). All messages are enqueued on to the root topic.

Next, say we split off the subtrees '0' and '1'. Whenever we see a message whose key starts with '0' or '1', it belongs to the corresponding subtree.

A message with the exact bitstring '' conflicts with both subtrees. It waits for
all earlier messages in the '0' and '1' subtrees to finish, and later messages
in those subtrees wait for it.

## Noisy neighbor

Start with a single root subtree (''). All messages are enqueued on to the root topic.

Split off the subtree '01'. Whenever we see a message whose key starts with '01', it belongs to the '01' subtree.

A message with the exact bitstring '' or '0' conflicts with the '01' subtree. It
waits for all earlier '01' messages to finish, and later '01' messages wait for
it. Messages like those starting with '00' or '1' do not conflict with '01', and
may be processed concurrently to it.

# Dynamic configuration

New subtrees, like '01' should be declarable at runtime. Once a noisy neighbor is identified, we should be able to quarantine it. A quarantined subtree is ordered like any other: it conflicts with the same messages it would have otherwise.

Eventually, it would be desirable to dynamically and automatically spin up and spin down partitions, but a manual configuration would be an excellent start.

# Architecture

We support only one handler method, which must be able to scale horizontally and be dispatched concurrently. To allow this, scheduling is separated from execution.

- Scheduler. One logical component reads the Kinesis shards. It holds the trie, the conflict tracking, and the barrier logic. It dispatches messages and tracks completions. It never runs the handler.
- Workers. The handler method runs here, with as many concurrent copies as needed. Workers are stateless and know nothing about ordering. They are assumed to have idempotent semantics. They run what they're given and report back.

# Technology

Kinesis appears to be a solid fit for these requirements, because granular `SplitShard` support already exists.

https://docs.aws.amazon.com/kinesis/latest/APIReference/API_SplitShard.html

A downside of this approach is that isolating a tenant will take two splits, which must be performed sequentially instead of concurrently.

## What a split does to existing data

- SplitShard changes shard metadata, and consumers aren't involved. It takes effect whatever the backlog is.
- The parent shard closes. Its existing records stay in it and remain readable until retention expires. Nothing is moved or re-sharded.
- New writes go to the two child shards, according to their hash keys.
- A consumer must read the parent to its end before reading the children. Standard consumer libraries such as KCL enforce this, and the scheduler must preserve it too.

## Why splitting during a spike disappoints

Say a noisy tenant has already enqueued a large backlog and you split to isolate it.

1. The backlog is stuck in the parent. The split doesn't redistribute it.
2. The children's new messages wait until the parent is fully consumed. That includes new messages from innocent tenants whose keys now live in a child.
3. So the isolation arrives after the noisy backlog has already cleared. It protects against the next spike, not the current one.

This is why splitting ahead of need is better. The split only helps traffic that arrives after it, so it has to land before the spike.

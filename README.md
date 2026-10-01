This project intends to expand and contract kafka topics.

For example, given a schema of (key, value) where key is a bit string and value is a string. Bit strings can be quite long, upwards of 64 bits. Conceptually a bit string may represent a composite key of nested tenants.

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

Given, in arrival order: ('01','b'), ('','a'), ('10','c')
- 'a' waits for 'b'.
- 'c' waits for 'a', even though 'b' and 'c' do not conflict with each other.

# Scenarios

## Shared load

Start with a topic of ''. All messages are enqueued on to this topic.

Next, say we split the topics '0' and '1'. Whenever we see a message starting with '0' or '1' on the '' topic, delegate it to their corresponding streams.

A message with the exact bitstring '' conflicts with both streams. It waits for
all earlier messages on the '0' and '1' streams to finish, and later messages
on those streams wait for it.

## Noisy neighbor

Start with a topic of ''. All messages are enqueued on to this topic.

Split the topic '01'. Whenever we see a message starting with '01' on the '' topic, delegate it to the '01' stream.

A message with the exact bitstring '' or '0' conflicts with the '01' stream. It
waits for all earlier '01' messages to finish, and later '01' messages wait for
it. Messages like those starting with '00' or '1' do not conflict with '01', and
may be processed concurrently to it.

# Dynamic configuration

New topics, like '01' should be declarable at runtime. Once a noisy neighbor is identified, we should be able to quarantine it.

Eventually, it would be desirable to dynamically and automatically spin up and spin down partitions, but a manual configuration would be an excellent start.

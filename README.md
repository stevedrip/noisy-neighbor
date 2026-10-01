This project intends to expand and contract kafka topics.

For example, given a schema of (key, value) where key is a bit string and value is a string. Bit strings can be quite long, upwards of 64 bits. Conceptually a bit string may represent a composite key of nested tenants.

# Scenarios

## Shared load

Start with a topic of ''. All messages are enqueued on to this topic.

Next, say we split the topics '0' and '1'. Whenever with see a message starting with '0' or '1' on the '' topic, delegate it to their corresponding streams.

If we see a message with the exact bitstring of '', it must be performed in order relative to the '0' and '1' topic. We must wait until the '0' and '1' streams are drained before processing it.

## Noisy neighbor

Start with a topic of ''. All messages are enqueued on to this topic.

Split the topic '01'. Whenever with see a message starting with '01' on the '' topic, delegate it to the '01 stream.

If we see a message with the exact bitstring of '' or '0', it must be performed in order relative to the '01' topic. We must wait until the '01' stream is drained before processing it.

Other messages, like those starting with '00' or '1' may be processed concurrently to the '01' stream.

# Dynamic configuration

New topics, like '01' should be declarable at runtime. Once a noisy neighbor is identified, we should be able to quarantine it.

Eventually, it would be desirable to dynamically and automatically spin up and spin down partitions, but a manual configuration would be an excellent start.
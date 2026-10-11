These scenarios show how the shape of a FIFO trie decides which messages can be consumed concurrently.

Two messages conflict when one key is a prefix of the other, or the keys are equal. A message must be consumed after every earlier message it conflicts with.

Tries may be written using either a relative or absolute labeling schema. Our implementation of tries use a relative labeling schema with bitstring keys.

In the scenario diagrams, `key#n` is the key of the nth message enqueued, using an absolute labeling schema for the keys. Messages with no path between them may be consumed in either order, or at the same time.

## Minimally Concurrent

A minimally concurrent FIFO trie is indistinguishable from a FIFO queue. All messages are consumed in the exact order they are produced.

The relative and absolute representations are identical. There is an implicit queue at the root, and the root has no children.

### Relative Labeling

```
{}
```

### Absolute Labeling

```
{}
```

### Scenario

When we enqueue messages in this order:
```
""#1 ──► "0"#2 ──► "00"#3 ──► "01"#4 ──► "10"#5 ──► "11"#6 ──► "1"#7 ──► ""#8
```

They will be consumed in this partial order:
```
""#1 ──► "0"#2 ──► "00"#3 ──► "01"#4 ──► "10"#5 ──► "11"#6 ──► "1"#7 ──► ""#8
```

## Maximally Concurrent

A maximally concurrent trie never makes a message wait for a message it does not conflict with: every message that can be consumed concurrently is. Because tries are finite, a trie can only be maximally concurrent up to a given depth.

### Relative Labeling

```
{
  "0": {
    "0": {},
    "1": {}
  },
  "1": {
    "0": {},
    "1": {}
  }
}
```

### Absolute Labeling

```
{
  "0": {
    "00": {},
    "01": {}
  },
  "1": {
    "10": {},
    "11": {}
  }
}
```

### Scenario

Here the partial order is no longer a chain.

When we enqueue messages in this order:
```
""#1 ──► "0"#2 ──► "00"#3 ──► "01"#4 ──► "10"#5 ──► "11"#6 ──► "1"#7 ──► ""#8
```

They will be consumed in this partial order:
```
                     ┌──► "00"#3 ──┐
       ┌──► "0"#2 ───┤             │
       │             └──► "01"#4 ──┤
""#1 ──┤                           ├──► ""#8
       ├──► "10"#5 ──┐             │
       │             ├──► "1"#7 ───┘
       └──► "11"#6 ──┘

┏━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃""    ""#1 ─┬─  
┃━━━━╋━━━━━━━┿━━━━━━━━┿┿━━━━━━━━━━━━━━━━━━━━━━┿━━━━━━━┫
┃"0"         └┬┬► "0"#2       └┼► "01"#4 ────────────┬┘       ┃
┃━━━━╋━━━━━━━━┿┿━━━━━━━━━┿━━━━━━━━━━━━━━━━━━━━━┿━━━━━━━━┫
┃"00"         └┼─► "01"#3          │┌─► "11"#6 ┬► "1"#7 ─┘        ┃
┃━━━━╋━━━━━━━━━┿━━━━━━━┿━━━━━━━━━━━━━━━━━━━━━┿━━━━━━━━┫
┃"01"          └──► "10"#4        │┌─► "11"#6 ┬► "1"#7 ─┘        ┃
┃━━━━╋━━━━━━━━━━━━━━━━━┿━━━━━━━━━━━━━━━━━━━━━┿━━━━━━━━┫
┃"1"                 └┼► "01"#4 ────────────┬┘       ┃
┃━━━━╋━━━━━━━━━━━━━━━━━┿━━━━━━━━━━━━━━━━━━━━━┿━━━━━━━━┫
┃"10"                   │┌─► "11"#6 ┬► "1"#7 ─┘        ┃
┃━━━━╋━━━━━━━━━━━━━━━━━┿━━━━━━━━━━━━━━━━━━━━━┿━━━━━━━━┫
┃"11"                   │┌─► "11"#6 ┬► "1"#7 ─┘        ┃
┗━━━━┻━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
```

Messages with no path between them, such as `"0"#2` and `"10"#5`, may be consumed in either order, or at the same time.

## Mixed Concurrent

A trie can branch anywhere, as long as no child's keys conflict with another child's. Long prefixes isolate a particularly expensive tenant in a queue of its own. Short prefixes spread load across several FIFO queues.

### Relative Labeling

```
{
  "01": {},
  "1": {
    "0": {}
  }
}
```

### Absolute Labeling

```
{
  "01": {},
  "1": {
    "10": {}
  }
}
```

### Scenario

A trie that is not maximally concurrent can make a message wait even when waiting is not required for correctness. 

When we enqueue messages in this order:
```
""#1 ──► "0"#2 ──► "00"#3 ──► "01"#4 ──► "10"#5 ──► "11"#6 ──► "1"#7 ──► ""#8
```

They will be consumed in this partial order:
```
                 ┌──► "00"#3 ───────────────┐
                 │                          |
                 ├──► "01"#4 ───────────────┤
""#1 ──► "0"#2 ──┤                          ├──► ""#8
                 ├──► "10"#5 ──┐            |
                 │             ├──► "1"#7 ──┘
                 └──► "11"#6 ──┘
```

They will be consumed in this partial order:
```
┏━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃""    ""#1 ─► "0"#2 ─┬┬► "00"#3 ─────────────┬► ""#8 ┃
┃━━━━╋━━━━━━━━━━━━━━━━┿┿━━━━━━━━━━━━━━━━━━━━━━┿━━━━━━━┫
┃"01"                 └┼► "01"#4 ────────────┬┘       ┃
┃━━━━╋━━━━━━━━━━━━━━━━━┿━━━━━━━━━━━━━━━━━━━━━┿━━━━━━━━┫
┃"1"                   └┬─► "11"#6 ┬► "1"#7 ─┘        ┃
┃━━━━╋━━━━━━━━━━━━━━━━━━┿━━━━━━━━━━┿━━━━━━━━━━━━━━━━━━┫
┃"10"                   └► "10"#5 ─┘                  ┃
┗━━━━┻━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
```

Let's assume I adopt these rules. What would consuming look like? Would it be symmetric?

Producing:
If a message key is less than all children, it blocks.
If a message key is not comparable to any children, run inline.
If a message key is greater than or equal to any children, it is forwarded to that child.
Else reject

Otherwise, the message is rejected. (An alternate would be to forcibly split)

Consuming

When taking

```
             ┌► "0"#2                         
┏━━━━┳━━━━━━━┿━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃""    ""#1 ─┴┬┬► "00"#3 ─────────────┬► ""#8 ┃
┃━━━━╋━━━━━━━━┿┿━━━━━━━━━━━━━━━━━━━━━━┿━━━━━━━┫
┃"01"         └┼► "01"#4 ────────────┬┘       ┃
┃━━━━╋━━━━━━━━━┿━━━━━━━━━━━━━━━━━━━━━┿━━━━━━━━┫
┃"1"           └┬─► "11"#6 ┬► "1"#7 ─┘        ┃
┃━━━━╋━━━━━━━━━━┿━━━━━━━━━━┿━━━━━━━━━━━━━━━━━━┫
┃"10"           └► "10"#5 ─┘                  ┃
┗━━━━┻━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
```


`"11"#6` is consumed only after `"10"#5`, although their keys do not conflict.

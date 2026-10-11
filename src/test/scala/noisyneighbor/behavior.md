These scenarios show how the shape of a FIFO trie decides which messages can be consumed concurrently.

Two messages conflict when one key is a prefix of the other, or the keys are equal. A message must be consumed after every earlier message it conflicts with.

In the diagrams, `key#n` is the key of the nth message enqueued, so the two `""` messages can be told apart.

## Minimally Concurrent

A minimally concurrent FIFO trie is indistinguishable from a FIFO queue. All messages are consumed in the exact order they are produced.

The relative and absolute representations are identical. There is an implicit queue at the root, and the root has no children.

### Trie

Each node is a queue, named by its absolute prefix. Each edge is labelled with the prefix relative to its parent.

```
""
```

### Relative (labels are relative to the parent)

```
{}
```

### Absolute (labels are full prefixes)

```
{}
```

### Scenario

We describe ordering constraints with partial orders. In the diagrams, a line from one message to the one below it means the upper message must be consumed first. Messages with no path between them may be consumed in either order, or at the same time. Here the partial order is as simple as it can be: it is the total order itself.

Messages with these keys are enqueued in this order:
```
""#1 -> "0"#2 -> "00"#3 -> "01"#4 -> "10"#5 -> "11"#6 -> "1"#7 -> ""#8
```

They are consumed in the same order, which is trivially a partial order:
```
""#1 -> "0"#2 -> "00"#3 -> "01"#4 -> "10"#5 -> "11"#6 -> "1"#7 -> ""#8
```

## Maximally Concurrent

A maximally concurrent trie never makes a message wait for a message it does not conflict with: every message that can be consumed concurrently is. Because tries are finite, a trie can only be maximally concurrent up to a given depth.

### Trie

```
""
 |-- 0 --> "0"
 |         |-- 0 --> "00"
 |         `-- 1 --> "01"
 `-- 1 --> "1"
           |-- 0 --> "10"
           `-- 1 --> "11"
```

### Relative (labels are relative to the parent)

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

### Absolute (labels are full prefixes)

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

Here the partial order is no longer a chain. Messages with no path between them, such as `"0"#2` and `"10"#5`, may be consumed in either order, or at the same time.

Messages with these keys are enqueued in this order:
```
""#1 -> "0"#2 -> "00"#3 -> "01"#4 -> "10"#5 -> "11"#6 -> "1"#7 -> ""#8
```

They are consumed in this partial order:
```
                    ""#1
                     |
        +------------+------------+
        |            |            |
      "0"#2        "10"#5       "11"#6
        |            |            |
    +---+---+        +-----+------+
    |       |              |
  "00"#3  "01"#4         "1"#7
    |       |              |
    +-------+--------+-----+
                     |
                    ""#8
```

## Mixed Concurrent

A trie can branch anywhere, as long as no child's prefix is a prefix of another child's. Long prefixes isolate a particularly expensive tenant in a queue of its own. Short prefixes spread load across several FIFO queues.

### Trie

```
""
 |-- 01 --> "01"
 `-- 1 --> "1"
           `-- 0 --> "10"
```

### Relative (labels are relative to the parent)

```
{
  "01": {},
  "1": {
    "0": {}
  }
}
```

### Absolute (labels are full prefixes)

```
{
  "01": {},
  "1": {
    "10": {}
  }
}
```

### Scenario

A trie that is not maximally concurrent can make a message wait even when waiting is not required for correctness. In the diagram below, `"11"#6` is consumed only after `"10"#5`, although their keys do not conflict.

Messages with these keys are enqueued in this order:
```
""#1 -> "0"#2 -> "00"#3 -> "01"#4 -> "10"#5 -> "11"#6 -> "1"#7 -> ""#8
```

They are consumed in this partial order. Orders implied by others, such as `"10"#5` before `""#8`, are not drawn:
```
        ""#1
          |
        "0"#2
          |
       "00"#3
          |
          +-------------------+
          |                   |
       "01"#4               "10"#5
          |                   |
          |                 "11"#6
          |                   |
          |                 "1"#7
          |                   |
          +---------+---------+
                    |
                  ""#8
```

In the diagrams below, `key#n` is the key of the nth message enqueued, so that the two `""` messages can be told apart.

## Minimally Concurrent

A minimally concurrent fifo trie is indistinguishable from a fifo queue. All messages are consumed in the exact order they are produced.

Both the absolute and relative trie representations look identical. There is an implicit queue at the root, and the trie has no children.

### Trie

Nodes are queues, named by their absolute prefix. Edges are labelled with the relative prefix.

```
""
```

### Relative

```
{}
```

### Absolute

```
{}
```

### Scenario

We use partial orders, represented by lattices, to represent ordering constraints. The partial order lattice looks quite simple in this case and matches the total order exactly.

Given messages with these keys enqueued total order...
```
""#1 -> "0"#2 -> "00"#3 -> "01"#4 -> "10"#5 -> "11"#6 -> "1"#7 -> ""#8
```

...messages with those keys will be consumed in the following, trivial, partial order.
```
""#1 -> "0"#2 -> "00"#3 -> "01"#4 -> "10"#5 -> "11"#6 -> "1"#7 -> ""#8
```

## Maximally Concurrent

A trie that is maximally concurrent will never block when it queue messages concurrently. Because we use finite structures, a trie can only be maximally connected up to a given depth. All messages that can be consumed concurrently are.

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

### Relative

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

### Absolute

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

The partial order lattice finally looks interesting in this scenario and includes non-determinism.

Given messages with these keys enqueued total order...
```
""#1 -> "0"#2 -> "00"#3 -> "01"#4 -> "10"#5 -> "11"#6 -> "1"#7 -> ""#8
```

...messages with those keys will be consumed in the following partial order. A line is a message that must be consumed before the one below it.
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

Tries can be branched arbitrarily, as long as their children do not have conflicting prefixes. You can isolate particularly expensive tenants with long bit strings, or use smaller bit strings to spread load across multiple fifo queues.

### Trie

```
""
 |-- 01 --> "01"
 `-- 1 --> "1"
           `-- 0 --> "10"
```

### Relative

```
{
  "01": {},
  "1": {
    "0": {},
  }
}
```

### Absolute

```
{
  "01": {},
  "1": {
    "10": {}
  }
}
```

### Scenario

The partial order lattice blocks when it is not strictly required to for correctness under lattices that are not maximally concurrent

Given messages with these keys enqueued total order...
```
""#1 -> "0"#2 -> "00"#3 -> "01"#4 -> "10"#5 -> "11"#6 -> "1"#7 -> ""#8
```

...messages with those keys will be consumed in the following partial order. A line is a message that must be consumed before the one below it. Orders implied by others, such as `"10"#5` before `""#8`, are not drawn.
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

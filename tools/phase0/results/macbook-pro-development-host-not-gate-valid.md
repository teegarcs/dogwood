# Project Dogwood -- Phase 0 Results

**Host:** MacBook Pro (development host, not gate-valid)

**Platform:** Mac OS X aarch64, Java 21.0.12.1

> These numbers are gate-valid only on the low-end Android device the Phase 0
> harness appendix names. On any other host they establish the harness and bound
> expectations; they do not open or close the gate.

## Pinned toolchain

| Component | Version |
| --- | --- |
| Zipline | 1.27.0 |
| Kotlin | 2.3.20 |
| `androidx.compose.runtime:runtime-js` | 1.12.0 |
| `kotlinx-coroutines-core` | 1.10.2 |
| `kotlinx-serialization-json` | 1.10.0 |
| QuickJS | 2021-03-27 |

## Gate

The 0.3 leg is evaluated **both** ways, because roadmap.md sizes a per-tap budget
with a per-screen quantity: it gives the crossing 4 ms inside a tap-to-repaint path,
then writes the leg as "the 150-node batch crossing" -- and a tap never produces 150
nodes. Which reading was intended is a human ruling, so both are reported rather than
one being chosen here. See ADR-006 section 2.4.

| Leg | Budget | Measured | Verdict |
| --- | ---: | ---: | --- |
| Guest recomposition of the reference screen, 95th percentile (0.2) | 8.00 ms | 1.723 ms | within budget |
| Batch crossing, per-frame reading: a steady-state recomposition batch (0.3) | 4.00 ms | 0.124 ms | within budget |
| Batch crossing, per-screen reading: the whole initial batch (0.3) | 4.00 ms | 24.338 ms | **over budget** |
| Maximum garbage-collection pause under load, 99th percentile (0.4) | 16.70 ms | 1.470 ms | within budget |
| Cold start: module load plus first composition (0.1) | 500.00 ms | 128.111 ms | within budget |
| Cold start through the host holding the tree (composition plus initial crossing) (0.1 + 0.3) | 500.00 ms | 154.834 ms | within budget |

## 0.1 -- Cold-start cost

| Measure | Value |
| --- | ---: |
| Minified JavaScript | 2431092 bytes |
| Gzipped JavaScript | 316495 bytes |
| QuickJS bytecode | 1089695 bytes |
| `.zipline` file as delivered | 1089715 bytes |

| Measurement | n | p50 | p95 | p99 | min | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `module-load` | 10 | 14.4213 ms | 14.5188 ms | 14.5188 ms | 13.4195 ms | 15.4047 ms | 14.3860 ms |
| `main-function` | 10 | 0.0499 ms | 0.0552 ms | 0.0552 ms | 0.0481 ms | 1.9971 ms | 0.2447 ms |
| `cold-start-to-first-composition` | 10 | 128.1108 ms | 132.9029 ms | 132.9029 ms | 127.6057 ms | 331.7830 ms | 149.1178 ms |
| `cold-start-to-first-batch-delivered` | 10 | 154.8340 ms | 161.7054 ms | 161.7054 ms | 154.2116 ms | 366.8254 ms | 177.0478 ms |

`QuickJs.memoryUsage` after module load:

| Field | Value |
| --- | ---: |
| `memoryUsedSize` | 4205539 bytes |
| `memoryAllocatedSize` | 5066416 bytes |
| `objectsCount` | 12061 |
| `objectsSize` | 868392 bytes |
| `stringsCount` | 32 |
| `stringsSize` | 1422 bytes |
| `jsFunctionsCount` | 8024 |
| `jsFunctionsCodeSize` | 457641 bytes |
| `propertiesSize` | 710192 bytes |
| `arraysCount` | 485 |

`QuickJs.memoryUsage` after the first composition:

| Field | Value |
| --- | ---: |
| `memoryUsedSize` | 5284877 bytes |
| `memoryAllocatedSize` | 6292272 bytes |
| `objectsCount` | 19207 |
| `objectsSize` | 1382904 bytes |
| `stringsCount` | 391 |
| `stringsSize` | 8762 bytes |
| `jsFunctionsCount` | 8010 |
| `jsFunctionsCodeSize` | 457388 bytes |
| `propertiesSize` | 1122144 bytes |
| `arraysCount` | 1875 |

## 0.2 -- Composition and recomposition

### Reference screen at 23 rows

| Property | Value |
| --- | ---: |
| Widget nodes (each produces one `Create`) | 160 |
| Children-slot nodes (no protocol cost) | 77 |
| Changes in the initial batch | 572 |
| Initial batch, encoded | 20939 bytes |
| `mutableStateOf` holders | 24 |

| Measurement | n | p50 | p95 | p99 | min | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `clock-overhead` | 1000 | 0.0403 ms | 0.0427 ms | 0.0444 ms | 0.0375 ms | 0.0746 ms | 0.0404 ms |
| `initial-composition-rows-23` | 50 | 38.8674 ms | 41.2483 ms | 41.5863 ms | 38.4754 ms | 41.7006 ms | 39.1854 ms |
| `recompose-row` | 200 | 1.6522 ms | 1.7227 ms | 2.0091 ms | 1.5073 ms | 9.3205 ms | 1.7059 ms |
| `recompose-total` | 200 | 1.8828 ms | 2.0589 ms | 3.4932 ms | 1.6879 ms | 4.9257 ms | 1.9471 ms |

### Reference screen at 50 rows

| Property | Value |
| --- | ---: |
| Widget nodes (each produces one `Create`) | 322 |
| Children-slot nodes (no protocol cost) | 158 |
| Changes in the initial batch | 1139 |
| Initial batch, encoded | 42026 bytes |
| `mutableStateOf` holders | 24 |

| Measurement | n | p50 | p95 | p99 | min | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `clock-overhead` | 1000 | 0.0405 ms | 0.0433 ms | 0.0466 ms | 0.0380 ms | 0.0560 ms | 0.0407 ms |
| `initial-composition-rows-50` | 50 | 79.1145 ms | 81.2489 ms | 82.5718 ms | 77.8891 ms | 82.5786 ms | 79.4052 ms |
| `recompose-row` | 200 | 2.9661 ms | 3.1285 ms | 3.2874 ms | 2.7941 ms | 3.3414 ms | 2.9864 ms |
| `recompose-total` | 200 | 2.4893 ms | 2.5652 ms | 2.6132 ms | 2.4369 ms | 5.7168 ms | 2.5134 ms |

## 0.3 -- Protocol cost per frame

Method: End-to-end plus total bytes. The five-pass internal breakdown (guest encode, JSON.stringify, Java Native Interface transcode, host parse) needs timing inside Zipline's internal CallChannel, which needs a locally patched Zipline build; roadmap.md permits reporting end-to-end plus bytes when that slips. Guest encode and stringify ARE separated here, because the guest can time those itself.

Three encodings of the same batch are measured:

- **Encode (kotlinx)** -- ADR-004 section 2.2's class-discriminator JavaScript
  Object Notation (JSON), produced by `kotlinx.serialization`'s pure-Kotlin encoder.
- **Encode (array)** -- the same schema under array polymorphism, still through the
  pure-Kotlin encoder.
- **Encode (native)** -- array polymorphism through `encodeToDynamic` plus QuickJS's
  native `JSON.stringify`. This is the path Zipline's own `CallChannel` takes on
  Kotlin/JavaScript, so it is what `sendChanges` actually pays.

`Cross (pre-encoded)` sends an already-built string and so isolates transport;
`Cross (Zipline-serialized)` is the real `sendChanges(batch)` call, end to end.

| Changes | Bytes | Bytes (array) | Build p50 | Encode (kotlinx) p50 | Encode (array) p50 | Encode (native) p50 | Cross (pre-encoded) p50 | Cross (Zipline-serialized) p50 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 35 | 33 | 0.0453 ms | 0.1095 ms | 0.1108 ms | 0.0752 ms | 0.0960 ms | 0.1241 ms |
| 10 | 442 | 422 | 0.0489 ms | 1.0074 ms | 0.9855 ms | 0.6676 ms | 0.0989 ms | 0.7243 ms |
| 100 | 3657 | 3457 | 0.0769 ms | 7.8247 ms | 7.5783 ms | 4.5961 ms | 0.1172 ms | 4.6643 ms |
| 1000 | 36510 | 34510 | 0.3517 ms | 87.1678 ms | 83.5000 ms | 43.1442 ms | 0.3850 ms | 43.1151 ms |
| 572 | 20939 | 19795 | 0.2214 ms | 45.6141 ms | 43.9545 ms | 24.1149 ms | 0.2631 ms | 24.3376 ms |

## 0.4 -- Garbage-collection behaviour

Method: Host-forced QuickJs.gc() pauses (upper bound) plus the recomposition tail at each gcThreshold. NOT the patched-QuickJS JS_RunGC hook the appendix prefers; that needs a local native Zipline build and is recorded as outstanding work.

| `gcThreshold` | Recompose p50 | Recompose p99 | Recompose max | Forced pause p99 | Forced pause max | Heap used after churn |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 256 KiB | 1.619 ms | 1.680 ms | 1.720 ms | 1.345 ms | 2.291 ms | 4964598 bytes |
| 8192 KiB | 1.642 ms | 1.766 ms | 4.480 ms | 1.470 ms | 1.491 ms | 4964462 bytes |
| 16384 KiB | 1.621 ms | 1.673 ms | 1.728 ms | 1.466 ms | 6.337 ms | 4964990 bytes |

## Notes and caveats

- The gate device named in the roadmap appendix is a low-end 2022-tier Android phone. Numbers produced on any other host are NOT gate-valid; they establish the harness and bound expectations.
- Experiment 0.4 used the host-forced gc() fallback, not the patched-QuickJS hook.
- Experiment 0.3 reports end-to-end crossing plus bytes; the five-pass internal breakdown needs a locally patched Zipline build.

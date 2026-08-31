# Project Dogwood -- Phase 0 Results

**Host:** Google Pixel 10 Pro

**Platform:** Android 17 (API 37), arm64-v8a, blazer

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
| Guest recomposition of the reference screen, 95th percentile (0.2) | 8.00 ms | 1.583 ms | within budget |
| Batch crossing, per-frame reading: a steady-state recomposition batch (0.3) | 4.00 ms | 0.174 ms | within budget |
| Batch crossing, per-screen reading: the whole initial batch (0.3) | 4.00 ms | 27.920 ms | **over budget** |
| Maximum garbage-collection pause under load, 99th percentile (0.4) | 16.70 ms | 5.408 ms | within budget |
| Cold start: module load plus first composition (0.1) | 500.00 ms | 154.295 ms | within budget |
| Cold start through the host holding the tree (composition plus initial crossing) (0.1 + 0.3) | 500.00 ms | 190.535 ms | within budget |

## 0.1 -- Cold-start cost

| Measure | Value |
| --- | ---: |
| Minified JavaScript | -1 bytes |
| Gzipped JavaScript | -1 bytes |
| QuickJS bytecode | 1089695 bytes |
| `.zipline` file as delivered | 1089715 bytes |

| Measurement | n | p50 | p95 | p99 | min | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `module-load` | 10 | 27.9297 ms | 29.4320 ms | 29.4320 ms | 25.6767 ms | 46.1074 ms | 29.7548 ms |
| `main-function` | 10 | 0.0968 ms | 0.1109 ms | 0.1109 ms | 0.0867 ms | 0.2538 ms | 0.1135 ms |
| `cold-start-to-first-composition` | 10 | 154.2955 ms | 184.3230 ms | 184.3230 ms | 149.9494 ms | 380.8588 ms | 179.6451 ms |
| `cold-start-to-first-batch-delivered` | 10 | 190.5352 ms | 225.5127 ms | 225.5127 ms | 181.4811 ms | 515.4820 ms | 225.0929 ms |

`QuickJs.memoryUsage` after module load:

| Field | Value |
| --- | ---: |
| `memoryUsedSize` | 4205539 bytes |
| `memoryAllocatedSize` | 5056875 bytes |
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
| `memoryUsedSize` | 5569329 bytes |
| `memoryAllocatedSize` | 6633147 bytes |
| `objectsCount` | 20529 |
| `objectsSize` | 1478088 bytes |
| `stringsCount` | 392 |
| `stringsSize` | 8806 bytes |
| `jsFunctionsCount` | 8010 |
| `jsFunctionsCodeSize` | 457388 bytes |
| `propertiesSize` | 1311168 bytes |
| `arraysCount` | 1878 |

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
| `clock-overhead` | 1000 | 0.0612 ms | 0.0718 ms | 0.0793 ms | 0.0547 ms | 5.4854 ms | 0.0679 ms |
| `initial-composition-rows-23` | 50 | 34.0095 ms | 41.4080 ms | 41.8253 ms | 33.4598 ms | 42.0319 ms | 34.6885 ms |
| `recompose-row` | 200 | 1.5364 ms | 1.5834 ms | 1.6241 ms | 1.4951 ms | 1.6449 ms | 1.5411 ms |
| `recompose-total` | 200 | 1.7115 ms | 1.7654 ms | 1.8318 ms | 1.6474 ms | 11.5399 ms | 1.7623 ms |

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
| `clock-overhead` | 1000 | 0.0608 ms | 0.0696 ms | 0.0747 ms | 0.0534 ms | 5.0228 ms | 0.0663 ms |
| `initial-composition-rows-50` | 50 | 71.2616 ms | 79.5462 ms | 82.7369 ms | 69.1780 ms | 84.8450 ms | 72.2960 ms |
| `recompose-row` | 200 | 2.6866 ms | 2.7973 ms | 2.8678 ms | 2.6110 ms | 14.4260 ms | 2.7535 ms |
| `recompose-total` | 200 | 2.3285 ms | 2.4398 ms | 2.4703 ms | 2.2703 ms | 2.4733 ms | 2.3366 ms |

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
| 1 | 35 | 33 | 0.0616 ms | 0.1202 ms | 0.1260 ms | 0.0938 ms | 0.1467 ms | 0.1736 ms |
| 10 | 442 | 422 | 0.0645 ms | 0.9391 ms | 0.9476 ms | 0.6634 ms | 0.1966 ms | 0.8414 ms |
| 100 | 3657 | 3457 | 0.0961 ms | 7.4889 ms | 7.4736 ms | 4.6207 ms | 0.2573 ms | 5.4708 ms |
| 1000 | 36510 | 34510 | 0.4066 ms | 80.1730 ms | 80.4286 ms | 50.9135 ms | 1.3892 ms | 49.5667 ms |
| 572 | 20939 | 19795 | 0.2648 ms | 43.7347 ms | 43.8898 ms | 24.9749 ms | 0.9011 ms | 27.9197 ms |

## 0.4 -- Garbage-collection behaviour

Method: Host-forced QuickJs.gc() pauses (upper bound) plus the recomposition tail at each gcThreshold. NOT the patched-QuickJS JS_RunGC hook the appendix prefers; that needs a local native Zipline build and is recorded as outstanding work.

| `gcThreshold` | Recompose p50 | Recompose p99 | Recompose max | Forced pause p99 | Forced pause max | Heap used after churn |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 256 KiB | 1.596 ms | 1.690 ms | 1.736 ms | 3.584 ms | 10.079 ms | 4964286 bytes |
| 8192 KiB | 1.590 ms | 1.696 ms | 12.231 ms | 3.403 ms | 4.044 ms | 4964694 bytes |
| 16384 KiB | 1.595 ms | 1.680 ms | 1.731 ms | 5.408 ms | 22.067 ms | 4964886 bytes |

## Notes and caveats

- Run on a physical or virtual Android device. Only a physical low-end device of the tier the Phase 0 harness appendix names produces gate-valid numbers; an emulator runs on the development machine's processor and is not that.
- Experiment 0.4 used the host-forced gc() fallback, not the patched-QuickJS hook.
- Experiment 0.1 reports no minified-JavaScript size on device; only the bytecode is shipped to a device, and the JavaScript size is recorded by the development host run.

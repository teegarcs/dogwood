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

| Leg | Budget | Measured | Verdict |
| --- | ---: | ---: | --- |
| Guest recomposition of the reference screen, 95th percentile (0.2) | 8.00 ms | 1.670 ms | within budget |
| Initial batch crossing, end to end (0.3) | 4.00 ms | 24.056 ms | **over budget** |
| Maximum garbage-collection pause under load, 99th percentile (0.4) | 16.70 ms | 1.460 ms | within budget |
| Cold start: module load plus first composition (0.1) | 500.00 ms | 127.248 ms | within budget |

## 0.1 -- Cold-start cost

| Measure | Value |
| --- | ---: |
| Minified JavaScript | 2430996 bytes |
| Gzipped JavaScript | 316439 bytes |
| QuickJS bytecode | 1089616 bytes |
| `.zipline` file as delivered | 1089636 bytes |

| Measurement | n | p50 | p95 | p99 | min | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `module-load` | 10 | 14.3798 ms | 15.0190 ms | 15.0190 ms | 14.2173 ms | 17.2297 ms | 14.6920 ms |
| `main-function` | 10 | 0.0489 ms | 0.0578 ms | 0.0578 ms | 0.0474 ms | 2.5688 ms | 0.3018 ms |
| `cold-start-to-first-composition` | 10 | 127.2484 ms | 130.1673 ms | 130.1673 ms | 125.3984 ms | 355.3149 ms | 150.0016 ms |

`QuickJs.memoryUsage` after module load:

| Field | Value |
| --- | ---: |
| `memoryUsedSize` | 4205401 bytes |
| `memoryAllocatedSize` | 5066320 bytes |
| `objectsCount` | 12061 |
| `objectsSize` | 868392 bytes |
| `stringsCount` | 32 |
| `stringsSize` | 1422 bytes |
| `jsFunctionsCount` | 8024 |
| `jsFunctionsCodeSize` | 457595 bytes |
| `propertiesSize` | 710192 bytes |
| `arraysCount` | 485 |

`QuickJs.memoryUsage` after the first composition:

| Field | Value |
| --- | ---: |
| `memoryUsedSize` | 5680161 bytes |
| `memoryAllocatedSize` | 6772080 bytes |
| `objectsCount` | 20941 |
| `objectsSize` | 1507752 bytes |
| `stringsCount` | 391 |
| `stringsSize` | 8765 bytes |
| `jsFunctionsCount` | 8024 |
| `jsFunctionsCodeSize` | 457595 bytes |
| `propertiesSize` | 1417168 bytes |
| `arraysCount` | 1578 |

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
| `clock-overhead` | 1000 | 0.0397 ms | 0.0420 ms | 0.0443 ms | 0.0375 ms | 0.0585 ms | 0.0398 ms |
| `initial-composition-rows-23` | 50 | 38.6280 ms | 41.0056 ms | 41.2672 ms | 38.3704 ms | 41.3518 ms | 38.9322 ms |
| `recompose-row` | 200 | 1.6240 ms | 1.6698 ms | 1.7103 ms | 1.5873 ms | 1.7445 ms | 1.6279 ms |
| `recompose-total` | 200 | 1.8400 ms | 1.9008 ms | 1.9744 ms | 1.7955 ms | 4.6649 ms | 1.8567 ms |

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
| `clock-overhead` | 1000 | 0.0382 ms | 0.0414 ms | 0.0440 ms | 0.0363 ms | 0.0502 ms | 0.0385 ms |
| `initial-composition-rows-50` | 50 | 78.8650 ms | 81.3507 ms | 81.6025 ms | 77.9026 ms | 81.8484 ms | 79.0957 ms |
| `recompose-row` | 200 | 2.8942 ms | 2.9556 ms | 2.9839 ms | 2.8390 ms | 3.0159 ms | 2.9002 ms |
| `recompose-total` | 200 | 2.4319 ms | 2.4800 ms | 2.5300 ms | 2.3970 ms | 5.5402 ms | 2.4521 ms |

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
| 1 | 35 | 33 | 0.0446 ms | 0.1082 ms | 0.1095 ms | 0.0747 ms | 0.1005 ms | 0.1437 ms |
| 10 | 442 | 422 | 0.0476 ms | 0.9885 ms | 0.9694 ms | 0.6606 ms | 0.0964 ms | 0.7248 ms |
| 100 | 3657 | 3457 | 0.0770 ms | 7.7531 ms | 7.5420 ms | 4.5738 ms | 0.1165 ms | 4.6635 ms |
| 1000 | 36510 | 34510 | 0.3525 ms | 86.2346 ms | 83.1472 ms | 42.6494 ms | 0.3951 ms | 42.9728 ms |
| 572 | 20939 | 19795 | 0.2205 ms | 44.9971 ms | 43.6588 ms | 23.9200 ms | 0.2529 ms | 24.0556 ms |

## 0.4 -- Garbage-collection behaviour

Method: Host-forced QuickJs.gc() pauses (upper bound) plus the recomposition tail at each gcThreshold. NOT the patched-QuickJS JS_RunGC hook the appendix prefers; that needs a local native Zipline build and is recorded as outstanding work.

| `gcThreshold` | Recompose p50 | Recompose p99 | Recompose max | Forced pause p99 | Forced pause max | Heap used after churn |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 256 KiB | 1.640 ms | 1.711 ms | 1.752 ms | 1.448 ms | 2.377 ms | 4964748 bytes |
| 8192 KiB | 1.638 ms | 1.733 ms | 4.503 ms | 1.404 ms | 1.487 ms | 4963964 bytes |
| 16384 KiB | 1.627 ms | 1.679 ms | 1.702 ms | 1.460 ms | 5.122 ms | 4964324 bytes |

## Notes and caveats

- The gate device named in the roadmap appendix is a low-end 2022-tier Android phone. Numbers produced on any other host are NOT gate-valid; they establish the harness and bound expectations.
- Experiment 0.4 used the host-forced gc() fallback, not the patched-QuickJS hook.
- Experiment 0.3 reports end-to-end crossing plus bytes; the five-pass internal breakdown needs a locally patched Zipline build.

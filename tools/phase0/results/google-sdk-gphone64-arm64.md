# Project Dogwood -- Phase 0 Results

**Host:** Google sdk_gphone64_arm64

**Platform:** Android 15 (API 35), arm64-v8a, ranchu

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
| Guest recomposition of the reference screen, 95th percentile (0.2) | 8.00 ms | 1.524 ms | within budget |
| Initial batch crossing, end to end (0.3) | 4.00 ms | 22.541 ms | **over budget** |
| Maximum garbage-collection pause under load, 99th percentile (0.4) | 16.70 ms | 2.640 ms | within budget |
| Cold start: module load plus first composition (0.1) | 500.00 ms | 131.151 ms | within budget |

## 0.1 -- Cold-start cost

| Measure | Value |
| --- | ---: |
| Minified JavaScript | -1 bytes |
| Gzipped JavaScript | -1 bytes |
| QuickJS bytecode | 1089616 bytes |
| `.zipline` file as delivered | 1089636 bytes |

| Measurement | n | p50 | p95 | p99 | min | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `module-load` | 10 | 15.9789 ms | 38.6618 ms | 38.6618 ms | 15.3757 ms | 225.3077 ms | 40.2179 ms |
| `main-function` | 10 | 0.0531 ms | 0.0834 ms | 0.0834 ms | 0.0471 ms | 0.0836 ms | 0.0584 ms |
| `cold-start-to-first-composition` | 10 | 131.1506 ms | 263.1670 ms | 263.1670 ms | 125.5540 ms | 1088.8738 ms | 244.9584 ms |

`QuickJs.memoryUsage` after module load:

| Field | Value |
| --- | ---: |
| `memoryUsedSize` | 4205401 bytes |
| `memoryAllocatedSize` | 5056737 bytes |
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
| `memoryUsedSize` | 5002134 bytes |
| `memoryAllocatedSize` | 6004344 bytes |
| `objectsCount` | 17201 |
| `objectsSize` | 1238472 bytes |
| `stringsCount` | 391 |
| `stringsSize` | 8765 bytes |
| `jsFunctionsCount` | 8010 |
| `jsFunctionsCodeSize` | 457342 bytes |
| `propertiesSize` | 1016240 bytes |
| `arraysCount` | 1139 |

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
| `clock-overhead` | 1000 | 0.0544 ms | 0.0712 ms | 0.1176 ms | 0.0475 ms | 2.5303 ms | 0.0598 ms |
| `initial-composition-rows-23` | 50 | 33.5034 ms | 37.3645 ms | 38.3797 ms | 32.6929 ms | 39.9566 ms | 34.1708 ms |
| `recompose-row` | 200 | 1.4320 ms | 1.5245 ms | 1.6141 ms | 1.3918 ms | 1.6331 ms | 1.4438 ms |
| `recompose-total` | 200 | 1.6170 ms | 2.4597 ms | 2.7590 ms | 1.5397 ms | 5.2144 ms | 1.7922 ms |

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
| `clock-overhead` | 1000 | 0.0470 ms | 0.0699 ms | 0.1117 ms | 0.0433 ms | 9.8119 ms | 0.0627 ms |
| `initial-composition-rows-50` | 50 | 68.0508 ms | 76.6530 ms | 90.6879 ms | 67.0005 ms | 96.5252 ms | 70.2287 ms |
| `recompose-row` | 200 | 2.5077 ms | 2.6038 ms | 2.8577 ms | 2.4497 ms | 7.5059 ms | 2.5512 ms |
| `recompose-total` | 200 | 2.0708 ms | 2.1358 ms | 2.1639 ms | 2.0225 ms | 2.2741 ms | 2.0769 ms |

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
| 1 | 35 | 33 | 0.0532 ms | 0.1179 ms | 0.1152 ms | 0.0828 ms | 0.1192 ms | 0.1555 ms |
| 10 | 442 | 422 | 0.0553 ms | 0.8741 ms | 0.8585 ms | 0.5852 ms | 0.1685 ms | 0.7854 ms |
| 100 | 3657 | 3457 | 0.0808 ms | 6.7741 ms | 6.5625 ms | 3.9863 ms | 0.2760 ms | 4.5290 ms |
| 1000 | 36510 | 34510 | 0.3185 ms | 76.4338 ms | 73.7881 ms | 39.7243 ms | 1.7399 ms | 40.0839 ms |
| 572 | 20939 | 19795 | 0.2053 ms | 40.0200 ms | 38.9786 ms | 21.0738 ms | 1.1005 ms | 22.5409 ms |

## 0.4 -- Garbage-collection behaviour

Method: Host-forced QuickJs.gc() pauses (upper bound) plus the recomposition tail at each gcThreshold. NOT the patched-QuickJS JS_RunGC hook the appendix prefers; that needs a local native Zipline build and is recorded as outstanding work.

| `gcThreshold` | Recompose p50 | Recompose p99 | Recompose max | Forced pause p99 | Forced pause max | Heap used after churn |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 256 KiB | 1.420 ms | 1.504 ms | 1.655 ms | 2.640 ms | 4.172 ms | 4964460 bytes |
| 8192 KiB | 1.433 ms | 1.580 ms | 5.794 ms | 2.032 ms | 2.197 ms | 4963972 bytes |
| 16384 KiB | 1.437 ms | 1.551 ms | 1.588 ms | 2.211 ms | 8.471 ms | 4964844 bytes |

## Notes and caveats

- Run on a physical or virtual Android device. Only a physical low-end device of the tier the Phase 0 harness appendix names produces gate-valid numbers; an emulator runs on the development machine's processor and is not that.
- Experiment 0.4 used the host-forced gc() fallback, not the patched-QuickJS hook.
- Experiment 0.1 reports no minified-JavaScript size on device; only the bytecode is shipped to a device, and the JavaScript size is recorded by the development host run.

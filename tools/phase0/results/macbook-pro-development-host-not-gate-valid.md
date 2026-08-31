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
| Guest recomposition of the reference screen, 95th percentile (0.2) | 8.00 ms | 1.670 ms | within budget |
| Batch crossing, per-frame reading: a steady-state recomposition batch (0.3) | 4.00 ms | 0.126 ms | within budget |
| Batch crossing, per-screen reading: the whole initial batch (0.3) | 4.00 ms | 23.737 ms | **over budget** |
| Maximum garbage-collection pause under load, 99th percentile (0.4) | 16.70 ms | 1.702 ms | within budget |
| Cold start: module load plus first composition (0.1) | 500.00 ms | 125.005 ms | within budget |
| Cold start through the host holding the tree (composition plus initial crossing) (0.1 + 0.3) | 500.00 ms | 151.082 ms | within budget |

## 0.1 -- Cold-start cost

| Measure | Value |
| --- | ---: |
| Minified JavaScript | 2550018 bytes |
| Gzipped JavaScript | 334365 bytes |
| QuickJS bytecode | 1149235 bytes |
| `.zipline` file as delivered | 1149255 bytes |

| Measurement | n | p50 | p95 | p99 | min | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `module-load` | 10 | 14.9671 ms | 15.0935 ms | 15.0935 ms | 14.9424 ms | 18.8279 ms | 15.3729 ms |
| `main-function` | 10 | 0.0680 ms | 0.0692 ms | 0.0692 ms | 0.0669 ms | 2.4008 ms | 0.3014 ms |
| `cold-start-to-first-composition` | 10 | 125.0054 ms | 130.7268 ms | 130.7268 ms | 124.4517 ms | 362.1581 ms | 149.5186 ms |
| `cold-start-to-first-batch-delivered` | 10 | 151.0818 ms | 158.3077 ms | 158.3077 ms | 150.3369 ms | 396.7115 ms | 176.7001 ms |

`QuickJs.memoryUsage` after module load:

| Field | Value |
| --- | ---: |
| `memoryUsedSize` | 4458076 bytes |
| `memoryAllocatedSize` | 5370144 bytes |
| `objectsCount` | 12661 |
| `objectsSize` | 911592 bytes |
| `stringsCount` | 32 |
| `stringsSize` | 1422 bytes |
| `jsFunctionsCount` | 8413 |
| `jsFunctionsCodeSize` | 482249 bytes |
| `propertiesSize` | 745424 bytes |
| `arraysCount` | 511 |

`QuickJs.memoryUsage` after the first composition:

| Field | Value |
| --- | ---: |
| `memoryUsedSize` | 5447887 bytes |
| `memoryAllocatedSize` | 6496496 bytes |
| `objectsCount` | 19394 |
| `objectsSize` | 1396368 bytes |
| `stringsCount` | 391 |
| `stringsSize` | 8762 bytes |
| `jsFunctionsCount` | 8397 |
| `jsFunctionsCodeSize` | 481966 bytes |
| `propertiesSize` | 1098112 bytes |
| `arraysCount` | 1901 |

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
| `clock-overhead` | 1000 | 0.0406 ms | 0.0428 ms | 0.0460 ms | 0.0383 ms | 0.0854 ms | 0.0409 ms |
| `initial-composition-rows-23` | 50 | 38.2763 ms | 40.4361 ms | 40.6730 ms | 38.0264 ms | 40.6913 ms | 38.4990 ms |
| `recompose-row` | 200 | 1.6332 ms | 1.6701 ms | 1.7239 ms | 1.5994 ms | 4.2396 ms | 1.6476 ms |
| `recompose-total` | 200 | 1.8113 ms | 1.8560 ms | 1.9049 ms | 1.7703 ms | 1.9179 ms | 1.8148 ms |

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
| `clock-overhead` | 1000 | 0.0382 ms | 0.0401 ms | 0.0421 ms | 0.0366 ms | 0.0508 ms | 0.0383 ms |
| `initial-composition-rows-50` | 50 | 78.6292 ms | 81.5146 ms | 81.6764 ms | 77.4647 ms | 82.1361 ms | 78.9153 ms |
| `recompose-row` | 200 | 2.8793 ms | 2.9520 ms | 2.9828 ms | 2.8349 ms | 3.0427 ms | 2.8877 ms |
| `recompose-total` | 200 | 2.4287 ms | 2.5071 ms | 2.5307 ms | 2.3876 ms | 2.5602 ms | 2.4343 ms |

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
| 1 | 35 | 33 | 0.0437 ms | 0.1059 ms | 0.1068 ms | 0.0728 ms | 0.0943 ms | 0.1258 ms |
| 10 | 442 | 422 | 0.0474 ms | 0.9761 ms | 0.9583 ms | 0.6512 ms | 0.0935 ms | 0.7066 ms |
| 100 | 3657 | 3457 | 0.0760 ms | 7.5856 ms | 7.3775 ms | 4.5078 ms | 0.1163 ms | 4.5751 ms |
| 1000 | 36510 | 34510 | 0.3502 ms | 85.3075 ms | 80.4627 ms | 41.9353 ms | 0.3650 ms | 42.5533 ms |
| 572 | 20939 | 19795 | 0.2195 ms | 44.8274 ms | 43.4080 ms | 23.8477 ms | 0.2503 ms | 23.7365 ms |

### Encoding bake-off

Every candidate encodes the **same** batch of 572
changes. `Wire bytes` is what crosses `CallChannel`, which is a string channel -- so a
binary encoding pays a Base64 surcharge here and a textual one does not. `Encode` is
guest-side production cost; `Cross` is encode plus transport, end to end.

| Encoding | Payload bytes | Wire bytes | vs. today | Encode p50 | Cross p50 | vs. today |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `json-positional` | 9091 | 9091 | -54% | 1.14 ms | 1.23 ms | -95% |
| `json-positional-interned` | 7636 | 7636 | -61% | 1.52 ms | 1.61 ms | -93% |
| `json-v0-native` | 19795 | 19795 | +0% | 23.84 ms | 24.02 ms | +0% |
| `protobuf-base64` | 9561 | 12748 | -36% | 34.57 ms | 34.76 ms | +45% |
| `json-v0-kotlinx` | 20939 | 20939 | +6% | 44.23 ms | 44.49 ms | +85% |
| `cbor-base64` | 13905 | 18540 | -6% | 242.65 ms | 242.26 ms | +908% |

- **`json-v0-kotlinx`** -- ADR-004 section 2.2 as documented, through kotlinx.serialization's pure-Kotlin encoder. Not what ships; included as the baseline the schema was written against.
- **`json-v0-native`** -- What ships today: array polymorphism through encodeToDynamic plus QuickJS's native JSON.stringify, which is the path Zipline's CallChannel takes.
- **`json-positional`** -- Every change becomes a positional array, so no field names cross. Built as native JavaScript values and handed straight to JSON.stringify.
- **`json-positional-interned`** -- Positional, plus a modifier-chain table: each distinct chain crosses once and is referenced by index thereafter.
- **`protobuf-base64`** -- Protocol buffers over a schema mirror, because ADR-004's JsonElement values have no protocol-buffer representation. Base64 because CallChannel carries a string.
- **`cbor-base64`** -- Concise Binary Object Representation over the same schema mirror, same Base64 surcharge. Included so the answer covers binary formats generally, not just one.

## 0.4 -- Garbage-collection behaviour

Method: Host-forced QuickJs.gc() pauses (upper bound) plus the recomposition tail at each gcThreshold. NOT the patched-QuickJS JS_RunGC hook the appendix prefers; that needs a local native Zipline build and is recorded as outstanding work.

| `gcThreshold` | Recompose p50 | Recompose p99 | Recompose max | Forced pause p99 | Forced pause max | Heap used after churn |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 256 KiB | 1.607 ms | 1.672 ms | 1.697 ms | 1.702 ms | 2.112 ms | 5216016 bytes |
| 8192 KiB | 1.645 ms | 1.736 ms | 4.683 ms | 1.492 ms | 1.543 ms | 5215632 bytes |
| 16384 KiB | 1.622 ms | 1.693 ms | 1.732 ms | 1.555 ms | 4.940 ms | 5215200 bytes |

## Notes and caveats

- The gate device named in the roadmap appendix is a low-end 2022-tier Android phone. Numbers produced on any other host are NOT gate-valid; they establish the harness and bound expectations.
- Experiment 0.4 used the host-forced gc() fallback, not the patched-QuickJS hook.
- Experiment 0.3 reports end-to-end crossing plus bytes; the five-pass internal breakdown needs a locally patched Zipline build.

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
| Guest recomposition of the reference screen, 95th percentile (0.2) | 8.00 ms | 1.610 ms | within budget |
| Batch crossing, per-frame reading: a steady-state recomposition batch (0.3) | 4.00 ms | 0.179 ms | within budget |
| Batch crossing, per-screen reading: the whole initial batch (0.3) | 4.00 ms | 34.599 ms | **over budget** |
| Maximum garbage-collection pause under load, 99th percentile (0.4) | 16.70 ms | 5.908 ms | within budget |
| Cold start: module load plus first composition (0.1) | 500.00 ms | 157.977 ms | within budget |
| Cold start through the host holding the tree (composition plus initial crossing) (0.1 + 0.3) | 500.00 ms | 195.322 ms | within budget |

## 0.1 -- Cold-start cost

| Measure | Value |
| --- | ---: |
| Minified JavaScript | -1 bytes |
| Gzipped JavaScript | -1 bytes |
| QuickJS bytecode | 1149235 bytes |
| `.zipline` file as delivered | 1149255 bytes |

| Measurement | n | p50 | p95 | p99 | min | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `module-load` | 10 | 29.8230 ms | 32.7921 ms | 32.7921 ms | 26.1241 ms | 96.8538 ms | 36.5646 ms |
| `main-function` | 10 | 0.1283 ms | 0.1433 ms | 0.1433 ms | 0.1153 ms | 3.4886 ms | 0.4647 ms |
| `cold-start-to-first-composition` | 10 | 157.9774 ms | 167.6621 ms | 167.6621 ms | 153.2013 ms | 618.2191 ms | 204.8222 ms |
| `cold-start-to-first-batch-delivered` | 10 | 195.3219 ms | 203.8988 ms | 203.8988 ms | 187.4745 ms | 687.4040 ms | 244.1162 ms |

`QuickJs.memoryUsage` after module load:

| Field | Value |
| --- | ---: |
| `memoryUsedSize` | 4458076 bytes |
| `memoryAllocatedSize` | 5360060 bytes |
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
| `memoryUsedSize` | 5710667 bytes |
| `memoryAllocatedSize` | 6810917 bytes |
| `objectsCount` | 20613 |
| `objectsSize` | 1484136 bytes |
| `stringsCount` | 392 |
| `stringsSize` | 8806 bytes |
| `jsFunctionsCount` | 8397 |
| `jsFunctionsCodeSize` | 481966 bytes |
| `propertiesSize` | 1272400 bytes |
| `arraysCount` | 1904 |

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
| `clock-overhead` | 1000 | 0.0587 ms | 0.0663 ms | 0.0754 ms | 0.0546 ms | 6.8027 ms | 0.0664 ms |
| `initial-composition-rows-23` | 50 | 34.6502 ms | 35.6015 ms | 43.0121 ms | 34.1328 ms | 43.4577 ms | 35.1941 ms |
| `recompose-row` | 200 | 1.5319 ms | 1.6103 ms | 1.6293 ms | 1.4743 ms | 1.6333 ms | 1.5380 ms |
| `recompose-total` | 200 | 1.7166 ms | 1.8107 ms | 1.8322 ms | 1.6738 ms | 1.8344 ms | 1.7283 ms |

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
| `clock-overhead` | 1000 | 0.0616 ms | 0.0732 ms | 0.0789 ms | 0.0525 ms | 5.8729 ms | 0.0682 ms |
| `initial-composition-rows-50` | 50 | 72.2168 ms | 81.2484 ms | 82.2136 ms | 70.1073 ms | 85.7520 ms | 73.3882 ms |
| `recompose-row` | 200 | 2.7324 ms | 2.8634 ms | 2.8889 ms | 2.6364 ms | 2.9278 ms | 2.7486 ms |
| `recompose-total` | 200 | 2.3249 ms | 2.4352 ms | 2.5447 ms | 2.2323 ms | 15.8073 ms | 2.4090 ms |

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
| 1 | 35 | 33 | 0.0620 ms | 0.1192 ms | 0.1238 ms | 0.0932 ms | 0.1523 ms | 0.1793 ms |
| 10 | 442 | 422 | 0.0646 ms | 0.9697 ms | 0.9715 ms | 0.6714 ms | 0.2149 ms | 0.8749 ms |
| 100 | 3657 | 3457 | 0.0962 ms | 7.6570 ms | 7.6062 ms | 4.6737 ms | 0.2796 ms | 5.4887 ms |
| 1000 | 36510 | 34510 | 0.5266 ms | 80.7508 ms | 81.6630 ms | 45.2140 ms | 1.4028 ms | 50.3657 ms |
| 572 | 20939 | 19795 | 0.2877 ms | 48.1798 ms | 50.3714 ms | 28.3216 ms | 0.9780 ms | 34.5989 ms |

### Encoding bake-off

Every candidate encodes the **same** batch of 572
changes. `Wire bytes` is what crosses `CallChannel`, which is a string channel -- so a
binary encoding pays a Base64 surcharge here and a textual one does not. `Encode` is
guest-side production cost; `Cross` is encode plus transport, end to end.

| Encoding | Payload bytes | Wire bytes | vs. today | Encode p50 | Cross p50 | vs. today |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `json-positional` | 9091 | 9091 | -54% | 1.70 ms | 2.06 ms | -93% |
| `json-positional-interned` | 7636 | 7636 | -61% | 2.14 ms | 2.45 ms | -92% |
| `json-v0-native` | 19795 | 19795 | +0% | 45.67 ms | 29.76 ms | +0% |
| `protobuf-base64` | 9561 | 12748 | -36% | 41.65 ms | 45.49 ms | +53% |
| `json-v0-kotlinx` | 20939 | 20939 | +6% | 57.43 ms | 52.67 ms | +77% |
| `cbor-base64` | 13905 | 18540 | -6% | 304.49 ms | 301.33 ms | +912% |

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
| 256 KiB | 1.838 ms | 1.915 ms | 1.958 ms | 5.908 ms | 10.475 ms | 5215648 bytes |
| 8192 KiB | 2.012 ms | 2.203 ms | 15.119 ms | 4.704 ms | 5.267 ms | 5215728 bytes |
| 16384 KiB | 1.957 ms | 2.054 ms | 2.056 ms | 5.071 ms | 26.515 ms | 5215328 bytes |

## Notes and caveats

- Run on a physical or virtual Android device. Only a physical low-end device of the tier the Phase 0 harness appendix names produces gate-valid numbers; an emulator runs on the development machine's processor and is not that.
- Experiment 0.4 used the host-forced gc() fallback, not the patched-QuickJS hook.
- Experiment 0.1 reports no minified-JavaScript size on device; only the bytecode is shipped to a device, and the JavaScript size is recorded by the development host run.

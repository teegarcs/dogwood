# Project Dogwood -- Phase 0 Results

**Host:** emulator-4core-sensitivity-not-gate-valid

**Platform:** Android 15 (API 35), arm64-v8a, ranchu, 4 cores

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
| Guest recomposition of the reference screen, 95th percentile (0.2) | 8.00 ms | 2.366 ms | within budget |
| Batch crossing, per-frame reading: a steady-state recomposition batch (0.3) | 4.00 ms | 0.162 ms | within budget |
| Batch crossing, per-screen reading: the whole initial batch (0.3) | 4.00 ms | 24.706 ms | **over budget** |
| Maximum garbage-collection pause under load, 99th percentile (0.4) | 16.70 ms | 3.316 ms | within budget |
| Cold start: module load plus first composition (0.1) | 500.00 ms | 302.566 ms | within budget |
| Cold start through the host holding the tree (composition plus initial crossing) (0.1 + 0.3) | 500.00 ms | 353.411 ms | within budget |

## 0.1 -- Cold-start cost

| Measure | Value |
| --- | ---: |
| Minified JavaScript | -1 bytes |
| Gzipped JavaScript | -1 bytes |
| QuickJS bytecode | 1154642 bytes |
| `.zipline` file as delivered | 1154662 bytes |

| Measurement | n | p50 | p95 | p99 | min | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `module-load` | 10 | 39.1570 ms | 60.8216 ms | 60.8216 ms | 26.2381 ms | 126.4543 ms | 50.2845 ms |
| `main-function` | 10 | 0.1107 ms | 0.1771 ms | 0.1771 ms | 0.0935 ms | 0.2186 ms | 0.1289 ms |
| `cold-start-to-first-composition` | 10 | 302.5660 ms | 402.2798 ms | 402.2798 ms | 224.7677 ms | 842.4969 ms | 366.5759 ms |
| `cold-start-to-first-batch-delivered` | 10 | 353.4111 ms | 639.6084 ms | 639.6084 ms | 264.3879 ms | 1440.7300 ms | 494.6310 ms |

`QuickJs.memoryUsage` after module load:

| Field | Value |
| --- | ---: |
| `memoryUsedSize` | 4506346 bytes |
| `memoryAllocatedSize` | 5417434 bytes |
| `objectsCount` | 12908 |
| `objectsSize` | 929376 bytes |
| `stringsCount` | 32 |
| `stringsSize` | 1422 bytes |
| `jsFunctionsCount` | 8450 |
| `jsFunctionsCodeSize` | 484349 bytes |
| `propertiesSize` | 758352 bytes |
| `arraysCount` | 574 |

`QuickJs.memoryUsage` after the first composition:

| Field | Value |
| --- | ---: |
| `memoryUsedSize` | 5782873 bytes |
| `memoryAllocatedSize` | 6895203 bytes |
| `objectsCount` | 20970 |
| `objectsSize` | 1509840 bytes |
| `stringsCount` | 392 |
| `stringsSize` | 8806 bytes |
| `jsFunctionsCount` | 8434 |
| `jsFunctionsCodeSize` | 484066 bytes |
| `propertiesSize` | 1301104 bytes |
| `arraysCount` | 1967 |

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
| `clock-overhead` | 1000 | 0.0628 ms | 0.4560 ms | 2.2180 ms | 0.0423 ms | 11.2992 ms | 0.1735 ms |
| `initial-composition-rows-23` | 50 | 38.0045 ms | 49.3567 ms | 60.4682 ms | 34.5664 ms | 76.6725 ms | 39.9982 ms |
| `recompose-row` | 200 | 1.7158 ms | 2.3657 ms | 3.4887 ms | 1.4283 ms | 5.9513 ms | 1.8308 ms |
| `recompose-total` | 200 | 1.8049 ms | 2.4425 ms | 2.9849 ms | 1.5871 ms | 3.3333 ms | 1.8962 ms |

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
| `clock-overhead` | 1000 | 0.0463 ms | 0.0644 ms | 0.1062 ms | 0.0408 ms | 2.4161 ms | 0.0507 ms |
| `initial-composition-rows-50` | 50 | 78.6115 ms | 88.0777 ms | 92.1256 ms | 73.0091 ms | 134.4514 ms | 80.0378 ms |
| `recompose-row` | 200 | 2.7895 ms | 3.9791 ms | 7.7540 ms | 2.4950 ms | 19.6624 ms | 3.0348 ms |
| `recompose-total` | 200 | 2.2638 ms | 2.5920 ms | 3.0651 ms | 2.0873 ms | 9.2712 ms | 2.3329 ms |

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
| 1 | 35 | 33 | 0.0522 ms | 0.1202 ms | 0.1198 ms | 0.0842 ms | 0.1276 ms | 0.1625 ms |
| 10 | 442 | 422 | 0.0593 ms | 0.9463 ms | 0.9401 ms | 0.6343 ms | 0.1287 ms | 0.7411 ms |
| 100 | 3657 | 3457 | 0.0915 ms | 7.3768 ms | 7.2635 ms | 4.3667 ms | 0.3219 ms | 4.9075 ms |
| 1000 | 36510 | 34510 | 0.3782 ms | 83.9433 ms | 79.3584 ms | 39.8454 ms | 1.9326 ms | 46.8288 ms |
| 572 | 20939 | 19795 | 0.2389 ms | 43.3723 ms | 42.2998 ms | 22.7248 ms | 1.1660 ms | 24.7060 ms |

### Encoding bake-off

Every candidate encodes the **same** batch of 572
changes. `Wire bytes` is what crosses `CallChannel`, which is a string channel -- so a
binary encoding pays a Base64 surcharge here and a textual one does not. `Encode` is
guest-side production cost; `Cross` is encode plus transport, end to end.

| Encoding | Payload bytes | Wire bytes | vs. today | Encode p50 | Cross p50 | vs. today | Host decode p50 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `json-positional` | 9091 | 9091 | -54% | 1.25 ms | 1.50 ms | -94% | 1.09 ms |
| `json-positional-interned` | 7636 | 7636 | -61% | 1.60 ms | 1.78 ms | -93% | 0.96 ms |
| `json-v0-native` | 19795 | 19795 | +0% | 22.64 ms | 24.03 ms | +0% | 1.56 ms |
| `protobuf-base64` | 9561 | 12748 | -36% | 32.62 ms | 33.08 ms | +38% | not measured |
| `json-v0-kotlinx` | 20939 | 20939 | +6% | 43.53 ms | 44.87 ms | +87% | 2.14 ms |
| `cbor-base64` | 13905 | 18540 | -6% | 216.57 ms | 216.42 ms | +800% | not measured |

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
| 256 KiB | 1.573 ms | 4.627 ms | 4.802 ms | 3.172 ms | 5.140 ms | 5266502 bytes |
| 8192 KiB | 1.518 ms | 2.380 ms | 7.378 ms | 3.316 ms | 3.865 ms | 5266118 bytes |
| 16384 KiB | 1.533 ms | 1.698 ms | 1.745 ms | 2.996 ms | 14.240 ms | 5265014 bytes |

## Notes and caveats

- Run on a physical or virtual Android device. Only a physical low-end device of the tier the Phase 0 harness appendix names produces gate-valid numbers; an emulator runs on the development machine's processor and is not that.
- Experiment 0.4 used the host-forced gc() fallback, not the patched-QuickJS hook.
- Experiment 0.1 reports no minified-JavaScript size on device; only the bytecode is shipped to a device, and the JavaScript size is recorded by the development host run.

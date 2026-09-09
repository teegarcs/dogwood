# Project Dogwood -- Phase 0 Results

**Host:** emulator-1core-sensitivity-not-gate-valid

**Platform:** Android 15 (API 35), arm64-v8a, ranchu, 1 cores

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
| Guest recomposition of the reference screen, 95th percentile (0.2) | 8.00 ms | 6.075 ms | within budget |
| Batch crossing, per-frame reading: a steady-state recomposition batch (0.3) | 4.00 ms | 0.148 ms | within budget |
| Batch crossing, per-screen reading: the whole initial batch (0.3) | 4.00 ms | 47.191 ms | **over budget** |
| Maximum garbage-collection pause under load, 99th percentile (0.4) | 16.70 ms | 11.478 ms | within budget |
| Cold start: module load plus first composition (0.1) | 500.00 ms | 970.492 ms | **over budget** |
| Cold start through the host holding the tree (composition plus initial crossing) (0.1 + 0.3) | 500.00 ms | 1273.035 ms | **over budget** |

## 0.1 -- Cold-start cost

| Measure | Value |
| --- | ---: |
| Minified JavaScript | -1 bytes |
| Gzipped JavaScript | -1 bytes |
| QuickJS bytecode | 1154642 bytes |
| `.zipline` file as delivered | 1154662 bytes |

| Measurement | n | p50 | p95 | p99 | min | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `module-load` | 10 | 126.4563 ms | 152.8073 ms | 152.8073 ms | 102.9496 ms | 227.7809 ms | 136.5446 ms |
| `main-function` | 10 | 0.0997 ms | 0.1532 ms | 0.1532 ms | 0.0802 ms | 0.2327 ms | 0.1179 ms |
| `cold-start-to-first-composition` | 10 | 970.4920 ms | 1227.0617 ms | 1227.0617 ms | 886.2805 ms | 1396.0147 ms | 1045.1411 ms |
| `cold-start-to-first-batch-delivered` | 10 | 1273.0351 ms | 1735.3889 ms | 1735.3889 ms | 1195.5569 ms | 1808.5095 ms | 1405.3653 ms |

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
| `memoryUsedSize` | 5782481 bytes |
| `memoryAllocatedSize` | 6894795 bytes |
| `objectsCount` | 20969 |
| `objectsSize` | 1509768 bytes |
| `stringsCount` | 392 |
| `stringsSize` | 8806 bytes |
| `jsFunctionsCount` | 8434 |
| `jsFunctionsCodeSize` | 484066 bytes |
| `propertiesSize` | 1301024 bytes |
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
| `clock-overhead` | 1000 | 0.1279 ms | 4.6683 ms | 13.0737 ms | 0.0819 ms | 83.5510 ms | 0.8772 ms |
| `initial-composition-rows-23` | 50 | 81.1935 ms | 252.8987 ms | 324.6098 ms | 63.4365 ms | 326.3328 ms | 126.1482 ms |
| `recompose-row` | 200 | 1.3874 ms | 6.0748 ms | 9.3704 ms | 1.3002 ms | 12.4067 ms | 3.0091 ms |
| `recompose-total` | 200 | 1.6724 ms | 8.2415 ms | 15.2803 ms | 1.4688 ms | 19.8569 ms | 3.6441 ms |

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
| `clock-overhead` | 1000 | 0.0439 ms | 0.0570 ms | 3.9816 ms | 0.0403 ms | 6.9933 ms | 0.1136 ms |
| `initial-composition-rows-50` | 50 | 145.5203 ms | 205.3347 ms | 242.8495 ms | 135.6134 ms | 287.3899 ms | 158.9769 ms |
| `recompose-row` | 200 | 6.4806 ms | 9.3041 ms | 10.5494 ms | 2.4297 ms | 10.8001 ms | 5.3866 ms |
| `recompose-total` | 200 | 4.3300 ms | 15.1342 ms | 19.7267 ms | 2.0086 ms | 26.8054 ms | 5.2721 ms |

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
| 1 | 35 | 33 | 0.0507 ms | 0.1141 ms | 0.1140 ms | 0.0808 ms | 0.1192 ms | 0.1483 ms |
| 10 | 442 | 422 | 0.0560 ms | 0.9004 ms | 0.8851 ms | 0.5960 ms | 0.1247 ms | 0.7039 ms |
| 100 | 3657 | 3457 | 0.0860 ms | 14.5680 ms | 14.4768 ms | 8.1469 ms | 0.2725 ms | 8.5103 ms |
| 1000 | 36510 | 34510 | 0.3447 ms | 159.5330 ms | 151.1098 ms | 75.6966 ms | 2.9488 ms | 84.1632 ms |
| 572 | 20939 | 19795 | 0.2127 ms | 81.9705 ms | 80.8994 ms | 43.7450 ms | 1.0481 ms | 47.1906 ms |

### Encoding bake-off

Every candidate encodes the **same** batch of 572
changes. `Wire bytes` is what crosses `CallChannel`, which is a string channel -- so a
binary encoding pays a Base64 surcharge here and a textual one does not. `Encode` is
guest-side production cost; `Cross` is encode plus transport, end to end.

| Encoding | Payload bytes | Wire bytes | vs. today | Encode p50 | Cross p50 | vs. today | Host decode p50 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `json-positional` | 9091 | 9091 | -54% | 1.15 ms | 1.37 ms | -97% | 1.03 ms |
| `json-positional-interned` | 7636 | 7636 | -61% | 1.47 ms | 1.71 ms | -96% | 0.86 ms |
| `json-v0-native` | 19795 | 19795 | +0% | 42.94 ms | 44.91 ms | +0% | 1.43 ms |
| `protobuf-base64` | 9561 | 12748 | -36% | 60.18 ms | 61.00 ms | +36% | not measured |
| `json-v0-kotlinx` | 20939 | 20939 | +6% | 82.22 ms | 84.81 ms | +89% | 3.99 ms |
| `cbor-base64` | 13905 | 18540 | -6% | 404.12 ms | 402.05 ms | +795% | not measured |

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
| 256 KiB | 1.726 ms | 17.670 ms | 29.528 ms | 11.478 ms | 14.694 ms | 5266406 bytes |
| 8192 KiB | 1.476 ms | 9.550 ms | 14.038 ms | 9.298 ms | 9.403 ms | 5266310 bytes |
| 16384 KiB | 1.487 ms | 9.353 ms | 9.505 ms | 6.687 ms | 24.807 ms | 5266510 bytes |

## Notes and caveats

- Run on a physical or virtual Android device. Only a physical low-end device of the tier the Phase 0 harness appendix names produces gate-valid numbers; an emulator runs on the development machine's processor and is not that.
- Experiment 0.4 used the host-forced gc() fallback, not the patched-QuickJS hook.
- Experiment 0.1 reports no minified-JavaScript size on device; only the bytecode is shipped to a device, and the JavaScript size is recorded by the development host run.

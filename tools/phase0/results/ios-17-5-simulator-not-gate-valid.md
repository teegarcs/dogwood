# Project Dogwood -- Phase 0 Results

**Host:** iOS 17.5 simulator (not gate-valid)

**Platform:** Version 17.5 (Build 21F79), 8 cores, Kotlin/Native

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
| Guest recomposition of the reference screen, 95th percentile (0.2) | 8.00 ms | 1.536 ms | within budget |
| Batch crossing, per-frame reading: a steady-state recomposition batch (0.3) | 4.00 ms | 0.120 ms | within budget |
| Batch crossing, per-screen reading: the whole initial batch (0.3) | 4.00 ms | 22.065 ms | **over budget** |
| Maximum garbage-collection pause under load, 99th percentile (0.4) | 16.70 ms | 1.405 ms | within budget |
| Cold start: module load plus first composition (0.1) | 500.00 ms | 114.679 ms | within budget |
| Cold start through the host holding the tree (composition plus initial crossing) (0.1 + 0.3) | 500.00 ms | 139.764 ms | within budget |

## 0.1 -- Cold-start cost

| Measure | Value |
| --- | ---: |
| Minified JavaScript | -1 bytes |
| Gzipped JavaScript | -1 bytes |
| QuickJS bytecode | 1154642 bytes |
| `.zipline` file as delivered | 1154662 bytes |

| Measurement | n | p50 | p95 | p99 | min | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `module-load` | 10 | 14.1693 ms | 15.4920 ms | 15.4920 ms | 13.9555 ms | 15.7238 ms | 14.5859 ms |
| `main-function` | 10 | 0.0581 ms | 0.0653 ms | 0.0653 ms | 0.0556 ms | 0.0663 ms | 0.0599 ms |
| `cold-start-to-first-composition` | 10 | 114.6786 ms | 117.3678 ms | 117.3678 ms | 113.7402 ms | 119.8755 ms | 115.7927 ms |
| `cold-start-to-first-batch-delivered` | 10 | 139.7638 ms | 141.5080 ms | 141.5080 ms | 137.5002 ms | 144.6365 ms | 139.8792 ms |

`QuickJs.memoryUsage` after module load:

| Field | Value |
| --- | ---: |
| `memoryUsedSize` | 4506346 bytes |
| `memoryAllocatedSize` | 5282992 bytes |
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
| `memoryUsedSize` | 5525605 bytes |
| `memoryAllocatedSize` | 6419008 bytes |
| `objectsCount` | 19776 |
| `objectsSize` | 1423872 bytes |
| `stringsCount` | 391 |
| `stringsSize` | 8762 bytes |
| `jsFunctionsCount` | 8434 |
| `jsFunctionsCodeSize` | 484066 bytes |
| `propertiesSize` | 1130528 bytes |
| `arraysCount` | 1964 |

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
| `clock-overhead` | 1000 | 0.0367 ms | 0.0397 ms | 0.0415 ms | 0.0327 ms | 0.0805 ms | 0.0368 ms |
| `initial-composition-rows-23` | 50 | 35.1625 ms | 37.5938 ms | 37.8451 ms | 34.5783 ms | 37.9042 ms | 35.3486 ms |
| `recompose-row` | 200 | 1.4709 ms | 1.5360 ms | 1.5984 ms | 1.4393 ms | 1.6268 ms | 1.4780 ms |
| `recompose-total` | 200 | 1.6506 ms | 1.7525 ms | 1.8070 ms | 1.6148 ms | 1.9192 ms | 1.6635 ms |

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
| `clock-overhead` | 1000 | 0.0369 ms | 0.0392 ms | 0.0411 ms | 0.0336 ms | 0.0990 ms | 0.0371 ms |
| `initial-composition-rows-50` | 50 | 71.2510 ms | 74.3126 ms | 75.4725 ms | 70.5707 ms | 75.9698 ms | 71.8665 ms |
| `recompose-row` | 200 | 2.6168 ms | 2.7892 ms | 2.9148 ms | 2.5796 ms | 2.9894 ms | 2.6461 ms |
| `recompose-total` | 200 | 2.1940 ms | 2.5448 ms | 2.8271 ms | 2.1587 ms | 5.3489 ms | 2.2566 ms |

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
| 1 | 35 | 33 | 0.0435 ms | 0.1033 ms | 0.1045 ms | 0.0712 ms | 0.0862 ms | 0.1203 ms |
| 10 | 442 | 422 | 0.0466 ms | 0.9020 ms | 0.8846 ms | 0.5940 ms | 0.0915 ms | 0.6460 ms |
| 100 | 3657 | 3457 | 0.0719 ms | 6.9928 ms | 6.7555 ms | 4.0872 ms | 0.1442 ms | 4.2610 ms |
| 1000 | 36510 | 34510 | 0.3183 ms | 91.7584 ms | 81.6878 ms | 37.9971 ms | 0.6292 ms | 39.2900 ms |
| 572 | 20939 | 19795 | 0.2025 ms | 41.2103 ms | 39.6853 ms | 21.5593 ms | 0.3921 ms | 22.0648 ms |

### Encoding bake-off

Every candidate encodes the **same** batch of 572
changes. `Wire bytes` is what crosses `CallChannel`, which is a string channel -- so a
binary encoding pays a Base64 surcharge here and a textual one does not. `Encode` is
guest-side production cost; `Cross` is encode plus transport, end to end.

| Encoding | Payload bytes | Wire bytes | vs. today | Encode p50 | Cross p50 | vs. today | Host decode p50 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `json-positional` | 9091 | 9091 | -54% | 1.02 ms | 1.14 ms | -95% | 0.35 ms |
| `json-positional-interned` | 7636 | 7636 | -61% | 1.37 ms | 1.50 ms | -93% | 0.31 ms |
| `json-v0-native` | 19795 | 19795 | +0% | 21.44 ms | 21.78 ms | +0% | 0.42 ms |
| `protobuf-base64` | 9561 | 12748 | -36% | 31.31 ms | 31.35 ms | +44% | not measured |
| `json-v0-kotlinx` | 20939 | 20939 | +6% | 41.27 ms | 41.29 ms | +90% | 0.47 ms |
| `cbor-base64` | 13905 | 18540 | -6% | 215.24 ms | 216.35 ms | +893% | not measured |

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
| 256 KiB | 1.475 ms | 1.558 ms | 1.618 ms | 1.384 ms | 1.823 ms | 5264094 bytes |
| 8192 KiB | 1.496 ms | 1.639 ms | 1.789 ms | 1.329 ms | 1.385 ms | 5264102 bytes |
| 16384 KiB | 1.505 ms | 1.737 ms | 1.988 ms | 1.405 ms | 4.419 ms | 5264078 bytes |

## Notes and caveats

- The gate device named in the roadmap appendix is a low-end 2022-tier Android phone. A simulator on an Apple-silicon Macintosh runs on the development machine's processor and is NOT gate-valid; it establishes the iOS path and bounds expectations.
- Experiment 0.4 used the host-forced gc() fallback, not the patched-QuickJS hook.
- Experiment 0.1's minified-JavaScript sizes are absent here: they are properties of the build, measured once on the development host, not of this machine.

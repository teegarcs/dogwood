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
| Guest recomposition of the reference screen, 95th percentile (0.2) | 8.00 ms | 1.782 ms | within budget |
| Batch crossing, per-frame reading: a steady-state recomposition batch (0.3) | 4.00 ms | 0.127 ms | within budget |
| Batch crossing, per-screen reading: the whole initial batch (0.3) | 4.00 ms | 24.083 ms | **over budget** |
| Maximum garbage-collection pause under load, 99th percentile (0.4) | 16.70 ms | 1.820 ms | within budget |
| Cold start: module load plus first composition (0.1) | 500.00 ms | 127.301 ms | within budget |
| Cold start through the host holding the tree (composition plus initial crossing) (0.1 + 0.3) | 500.00 ms | 153.894 ms | within budget |

## 0.1 -- Cold-start cost

| Measure | Value |
| --- | ---: |
| Minified JavaScript | 2550994 bytes |
| Gzipped JavaScript | 334393 bytes |
| QuickJS bytecode | 1149892 bytes |
| `.zipline` file as delivered | 1149912 bytes |

| Measurement | n | p50 | p95 | p99 | min | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `module-load` | 10 | 15.2044 ms | 15.4393 ms | 15.4393 ms | 15.0709 ms | 16.4264 ms | 15.3245 ms |
| `main-function` | 10 | 0.0711 ms | 0.0793 ms | 0.0793 ms | 0.0664 ms | 2.4022 ms | 0.3043 ms |
| `cold-start-to-first-composition` | 10 | 127.3009 ms | 132.3965 ms | 132.3965 ms | 126.0168 ms | 353.0644 ms | 150.2074 ms |
| `cold-start-to-first-batch-delivered` | 10 | 153.8938 ms | 160.3100 ms | 160.3100 ms | 152.1330 ms | 388.8693 ms | 177.8740 ms |

`QuickJs.memoryUsage` after module load:

| Field | Value |
| --- | ---: |
| `memoryUsedSize` | 4464660 bytes |
| `memoryAllocatedSize` | 5377744 bytes |
| `objectsCount` | 12696 |
| `objectsSize` | 914112 bytes |
| `stringsCount` | 32 |
| `stringsSize` | 1422 bytes |
| `jsFunctionsCount` | 8418 |
| `jsFunctionsCodeSize` | 482477 bytes |
| `propertiesSize` | 747216 bytes |
| `arraysCount` | 520 |

`QuickJs.memoryUsage` after the first composition:

| Field | Value |
| --- | ---: |
| `memoryUsedSize` | 5457423 bytes |
| `memoryAllocatedSize` | 6507472 bytes |
| `objectsCount` | 19445 |
| `objectsSize` | 1400040 bytes |
| `stringsCount` | 391 |
| `stringsSize` | 8762 bytes |
| `jsFunctionsCount` | 8402 |
| `jsFunctionsCodeSize` | 482194 bytes |
| `propertiesSize` | 1102016 bytes |
| `arraysCount` | 1910 |

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
| `clock-overhead` | 1000 | 0.0410 ms | 0.0436 ms | 0.0472 ms | 0.0390 ms | 0.0702 ms | 0.0412 ms |
| `initial-composition-rows-23` | 50 | 38.7470 ms | 40.9387 ms | 41.6967 ms | 38.2496 ms | 41.7355 ms | 39.0200 ms |
| `recompose-row` | 200 | 1.6515 ms | 1.7815 ms | 1.8264 ms | 1.5863 ms | 4.7189 ms | 1.6781 ms |
| `recompose-total` | 200 | 1.8195 ms | 1.8777 ms | 1.9097 ms | 1.7828 ms | 1.9368 ms | 1.8245 ms |

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
| `clock-overhead` | 1000 | 0.0387 ms | 0.0427 ms | 0.0475 ms | 0.0366 ms | 0.1420 ms | 0.0394 ms |
| `initial-composition-rows-50` | 50 | 79.8681 ms | 82.1064 ms | 82.8805 ms | 78.6002 ms | 82.9958 ms | 80.0563 ms |
| `recompose-row` | 200 | 2.9215 ms | 2.9750 ms | 3.0335 ms | 2.8663 ms | 3.1913 ms | 2.9259 ms |
| `recompose-total` | 200 | 2.4806 ms | 2.5558 ms | 2.5920 ms | 2.4343 ms | 2.6419 ms | 2.4863 ms |

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
| 1 | 35 | 33 | 0.0438 ms | 0.1062 ms | 0.1074 ms | 0.0726 ms | 0.0943 ms | 0.1273 ms |
| 10 | 442 | 422 | 0.0490 ms | 1.0066 ms | 0.9884 ms | 0.6639 ms | 0.0996 ms | 0.7270 ms |
| 100 | 3657 | 3457 | 0.0761 ms | 7.6784 ms | 7.4390 ms | 4.5176 ms | 0.1159 ms | 4.6188 ms |
| 1000 | 36510 | 34510 | 0.3556 ms | 86.8403 ms | 83.0531 ms | 42.3532 ms | 0.3804 ms | 42.6580 ms |
| 572 | 20939 | 19795 | 0.2202 ms | 44.6851 ms | 43.1703 ms | 23.7473 ms | 0.2608 ms | 24.0826 ms |

### Encoding bake-off

Every candidate encodes the **same** batch of 572
changes. `Wire bytes` is what crosses `CallChannel`, which is a string channel -- so a
binary encoding pays a Base64 surcharge here and a textual one does not. `Encode` is
guest-side production cost; `Cross` is encode plus transport, end to end.

| Encoding | Payload bytes | Wire bytes | vs. today | Encode p50 | Cross p50 | vs. today | Host decode p50 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `json-positional` | 9091 | 9091 | -54% | 1.14 ms | 1.23 ms | -95% | 0.17 ms |
| `json-positional-interned` | 7636 | 7636 | -61% | 1.52 ms | 1.62 ms | -93% | 0.11 ms |
| `json-v0-native` | 19795 | 19795 | +0% | 24.04 ms | 24.06 ms | +0% | 0.15 ms |
| `protobuf-base64` | 9561 | 12748 | -36% | 35.06 ms | 35.24 ms | +46% | not measured |
| `json-v0-kotlinx` | 20939 | 20939 | +6% | 45.67 ms | 45.66 ms | +90% | 0.20 ms |
| `cbor-base64` | 13905 | 18540 | -6% | 243.80 ms | 244.24 ms | +915% | not measured |

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
| 256 KiB | 1.635 ms | 1.726 ms | 1.796 ms | 1.517 ms | 2.291 ms | 5222216 bytes |
| 8192 KiB | 1.658 ms | 1.750 ms | 4.722 ms | 1.620 ms | 1.703 ms | 5222608 bytes |
| 16384 KiB | 1.613 ms | 1.698 ms | 1.703 ms | 1.820 ms | 5.105 ms | 5222384 bytes |

## Notes and caveats

- The gate device named in the roadmap appendix is a low-end 2022-tier Android phone. Numbers produced on any other host are NOT gate-valid; they establish the harness and bound expectations.
- Experiment 0.4 used the host-forced gc() fallback, not the patched-QuickJS hook.
- Experiment 0.3 reports end-to-end crossing plus bytes; the five-pass internal breakdown needs a locally patched Zipline build.

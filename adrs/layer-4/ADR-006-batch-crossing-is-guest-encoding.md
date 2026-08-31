# ADR-006: The Batch Crossing Is Guest-Side Encoding, Not Transport

**Date:** 2026-08-31
**Status:** Accepted for §2.1, §2.2, and §2.4 (ruled 2026-08-31). §2.3's v1 encoding question is
**resolved by [ADR-007](ADR-007-v1-wire-format-positional-json.md)**, which also **withdraws this
document's "linear in bytes" claim** — see §1. No leg is settled against the *named* gate device,
which has not been run.

## 1. Context & Problem Statement

Experiment 0.3 was specified to answer "protocol cost per frame," with the note that "every
crossing is one JavaScript Object Notation (JSON) string; the cost that matters is per byte."
It has now been run. The measurement says something sharper than the specification
anticipated, and it changes where optimisation effort belongs.

All figures below are the **development host** (Apple silicon MacBook Pro, Java Development
Kit (JDK) 21, Zipline 1.27.0, `androidx.compose.runtime:runtime-js` 1.12.0), for the 23-row
reference screen's initial batch: **572 changes, 19,795 bytes**. This machine is substantially
faster than the gate device named in the Phase 0 harness appendix, so these numbers are a
**lower bound** on what a low-end Android phone will show. They are not gate-valid and they do
not close the gate; they are alarming enough to record before anyone builds Phase 1 on the
current shape.

| Leg | p50 | Share of the crossing |
| --- | ---: | ---: |
| `sendChanges(batch)` end to end — the real call | **24.06 ms** | 100% |
| Guest-side encoding alone, via Zipline's own path | **23.92 ms** | **99.4%** |
| Transport alone — sending an already-built string of the same size | **0.25 ms** | 1.0% |
| Building the `List<Change>` | 0.22 ms | 0.9% |

The 0.3 gate leg is "the 150-node **batch crossing** is ≤ 4 ms end to end." Measured: 24.06 ms
at p50, 26.98 ms at p95, 29.85 ms at p99. **The leg fails by a factor of six, on hardware
faster than the device the gate is defined on.**

Three further measurements bear on what to do about it.

**Encoder choice matters more than schema choice.** The same batch, three ways:

| Encoding | p50 | Bytes |
| --- | ---: | ---: |
| `kotlinx.serialization` pure-Kotlin encoder, ADR-004's class discriminator | 45.00 ms | 20,939 |
| `kotlinx.serialization` pure-Kotlin encoder, array polymorphism | 43.66 ms | 19,795 |
| `encodeToDynamic` plus QuickJS's native `JSON.stringify`, array polymorphism | **23.92 ms** | 19,795 |

Array polymorphism buys 3.0% of time and 5.5% of bytes. Reaching the interpreter's **native**
`JSON.stringify` buys **47%**. That is not a Dogwood invention; it is what Zipline already
does. `Endpoint.json` sets `useArrayPolymorphism = true`, and on Kotlin/JavaScript
`encodeToStringFast` is literally `JSON.stringify(encodeToDynamic(serializer, value))`
([`jsonJs.kt`](https://github.com/cashapp/zipline/blob/1.27.0/zipline/src/jsMain/kotlin/app/cash/zipline/internal/jsonJs.kt)).
The measured native-encode figure and the measured end-to-end figure agree to within 0.6%,
which is what confirms the decomposition rather than assuming it.

**Cost tracked bytes across the encodings compared here.** 1.21 microseconds per byte at 572
changes, 1.24 at 1,000.

> **Withdrawn by [ADR-007](ADR-007-v1-wire-format-positional-json.md).** Read as a causal law —
> "halve the bytes, halve the time" — this is wrong, and the bake-off in ADR-007 falsifies it
> directly: one candidate produces 7% fewer bytes and takes 31% longer, another 36% fewer wire
> bytes and 45% longer. Bytes and time correlated here only because every encoding compared in
> this ADR walked `kotlinx.serialization`'s pure-Kotlin serializers. **The governing variable is
> how much interpreted Kotlin runs during encoding.**

**Steady state is not the problem.** A one-change batch — the shape a real recomposition
produces — crosses in **0.144 ms** at p50. The reference screen's *recomposition* batches are
one and two changes. What costs 24 ms is the **initial** batch, produced once when a screen
opens.

## 2. Decision

**2.1 The guest crosses batches as typed service arguments, never as pre-encoded strings.**
`DogwoodHost.sendChanges(batch: ChangeBatch)` stays exactly as
[ADR-004](ADR-004-change-event-protocol-v0.md) §2.3 defines it, and guest code must not call
`Json.encodeToString` on the hot path and hand over a `String`. Doing so would cost 45 ms
where Zipline's own path costs 24 ms, for identical bytes. This inverts the intuition that
"encode it yourself and send bytes" is the cheaper route: on Kotlin/JavaScript the
interpreter's C implementation of `JSON.stringify` is the fast path, and only Zipline's
encoder reaches it.

The harness's `sendChangesEncoded(json: String)` method exists **only** to isolate transport
for this measurement and is not part of the Layer 4 boundary.

**2.2 ADR-004 §2.4's worked example is documentation of the schema, not of the wire.** The
bytes that actually cross are array-polymorphic — `["c",{"i":1,"w":1}]` rather than
`{"k":"c","i":1,"w":1}` — because Zipline's `CallChannel` re-encodes with
`useArrayPolymorphism = true` regardless of what `DogwoodJson` is configured to do. ADR-004
is amended to say so, so that nobody debugs against a rendering that never appears on the
boundary and nobody attributes the class discriminator's cost to Dogwood's design.

**2.3 A binary wire format is not available without patching Zipline, and must stop being
listed as a cheap escape hatch.** ADR-004 §4 assumed "if 0.3's byte counts blow the ≤ 4 ms
gate leg, the v1 revision considers a binary encoding." Zipline's boundary is
`CallChannel.call(callJson: String): String` — a string in, a string out. A binary payload
would have to be text-encoded to cross it, which *adds* bytes to a cost that is linear in
bytes. The real candidates are therefore, in order of evidence:

1. ~~**Send fewer bytes.**~~ **Superseded by [ADR-007](ADR-007-v1-wire-format-positional-json.md).**
   The winning lever turned out to be getting encoding work out of the interpreter, not shaving
   the schema. Positional JSON built as native JavaScript values and handed to QuickJS's own
   `JSON.stringify` measured **1.23 ms against 24.02 ms**, a 95% reduction, of which the 54%
   byte reduction is the smaller half of the story. Chain interning — listed here as an obvious
   win — was measured and **rejected**: it costs more interpreted work than the repetition it
   removes.
2. **Send fewer changes in the first crossing** — slice the initial batch, so a screen's first
   paint does not wait on all 572 changes. Still available; no longer needed at these numbers.
3. ~~**Patch or extend Zipline's channel** to carry bytes.~~ **Closed.** ADR-007 measured
   protocol buffers and Concise Binary Object Representation (CBOR) and both are *slower* than
   the JSON they would replace, because their encoders are pure Kotlin and run interpreted. A
   byte channel would remove the Base64 surcharge but not the encoder cost that dominates.

**2.4 The gate leg's reading was escalated rather than renegotiated, and has now been ruled.**
The leg was reported **FAILED** as written, because roadmap.md says thresholds "may be
renegotiated *before* the experiments run — never after seeing the numbers," and that rule is
worth more than this result. The specification was genuinely ambiguous about which cost the
4 ms bounds, so both readings were measured and the ruling was left to a person.

**Ruling (2026-08-31): the 4 ms bounds the per-tap crossing.** That leg measures 0.12 ms on the
development host and 0.17 ms on a Pixel 10 Pro, and passes. The whole-screen initial batch is a
once-per-screen cost carried by the cold-start budget, and is to be driven down as far as it
will go under §2.3 rather than treated as satisfied. The two readings were:

- Read as **per-frame**, the leg belongs to steady-state recomposition batches, which measure
  **0.124 ms** and pass with three orders of magnitude to spare.
- Read as **the initial batch**, the leg measures **24.34 ms** and fails. That cost is paid
  once per screen open, so under the per-frame reading it does not disappear — it moves to the
  cold-start budget, where it is now **measured rather than assumed**: cold start through the
  moment the host holds the whole tree (module load, `main()`, first composition, and the
  initial crossing) is **154.8 ms** at p50 and 161.7 ms at p95, against a 500 ms budget.

The harness reports every one of those numbers and evaluates the 0.3 leg under both readings,
so the ruling was made on evidence rather than on which number someone saw first.

Note the shape of the honesty risk, since the author of this ADR is also the holder of the
failing number: the per-frame reading is the one that makes the phase pass, and it was therefore
the reading that deserved the most scepticism. What keeps it from being a free pass is the
`coldStartToFirstBatchDelivered` leg — the ruling obliges the project to carry the initial batch
in the cold-start budget, and that budget is now instrumented so the cost cannot quietly go
unwatched.

## 3. Rationale & Research

The decomposition is measured, not inferred. `crossPreEncoded` sends a string of exactly the
size the batch encodes to and does nothing else, isolating `CallChannel` transport at 0.25 ms;
`stringifyNative` performs, inside the guest, the same `encodeToDynamic` plus `JSON.stringify`
that Zipline performs, timed by the same monotonic clock. Their sum reproduces the end-to-end
figure. Each series is 200 samples after 20 warm-ups, with the clock's own round-trip cost
(p50 0.040 ms) subtracted, per the Phase 0 harness appendix.

The Zipline internals cited are read from the pinned 1.27.0 tag:
[`Endpoint.kt`](https://github.com/cashapp/zipline/blob/1.27.0/zipline/src/commonMain/kotlin/app/cash/zipline/internal/bridge/Endpoint.kt)
for `useArrayPolymorphism = true`,
[`jsonJs.kt`](https://github.com/cashapp/zipline/blob/1.27.0/zipline/src/jsMain/kotlin/app/cash/zipline/internal/jsonJs.kt)
for `encodeToStringFast`, and
[`CallCodec.kt`](https://github.com/cashapp/zipline/blob/1.27.0/zipline/src/commonMain/kotlin/app/cash/zipline/internal/bridge/CallCodec.kt)
for where the call is encoded.

The finding is consistent with [ADR-002](ADR-002-adopt-zipline-quickjs-substrate.md)'s
conclusion that "batching, not the engine, is what matters," and sharpens it: batching matters
because **the per-crossing cost is the encoding, and the encoding is per byte**. It also
retires, with a number, the worry that the Java Native Interface (JNI) transcode or the
`CallChannel` hop would dominate. They do not; together they are one percent.

## 4. Unstated Assumptions

- ~~**Assumes the ratio between encoding and transport holds on the gate device.**~~ **Checked
  on a physical Pixel 10 Pro (Tensor G5, Android 17), and the two legs do *not* scale together.**
  Guest-side encoding is essentially identical to the development host (1.04×), while transport
  of the same pre-built string across the Java Native Interface (JNI) is 3.42× slower. Encoding
  still dominates — 24.98 ms of a 27.92 ms crossing, 89% — but the split moves with the
  platform, so it must be re-measured on the gate device rather than carried over. The wider
  finding is recorded in [roadmap.md](../../roadmap.md): interpreted guest work is bound by
  single-core instruction throughput and is otherwise hardware-independent across these hosts.
- **Assumes `encodeToDynamic` remains Zipline's Kotlin/JavaScript path.** Zipline's own source
  marks it `@OptIn(ExperimentalSerializationApi::class)` with a note that Zipline must track
  changes to it. A `kotlinx.serialization` change could take the 47% back.
- **Assumes compression is not available on this path.** The initial batch compresses
  7.5-fold (19,795 bytes to 2,647), but `CallChannel` carries an uncompressed string, and
  compressing in the guest would add interpreted work to a leg already dominated by
  interpreted work.
- **Assumes the initial batch must be one crossing.** Slicing it is candidate 2 above and is
  untested.

## 5. Updated Documents

- [adrs/layer-4/ADR-004-change-event-protocol-v0.md](ADR-004-change-event-protocol-v0.md) —
  §2.4 relabelled as schema documentation; §4's binary-encoding assumption corrected
- [specs/layer-4-sandbox.md](../../specs/layer-4-sandbox.md) — Interfaces & Boundary: what the
  boundary actually encodes, and where the cost is
- [roadmap.md](../../roadmap.md) — Phase 0 gate: the 0.3 leg's two readings and the escalation
- [tools/phase0/README.md](../../tools/phase0/README.md) — the harness that produced this
- [tools/phase0/results/](../../tools/phase0/results/) — the raw measurements

# ADR-007: The v1 Wire Format Is Positional JSON Built as Native JavaScript Values; Binary Encodings Are Rejected

**Date:** 2026-08-31
**Status:** Proposed — the measurement is done and the direction is clear; §2.4's open item
(host-side decode) must be measured before this is Accepted.

## 1. Context & Problem Statement

[ADR-006](ADR-006-batch-crossing-is-guest-encoding.md) established that the whole cost of a
`sendChanges` crossing is guest-side encoding, and left the v1 encoding open with three
candidates. It also asserted, from within a single encoder, that **"cost is linear in bytes,
and the constant is large — 1.2 microseconds per byte"**, and reasoned from that: send fewer
bytes, and time falls proportionally.

That reasoning has now been tested against six encodings of the same batch, and **it is
wrong as a causal law.** Bytes and time were correlated only because every encoding compared
at that point walked `kotlinx.serialization`'s pure-Kotlin serializers. The variable that
actually governs cost is **how much interpreted Kotlin runs during encoding**, and byte count
is a weak proxy for it. Two of the six results falsify the linear reading directly:

- `json-positional-interned` produces **7% fewer bytes** than `json-positional` and takes
  **31% longer**, because the interning table costs more interpreted work than the bytes it
  saves.
- `protobuf-base64` produces **36% fewer wire bytes** than today's encoding and takes
  **45% longer**.

Separately, the question "could we use protocol buffers?" needed an answer made of numbers.
It has two structural obstacles before any measurement: Zipline's boundary is
`CallChannel.call(callJson: String): String`, a string channel with no byte path, so a binary
payload must be text-encoded to cross; and [ADR-004](ADR-004-change-event-protocol-v0.md)
§2.2 types every dynamic value as `JsonElement`, which protocol buffers cannot represent at
all, so adopting them is a schema change rather than an encoder swap.

## 2. Decision

**2.1 The v1 wire format is positional JSON, constructed directly as native JavaScript
values.** Each change becomes a positional array whose first element is a small integer kind
discriminator:

| Change | Encoding |
| --- | --- |
| `Create` | `[0, id, widgetTag]` |
| `PropertySet` | `[1, id, propertyTag, value]` |
| `ModifierSet` | `[2, id, [[tag, value], …]]` |
| `ChildAdd` | `[3, parentId, slot, childId, index]` |
| `ChildRemove` | `[4, id, slot, index, count]` |
| `ChildMove` | `[5, id, slot, from, to, count]` |

The batch is `[sequence, [changes…]]`. No field names cross, and no class discriminator
strings cross.

**The construction matters as much as the shape.** The guest builds the batch as native
JavaScript arrays, numbers, and strings, and hands the finished structure to QuickJS's own
`JSON.stringify`. It does **not** route through `kotlinx.serialization` — not through the
pure-Kotlin encoder, and not through `encodeToDynamic`. That is where the time goes.

**2.2 Protocol buffers and Concise Binary Object Representation (CBOR) are rejected**, on
measurement rather than on principle. Both are slower than the format they would replace, and
protocol buffers are *larger* on the wire than positional JSON once Base64 is paid.

**2.3 Modifier-chain interning is rejected**, also on measurement. The reference screen sends
160 modifier chains of which only 13 are distinct, which looked like an obvious win. It is
not: building the table costs more than the repetition costs.

**2.4 ADR-006's "linear in bytes" claim is withdrawn** and replaced with: **the cost of a
crossing is dominated by the amount of interpreted Kotlin executed while encoding it.** Byte
count matters only insofar as it tracks that. Optimisation effort belongs on getting work out
of the interpreter and into QuickJS's C implementations, not on shaving the schema.

**2.5 Open before this is Accepted: host-side decode is unmeasured.** Every figure below is
guest encode plus transport. The host must still parse whatever crosses, and positional arrays
are cheaper to produce but not obviously cheaper to consume than a named-field structure. That
work runs on the Java Virtual Machine rather than in the interpreter, so it is expected to be
small — the full `sendChanges` path measured 24.06 ms against 23.92 ms of encoding, leaving
little room for it — but "expected to be small" is exactly the kind of claim this project
requires a number for.

## 3. Rationale & Research

Six encodings of the same 572-change batch — the reference screen's initial batch —
measured inside the guest, 200 iterations after 20 warm-ups. Development host (Apple silicon),
where every distribution is tight:

| Encoding | Wire bytes | vs. today | Encode p50 | Cross p50 | vs. today |
| --- | ---: | ---: | ---: | ---: | ---: |
| **`json-positional`** | **9,091** | **−54%** | **1.14 ms** | **1.23 ms** | **−95%** |
| `json-positional-interned` | 7,636 | −61% | 1.52 ms | 1.61 ms | −93% |
| `json-v0-native` *(today)* | 19,795 | — | 23.84 ms | 24.02 ms | — |
| `protobuf-base64` | 12,748 | −36% | 34.57 ms | 34.76 ms | +45% |
| `json-v0-kotlinx` | 20,939 | +6% | 44.23 ms | 44.49 ms | +85% |
| `cbor-base64` | 18,540 | −6% | 242.65 ms | 242.26 ms | +908% |

Confirmed on a physical Pixel 10 Pro: `json-positional` at **1.70 ms** encode against the same
run's independently measured baseline of **28.32 ms**, an improvement of roughly seventeen-fold.

**A measurement caveat, stated rather than smoothed over.** On the Pixel the two baseline
entries (`json-v0-kotlinx` and `json-v0-native`) are inflated relative to the same run's own
experiment 0.3 figure for the identical operation — 45.67 ms in the bake-off against 28.32 ms
in experiment 0.3 — and they are the only two entries whose p50-to-p95 spread is wide
(45.67 → 63.54 ms, where `json-positional` runs 1.70 → 1.73 ms). Both are measured first in
the sequence. This has the signature of scheduler migration or thermal behaviour on a phone
running a long, hot, single-threaded workload, and it is a hazard the harness does not yet
control for. It does not change the conclusion — the honest Pixel comparison uses the
independently measured 28.32 ms baseline and still shows seventeen-fold — but the Pixel's
absolute bake-off numbers for those two rows should not be quoted.

**Why the binary formats lose.** Both obstacles compound. `kotlinx.serialization`'s protocol
buffer and CBOR encoders are pure Kotlin, so they run as interpreted bytecode in QuickJS,
forfeiting the C implementation that `JSON.stringify` provides — the same effect ADR-006
measured at 47% between kotlinx JSON and native JSON. Base64 then adds a third to the size and
is itself performed in interpreted Kotlin. The result is a format that is smaller in principle
and slower and larger in practice. CBOR's ninefold cost is a `kotlinx.serialization`
implementation characteristic and should not be read as a statement about CBOR generally.

**Why positional JSON wins by more than its byte count.** It skips `encodeToDynamic` — the
step that walks generated serializers in interpreted Kotlin to build a JavaScript object graph
— by building that graph directly. What remains is one native `JSON.stringify` over a
structure QuickJS already holds. The 54% byte reduction is real but secondary; the 95% time
reduction comes from the work that no longer happens.

The measurement lives in
[`tools/phase0/guest/src/jsMain/kotlin/dev/dogwood/guest/Encodings.kt`](../../tools/phase0/guest/src/jsMain/kotlin/dev/dogwood/guest/Encodings.kt),
the captured wire bytes in
[`tools/phase0/results/wire-format.md`](../../tools/phase0/results/wire-format.md).

## 4. Unstated Assumptions

- **Assumes the generator can emit positional encoders.** The Phase 0 encoder is hand-written
  for ten components. Generating this shape is strictly easier than generating a named-field
  one, but it is unbuilt.
- **Assumes the change recorder can build native arrays directly.** The measurement converts
  `List<Change>` into arrays and pays for that conversion, so a recorder that writes arrays as
  it records would be **faster** than measured, not slower. The figures here are therefore
  conservative for the chosen design.
- **Assumes deferred expressions will fit a positional shape.** They do not exist yet. When
  they do, structured values will need a positional encoding of their own, and this ADR does
  not define one.
- **Assumes host-side decode is small.** See §2.5. Unmeasured.
- **Assumes readability is an acceptable loss.** `[0,1,2]` is not debuggable by eye the way
  `{"k":"c","i":1,"w":2}` is. The mitigation is a decoder in the harness that renders a
  positional batch back into the named form, which does not exist yet.

## 5. Updated Documents

- [adrs/layer-4/ADR-006-batch-crossing-is-guest-encoding.md](ADR-006-batch-crossing-is-guest-encoding.md) —
  the "linear in bytes" claim withdrawn; §2.3's candidate list resolved
- [adrs/layer-4/ADR-004-change-event-protocol-v0.md](ADR-004-change-event-protocol-v0.md) —
  §2.4's rendering superseded for v1; §4's binary-encoding assumption closed
- [specs/layer-4-sandbox.md](../../specs/layer-4-sandbox.md) — Interfaces & Boundary: what the
  boundary encodes and where its cost lies
- [roadmap.md](../../roadmap.md) — Phase 0 status; Phase 1 step 4 implements this shape
- [tools/phase0/](../../tools/phase0/) — the bake-off and its results

# Allocation and garbage collection on the Layer 4 boundary

The wire-format bake-off behind [ADR-007](../../../adrs/layer-4/ADR-007-v1-wire-format-positional-json.md)
measured **latency only**, on a **one-shot** batch, and reported medians. It never measured
what an encoding allocates, and it never ran a sustained load, so it could not see a
collection pause. This is that measurement: experiment 0.5, `AllocationGcExperiment` in
`host-core/`, run on both hosts.

The hypothesis under test was that the wire format's real cost is allocation pressure rather
than encode latency, and that a garbage-collection pause landing inside a frame is a failure
the medians hide. **Half of it survives.** Allocation separates the encodings by four orders
of magnitude at screen-open size, and ADR-007's winner wins there too, decisively. But no
collection observed here came close to a frame budget, and `gcThreshold` — the knob
[ADR-005](../../../adrs/layer-4/ADR-005-phase-0-harness-resolutions.md) recommends raising —
turns out to control almost nothing, for a reason in the QuickJS source.

## Machines

| | Development host | Emulator |
| --- | --- | --- |
| Hardware | Apple M3, 16 GB, macOS 26.5.1 | Google `sdk_gphone64_arm64`, `ranchu`, 4 cores |
| Runtime | Java 21.0.12.1, `arm64` | Android 15 (API 35), `arm64-v8a` |
| QuickJS | Bellard `2021-03-27`, via Zipline 1.27.0 | same |
| Live guest heap after one composition | 6,162,423 B used, 22,439 objects | 6,265,404 B used, 23,281 objects |

**Neither is gate-valid.** The Phase 0 gate is defined on a low-end 2022-tier Android phone.
The emulator here runs `arm64` code on the development machine's own processor and lands
within 15% of it on every per-frame figure below — that is a correctness check of the Android
path, not a measurement of a phone. Both are labelled throughout, and no number here should be read as a
phone number.

## Method

- Reference screen at 23 rows, 160 widget nodes.
- **Steady-state frame:** two state writes (the running total, which two nodes read, and one
  row's selection), one synchronous frame, one batch. **Four changes per batch** — the small,
  scroll-shaped diff, not a screen open.
- Allocation probes: 2,000 iterations (1,000 on the emulator) after a 200-iteration warm-up,
  in twenty chunks, with a heap reading at every chunk boundary.
- Sustained runs: 8,000 frames (6,000 on the emulator) after 500 warm-up frames, at each of
  three `gcThreshold` values. Raw nanosecond samples are kept in the JSON, so any percentile
  can be recomputed.
- Traced runs: 4,000 frames (3,000 on the emulator), each frame's latency paired with a heap
  reading taken **between** frames.
- Results: [`alloc-gc-macbook-pro-development-host-not-gate-valid.json`](alloc-gc-macbook-pro-development-host-not-gate-valid.json),
  [`alloc-gc-google-sdk-gphone64-arm64-emulator.json`](alloc-gc-google-sdk-gphone64-arm64-emulator.json).

**Timing is done on the host, not in the guest, and that matters.** One
`MonotonicClock.nowNanos()` round trip costs **0.055 ms** on the development host and
**0.220 ms** on the emulator — the same order as, or an order larger than, the crossing being
measured. A guest-timed sample of the steady-state crossing measured here at 0.057 ms would
therefore read about **0.11 ms**, half of it timer. Experiment 0.5 avoids that entirely: the
sustained loops call no clock at all, and the host timestamps `System.nanoTime()` the moment
each `sendChangesEncoded` arrives. The gap between two arrivals is one whole iteration —
state write, recomposition, `takeBatch`, encode, Zipline's outbound serialisation, the Java
Native Interface crossing, and the return into JavaScript — with no instrument inside it.

**Measured versus inferred.** Every byte and every nanosecond below is measured. Whether a
*collection* occurred is **inferred**: Zipline exposes no garbage-collection callback, so a
collection is deduced from a fall in `QuickJs.memoryUsage.memoryAllocatedSize` between two
consecutive frames. Where a claim rests on that inference it says so.

## 0. What "allocated" means here — a correction to the premise

QuickJS is **primarily reference counted**. The mark-and-sweep collector exists to break
reference *cycles*, not to reclaim ordinary garbage. An acyclic object graph — an array of
arrays of numbers and strings, for instance — is freed the instant its last reference drops,
and the allocator's byte count falls again immediately, with no collector involvement.
`__JS_FreeValueRT` is that path
([`quickjs.c:5476`](https://github.com/cashapp/zipline/blob/1.27.0/zipline/native/quickjs/quickjs.c#L5476),
in the copy Zipline 1.27.0 vendors and ships).

So "bytes allocated per batch" splits in two, and only one half is observable:

- **Gross allocation** — QuickJS does not expose it. `memoryUsage` reports what is *currently*
  allocated, not a running total, and there is no cumulative counter to read.
- **Net heap growth** — what every number in sections 1 and 2 measures, and the one that
  matters. It is the part of a batch's garbage that reference counting could **not** reclaim,
  and therefore the only part that pushes the heap towards a collection. Gross allocation that
  the refcount frees never triggers anything.

The probes measure it by setting `gcThreshold` to 1 GiB so nothing can collect, then watching
the heap grow. Each probe checks that the heap grew monotonically across all twenty chunks,
that the second-half slope matches the whole-run slope (so a warm-up cost is not hiding in the
average), and that a forced `gc()` afterwards returns the heap to its baseline. **Every probe
reported below passed all three, and every probe returned to baseline to within 64 bytes** —
which is the proof that the growth was garbage rather than something genuinely retained.

## 1. Allocation per steady-state frame

Net heap growth per frame, four changes per batch, collection disabled. Each rung adds one
stage to the same frame, so the difference between rungs is that stage's cost.

| Stage | Encoding | Dev host B/frame | Emulator B/frame | Δ from rung above (dev) |
| --- | --- | ---: | ---: | ---: |
| recompose only | — | 5,228 | 5,481 | — |
| + encode | `json-positional` | 5,228 | 5,481 | **+0** |
| + cross | `json-positional` | 6,705 | 6,950 | +1,477 |
| + encode | `json-positional-interned` | 5,228 | 5,481 | +0 |
| + cross | `json-positional-interned` | 6,708 | 6,953 | +1,480 |
| + encode | `json-v0-native` *(v0, rejected)* | 8,769 | 9,058 | **+3,541** |
| + cross | `json-v0-native` | 10,320 | 10,586 | +1,550 |
| + encode | `json-v0-kotlinx` | 8,557 | 8,743 | +3,329 |
| + cross | `json-v0-kotlinx` | 10,098 | 10,277 | +1,541 |

Three readings:

1. **Positional encoding allocates nothing the collector can see.** Adding it to a frame moves
   the heap by zero bytes, on both hosts, over two thousand frames. It builds native
   JavaScript arrays and hands them to `JSON.stringify`; that graph is acyclic and the
   refcount takes it back immediately.
2. **The `kotlinx.serialization` path leaves 3.5 KB per four-change batch behind.** That is
   the encoder alone, before any crossing. Why an encoder should leave cycle garbage at all is
   not something these measurements answer; the shape of it — linear in batch size, freed
   completely by a forced collection — is consistent with the encoder's own object graph
   containing reference cycles, which is an **inference**, not a measurement.
3. **The crossing itself costs about 1.5 KB per call**, near enough the same for every
   encoding, at a payload of about a hundred bytes. That is Zipline's call path, not Dogwood's.

**The dominant term is the recomposition, not the wire format.** Once the encoding is
positional, 5,228 of the 6,705 bytes a frame leaves behind — 78% — come from Compose's own
recomposition, and 1,477 from Zipline's crossing. The wire format contributes zero.

## 2. Allocation per batch, by encoding

The same encodings over a fixed batch, with no recomposition in the way. Both hosts, because
the two disagree in one place worth pointing at.

**A steady-state batch (3 changes):**

| Encoding | Encode only, dev | Encode only, emu | Encode + cross, dev | per change, dev |
| --- | ---: | ---: | ---: | ---: |
| `json-positional` | **5 B** | **11 B** | 1,445 B | 482 B |
| `json-positional-interned` | **5 B** | **11 B** | 1,445 B | 482 B |
| `json-v0-native` *(v0, rejected)* | 3,638 B | 3,707 B | 5,174 B | 1,725 B |
| `json-v0-kotlinx` | 3,478 B | 3,411 B | 4,982 B | 1,661 B |

**A screen-open batch (572 changes):**

| Encoding | Wire bytes | Encode only, dev | per change | Encode only, emu | Encode + cross, dev | Encode + cross, emu |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `json-positional` | 9,091 | **53 B** | 0.1 B | **108 B** | 11,670 B | 10,601 B |
| `json-positional-interned` | 7,636 | **53 B** | 0.1 B | **108 B** | 11,670 B | 9,146 B |
| `json-v0-native` *(v0, rejected)* | 19,795 | 616,920 B | 1,079 B | 622,907 B | 642,873 B | 644,104 B |
| `json-v0-kotlinx` | 20,939 | 501,560 B | 877 B | 485,698 B | 527,513 B | 508,039 B |

The two hosts agree to within 4% on every figure above a kilobyte — allocation is a property
of the compiled guest, not of the machine, and the emulator confirms that. The one place they
differ is the positional encoder's own growth, where the emulator measures twice the
development host's. Both numbers are two orders of magnitude below the noise floor of anything
else on the page (11 bytes against 3,707; 108 against 622,907), and a probe that is measuring
five bytes per iteration is measuring allocator quantisation, not the encoder. The finding is
"indistinguishable from zero", on both hosts, and nothing in this document leans on which of
5 B and 108 B is nearer the truth.

The screen-open row is the one to look at. **The named-field v0 encoder leaves 617 KB of
collector-only garbage behind for every screen open; the positional encoder leaves 53 bytes.**
That is a factor of 11,600. Per change it is 1,079 bytes against 0.1.

**Neither number describes a shipping defect.** `json-v0-native` is the encoder ADR-007
*rejected*; `engine/dogwood-compose/.../Wire.kt` builds positional arrays and has since. This
row is the counterfactual, not the status quo, and it is here because it is the only way to say
how much the decision was worth.

Put in terms of section 5's measured collection allowance of roughly 3.1 MB: five screen opens
on the v0 encoder would have been enough to force a collection all by themselves. Five hundred
screen opens on the positional encoder are not.

This is an independent argument for ADR-007's decision, reaching the same conclusion by a
different route and by a far wider margin than latency did. Modifier-chain interning, which
ADR-007 rejected on latency, is exactly tied with plain positional on allocation, so nothing
here reopens it.

## 3. The sustained-load tail

Thousands of consecutive small batches, timed arrival to arrival at the host. `json-positional`
throughout.

**Frame loop** — recompose, encode, cross. This is the whole frame.

| Host | `gcThreshold` | n | p50 | p95 | p99 | p99.9 | max | ≥ 8 ms | ≥ 16.7 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| dev | 256 KiB | 7,998 | 2.689 | 3.444 | 5.031 | 18.373 | 69.211 | 26 | 9 |
| dev | 8 MiB | 7,998 | 2.704 | 3.397 | 3.610 | 6.118 | 17.731 | 2 | 1 |
| dev | 16 MiB | 7,998 | 2.710 | 3.423 | 3.687 | 6.299 | 18.025 | 3 | 1 |
| emulator | 256 KiB | 5,998 | 2.365 | 3.047 | 3.624 | 8.322 | 14.601 | 9 | 0 |
| emulator | 8 MiB | 5,998 | 2.355 | 3.009 | 3.633 | 8.648 | 14.824 | 18 | 0 |
| emulator | 16 MiB | 5,998 | 2.407 | 3.106 | 3.402 | 8.381 | 14.435 | 9 | 0 |

**Crossing loop** — encode and cross a fixed three-change batch, no recomposition. This is the
wire format's own steady-state cost, with the timer taken out of it.

| Host | `gcThreshold` | n | p50 | p95 | p99 | p99.9 | max | ≥ 16.7 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| dev | 256 KiB | 7,998 | 0.057 | 0.060 | 0.064 | 0.257 | 2.746 | 0 |
| dev | 8 MiB | 7,998 | 0.056 | 0.060 | 0.063 | 0.074 | 2.777 | 0 |
| dev | 16 MiB | 7,998 | 0.057 | 0.062 | 0.065 | 0.082 | 6.123 | 0 |
| emulator | 256 KiB | 5,998 | 0.065 | 0.090 | 0.126 | 0.330 | 4.326 | 0 |
| emulator | 8 MiB | 5,998 | 0.061 | 0.086 | 0.101 | 0.208 | 4.110 | 0 |
| emulator | 16 MiB | 5,998 | 0.060 | 0.086 | 0.099 | 0.226 | 1.032 | 0 |

**Not one of 41,988 crossings exceeded a frame budget**, on either host, at any threshold. The
p99 of a steady-state crossing is 65 microseconds on the development host and 126 on the
emulator — 0.4% and 0.8% of a 16.7 ms frame. Even the crossing loop's worst single samples,
2.7 to 6.1 ms, stay inside a frame, and those are attributable to the host rather than to the
guest; see the next section.

## 4. Where the outliers actually come from

The frame loop's tail has two populations, and they are distinguishable by their **shape in
time**, which is why the raw samples are kept.

**Population one is periodic.** Isolating the frame-loop samples at or above 5 ms and
discarding consecutive clusters leaves a recurring gap of **471–472 frames on the development
host and 456–458 on the emulator**, and — this is the point — *the period is the same at all
three `gcThreshold` values*. Section 5 identifies these directly, by heap reading, as
collections.

The period is predicted by section 1 to within half a percent, which is a useful check that
the two measurements are of the same thing:

| | Allocation per frame (§1) | Collection frees (§5) | Predicted period | Observed period |
| --- | ---: | ---: | ---: | ---: |
| dev host | 6,705 B | 3,151,168 B | 470 frames | 471–472 |
| emulator | 6,950 B | 3,163,833 B | 455 frames | 456–458 |

At 60 Hz that is **one collection every 7.8 seconds** of continuous scrolling on the
development host.

**Population two is bursty and host-side.** The remaining outliers arrive in runs of
consecutive frames — indices 3,938/3,939 then 3,996 then 4,057/4,058 on the development host's
256 KiB run — and do not coincide with any heap movement. The clearest case is the development
host's 8 MiB *traced* run, whose worst six frames are 73.3, 72.0, 60.7, 47.2, 39.4 and 25.5 ms,
all clustered between frames 356 and 635, and **every one of them recorded the heap
+7,168 bytes, not a drop.** A QuickJS collection cannot make the heap grow. These are the
development machine's own Java Virtual Machine and scheduler, and they follow no threshold.

This matters for reading the table in section 3: the development host's 69 ms maximum at
256 KiB is population two, and the run-to-run wandering of `≥ 8 ms` counts between 2 and 26
tracks nothing but host weather.

## 5. Does a collection land inside a frame?

Every frame in the traced runs is paired with a heap reading taken between frames, so each one
can be labelled. Reading the heap costs a `JS_ComputeMemoryUsage` walk of the live object list;
it sits outside every timed section, and its own cost is recorded in the result files.

| Host | `gcThreshold` | Quiet frames p50 | Collection frames p50 | Collection frames max | Inferred collections | Freed each | Forced `gc()` p50 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| dev | 256 KiB | 2.765 | 5.562 | 6.327 | 9 / 4,000 | 3.15 MB | 1.725 |
| dev | 8 MiB | 2.784 | 5.409 | 5.995 | 9 / 4,000 | 3.15 MB | 1.801 |
| dev | 16 MiB | 2.740 | 5.521 | 9.976 | 8 / 4,000 | 3.15 MB (first: 10.45 MB) | 1.719 |
| emulator | 256 KiB | 2.447 | 6.604 | 7.038 | 7 / 3,000 | 3.16 MB | 2.508 |
| emulator | 8 MiB | 2.509 | 6.641 | 13.025 | 7 / 3,000 | 3.16 MB | 2.751 |
| emulator | 16 MiB | 2.496 | 6.806 | 12.581 | 6 / 3,000 | 3.16 MB (first: 10.43 MB) | 3.135 |

All figures in milliseconds except the counts and bytes.

**Yes, a collection lands inside a frame — and it costs about 2.8 ms on the development host
and 4.2 ms on the emulator.** A frame that collects is roughly twice a frame that does not.
The independently measured forced-`gc()` pause, 1.7 to 3.1 ms, brackets the same quantity from
the other side, which is the corroboration that the inferred spike really is a collection.

**But it does not miss a frame.** Of the 46 inferred collections across both hosts, 41 cost
between **5.20 and 7.04 ms**. The five exceptions:

- The *first* collection at `gcThreshold` 16 MiB, which has 10.4 MB to free instead of 3.1 MB:
  **9.98 ms** (dev host, frame 905) and **12.58 ms** (emulator, frame 858). Section 6 explains
  why raising the threshold produces exactly one collection like this and no more.
- Three ordinary 3.1 MB collections on the emulator, at 13.02, 11.84 and 11.44 ms. The
  emulator's *non*-collecting frames reached 14.5 ms in the same runs, and one of the three
  (frame 1008 of the 8 MiB traced run) sits inside a burst of host interference where quiet
  frames reached 83 ms, so none of the three can be cleanly attributed to the collection. The
  development host, which is the quieter of the two, produced no such outlier at all.

Against a 16.7 ms budget there is room. Against the Phase 0 gate's 8 ms guest-side budget
there is not much: a 2.8 ms collection on top of a 2.8 ms recomposition is already 5.6 ms
here, and the gate device is several times slower than this emulator. **That is the number
this experiment hands to the gate device**, and it is the only one on this page with a
plausible route to failing.

## 6. `gcThreshold` controls almost nothing

The threshold moved the median by nothing, as expected. It also moved the tail by nothing,
which was not expected — and the reason is in the source.

`js_trigger_gc` collects when the allocator's byte count would exceed `malloc_gc_threshold`,
and then **immediately resets that threshold to 1.5× the live heap**
([`quickjs.c:1262–1280`](https://github.com/cashapp/zipline/blob/1.27.0/zipline/native/quickjs/quickjs.c#L1262)):

```c
JS_RunGC(rt);
rt->malloc_gc_threshold = rt->malloc_state.malloc_size +
    (rt->malloc_state.malloc_size >> 1);
```

`JS_SetGCThreshold` — what Zipline's `QuickJs.gcThreshold` setter calls
([`quickjs.c:1780`](https://github.com/cashapp/zipline/blob/1.27.0/zipline/native/quickjs/quickjs.c#L1780))
— just writes that same field. So a threshold set by the host governs **the first collection
and nothing after it.** The default of 256 KiB
([`quickjs.c:1620`](https://github.com/cashapp/zipline/blob/1.27.0/zipline/native/quickjs/quickjs.c#L1620))
is likewise overwritten by the first collection, which is why a guest with a 6 MB live set
does not collect on every allocation.

Measured, not assumed. For every inferred collection the traced runs record the heap
immediately before and immediately after:

| Host | `gcThreshold` | Ratio, first collection | Ratio, all later collections |
| --- | ---: | ---: | ---: |
| dev | 256 KiB | 1.499 | 1.498 – 1.500 |
| dev | 8 MiB | 1.500 | 1.498 – 1.500 |
| dev | 16 MiB | **2.656** | 1.498 – 1.500 |
| emulator | 256 KiB | 1.500 | 1.498 – 1.501 |
| emulator | 8 MiB | 1.500 | 1.498 – 1.500 |
| emulator | 16 MiB | **2.647** | 1.499 – 1.500 |

A note on "first" in that table: it means the first collection *inside the traced 4,000
frames*, not the first of the process. At 256 KiB and at 8 MiB the run has already collected
during module load, composition and the 500 warm-up frames, so QuickJS has taken the threshold
over before the trace begins and every ratio the trace sees is 1.5. Only 16 MiB survives the
warm-up — the live heap is about 6.2 MB and 500 warm frames add roughly 3.4 MB, which is not
enough to reach it — which is why 16 MiB is the only row where the host's setting is still
visible when the measurement starts, and it is visible exactly once.

Every steady-state collection reclaims the heap down to exactly two-thirds of its peak: the
1.5× rule, measured. Raising `gcThreshold` changes one thing only — it lets the heap reach
16 MB before the *first* collection, which then has 10.4 MB to free instead of 3.1 MB and is
the slowest single collection frame in the dataset (9.98 ms dev, 12.58 ms emulator).

An ordering control, because on the development host 256 KiB always ran first: four
interleaved 8,000-frame runs, `16 MiB, 256 KiB, 16 MiB, 256 KiB`, development host.

| Order | `gcThreshold` | p50 | p99 | p99.9 | max | ≥ 16.7 ms |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 16 MiB | 2.630 | 3.381 | 6.089 | 11.217 | 0 |
| 2 | 256 KiB | 2.658 | 4.064 | 6.259 | 9.595 | 0 |
| 3 | 16 MiB | 2.612 | 3.560 | 7.213 | 14.327 | 0 |
| 4 | 256 KiB | 2.620 | 3.365 | 5.526 | 11.335 | 0 |

**No threshold effect, in either direction, at any percentile.** ADR-005's recommendation to
raise `gcThreshold` to 8–16 MB is not wrong so much as inert: it buys a longer runway to the
first collection at the price of making that one collection three times bigger, and changes
nothing thereafter.

## 7. What survives

**Survives.** Allocation is a real axis on which the encodings differ, it was never measured
before, and it separates them by four orders of magnitude at screen-open size — far more
sharply than latency did. Anything that reconsiders the wire format has to answer this table
as well as ADR-007's.

**Does not survive.** "A collection pause landing inside a frame is a failure the medians
hide" is not what happens here. Collections do land inside frames, every 470 frames or so, and
they do roughly double that frame — but 41 of the 46 collection frames measured
cost between 5.2 and 7.0 ms, inside a 16.7 ms budget, and the p99 of a crossing is under 1% of
that budget on both hosts. The frame-loop maxima that *do* exceed
16.7 ms are host-side bursts, identified by their shape and by the heap growing rather than
shrinking during them.

**Does not survive either.** `gcThreshold` is not a tuning knob. QuickJS overwrites it at the
first collection and self-tunes to 1.5× live thereafter.

**Left open.** Everything above was produced on an Apple M3 and on an emulator running on that
same M3. A steady-state collection frame costs 5.2–7.0 ms here, of which about 2.8 ms (dev)
to 4.2 ms (emulator) is the collection itself. The Phase 0 gate's guest-side budget is 8 ms
and the gate device is several times slower. The number to carry to the gate device is not the
median — it is **the cost of a collection frame**, and it is the one figure in this document
with a plausible route to failing.

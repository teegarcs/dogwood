# Project Dogwood: Delivery Plan

**Companion documents:** [Technical Specification v4.0](high-level-tech-spec-final.md), the per-layer milestones in [`specs/`](specs/), and the decision record in [`adrs/`](adrs/).

This document sequences the work *across* layers and states the gates between phases. The per-layer specifications say what to build; this says in what order, and what would stop us.

Durations assume **one engineer** on the critical path and are rough. They are sequencing guidance, not commitments.

---

## Adoption Path and Platform Order

Two sequencing decisions, recorded in [Layer 5 ADR-006](adrs/layer-5/ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md), reshape the phases below for a company deployment:

**Design-system-first.** The first shippable surface is built from **registered design-system components plus the layout primitives**, not the generated Material tier. Registered components absorb the hardest bespoke subsystems for the screens that matter (a design-system button owns its own animation; a design-system `AsyncImage(url)` solves images; a registered chart delivers what `Canvas` cannot), and curated signatures are systematically more bindable than raw Material. Generator v1 therefore targets **registered modules plus `foundation-layout` only** — first-party sources, no metalava, few exotic defaults — and the full Material tier with its defaults-expression complexity becomes **generator v2**. This pulls the first production screen from roughly a year to roughly four to five months, and it mirrors the architecture Redwood actually shipped while keeping the generated full-surface tier as the upgrade that ends the treadmill.

**Platform order: Android → Web → iOS, with iOS measured first and built last.**

- **Android first.** The host is Android's runtime, Zipline's most exercised target, and native Compose. All of Phases 1–4 land here.
- **The Compose Multiplatform desktop host is the development loop, not a shipping target.** It runs stable Compose Multiplatform on the Java Virtual Machine (JVM), where Zipline also runs, giving second-scale iteration without emulators. Build it alongside the Android host in Phase 1; it costs little because the host layer is common Kotlin.
- **Web second.** It avoids the Kotlin/Native toolchain, Apple review risk, and the iOS organisational dependency. Three things are different on Web and must be treated as a distinct **web profile**, not a port: the guest loads into the browser's JavaScript engine directly (no QuickJS, no `.zipline` bytecode), delivery and integrity ride ordinary web deployment rather than `ZiplineLoader` and Ed25519 manifests, and the render target is Compose Multiplatform for Web — **Beta**, with `material3-wasm-js` trailing at alpha and a multi-megabyte Skiko payload ([Layer 4 ADR-003](adrs/layer-4/ADR-003-treehouse-precedent-and-evidence-refresh.md)). The protocol, applier, generator, and dictionary are unchanged; the substrate and delivery layers fork. Budget the profile as real design work.
- **iOS last in code, first in lead time.** The three iOS risks all have long lead times and none needs iOS code to start: the **Phase 0 measurements include an iOS device** (Zipline and the Treehouse samples both run there — deferring iOS numbers until after everything is built risks discovering an iOS-specific performance wall late), the **Apple Guideline 4.7 inquiry** files in Phase 0, and the **iOS organisation's written yes** is a Phase 0 conversation. Building iOS last is a velocity decision; measuring it last would be a mistake.

---

## Phase 0 — Falsify It Cheaply

**Goal:** find out whether the architecture is viable before building anything. Every experiment here is designed to produce a number that could stop the project.

| # | Experiment | Effort | What it settles |
|---|---|---|---|
| 0.1 | Compile the **Phase 0 probe program** (defined in the harness appendix below — the reference screen plus its state and event handlers; the Phase 1 slice does not exist yet) to Kotlin/JavaScript with `androidx.compose.runtime:runtime-js`, `runtime-saveable-js`, `kotlinx-coroutines-core-js`, and `kotlinx-serialization-json-js` linked in production configuration. Package as `.zipline` bytecode. Measure minified bytes, gzipped bytes, bytecode bytes, on-device module-load time, and `QuickJs.memoryUsage` after load. | ~1 day | Cold-start cost. `runtime-js` alone is 1,777,599 bytes of klib. Cash App's published baseline for a real Kotlin/JavaScript application is 360 ms of QuickJS module loading. |
| 0.2 | **Start by instrumenting Redwood Treehouse's runnable samples** (`counter`, `emoji-search`) — they already run the real Compose runtime inside Zipline's QuickJS on device ([Layer 4 ADR-003](adrs/layer-4/ADR-003-treehouse-precedent-and-evidence-refresh.md)); measuring them is hours, not days, and produces the first number anyone has published. Then run a real composition with a trivial Dogwood `Applier` and **Dogwood-shaped stubs** (deferred-expression recording included — a bare `Applier` understates recording cost). Measure initial composition and, separately, recomposition after a single state change, for the **reference screen defined in the harness appendix below**, on the two named devices, warm and cold per the appendix protocol. | ~3 days | **The central unknown is the number, not the existence.** Redwood Treehouse proves Compose composition runs in a JavaScript interpreter; nobody has published a measurement of it. Initial composition being slow is maskable; recomposition being slow is fatal to interactivity. |
| 0.3 | Encode the reference screen's initial batch (~150 nodes) **in the provisional v0 wire format of [Layer 4 ADR-004](adrs/layer-4/ADR-004-change-event-protocol-v0.md)** through Zipline's `CallChannel`, end to end. Report guest encode, `JSON.stringify`, Java Native Interface (JNI) transcode, host parse, and total bytes, at batch sizes 1 / 10 / 100 / 1,000. The five-pass breakdown requires timing inside Zipline's internal `CallChannel`, so budget a locally patched Zipline build; if that slips, report end-to-end plus total bytes only. | ~2 days | Protocol cost per frame. Every crossing is one JSON string; the cost that matters is per byte. |
| 0.4 | Measure guest garbage-collection behaviour under 0.2 with `gcThreshold` at Zipline's default 256 KiB and at 8–16 MB. Record collection count, total pause, and maximum pause — via the patched-QuickJS timing hook defined in the harness appendix (Zipline's public Application Programming Interface (API) exposes no garbage-collection hooks). **✅ Done, as Experiment 0.5** ([`results/allocation-and-gc.md`](tools/phase0/results/allocation-and-gc.md)). Collections land in frames and roughly double them — 5.2–7.0 ms against a quiet 2.7 ms — but **not one of 41,988 crossings, and no collecting frame, exceeded the 16.7 ms budget** on either host. Garbage collection is not the source of jank here. Two findings the question did not anticipate: **`gcThreshold` does nothing**, because QuickJS resets it to 1.5× live after every collection, so the host's value governs only the first one; and what actually sets collection frequency is how much collector-only garbage the encoder leaves, where positional beats the rejected named-field encoder by four orders of magnitude. | ~1 day | Whether garbage collection, rather than interpretation, is the source of any jank. |

**Gate — stated as a computable budget, because Phase 0 has no host renderer.** The end-to-end tap-to-repaint path is: event decode + guest recomposition (0.2) + batch encode/transcode/parse (0.3) + two vertical-sync intervals + host apply-and-render (built in Phase 1, estimated here as one frame). Proceed only if, on the low-end devices measured:

- guest **recomposition** of the reference screen is ≤ 8 ms at the 95th percentile (0.2),
- the 150-node **batch crossing** is ≤ 4 ms end to end (0.3),
- maximum garbage-collection pause under load is ≤ one frame **at 60 Hz (16.7 ms)** at the 99th percentile (0.4) — the guest is capped at 60 Hz by design, so 60 Hz defines the frame, and
- **cold start** (0.1): module load plus first composition adds ≤ **500 ms** to screen-open on the named low-end Android device (provisional product number — replace it with a signed-off figure before running, not after).

These thresholds are provisional and may be renegotiated *before* the experiments run — never after seeing the numbers. If recomposition inside QuickJS cannot meet the budget, the substrate decision in [Layer 4 ADR-002](adrs/layer-4/ADR-002-adopt-zipline-quickjs-substrate.md) must be reopened before anything else is built (the strongest known alternative to evaluate at that point is Meta's Hermes — ahead-of-time bytecode, active maintenance, mobile pedigree — noting Kotlin/JavaScript-on-Hermes is itself unproven; see [Layer 4 ADR-003](adrs/layer-4/ADR-003-treehouse-precedent-and-evidence-refresh.md)).

### Phase 0 status — the harness exists and has been run

The harness is committed at [`tools/phase0/`](tools/phase0/) and its results at
[`tools/phase0/results/`](tools/phase0/results/). It implements 0.1 through 0.4 exactly as
this appendix specifies, evaluates the gate mechanically, and has produced numbers on three
hosts — an Apple silicon development machine, an Android emulator, and a **physical Pixel 10
Pro** (Tensor G5, Android 17). **None is gate-valid**, because the gate is defined on a low-end
2022-tier Android phone and no such device was available; all three hosts are faster than that
device, so every figure below is a lower bound on what the gate device will show.

The first and largest unknown is settled: **`androidx.compose.runtime` compiled to
Kotlin/JavaScript composes and recomposes inside Zipline's QuickJS**, driving a Dogwood
`Applier` with a change recorder and a lambda slot table. [Layer 4](specs/layer-4-sandbox.md)
Milestone 1 is done. The Redwood Treehouse instrumentation that 0.2 proposed as a cheap start
was **not** performed: its purpose was to get a first number quickly, and the harness measures
Dogwood's own reference screen through a Dogwood applier, which is the number the project
actually needs. The samples remain available in Cash App's repository if a cross-check against
an independent guest is later wanted.

Provisional readings, reference screen at 23 rows (160 widget nodes, 572 changes), development
host:

| Gate leg | Budget | Measured | Reading |
|---|---:|---:|---|
| 0.2 recomposition, p95 | 8 ms | **1.67 ms** | within budget |
| 0.3 crossing — **the leg as ruled**: steady-state per-tap batch | 4 ms | **0.12 ms** | within budget |
| 0.3 whole-screen initial batch (not this leg; a cold-start cost) | — | 24.34 ms → **1.23 ms** with the v1 encoding | driven down 95% |
| 0.4 maximum collection pause, p99 | 16.7 ms | **1.45 ms** | within budget |
| 0.4 maximum collection pause, worst single sample (Pixel 10 Pro) | 16.7 ms | **22.1 ms** | outside a 60 Hz frame |
| 0.1 cold start to first composition | 500 ms | **128 ms** | within budget |
| 0.1 + 0.3 cold start until the host holds the tree | 500 ms | **155 ms** | within budget |

Payload: 1,089,616 bytes of QuickJS bytecode, 2,430,996 bytes of minified JavaScript
(316,439 gzipped); QuickJS heap 4.2 MB after module load and 5.7 MB after the first
composition. Module load is 14.4 ms.

### What the physical device changed

Running on real silicon produced a result worth more than the gate table: **the interpreted
guest work is effectively hardware-independent across the two high-end hosts.** Against the
Apple silicon development machine, the Pixel 10 Pro measured:

| Measure | Ratio, Pixel ÷ development host |
|---|---:|
| Initial composition, 160 nodes | **1.00×** |
| Recomposition, one-node diff | **0.92×** (the phone is *faster*) |
| Guest-side encoding of the initial batch, native path | **1.04×** |
| Module load | 1.94× |
| Transport of a pre-built 20 KB string across the Java Native Interface (JNI) | 3.42× |

Everything that runs *inside* QuickJS matches; everything that crosses into the platform is
slower on Android. That tells us the guest workload is **bound by single-core instruction
throughput**, not by memory bandwidth, platform, or input/output. Which has a sharp consequence
for the gate: **the gate device's numbers will track its single-core performance close to
linearly**, and a low-end 2022 entry phone is several times slower per core than either host
measured here. The multiplier must be measured rather than guessed — but the direction is not
in doubt.

**That reprioritises which leg is actually at risk.** The 0.3 crossing looks worst today, and
it has an obvious lever: cost is linear in bytes, so trimming the payload buys time
proportionally. The **0.2 recomposition leg has no such lever.** It measures 1.58 ms at p95
against an 8 ms budget — comfortable here, and comfortable at a 3× slower core (~4.7 ms), and
*at the edge* at 5× (~7.9 ms). If the gate device is slower than that, the only remedies are a
faster substrate — which means reopening [ADR-002](adrs/layer-4/ADR-002-adopt-zipline-quickjs-substrate.md)
for Hermes — or a smaller reference screen, and the roadmap already names slow recomposition as
fatal rather than maskable. **Acquiring the named gate device is therefore the highest-value
remaining task in Phase 0**, and it is a hundred-and-fifty-dollar purchase, not an engineering
problem.

One more thing the physical device showed that neither the Mac nor the emulator did: the
**tails are noisier**. At `gcThreshold` 16 MB a forced collection reached 22.1 ms — beyond a
60 Hz frame — and at 8 MB a single recomposition reached 12.2 ms against a p99 of 1.70 ms. The
harness cannot currently tell a collection pause from scheduler preemption, which is precisely
what the patched-QuickJS `JS_RunGC` hook this appendix specifies would settle. That hook is now
worth building rather than deferring.

**The 0.3 finding, and the fix that came out of it.** 99.4% of a crossing is guest-side
JavaScript Object Notation (JSON) encoding; `CallChannel` transport is 1.0%
([ADR-006](adrs/layer-4/ADR-006-batch-crossing-is-guest-encoding.md)). An encoding bake-off over
six candidate wire formats then found the lever
([ADR-007](adrs/layer-4/ADR-007-v1-wire-format-positional-json.md)): **positional JSON built as
native JavaScript values and handed to QuickJS's own `JSON.stringify` crosses the whole initial
batch in 1.23 ms instead of 24.02 ms — a 95% reduction**, confirmed at roughly seventeen-fold on
the Pixel. The governing variable is not byte count but **how much interpreted Kotlin runs while
encoding**; an earlier "cost is linear in bytes" reading was measured and withdrawn.

Protocol buffers and Concise Binary Object Representation (CBOR) were measured and **rejected**:
both are slower than the JSON they would replace, because their encoders are pure Kotlin and run
interpreted, and both must be Base64-encoded to cross a string channel — which leaves protocol
buffers *larger* on the wire than positional JSON as well as 45% slower.

**Three things are outstanding before Phase 0 can be called complete:**

1. ~~**Acquire and run the named gate device.**~~ **Decided: the device will not be acquired, and
   the gate is formally not closed** ([Layer 4 ADR-008](adrs/layer-4/ADR-008-gate-device-not-available.md)).
   Work proceeds with the shortfall carried as a standing risk rather than discharged. The
   specific risk is **0.2, recomposition** — 1.58 ms at p95 against 8 ms, comfortable at a
   three-times-slower core and at the edge at five times, with the multiplier unmeasured. The
   harness runs on any Android device and writes a results file, so the first low-end device it
   ever meets closes or reopens the gate.
2. ~~**Rule on what the 0.3 leg bounds.**~~ **Ruled.** The 4 ms bounds the **per-tap** crossing —
   the steady-state recomposition batch — which measures **0.17 ms** on a Pixel 10 Pro and
   passes. The whole-screen initial batch is a once-per-screen cost and belongs to the
   cold-start budget, where it is measured at 191 ms of 500 ms on the same device. The initial
   batch is nevertheless to be driven down as far as it will go; see the wire analysis in
   [`tools/phase0/results/wire-format.md`](tools/phase0/results/wire-format.md) and
   [ADR-006](adrs/layer-4/ADR-006-batch-crossing-is-guest-encoding.md) §2.3.
3. ~~**Measure host-side decode of the v1 positional format.**~~ **Done: 0.17 ms**, against
   1.14 ms of guest encode. The complete positional path is ≈ 1.4 ms where today's is ≈ 24.2 ms.
   [ADR-007](adrs/layer-4/ADR-007-v1-wire-format-positional-json.md) is Accepted.
4. **The two parallel tracks below** — the Apple Developer Technical Support incident and the
   iOS organisation's written yes — neither of which is engineering work.

The patched-QuickJS `JS_RunGC` hook was not built; 0.4 used the forced-`gc()` fallback this
appendix permits, and reports which method produced its numbers. The Pixel's tail behaviour
above is the argument for building it: without the hook, a 22 ms outlier cannot be attributed
to garbage collection rather than to the scheduler.

### Phase 0 Harness Appendix — the decisions the experiments depend on

Fixed here so two teams running Phase 0 produce comparable numbers, and so no instrumentation choice is invented after seeing results.

**Pinned toolchain.** Zipline **1.27.0**, Kotlin **2.3.x** (whatever 1.27.0 was built against — Zipline pins its Kotlin), `androidx.compose.runtime:runtime-js` **1.12.0** (latest stable per [Layer 4 ADR-003](adrs/layer-4/ADR-003-treehouse-precedent-and-evidence-refresh.md)), matching `runtime-saveable-js`, current stable `kotlinx-coroutines-core-js` and `kotlinx-serialization-json-js`. Record exact versions in the results file; do not float any of them mid-phase.

**Named devices.** One low-end Android device of roughly 2022 entry tier (target: a Samsung Galaxy A14 or the nearest device the team owns — name the actual model in the results) and one iPhone SE (3rd generation). The Android device is the gate device; the iPhone provides the early iOS datapoint per the platform-order section.

**Timing mechanism (0.2).** The pinned QuickJS has no `performance.now` and `Date.now` is millisecond-granular, so: the host injects a **monotonic clock Zipline service** backed by `System.nanoTime` / `CLOCK_MONOTONIC`. Its own crossing cost is measured first (call it in a tight loop of 1,000; report the median) and subtracted. Timers wrap the recomposition body inside the guest — start after event dispatch, stop when the change batch is handed to `sendChanges` — so bridge cost is excluded from the recomposition number and measured separately in 0.3. Per measurement: **200 iterations after 20 warm-up iterations**; report p50/p95/p99. "Cold" = first composition after fresh `Zipline` instantiation; "warm" = steady-state recomposition thereafter.

**Garbage-collection hook (0.4).** Zipline's public Application Programming Interface (API) exposes no garbage-collection hooks, so 0.4 uses a **locally patched Zipline native build**: wrap `JS_RunGC` in the vendored QuickJS with monotonic timestamps and a counter, exported through a debug method. This is measurement scaffolding only — nothing patched ships. If the native build proves slow to stand up, `gc()`-forced pauses timed from the host bound the answer from above; say which method produced the reported numbers.

**The reference screen.** One committed Kotlin file, used by 0.1 (payload), 0.2 (composition), and 0.3 (batch): a product-detail-like screen of **~160 nodes** — a header block (image placeholder box, title, subtitle, price row), a plain `Column` of rows (each row: `Row(image-box, Column(Text, Text), Text)` — `LazyColumn` does not exist in the guest), a footer with three buttons and a selectable chip row of 8, **24 `mutableStateOf` holders** (row selection ×20, quantity, promo visibility, total, loading flag), modifier chains of 2–4 elements on every container, and one event handler per row plus three on the footer. Recomposition measurement mutates exactly one row-selection state (small diff) and, separately, the total (two-node diff). The file is written once in 0.1 and reused verbatim thereafter; it is committed at [`tools/phase0/guest/src/jsMain/kotlin/dev/dogwood/guest/ReferenceScreen.kt`](tools/phase0/guest/src/jsMain/kotlin/dev/dogwood/guest/ReferenceScreen.kt).

**The row count is 23, and 50 is measured as a second point.** An earlier draft of this appendix said "50 rows" and "~160 nodes" in the same sentence; those cannot both hold, because the specified row is six nodes (`Row`, `Box`, `Column`, and three `Text`) and the screen totals `22 + 6 × rows` widget nodes. At **23 rows** the screen is **exactly 160 widget nodes**, which is what "~160 nodes" and the gate's "150-node batch" describe, so 23 rows is the primary point and every gate leg is read against it. **50 rows** (322 widget nodes) is measured alongside, so the discrepancy costs a data point rather than an argument. Two further resolutions travel with it: chip selection derives from the `quantity` holder so the holder count stays at exactly 24, and the per-row event handler is a direct `onClick` parameter standing in for `Modifier.clickable`, whose lambda argument needs the Phase 2 modifier grammar. All three are recorded in [Layer 4 ADR-005](adrs/layer-4/ADR-005-phase-0-harness-resolutions.md).

**In parallel, costing nothing on the critical path:**
- Open a paid Apple Developer Technical Support incident on downloaded, signed, first-party interpreted payloads — framed around **Guideline 4.7** (which now governs downloaded scripting; the old 2.5.2 JavaScriptCore-exception sentence no longer exists — see [Layer 4 ADR-003](adrs/layer-4/ADR-003-treehouse-precedent-and-evidence-refresh.md)). Lead time is long; the answer is needed before Layer 3 ships, not after.
- Confirm with the iOS team that the host application will be a Compose Multiplatform application ([Layer 5 ADR-004](adrs/layer-5/ADR-004-compose-multiplatform-sole-host-target.md)).

---

## Phase 1 — Vertical Slice, Hand-Written, Android Only

**Goal:** one real screen, driven from a payload, rendering natively, responding to taps. No generator yet.

**Approximately 4–6 weeks.**

1. Hand-write recording stubs and host bindings for ten composables — five layout primitives (`Text`, `Column`, `Row`, `Box`, `Spacer`) **and five registered design-system components** (for example `PrimaryButton`, `AsyncImage`, `Card`-equivalent, badge, divider), per the design-system-first path above, using the segment/tag assignments in [Layer 4 ADR-004](adrs/layer-4/ADR-004-change-event-protocol-v0.md) §2.1 (segment 0 = layout primitives, segment 1 = design system). This exercises both dictionary segments from day one and lands a surface a product team recognises. (`Icon` is deliberately excluded — its required `Painter` is asset-gated; the design-system image component covers the need.)

   **Selecting the five design-system components** — ~~a half-day audit, done before the sprint~~ **done; see [Layer 5 ADR-008](adrs/layer-5/ADR-008-design-system-audit-backpack.md)**, which audits Skyscanner Backpack and finds five of eleven components bindable as declared, all three predicted failure classes, and two the list below did not name. The method below is the one to use with a real design system: list the ten most-used components in the company design system by call-site count; hand-apply the bindability rule ([Layer 5](specs/layer-5-host.md), "Bindability: The Real Rule") to each signature — every lambda must be a content slot or a discrete event; no live-state, callback-object, or asset parameters except a `String` image Uniform Resource Locator (URL). Expected failure classes and their fixes: a component taking `Painter` → wrap with a URL-taking variant; one taking `interactionSource` or a scroll state → expose a wrapper without it; one taking a styles object → either register the style type as a deferred-expression factory or fix the style in the wrapper. Pick the five highest-usage components that pass (or pass after a thin wrapper); record the audit table in the results file — it seeds the Phase 3 registration list.
2. Stand up the **Compose Multiplatform desktop host** alongside the Android host as the development loop; the host layer is common Kotlin, so the cost is small and the iteration payoff is immediate.
3. Implement `DogwoodApplier` over `AbstractApplier`, with `WidgetNode` and `ChildrenNode`.
4. Implement the **v0 `Change`/`Event` protocol exactly as specified in [Layer 4 ADR-004](adrs/layer-4/ADR-004-change-event-protocol-v0.md)** — `ChangeBatch` envelope with sequence numbers, the six change kinds, absence-as-default — including the lambda slot table and its depth-first reclamation. Protocol deltas discovered while building feed the ADR's v1 revision; do not fork the wire format silently.
5. Wire the frame clock, including `requestFrame()` so idle experiences produce no traffic.
6. Implement the threading contract explicitly, with dispatcher assertions on both sides.
7. ~~**Decide host rendering strategy by measurement.**~~ **Done — the snapshot mirror is kept** ([Layer 5 ADR-007](adrs/layer-5/ADR-007-keep-the-snapshot-mirror.md)). Both strategies were built and measured at batch sizes 1 / 10 / 100 / 1,000 on trees of 160 and 1,222 nodes. The imperative applier applies two to four times faster and it does not matter; it recomposes 801 bindings for a one-property change where the snapshot mirror recomposes one.
8. Implement the **minimal entry-point contract** of [Layer 4 ADR-004](adrs/layer-4/ADR-004-change-event-protocol-v0.md) §2.5: the manifest names the entry composable, `start(...)` carries serializable launch parameters, and outcome callbacks are host services — no host-directed lambdas in Phase 1.
9. ~~Deliver through Zipline properly — signed manifest, Ed25519 verification, disk cache.~~ **Done**, and extended past the step: `DogwoodSession` consumes `ZiplineLoader`'s update flow, so code update while a screen is live — which [Layer 4](specs/layer-4-sandbox.md) calls the *normal* case — works, with guest `rememberSaveable` state carried across the swap.

**Gate.** A tap-driven screen updates correctly and feels responsive on a mid-range device. Node identity survives list reordering. A state change three guest-defined wrapper layers deep crosses as a single `PropertyChange` (the wrapper-scoping test in [Layer 4](specs/layer-4-sandbox.md) Milestone 4).

---

## Phase 2 — The `Modifier` Subsystem

**Goal:** the single highest-leverage deliverable in the project.

**Approximately 4–6 weeks.**

Compose's `Modifier.Element` implementations are `internal`, so Dogwood defines its own tagged, serializable modifier type. Under the design-system-first path its near-term justification is concrete: the Phase 1 slice's call sites need layout modifiers (`padding`, `weight`, `fillMaxWidth`) on both registered and generated components, and the generator's output shape depends on the modifier representation. Long-term it remains the single highest-leverage shared subsystem — 62.2% of the measured widget surface (the generator-v2 Material tier) depends on it and on nothing else bespoke ([corrected measurement](adrs/layer-5/ADR-005-corrected-coverage-and-bespoke-subsystem-list.md)).

**Sequencing dependency found in review:** modifier arguments are themselves deferred expressions — `clip(RoundedCornerShape(8.dp))` and `background(brush)` carry `Shape` and `Brush` values that only the deferred-expression protocol can represent. Phase 2's implementation is therefore **scoped to modifiers whose arguments are primitives and value classes** (`padding`, `fillMaxWidth`, `weight`, `alpha`, `size`), and the **deferred-expression grammar ADR is written in this phase**, jointly with the `Modifier` ADR, so the Phase 3 generator consumes a settled pair.

1. ~~Write the two ADRs together.~~ **Done:** [Layer 5 ADR-009](adrs/layer-5/ADR-009-modifier-subsystem.md) (tag space, `then()` semantics, ordering, scope) and [ADR-010](adrs/layer-5/ADR-010-deferred-expression-grammar.md) (the grammar), written jointly.
2. ~~Implement the guest-side modifier type and chain builder.~~ **Done.**
3. ~~Implement host-side reconstruction into real Compose modifiers.~~ **Done**, including `clip` and `background`, whose arguments are deferred expressions evaluated host-side with a bounded memo.
4. ~~Handle scoped modifiers.~~ **Done, and more strongly than asked.** `weight` and `align` are members of guest scope receivers supplied by each container, so out-of-scope use does not compile. The roadmap asked for a build error; this makes the mistake **unwritable**, which needs no diagnostic and mirrors the mechanism Compose itself uses.

**Gate.** ~~Arbitrary chains of the in-scope modifiers produce pixel-identical output to the same chain written statically.~~ **Met at the protocol level, not at the pixel level, and the difference is stated rather than glossed:** the tests assert that an arbitrary chain crosses in order with its arguments intact, that two chains differing only in order cross differently, that a scoped modifier crosses with its scope intact, and that an expression argument crosses as a recipe. **Pixel identity is not asserted** — screenshot testing does not exist in this project, and claiming it without one would be a claim nobody checked. Expression-argument modifiers arrived early rather than waiting for Phase 3: `clip` and `background` work now, because the grammar they need had to be settled here anyway.

---

## Phase 3 — The Generator

**Goal:** replace hand-written bindings with generated ones, and stop the registry from ever being hand-maintained again.

**Approximately 6–8 weeks — for generator v1.** Per the design-system-first path above, v1 targets **registered modules plus `foundation-layout`**: first-party Kotlin sources, no metalava, curated signatures, few `@Composable` defaults. The full Material tier — the defaults-expression problem in its general form — is **generator v2**, scheduled after the first production screen ships, and its 6–8 week estimate should be treated as a floor (see the effort-realism note at the end of this document). The multi-segment dictionary (namespaced tag spaces, per-segment versions — [ADR-006](adrs/layer-5/ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md)) is a v1 requirement, not a later refinement, because the vertical slice already spans two segments.

1. ~~Build the surface parser on the Kotlin frontend.~~ **Done.** `dogwood-codegen` parses Kotlin *source* with the compiler's own frontend, applies the bindability rule, and classifies every parameter as value, modifier, slot, event, expression or unsupported — including all five failure classes the [Backpack audit](adrs/layer-5/ADR-008-design-system-audit-backpack.md) found in the wild. It reads source rather than a metalava dump for the reason [Layer 5 ADR-002](adrs/layer-5/ADR-002-standalone-codegen-tool-not-ksp.md) gives: dumps carry no default expressions.
2. **Three of the four artifacts, and the fourth is not needed by v1.** The **versioned dictionary**, the **guest stubs** and the **host binding layer** are all emitted from one parsed model. **Guest-side value-type stand-ins** (`Dp`, `Color`, `TextStyle`) are not emitted, and generator v1 does not need them: the registered surface uses primitives deliberately. Generator v2's Material tier will need them, because those types run through its signatures.
3. ~~Implement the deferred-expression protocol per the grammar ADR.~~ **Done in Phase 2** for modifier arguments ([ADR-010](adrs/layer-5/ADR-010-deferred-expression-grammar.md)), evaluated host-side with a bounded memo. The general case — `@Composable` defaults, expressions over live state — is scoped there and unbuilt.
4. ~~Implement the "use host default" sentinel.~~ **Done and in service.** The parser recognises a default the host must resolve; the emitted stub sends nothing for it and the emitted binding reads absence as null so the implementation chooses. This is what caught the two latent null bugs described in [ADR-011](adrs/layer-5/ADR-011-generator-emits-the-bridge.md) §3.
5. **Layer 1's dictionary checker is built; Layer 2's per-version builds are not.** Every generation compares against a committed lock file and **fails the build** on a renumbering or removal, naming the component and both tags. Reordering two declarations is an innocent-looking edit that a reviewer has no reason to flag, and a renumbered tag renders the wrong widget rather than failing to render, so the check is mechanical rather than a convention.

**Phase 3 is substantially built, and the substitution has happened.** Nine of the eleven components in the registered segment are bound by generated code with no hand-written dispatch on either side. Two remain hand-written — the lazy containers, whose children must be composed inside the host's `items` block, a slot shape the generator does not model. That is the same boundary the [Backpack audit](adrs/layer-5/ADR-008-design-system-audit-backpack.md) found: laziness is not absorbable by registration.

**Gate — met for generator v1.** Generated bindings reproduce the Phase 1 and 2 behaviour exactly: the substitution is done, the forty existing tests pass unchanged, and the sample screen renders identically to the hand-written version. A Compose version bump is not reproducible in a test, but the property that matters under one is tested directly — when the surface gains a component and an optional parameter, every existing widget, property and event tag keeps its number, and the build fails if one moves.

**What the gate does not cover, and should not be read as covering:** this is generator v1, whose target is *registered modules with curated signatures*. The full Compose surface is generator v2, its parse needs resolved types rather than declaration text, and the roadmap's "6–8 weeks is a floor" applies to that, not to this.

---

## Phase 4 — Bespoke Subsystems

**Goal:** reach the components developers actually reach for. This is the larger half of the total work, and it is incremental — each subsystem ships independently.

✅ **All nine subsystems are delivered**, each with an Architecture Decision Record, tests, and a
verification pass on a device. That does not mean each is *finished* — every row below names what
it does not yet cover, and those limits are the honest boundary of what a product can build today.

Five of the nine turned up defects that reading could not have found, which is the argument for
verifying on a device rather than in a suite:

| Subsystem | What only a device (or a real composition) showed |
|---|---|
| Host services | A suspending call resumed, wrote state, and **nobody was left to notice** — the fetch completed and the screen sat on "Loading…". Layer 4 had assumed every guest state change began with a host call. |
| Live-state holders | A restored scroll position was saved, restored, and **still lost**: the replacement guest declares its target while its content is still loading, so `scrollToItem` clamped to a two-item list. |
| Node reuse | `key` is **silently defeated by a per-child conditional**, because a movable group can only be matched among its immediate siblings. Nothing warns, and the screen still renders. |
| Leak detection | The instrument found a real leak on its first run: the lazy list's viewport reporter was **still reporting to the previous, closed guest** after a code update, holding a whole QuickJS heap alive. |
| Resources, text input, animation | A tag collision would have rendered every list as an icon; a masked field transposed digits through a real keyboard that the test harness could not reproduce; and the generated dispatch had been **building every layout primitive's modifier chain twice** since it shipped. |

**Prioritise by real usage in your own product, not by this order.**

| Subsystem | Notes |
|---|---|
| Host environment ✅ | **Delivered** ([ADR-012](adrs/layer-5/ADR-012-host-environment-subsystem.md)). Density, font scale, layout direction, dark mode, safe-area insets, viewport size and **locale**, derived from Compose Multiplatform ambients in common code and pushed into the running guest; `Palette` became a value so colour tokens resolve in theme; `WidthClass` and `language` shared through the protocol module so both sides agree. Verified live on the emulator: rotation re-lays out the guest with **zero** reloads and no lost state. **Not covered:** a richer theme identity than a dark-mode boolean, and host-served locale formatting, which belongs to resources. |
| Resources & assets ✅ | **Delivered** ([ADR-017](adrs/layer-5/ADR-017-resources-and-assets.md)): icon dictionary, typography tokens (the font story under design-system-first), payload-carried string tables, and locale-aware **formatting as deferred expressions rather than a service** — money crosses as minor units and a currency code, because the host is the side that knows `JPY` has no decimal places. Found and fixed a generator defect on the way: adding `Icon` took a local tag `VerticalList` already owned, so a tag collision would have rendered every list as an icon. Tags in a segment can now be reserved, and the lock fails a collision, a withdrawn reservation, or an addition without a version bump. ✅ **Recipes now reach every generated component** ([ADR-021](adrs/layer-5/ADR-021-host-resolved-values.md)): `TextValue`, `Color` and `Shape` are surface types the generator carries, and the lock tracks parameter types so a widening cannot ship silently. ✅ **Configurable patterns and plural rules added** ([ADR-024](adrs/layer-5/ADR-024-configurable-formatting-and-plurals.md)): a number pattern ships over the air and renders with the device's symbols; plurals split so the host picks the category and the payload owns the words. **The theme is now a document** ([ADR-026](adrs/layer-5/ADR-026-the-theme-is-a-document.md)): token values ship as data over any channel, with field-by-field degradation and a last-known-good cache, so a rebrand is no longer an application release. **Remaining:** `AsyncImage` still has no placeholder or error slot. Original scope: blocks the first real screen **on the generated-tier path**; under design-system-first, a registered `AsyncImage(url)` covers images day one and this subsystem generalises it later ([ADR-006](adrs/layer-5/ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md)). Uniform Resource Locator (URL)-keyed host image loading with placeholder/error slots (Redwood's `Image(url)` shape), icon dictionary, fonts, localized strings. See [ADR-005](adrs/layer-5/ADR-005-corrected-coverage-and-bespoke-subsystem-list.md). |
| Host services & entry points ◐ | **Two thirds delivered** ([ADR-013](adrs/layer-5/ADR-013-host-services-and-entry-points.md)): named entry points with serializable launch parameters, and a versioned, individually-optional service surface — log, clock, feature flags, analytics, and a suspending network service whose host implementation defaults to refusing every request. Uncovered and closed a Layer 4 gap: guest-originated state changes never woke the frame loop, because until services existed every change began with a host call. **Remaining:** the host-registered-component mechanism, item (c). Original scope: launch contract (serializable parameters, named entry point in the manifest), versioned service surface (network, auth, analytics, logging, feature flags, clock), and the host-registered-component mechanism (run the generator over the host's own design-system modules, each landing in its own namespaced dictionary segment — [ADR-006](adrs/layer-5/ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md)). The Phase 1 entry-point contract ([Layer 4 ADR-004](adrs/layer-4/ADR-004-change-event-protocol-v0.md) §2.5) is the seed this subsystem grows from. |
| Live-state holders ◐ | **`LazyListState` delivered** ([ADR-014](adrs/layer-5/ADR-014-live-state-holders.md)), and with it the pattern the rest are meant to share: targets down as ordinary properties, reports up per item rather than per pixel, host authoritative, conflict rule structural rather than enforced. Scroll position survives a code update. **Remaining:** `FocusRequester` next, then the other ~28. 25.2% of the widget surface needs some bespoke protocol; ~30 holder types plus 76 `remember*` factories. |
| Lazy layouts | Guest-side windowing, placeholder pool, throttled viewport callbacks. Redwood needed ten modules for this alone — budget accordingly. |
| Text input ✅ | **Delivered** ([ADR-019](adrs/layer-5/ADR-019-text-input.md)). Host-authoritative text, guest holds a version-stamped mirror, stale guest values discarded. Masks, limits and counters declared once and applied where the typing is. **A device found a bug the harness could not**: with the mask applied to the field's value, the caret arithmetic was the binding's and typing sixteen digits produced transposed ones — fixed with a `VisualTransformation` that hands the arithmetic back to Compose. **Remaining:** one simple mask language, and no way for a guest to place or read the caret. Original scope: version vector plus optimistic host state. Do not attempt a naive controlled `TextField`. Scope must also cover declarative masks/formatting (card numbers) and host-computed counters — per-keystroke guest round trips are forbidden by the Layer 4 invariant. |
| Animation ✅ | **Delivered** ([ADR-020](adrs/layer-5/ADR-020-animation.md)). Declared targets on seven modifier arguments, named springs and easings, completion events on the element's chain position, and interruption semantics **inherited from Compose rather than invented** — retargeting is what `animateFloatAsState` already does, which is the strongest argument for declaring targets instead of starting animations. One crossing per animation, whatever its duration. Exposed a bug present since the generator shipped: the generated dispatch built the modifier chain before checking the tag, so every layout primitive built its chain twice — invisible until a duplicate chain meant a duplicate animation. ✅ **Colour and repetition added** ([ADR-022](adrs/layer-5/ADR-022-animated-colour-and-repetition.md)): an animated colour is still a `Color`, so every colour parameter accepts one; `oscillate(from, to)` covers skeletons and spinners. ✅ **Enter and exit added** ([ADR-023](adrs/layer-5/ADR-023-enter-and-exit.md)): a `Presence` container plus an `onExited` handshake, rather than applier retention which would break batch index arithmetic. **Remaining:** no shared-element transitions. Original note: the largest addition from re-review. Less urgent under design-system-first — registered components own their internal transitions — but required for any guest-authored motion. Declarative targets, springs/easings, interruption semantics, completion events, time-varying `Modifier` values. Until it ships, Layer 1 rejects the `animate*` Application Programming Interfaces — the product promise "animation without a release" is **not true on day one**. |
| Node reuse ✅ | **Delivered** ([ADR-015](adrs/layer-5/ADR-015-node-identity-and-reuse.md)). The mechanism was already built; what was missing was the test, and the project had no way to run a host composition off a device. It has one now — `compose.uiTest` in the ordinary unit-test suite — with a negative control. Found that `key` is silently defeated by a per-child conditional, which is a live generator hazard, and closed [ADR-009](adrs/layer-5/ADR-009-modifier-subsystem.md)'s open claim that modifier order affects layout. |
| Leak detection ✅ | **Delivered** ([ADR-016](adrs/layer-5/ADR-016-leak-detection.md)). `redwood-leak-detector` adopted as written, behind a one-method Dogwood interface because Redwood marks the type internal-only and the project is discontinued. Watches detached subtrees and replaced guest generations. **It found a real defect on its first run**: the lazy list's viewport reporter was still delivering reports to the previous, closed guest after a code update, holding an entire QuickJS heap alive. The iOS cross-language hunt still belongs to Phase 6; this is the instrument being in place first, which is what the sequencing asks for. |

---

## Phase 4.5 — Experience Composition & the Host Shell

**Complete** — [`plans/experience-composition.md`](plans/experience-composition.md), decided in
[ADR-027](adrs/layer-5/ADR-027-the-host-shell-and-warm-experiences.md),
[ADR-028](adrs/layer-5/ADR-028-guest-initiated-navigation.md) and
[ADR-029](adrs/layer-5/ADR-029-a-null-service-cannot-cross.md). The demo's tabs surfaced a question
the architecture had only answered implicitly, and both answers are now first-class:

- **Path A — one experience, many screens**: the guest owns navigation as ordinary Compose; state
  shares freely; already works and costs nothing new.
- **Path B — many experiences**: independent teams, cadences, or isolation requirements; each its
  own QuickJS runtime. Switching used to pay a full cold start and leak the prior session; it now
  goes through the shell and costs nothing the shell controls.

Delivered: a host **shell** retaining warm experiences, a **navigation service** so a guest can ask
the host to route, and **two reference examples** a product team can copy — `AppShell.kt` in the
guest for Path A, `TabsActivity.kt` in the Android sample for Path B.

**Measured on device.** A warm switch costs the shell **0 ms, taken synchronously**; what remains
is Compose drawing an already-applied tree, which a native tab switch of the same content would
also pay. What the shell removes is the **140–650 ms of guest cold start** that made Path B
unusable. Each warm experience costs **9–14 MB**, which set the default cap at three. Hidden
experiences requested **zero** frames over twenty idle seconds, so keeping one warm costs memory
and nothing else. Two experiences render at once from two runtimes on one Zipline thread, with no
cross-contamination.

The plan's "switching costs one frame" target was optimistic rather than the result being poor: it
counted the swap and forgot the draw.

**Five defects were found only by running the code**, three of them in subsystems this work was not
aiming at — a `rememberSaveableStateHolder` whose state could not cross the boundary *at all*, an
eviction that freed nothing, a host environment derived per window rather than per surface, an
`onTrimMemory` calling through a null reference, and — the oldest — a **null host service crashing
the guest at start**, which falsified "every service is optional and its absence is normal", a
property documented since [ADR-013](adrs/layer-5/ADR-013-host-services-and-entry-points.md) and
never once exercised because every host in the repository wired every service.

Precedes the Web host build, which will mount experiences through the same shell.

---

## Phase 5 — Web Host (Second Shipping Target)

**Approximately 4–6 weeks**, dominated by the web-profile design rather than the host itself. Per the platform order above, Web precedes iOS.

1. ✅ **The web profile is designed** — [ADR-032](adrs/layer-5/ADR-032-the-web-profile.md). The guest is ordinary JavaScript in a **Web Worker**; the host is Kotlin/WebAssembly with Compose Multiplatform on the main thread; the bridge is `postMessage` carrying the **same positional protocol**, since the measurements show no per-platform transport is justified. The dictionary check moves to a sidecar manifest verified before the Worker is created. Isolation is the Worker (no document) plus a Content Security Policy making the network allow-list browser-enforced, with the specification's sandbox claim amended to say it is weaker here rather than left to imply parity. Integrity is the named gap: HTTPS authenticates the server but does not give what Ed25519 manifest signing gives. Original framing follows.

   **Design the web profile as an ADR first.** The guest loads into the browser's JavaScript engine directly — no QuickJS, no `.zipline` bytecode — and delivery and integrity ride ordinary web deployment (same-origin scripts over Hypertext Transfer Protocol Secure (HTTPS)) rather than `ZiplineLoader` and Ed25519 manifests. The dictionary check must still gate loading; specify where it runs.
2. ✅ **A Compose Multiplatform Web host runs**, verified in a real browser — `engine/dogwood-web/`, sample and harness at `engine/samples/web-slice/` (`run.sh` asserts rather than prints). A JavaScript guest in a Worker sends the **unchanged positional batch** over `postMessage`; the Wasm host decodes it, applies it to a snapshot mirror, and Compose Multiplatform draws it — asserted on measured glyph boxes and on 2,304 pixels at exactly the colour the guest asked for. The dictionary check refuses a too-new payload with the guest script **never fetched**, let alone executed. **Time to first frame is no longer unmeasured: 331–468 ms** — but over loopback, uncompressed, under SwiftShader, so indicative rather than the adoption number.

   What is **not** built, and the honest scope of that number: no real Kotlin guest (the guest is hand-written JavaScript), no `DogwoodServices`, and no design system — five layout bindings only, so no expression evaluator, palette, lazy containers or text recipes. `dogwood-host` cannot compile for WebAssembly today for four separable reasons: Zipline in its common source set, threading actuals, Coil and OkHttp, and generated bindings that must avoid Java Virtual Machine types. Once those are addressed most of `dogwood-web` deletes itself, because `HostTree`, `WidgetView`, `Bindings`, `Modifiers`, `Expressions` and `Theme` are already platform-neutral Compose.

   Original framing: bring up the Compose Multiplatform Web host with the unchanged protocol, applier, generated bindings, and registered design-system segments. Constraints verified in [Layer 4 ADR-003](adrs/layer-4/ADR-003-treehouse-precedent-and-evidence-refresh.md): **Compose Multiplatform for Web is Beta**, and `material3-wasm-js` trails at `1.12.0-alpha03` (less limiting under the design-system-first path, which leans on registered components rather than Material).

   **Page weight has now been measured and does not block this phase** — [ADR-030](adrs/layer-5/ADR-030-web-page-weight-measured.md), harness at [`tools/web-weight/`](tools/web-weight/). A Compose Multiplatform page linking Material 3 is **10.23 MB raw, 2.92 MB brotli**, of which **81% is `skiko.wasm`**, a prebuilt JetBrains artifact no amount of Dogwood code discipline can shrink. The community figure was right about compressed and understated raw: ~8 MB uncompressed is the Skiko blob alone, not the page. The practical consequence is that page weight is a **fixed entry toll** rather than something that scales with how much design system a team registers.

   **And that toll has now been timed** — [ADR-038](adrs/layer-5/ADR-038-first-frame-is-transfer-bound.md), harness at [`tools/web-ttff/`](tools/web-ttff/). Ten cold loads per preset under Chrome's own throttling: the first frame arrives in **135 ms unthrottled, 3.2 s on 4G, and 17.4 s on Fast 3G**, with 3,103,293 bytes transferred identically in every load. Transfer outweighs everything the host does — decompression, WebAssembly compilation, first composition — by roughly a hundred to one, so **page weight is the only lever**, which is what ADR-030 assumed and could not show. Seventeen seconds is a product constraint rather than a benchmark result, and it belongs in the decision to choose this profile rather than after it.

   **What still gates the phase is time to first frame**, which is unmeasured. Bytes are only a proxy for waiting, and the harness deliberately reports none — measuring it needs a real browser with a graphics context on a representative machine, and two plausible shortcuts (headless Chrome, `WebAssembly.compile` under Node) each produce numbers that are wrong in the direction that flatters the result. The page carries a `#dogwood-first-frame` probe so that doing it properly needs a browser and a network, not new code.
3. **Toolchain hazard, found while measuring the bridge and unresolved: Kotlin 2.3.20's production `wasm-opt` pass list silently miscompiles `String.toCharArray()`.** It returns an array of the correct length filled with zeros. Reproduced from a clean build in [`tools/web-weight/bridge/`](tools/web-weight/bridge/): the default pipeline yields `viaBulkCopy = 0` where a per-character read of the same 28-byte input yields `1219597841`; removing `--gufa` from the pass list makes both agree. GUFA does not model the imported `wasm:js-string intoCharCodeArray` builtin as mutating a WasmGC array, and `--closed-world` with `-O3` must precede `--gufa` to trigger it. **It is context-sensitive** — adding an unrelated caller of `toCharArray()` made it vanish and removing that caller brought it back — so it cannot be reasoned about locally, and **the blast radius is unknown**: any WasmGC array written by an imported builtin is a candidate. This is a wrong-answer bug in a release build, not a performance problem, and it must be resolved or bounded before a Web host ships. It is also why the bridge harness carries a correctness gate: without one the run would have reported a fabricated four-times speed-up for the miscompiled path.

4. **The bridge itself is measured and is not a problem** — [`tools/web-weight/results/bridge.md`](tools/web-weight/results/bridge.md). Passing a string from JavaScript into Kotlin/Wasm costs **0.009 µs regardless of size** (flat from 106 bytes to 16 KB), because `kotlin.String` is a `JsString` externref under `builtins: ['js-string']` — there is no transcoding to pay for. String versus bytes is a tie at every size; the only real win is dropping text entirely, and the structured JavaScript-object path that avoids serialisation altogether is the *worst* option, 2.4x behind, because each element read is an imported call. **The mobile conclusion survives as an outcome but not as an argument**: the cost split inverts from encoding 99.4% / transport 1.0% to encoding 44.7% / transport 0.02% / **decoding 55.3%**. A per-platform transport is not justified — the worst path costs 0.71% of a frame and steady state under 0.005% — so one logical protocol stands.

5. Hardening drills that need no new platform: key rotation (ship a manifest with two signatures, roll clients forward, retire the old key), the skew containment drill — ✅ **done** ([`tools/skew-drill/`](tools/skew-drill/)): a version 8 payload served to an installed version 7 client kept index arithmetic across a placeheld unknown widget, ignored an unknown property on a widget with no affordance, and **withheld** a button carrying an unknown property on a widget that owns one, reporting both. It also found that no sample read `SkewReport` at all, so the containment was working and invisible, and that the report must be sampled rather than observed, and guest state preservation across code update, backgrounding, and process death — ✅ **done** ([ADR-010](adrs/layer-4/ADR-010-state-that-outlives-the-process.md)); verified on device by setting state, backgrounding, `adb shell am kill`, and relaunching to find it intact.

---

## Phase 6 — iOS Parity

**Approximately 4–6 weeks**, entered only with the iOS organisation's written yes and the Apple Guideline 4.7 answer in hand (both sought in Phase 0 — see the platform order above).

1. Bring the full path up on iOS. Confirm VoiceOver, the input method editor, and text selection work with no Dogwood-specific code — they should, because Compose Multiplatform owns the layout tree.
2. Run the leak suite across the language boundary — cross-language reference cycles span Kotlin/Native garbage collection and Swift reference counting, which is why the leak-detection milestone in Phase 4 lands **before** this phase.
3. Re-run the Phase 0 performance suite on current iOS hardware against the numbers captured in Phase 0, so any drift is caught against a baseline rather than discovered by users.

---

## Phase 7 — Cross-Client Conformance & Web Parity *(current)*

Phases 0–6 built the engine and brought it up on three shipping clients plus the desktop
development loop. What they did not produce is *consistency*: every verification instrument was
built on the client where a problem happened, so accessibility was asserted on iOS and nowhere
else, skew containment ran once on Android, and the web host — its own tree, bindings and decoder —
inherited almost none of the mobile evidence. The first day of grading all clients against one
capability list found a real cross-client defect (Android announcing a text-field label twice,
because Material 3 merges semantics differently per platform) and exposed that **web is graded on
8 of 28 claims**.

**The conformance rollout is complete** ([`plans/conformance.md`](plans/conformance.md), [Layer 5
ADR-040](adrs/layer-5/ADR-040-conformance-is-a-catalogue-not-a-suite.md)): one numbered claim
catalogue, one text report grammar, per-client instruments, a generated matrix, and gates in two
places — tier S in continuous integration on every push, tier C locally and before a release
through `tools/conformance/run-all.sh`. Every claim group now has real-client evidence on the
platforms it applies to, and the matrix is generated from real runs rather than maintained by hand.

It found four defects no existing test could have caught, and each was a cross-client question:
a text input reaching VoiceOver with no name, the fix for it making Android announce the label
twice, a blocked redirect that refused explicitly on Android and returned a bare `302` on iOS, and
a missing `HostTree.clear()` the mobile hosts had never needed because they build a fresh tree per
experience.

**Web parity is the build-out this phase ends with**, and the deciding question is decided:
**split `dogwood-host` at the Zipline seam** ([Layer 5
ADR-041](adrs/layer-5/ADR-041-one-host-core-split-at-the-zipline-seam.md)), measured by spike —
only 2 of 26 common files import Zipline, and everything else including the generated
design-system bindings compiled for `wasmJs` unmodified. The work, in order:

1. ✅ **The split.** Done as a `ziplineMain` source set rather than two modules — same seam, no
   consumer changes (ADR-041 §2). `commonMain` is transport-free and compiles for `wasmJs`; the
   two stranded service constants moved to `dogwood-wire`; `DogwoodLeakWatcher`'s interface is in
   core with the Redwood implementation in the Zipline layer. The `Intl`-backed `Format` actuals
   landed with it. Verified: 484 tests green, full build green, and the seam is enforced by
   compilation — a `ZiplineService` reference added to a core file fails the `wasmJs` build.
2. ✅ **The web actuals.** `Format` over the browser's ECMA-402 `Intl` (an upgrade — QuickJS has
   no `Intl`; the browser does), `ThreadIdentity`, and `BrowserFileSystem`, an Okio file system
   over `localStorage` so saved state survives a tab close. Verified in headless Chrome: 10 tests,
   and the negative control — reverting to the in-memory file system — turns 5 of them red.
3. ✅ **`dogwood-web` consumes core**, and the web host gained leak detection on the way
   (`BrowserLeakWatcher`) — the one gap the split had left open, closed once the assumption behind
   it was tested rather than restated. `WebTree` and `WebBindings` deleted — 627 lines of second
   implementation. The Worker bridge, sidecar loader and fast decoder stay, being genuinely
   web-shaped. Three things moved *into* core on the way, each because the web host had them and
   no other client did: `HostTree.describe()`, `HostTree.clear()` (which fixed a real bug the
   mobile hosts never met, because they build a fresh tree per experience), and `RenderTranscript`,
   whose measured glyph boxes are the difference between "the bindings ran" and "the screen is not
   blank". Gate met: the web slice renders (7 nodes, 7,017 non-white pixels, the guest's swatch
   colour present), the refusal path still refuses before a Worker exists, and the conformance
   matrix's web column went from **8 claims to 27**.

This does **not** reopen the mobile substrate decision ([Layer 4
ADR-002](adrs/layer-4/ADR-002-adopt-zipline-quickjs-substrate.md)): shared *source*, not shared
*runtime*. ADR-041 records the boundary and why Wasm is also no hedge against the Apple risk.

**Deferred engineering, carried here from the records that deferred it:**

- **The remaining live state holders** — `LazyListState` is built; ~30 holder types remain, each
  needing mirrored state and a conflict rule ([ADR-014](adrs/layer-5/ADR-014-live-state-holders.md),
  subsystem 4, ◐).
- **The patched-QuickJS `JS_RunGC` hook**, so a tail outlier can be attributed to collection
  rather than the scheduler (Phase 0 appendix).
- **Investigate web page-size reduction.** *(Last item of Phase 7 — measured, scoped, and
  deliberately last, because nothing above it is blocked on it.)*

  **The measurement.** The shipped slice is **3,573,294 bytes brotli**, all of it fetched before
  the first frame — nothing is lazy, because Compose cannot draw without Skiko and Skiko does
  nothing without the application. First frame, three cold loads per preset:

  | Connection | First frame |
  |---|---|
  | unthrottled | 139 ms |
  | 5G — 100 Mbit/s, 30 ms | 520 ms |
  | 4G — 9 Mbit/s, 85 ms | 3,606 ms |
  | Fast 3G — 1.6 Mbit/s, 562 ms | 19,822 ms |

  **Only the tail hurts**, and that is what makes this an investigation rather than an emergency.

  **What the bytes are**, because the two halves have entirely different prospects:

  | Chunk | Brotli | What it is |
  |---|---:|---|
  | `*.wasm` (Skiko) | 2,596 KB | Skia compiled to WebAssembly — a JetBrains build artifact |
  | `*.wasm` (application) | 890 KB | Compose runtime, Material 3, `dogwood-host` core, the slice |
  | `app.js` | 86 KB | the loader |

  **73% is Skiko and Dogwood has no lever on it** beyond choosing a version. The 890 KB is the part
  this project controls, and it is unremarkable for an application of its kind — a heavy
  single-page-application bundle lands in the same range. The nearest architectural peer is Flutter
  Web with CanvasKit, which pays a comparable 1.5–2 MB for the same reason: a canvas renderer
  instead of the Document Object Model.

  **Lines worth investigating**, in the order their payoff looks likeliest:
  - **Whether both WebAssembly chunks are needed on the first frame.** They are fetched together
    today. If the design-system half could load after the layout tier has painted, the first frame
    would be gated on Skiko plus a smaller application chunk.
  - **What the design-system bindings actually pull from Material 3**, and whether a registration
    seam could let a product link only the components it uses. `DesignSystemImpl` reaches Material
    3 broadly; whether Kotlin/Wasm's dead-code elimination already handles this is an unknown to
    measure rather than assume.
  - **Skiko's own configuration.** A version choice and possibly a build variant; the ceiling on
    this is whatever JetBrains ships.
  - **Whether the profile wants a Document Object Model tier at all** for text-and-layout screens,
    which would sidestep Skiko entirely for a subset of surfaces. This is a large question and is
    named here so it is not mistaken for a small one.

  **What this is not.** It is not a regression to undo. The web host gained 463 KB when it stopped
  reimplementing the host, and that bought Material 3, the icon set and the whole design system —
  pre-split `dogwood-web` linked `compose.runtime`, `foundation` and `ui` and could render five
  layout widgets. The before-and-after is not like-for-like, and reading it as waste would be
  reading a capability as a defect.

  **What would make it urgent.** A product that needs a first visit on a slow connection — a
  landing page, anything search-driven. The profile suits a returning-user application surface,
  where the bytes are cached, far better than a first-impression page. That is a product judgement
  and belongs with whoever chooses the profile, which is why it is written down rather than left to
  be discovered at 19.8 seconds.

  Gate: a decision recorded per line above — measured saving, or a reason it does not pay. The
  budget in `tools/conformance/budgets.tsv` holds the number in place meanwhile.

**Standing non-engineering items, unchanged:** the Apple ruling and the iOS organisation's written
yes (parallel track); the Phase 0 gate device, not acquired by decision
([Layer 4 ADR-008](adrs/layer-4/ADR-008-gate-device-not-available.md)); three upstream reports
drafted and unfiled by decision.

---

## Decision Backlog

All four decisions that blocked work directly are resolved and recorded — the `Modifier`
representation ([Layer 5 ADR-009](adrs/layer-5/ADR-009-modifier-subsystem.md)), the
deferred-expression grammar ([ADR-010](adrs/layer-5/ADR-010-deferred-expression-grammar.md)), the
animation protocol ([ADR-020](adrs/layer-5/ADR-020-animation.md)), and the resources protocol
([ADR-017](adrs/layer-5/ADR-017-resources-and-assets.md)); "no per-frame state in the guest" is
enforced by ADR-020's design rather than by a rule.

What remains deferred is **carried in Phase 7 below**, so it has one home instead of living in the
unstated-assumptions sections of the records that deferred it.

---

## What Would Stop the Project

1. Recomposition inside QuickJS too slow for responsive interaction (Phase 0.2).
2. Cold start unacceptable with the Compose runtime linked (Phase 0.1).
3. Apple ruling against downloaded interpreted payloads for this use (parallel track).
4. A requirement to render into UIKit or Android Views directly, which reintroduces the per-platform multiplier and invalidates [Layer 5 ADR-004](adrs/layer-5/ADR-004-compose-multiplatform-sole-host-target.md).
5. **The iOS organisation declining to adopt Compose Multiplatform.** This is the reported killer of Redwood's adoption, and Dogwood asks strictly more of an iOS team than Redwood did (see the costs recorded in [Layer 5 ADR-004](adrs/layer-5/ADR-004-compose-multiplatform-sole-host-target.md)). It is a Phase 0 conversation, not a Phase 6 discovery.

Everything else is engineering with known shape.

## A Note on Effort Realism

Durations above assume one engineer and are sequencing guidance. Two honesty checks from review: **Phase 3 is the least certain estimate** — parsing the full Compose surface with the embedded Kotlin frontend *including default-value expressions*, classifying each default as guest-resolvable or host-sentinel, and re-emitting four artifacts is well beyond Redwood's schema parser (which handled small, hand-annotated schemas); treat 6–8 weeks as a floor, not a midpoint. And the time to the first production screen depends on the path: under **design-system-first** (the recommended path above), registered components cover images and motion, and the first shippable screen lands around **four to five months** in — Phases 0–3 plus the host-environment and entry-point work. On the **generated-tier path** — or for screens needing lazy lists, text input, or guest-authored animation — the figure is on the order of **a year of one engineer's critical path**, because several Phase 4 subsystems must land first. Staff accordingly or parallelise Phase 4.

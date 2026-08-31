# Project Dogwood: Delivery Plan

**Companion documents:** [Technical Specification v4.0](high-level-tech-spec-final.md), the per-layer milestones in [`specs/`](specs/), and the decision record in [`adrs/`](adrs/).

This document sequences the work *across* layers and states the gates between phases. The per-layer specifications say what to build; this says in what order, and what would stop us.

Durations assume **one engineer** on the critical path and are rough. They are sequencing guidance, not commitments.

---

## Phase 0 — Falsify It Cheaply

**Goal:** find out whether the architecture is viable before building anything. Every experiment here is designed to produce a number that could stop the project.

| # | Experiment | Effort | What it settles |
|---|---|---|---|
| 0.1 | Compile the authoring slice to Kotlin/JavaScript with `androidx.compose.runtime:runtime-js`, `runtime-saveable-js`, `kotlinx-coroutines-core-js`, and `kotlinx-serialization-json-js` linked in production configuration. Package as `.zipline` bytecode. Measure minified bytes, gzipped bytes, bytecode bytes, on-device module-load time, and `QuickJs.memoryUsage` after load. | ~1 day | Cold-start cost. `runtime-js` alone is 1,777,599 bytes of klib. Cash App's published baseline for a real Kotlin/JavaScript application is 360 ms of QuickJS module loading. |
| 0.2 | Run a real Compose composition inside Zipline's QuickJS with a trivial custom `Applier`. Measure initial composition and, separately, recomposition after a single state change, for a realistic screen. | ~3 days | **The central unknown.** Nobody has run Compose composition in a JavaScript interpreter. Initial composition being slow is maskable; recomposition being slow is fatal to interactivity. |
| 0.3 | Encode a realistic change batch (roughly 150 nodes) through Zipline's `CallChannel`, end to end. Report guest encode, `JSON.stringify`, Java Native Interface transcode, host parse, and total bytes, at batch sizes 1 / 10 / 100 / 1,000. | ~2 days | Protocol cost per frame. Every crossing is one JSON string; the cost that matters is per byte. |
| 0.4 | Measure guest garbage-collection behaviour under 0.2 with `gcThreshold` at Zipline's default 256 KiB and at 8–16 MB. Record collection count, total pause, and maximum pause. | ~1 day | Whether garbage collection, rather than interpretation, is the source of any jank. |

**Gate.** Proceed only if a tap-to-repaint round trip is comfortably within human-perceptible latency and cold start is within product tolerance. If recomposition inside QuickJS cannot meet that, the substrate decision in [Layer 4 ADR-002](adrs/layer-4/ADR-002-adopt-zipline-quickjs-substrate.md) must be reopened before anything else is built.

**In parallel, costing nothing on the critical path:**
- Open a paid Apple Developer Technical Support incident on Guideline 2.5.2 as it applies to signed, first-party interpreted payloads. Lead time is long; the answer is needed before Layer 3 ships, not after.
- Confirm with the iOS team that the host application will be a Compose Multiplatform application ([Layer 5 ADR-004](adrs/layer-5/ADR-004-compose-multiplatform-sole-host-target.md)).

---

## Phase 1 — Vertical Slice, Hand-Written, Android Only

**Goal:** one real screen, driven from a payload, rendering natively, responding to taps. No generator yet.

**Approximately 4–6 weeks.**

1. Hand-write recording stubs and host bindings for ten composables — `Text`, `Column`, `Row`, `Box`, `Spacer`, `Button`, `Card`, `Icon`, `Surface`, `Divider`.
2. Implement `DogwoodApplier` over `AbstractApplier`, with `WidgetNode` and `ChildrenNode`.
3. Implement the batched `Change` protocol and the `Event` return path, including the lambda slot table and its depth-first reclamation.
4. Wire the frame clock, including `requestFrame()` so idle experiences produce no traffic.
5. Implement the threading contract explicitly, with dispatcher assertions on both sides.
6. **Decide host rendering strategy by measurement:** the snapshot mirror specified in [Layer 5](specs/layer-5-host.md) against an imperative applier that mutates retained nodes, which is what Redwood does. Compare apply-to-pixel latency at batch sizes 1 / 10 / 100 / 1,000.
7. Deliver through Zipline properly — signed manifest, Ed25519 verification, disk cache.

**Gate.** A tap-driven screen updates correctly and feels responsive on a mid-range device. Node identity survives list reordering.

---

## Phase 2 — The `Modifier` Subsystem

**Goal:** the single highest-leverage deliverable in the project.

**Approximately 4–6 weeks.**

Compose's `Modifier.Element` implementations are `internal`, so Dogwood defines its own tagged, serializable modifier type. This phase unlocks **73.8% of the measured surface** — no other single piece comes close.

1. Write the ADR: tag space, `then()` semantics, scope-awareness, ordering guarantees.
2. Implement the guest-side modifier type and chain builder.
3. Implement host-side reconstruction into real Compose modifiers.
4. Handle scoped modifiers — `RowScope.weight`, `BoxScope.align` are interface methods, so generated dispatch for a children slot must be emitted *inside* the parent's scope, and out-of-scope use must be a build error rather than a silent drop.

**Gate.** Arbitrary modifier chains over the Phase 1 composables produce pixel-identical output to the same chain written statically.

---

## Phase 3 — The Generator

**Goal:** replace hand-written bindings with generated ones, and stop the registry from ever being hand-maintained again.

**Approximately 6–8 weeks.**

1. Build the surface parser on the Kotlin frontend. Metalava dumps carry no default expressions and 73.9% of parameters are optional, so they cannot be the source of truth — see [Layer 5 ADR-002](adrs/layer-5/ADR-002-standalone-codegen-tool-not-ksp.md).
2. Emit four artifacts from one parsed model: guest stubs, **guest-side value-type stand-ins** (`Dp`, `Color`, `TextStyle` — Google publishes no Kotlin/JavaScript artifact for these), host bindings, and the versioned binding dictionary.
3. Implement the deferred-expression protocol, evaluated **inside the host composition** and memoized against the composition-local snapshot, with a bounded cache. Write its ADR first.
4. Implement the "use host default" sentinel for parameters whose defaults are `@Composable`.
5. Implement the build-time dictionary checker in Layer 1, and per-dictionary-version builds in Layer 2.

**Gate.** Generated bindings reproduce the Phase 1 and 2 behaviour exactly, and the generator round-trips a Compose version bump without hand edits.

---

## Phase 4 — Bespoke Subsystems

**Goal:** reach the components developers actually reach for. This is the larger half of the total work, and it is incremental — each subsystem ships independently.

**Ongoing. Prioritise by real usage in your own product, not by this order.**

| Subsystem | Notes |
|---|---|
| Host environment | `DogwoodConfiguration` flow: density, layout direction, dark mode, safe-area insets, viewport size. Cheapest, and everything else assumes it. |
| Live-state holders | 12.9% of the surface. Start with `LazyListState` and `FocusRequester`. |
| Lazy layouts | Guest-side windowing, placeholder pool, throttled viewport callbacks. Redwood needed ten modules for this alone — budget accordingly. |
| Text input | Version vector plus optimistic host state. Do not attempt a naive controlled `TextField`. |
| Node reuse | Key stability across list mutation. |
| Leak detection | Adopt `redwood-leak-detector`. **Before iOS, not after** — cross-language reference cycles span Kotlin/Native garbage collection and Swift reference counting. |

---

## Phase 5 — iOS Parity and Hardening

**Approximately 4–6 weeks.**

1. Bring the full path up on iOS. Confirm VoiceOver, the input method editor, and text selection work with no Dogwood-specific code — they should, because Compose Multiplatform owns the layout tree.
2. Run the leak suite across the language boundary.
3. Key rotation drill: ship a manifest with two signatures, roll clients forward, retire the old key.
4. Skew containment drill: build a guest against a newer dictionary and confirm the three requirements in section 6 of the [specification](high-level-tech-spec-final.md) — placeholder nodes keep index arithmetic consistent, unknown properties fall back to documented defaults, and safety-relevant parameters trigger a declared fallback.
5. Guest state preservation across code update, backgrounding, and process death.

---

## Phase 6 — Web (Optional)

Compose Multiplatform publishes `ui-wasm-js`, `foundation-wasm-js`, and `material3-wasm-js`, so the host runs on the Web unchanged. Two things differ and neither is designed yet: the guest would load directly into the browser's just-in-time JavaScript engine rather than QuickJS, and Skiko's WebAssembly module is roughly 8.6 MB, so payload viability is **unproven**.

---

## Decision Backlog

Nine decisions are already reflected in the specifications but owe an Architecture Decision Record, per `AGENTS.md` section 3. They are listed in [`adrs/README.md`](adrs/README.md). Three block work directly:

- **No per-frame state in the guest** — blocks Phase 1 step 4.
- **The `Modifier` representation** — blocks Phase 2 entirely.
- **The deferred-expression grammar** — blocks Phase 3 step 3.

---

## What Would Stop the Project

1. Recomposition inside QuickJS too slow for responsive interaction (Phase 0.2).
2. Cold start unacceptable with the Compose runtime linked (Phase 0.1).
3. Apple ruling against downloaded interpreted payloads for this use (parallel track).
4. A requirement to render into UIKit or Android Views directly, which reintroduces the per-platform multiplier and invalidates [Layer 5 ADR-004](adrs/layer-5/ADR-004-compose-multiplatform-sole-host-target.md).

Everything else is engineering with known shape.

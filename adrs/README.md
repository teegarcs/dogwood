# Architecture Decision Records (ADRs)

This directory records the technical decisions behind Project Dogwood — what was chosen,
why, what it assumes, and which specifications changed as a result.

## Organization

ADRs are grouped by the layer they affect:

```
adrs/
  layer-1/ADR-001-example.md
  layer-2/ADR-001-example.md
  ...
```

A decision that spans several layers is filed under the layer it most affects, and links to
the others from its **Updated Documents** section.

## When an ADR Is Required

Drafting the initial specifications did not require ADRs. That phase is over. From here on,
**any technical decision, pivot, or resolved assumption requires one** — see `AGENTS.md` §3.

An ADR is valid only when it lists every specification document updated as a result of the
decision. Traceability is the point: a reader should be able to start at any line in a
specification and find the decision that put it there.

## Template

Copy the template from [`AGENTS.md`](../AGENTS.md) §3. It covers: Context & Problem
Statement, Decision, Rationale & Research (with links proving feasibility), Unstated
Assumptions, and Updated Documents.

## Index

### Layer 2 — Server Compiler
- [ADR-001: Kotlin/Wasm Requires WebAssembly Garbage Collection (WasmGC) on Every Target](layer-2/ADR-001-kotlin-wasm-requires-wasmgc.md) — **Accepted.** `wasmWasi` is not a linear-memory target; there is no opt-out and no fallback. Removes Wasm3 from consideration.
- [ADR-002: Replace Compose Call Interception with a Generated Stub API](layer-2/ADR-002-generated-stub-api-replaces-ir-interception.md) — **Proposed.** `@Composable` and `external` are mutually exclusive and the core layouts are `inline`. Guests compile against generated stubs instead.

### Layer 4 — Sandbox Engine
- [ADR-001: Reject WebAssembly Dynamic Linking (`dylink.0`)](layer-4/ADR-001-reject-dylink-dynamic-linking.md) — **Accepted, now moot.** Linking Wasm against native ARM code is a category error.
- [ADR-002: Adopt Kotlin/JS on QuickJS (Zipline); Reject WebAssembly](layer-4/ADR-002-adopt-zipline-quickjs-substrate.md) — **Accepted.** The boundary costs 0.2-0.5% of a frame either way; WasmGC forces the slowest interpreter; `@WasmImport` cannot express the bridge. Batching, not the engine, is what matters.
- [ADR-003: Redwood Treehouse Is the Existence Proof; External Evidence Refresh](layer-4/ADR-003-treehouse-precedent-and-evidence-refresh.md) — **Accepted.** Compose composition verifiably ran in Zipline's QuickJS (Treehouse samples + limited production); Phase 0.2 becomes a measurement question with a cheaper start. Redwood's shutdown publicly characterised as non-technical. App Store analysis re-anchored on Guideline 4.7. Compose Multiplatform: iOS Stable, Web Beta. Hermes recorded as the fallback substrate.
- [ADR-004: The `Change`/`Event` Protocol Shape (Provisional v0)](layer-4/ADR-004-change-event-protocol-v0.md) — **Proposed.** Field-by-field schema, 8-bit-segment tag encoding, batch/event sequence numbers, absence-as-default sentinel, a worked wire example, and the minimal Phase 1 entry-point contract. Binding for Phase 0.3 and Phase 1.
- [ADR-005: Phase 0 Harness Resolutions — Reference Screen Size, Event Handlers, and Stack Size](layer-4/ADR-005-phase-0-harness-resolutions.md) — **Accepted.** The harness appendix's "50 rows" and "~160 nodes" cannot both hold; the reference screen is **23 rows** (exactly 160 widget nodes) with 50 rows measured as a second point. Chip selection derives from `quantity` so the holder count stays at 24; the per-row handler is an `onClick` parameter standing in for `Modifier.clickable`. Corrects Layer 4's stack figure: `Zipline.create` raises `maxStackSize` to **6 MiB**, so 512 KiB is not what a host inherits.
- [ADR-006: The Batch Crossing Is Guest-Side Encoding, Not Transport](layer-4/ADR-006-batch-crossing-is-guest-encoding.md) — **Proposed.** Phase 0.3 measured: the 572-change initial batch crosses in **24.06 ms**, of which **99.4% is guest-side encoding** and 1% is transport — six times the 4 ms gate leg, on hardware faster than the gate device. Reaching QuickJS's native `JSON.stringify` (which Zipline already does) is worth 47%; array polymorphism is worth 3%. A binary wire format is **not** the available escape hatch, because `CallChannel` is string-typed. The gate leg's reading is escalated, not renegotiated.
- [ADR-007: The v1 Wire Format Is Positional JSON Built as Native JavaScript Values; Binary Encodings Are Rejected](layer-4/ADR-007-v1-wire-format-positional-json.md) — **Proposed.** A bake-off over six encodings of the same batch. Positional JSON built as native JavaScript values and handed to QuickJS's own `JSON.stringify` crosses in **1.23 ms against 24.02 ms — 95% less**, confirmed at roughly 17× on a Pixel 10 Pro. Protocol buffers (+45%) and CBOR (+908%) **rejected on measurement**: their encoders are pure Kotlin and run interpreted, and a string channel forces a Base64 surcharge that leaves protocol buffers larger on the wire as well as slower. Modifier-chain interning **rejected** — 7% fewer bytes, 31% longer. Withdraws ADR-006's "cost is linear in bytes": the governing variable is how much interpreted Kotlin runs while encoding.

### Layer 5 — Native Host
- [ADR-001: Compose Runs Natively on the Host](layer-5/ADR-001-host-native-compose-owns-semantics-and-input.md) — **Accepted, corrected.** Resolves the contradiction between section 1 and section 8.3 of the overview. Accessibility is inherited from Compose Multiplatform; the text-input half of the original conclusion was withdrawn after review — text input is bespoke subsystem 3.
- [ADR-002: Standalone FIR Codegen Tool, Not KSP](layer-5/ADR-002-standalone-codegen-tool-not-ksp.md) — **Proposed.** Neither Zipline nor Redwood uses KSP for its bridge. Adopts Redwood's embedded-frontend + KotlinPoet pipeline generating both ends from one source.
- [ADR-004: Compose Multiplatform Is the Sole Host Rendering Target](layer-5/ADR-004-compose-multiplatform-sole-host-target.md) — **Accepted.** Redwood ships 4 host implementations per widget set; one Compose Multiplatform target reaches Android, Web (Beta), and iOS with one generated binding, in the roadmap's platform order. Requires the host app to be a Compose Multiplatform app, and records the costs: ~9 MB on iOS, Skia rendering rather than native widgets, and an organisational ask that amplifies Redwood's reported adoption failure.
- [ADR-003: Opaque Handle Binding Surface](layer-5/ADR-003-opaque-handle-binding-surface.md) — **Proposed; measurement superseded by ADR-005.** The opaque-handle model and the published-dictionary decision stand; its coverage figures are withdrawn.
- [ADR-005: Corrected Coverage Measurement (Third Revision) and the Re-Enumerated Bespoke Subsystem List](layer-5/ADR-005-corrected-coverage-and-bespoke-subsystem-list.md) — **Accepted.** 445 widget-shaped composables: 67.6% generable after `Modifier` (76.4% of those also need the deferred-expression protocol), 25.2% bespoke, 7.2% unreachable; a 458-function lowercase surface reported separately. The bespoke list grows from six to **nine**: animation, resources & assets, and host services/entry points/host-registered components are added.
- [ADR-006: Guest-Composed vs Host-Registered Components, Multi-Design-System Registration, and the Design-System-First Adoption Path](layer-5/ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md) — **Accepted.** Developer-defined composables are plain Compose: no dictionary entry, no host release, no skew surface. A component is bridged only if its implementation must live host-side. The dictionary is segmented so any number of design systems register independently; registered components absorb bespoke subsystems; generator v1 targets registered modules + `foundation-layout`, Material tier is v2. Sets the roadmap's design-system-first sequencing and Android → Web → iOS platform order.

### Open — no ADR yet

Decisions already reflected in the specifications that require an ADR before implementation, per `AGENTS.md` section 3:

- **No per-frame state in the guest.** Animation targets, scroll offset, gesture recognition, and text-field edit state live host-side. Determines which Compose APIs are bindable at all; blocks Layer 4 Milestone 5.
- **The deferred-expression grammar.** A peer protocol to `Change`, with its own tag space and skew rules. Blocks Layer 5 Milestone 6.
- **The `Modifier` representation.** Dogwood's tagged type, `then()` semantics, and scope-aware tags. Highest-leverage deliverable — 62.2% of the widget surface depends on it. Written jointly with the deferred-expression grammar, whose values modifier arguments carry.
- **The animation protocol.** Declarative targets, springs and easings, interruption semantics, completion events, time-varying `Modifier` values. Added by ADR-005; blocks the first animated production screen.
- **The resources and assets protocol.** Uniform Resource Locator (URL)-keyed images with placeholder/error slots, icon dictionary, fonts, localized strings. Added by ADR-005; blocks the first production screen on the generated-tier path (a registered `AsyncImage` covers it under design-system-first — ADR-006).
- **Host services, entry points, and host-registered components.** Launch contract, versioned service surface, per-module dictionary segments for registered components (ADR-006). Added by ADR-005; its Phase 1 seed is ADR-004 §2.5.
- **The live-state mirroring protocol.** Per holder, with a conflict rule.
- ~~The `Change`/`Event` protocol shape~~ — **now proposed as [Layer 4 ADR-004](layer-4/ADR-004-change-event-protocol-v0.md)** (provisional v0, binding for Phase 1; v1 revision fed by Phase 1 measurements).
- **Host composition versus imperative applier.** Redwood mutates widgets imperatively and does not recompose; Layer 5 currently specifies a snapshot mirror. Benchmark before Layer 5 Milestone 3 commits.
- **Dropping the ZiplineLoader fork**, and Layer 3 owning interpreter instantiation.
- **Surface source: Kotlin frontend over metalava**, forced by metalava carrying no default expressions.
- **Policy on binding `@Deprecated` APIs** — 6.1% of the measured surface.

### Open questions — not yet decisions

- **Why Cash App discontinued Redwood.** Now publicly characterised: maintainer Jake Wharton, announcing the final release, wrote "The decision wasn't technical" ([discussion #2894](https://github.com/cashapp/redwood/discussions/2894); [Layer 4 ADR-003](layer-4/ADR-003-treehouse-precedent-and-evidence-refresh.md)). The specifics of the reported iOS-adoption reluctance remain worth confirming, because [Layer 5 ADR-004](layer-5/ADR-004-compose-multiplatform-sole-host-target.md) asks more of an iOS team than Redwood did. **No longer blocking.**
- **Apple App Store review of downloaded interpreted payloads.** Now governed by Guideline 4.7 rather than a 2.5.2 JavaScriptCore exception ([Layer 4 ADR-003](layer-4/ADR-003-treehouse-precedent-and-evidence-refresh.md)). Obtain a written ruling before Layer 3 ships.
- **Payload size and module-load time** with the Compose runtime linked — Layer 2 Milestone 1, roughly one day, and the cheapest way to falsify the architecture.

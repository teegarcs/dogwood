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

### Layer 5 — Native Host
- [ADR-001: Compose Runs Natively on the Host](layer-5/ADR-001-host-native-compose-owns-semantics-and-input.md) — **Accepted.** Resolves the contradiction between section 1 and section 8.3 of the overview. Accessibility and text input are inherited from Compose Multiplatform.
- [ADR-002: Standalone FIR Codegen Tool, Not KSP](layer-5/ADR-002-standalone-codegen-tool-not-ksp.md) — **Proposed.** Neither Zipline nor Redwood uses KSP for its bridge. Adopts Redwood's embedded-frontend + KotlinPoet pipeline generating both ends from one source.
- [ADR-004: Compose Multiplatform Is the Sole Host Rendering Target](layer-5/ADR-004-compose-multiplatform-sole-host-target.md) — **Accepted.** Redwood ships 4 host implementations per widget set; one Compose Multiplatform target reaches Android, iOS, and Web with one generated binding. Requires the host app to be a Compose Multiplatform app.
- [ADR-003: Opaque Handle Binding Surface](layer-5/ADR-003-opaque-handle-binding-surface.md) — **Proposed.** Measured 381 Compose functions: ~1.3% unbindable. Handles carry 76.6% of the surface. Dictionary published as a versioned artifact.

### Open — no ADR yet

Decisions already reflected in the specifications that require an ADR before implementation, per `AGENTS.md` section 3:

- **No per-frame state in the guest.** Animation targets, scroll offset, gesture recognition, and text-field edit state live host-side. Determines which Compose APIs are bindable at all; blocks Layer 4 Milestone 5.
- **The deferred-expression grammar.** A peer protocol to `Change`, with its own tag space and skew rules. Blocks Layer 5 Milestone 6.
- **The `Modifier` representation.** Dogwood's tagged type, `then()` semantics, and scope-aware tags. Highest-leverage deliverable — 73.8% of the surface depends on it.
- **The live-state mirroring protocol.** Per holder, with a conflict rule.
- **The `Change`/`Event` protocol shape.** Integer tag value classes and `JsonElement` values, currently specified in Layer 4 with no ADR.
- **Host composition versus imperative applier.** Redwood mutates widgets imperatively and does not recompose; Layer 5 currently specifies a snapshot mirror. Benchmark before Layer 5 Milestone 3 commits.
- **Dropping the ZiplineLoader fork**, and Layer 3 owning interpreter instantiation.
- **Surface source: Kotlin frontend over metalava**, forced by metalava carrying no default expressions.
- **Policy on binding `@Deprecated` APIs** — 6.2% of the measured surface.

### Open questions — not yet decisions

- **Why Cash App discontinued Redwood.** Still unconfirmed by any public source. Reported second-hand: iOS adoption reluctance, compounded by per-platform native mappers rather than a shared renderer. Recorded as context in [Layer 5 ADR-004](layer-5/ADR-004-compose-multiplatform-sole-host-target.md), which notes that the verified four-implementations-per-widget cost is a sufficient explanation on its own. Worth confirming, but **no longer blocking** — the constraint it describes is one Dogwood does not share.
- **Apple App Store Guideline 2.5.2**, which conflicts with License Agreement section 3.3.1(B). Obtain a written ruling before Layer 3 ships.
- **Payload size and module-load time** with the Compose runtime linked — Layer 2 Milestone 1, roughly one day, and the cheapest way to falsify the architecture.

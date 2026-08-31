# ADR-003: Binding Surface Model — Opaque Host Handles and a Published Dictionary Artifact

**Date:** 2026-08-30
**Status:** Proposed — **superseded in part**, twice. The `i32`-into-linear-memory mechanics are replaced by protocol identifiers over Zipline ([Layer 4 ADR-002](../layer-4/ADR-002-adopt-zipline-quickjs-substrate.md)). **The coverage figures in section 3 (450 / 81.1% / 12.9% / 6.0%) are withdrawn by [ADR-005](ADR-005-corrected-coverage-and-bespoke-subsystem-list.md)** — this second measurement was itself found optimistically wrong (annotation-name capture, an optimistic unknown-type default, and an unmeasured lowercase surface); the corrected figures are 445 / 67.6% / 25.2% / 7.2%. The opaque-handle model and the published-dictionary decision stand. The tables below are retained as history; do not quote them.

## 1. Context & Problem Statement

The primary product objective for Project Dogwood is the elimination of a hand-maintained component mapper — the "giant `when` statement" that maps serialized variables to native components. The intended replacement is: ship a client runner built against a known set of Compose libraries, compile server payloads against the same set, and let anything expressible in those libraries work.

This rests on an unmeasured assumption: that a generated binding surface can provide **nearly full access to what Compose offers**. Section 5 of the overview specification also proposes that the compiler (Layer 2) and the client generator (Layer 5) independently compute matching symbol names via a shared `dogwood-symbol-naming` library. Both the coverage claim and the agreement mechanism needed validation.

## 2. Decision

**Adopt an opaque-handle binding model**, and **publish the generated binding dictionary as a versioned artifact** rather than recomputing it independently on each side.

1. Primitives, `String`, Kotlin inline value classes over primitives (`Dp`, `Color`, `TextUnit`, `IntSize`, and similar), and `Modifier` chains marshal **by value** into linear memory.
2. All other object types cross as **opaque `i32` handles into a host-side table**. The guest never inspects them. It obtains them by calling generated host-side factory bindings (`ButtonDefaults.buttonColors(...)`, `RoundedCornerShape(...)`, `PaddingValues(...)`) and passes them straight back.
3. Lambdas cross as `i32` slot identifiers, with the host calling back into an exported guest dispatcher — including lambdas that return values.
4. The generator emits, and the client build publishes, a **versioned dictionary artifact** describing the exact bound surface. The server compiler resolves against that published artifact. `dogwood-symbol-naming` remains the deterministic naming algorithm inside the generator, but agreement between client and server is guaranteed by a shared artifact, not by independent recomputation.

## 3. Rationale & Research

**The coverage claim was measured — twice.** An initial measurement was found invalid during adversarial review: it omitted `compose/foundation/foundation-layout` (so `Column`, `Row`, `Box`, `Spacer`, and `padding` were never counted), classified by parameter type rather than bindability, counted composition-control constructs such as `LaunchedEffect` as widgets, and published no reproducible script. Those figures — 381 composables, 76.6% needing handles, 1.3% unbindable — are **withdrawn**.

The measurement was redone with a committed, re-runnable classifier at [`tools/measure-compose-surface.py`](../../tools/measure-compose-surface.py), over ten modules pinned to `androidx-main` commit `5bd169266a7ea9b28c5caf2c040e021677a7adc0`: `foundation`, `foundation-layout`, `material3`, `material`, `ui`, `ui-text`, `ui-graphics`, `runtime`, `animation`, `animation-core`.

**450 public `@Composable` User Interface functions are in scope**, after excluding 26 composition-control constructs (`LaunchedEffect`, `DisposableEffect`, `CompositionLocalProvider`, `ComposeNode` and relatives — these execute in the guest and are never dispatched as a widget) and 4 Android-only functions. 28 of the 450 (6.2%) are `@Deprecated`; whether deprecated Application Programming Interfaces are bound is an open policy question.

| Verdict | Count | Share |
|---|---:|---:|
| Generable with no bespoke dependency | 33 | 7.3% |
| Generable once the `Modifier` subsystem exists | 332 | 73.8% |
| **Total generable after `Modifier`** | **365** | **81.1%** |
| Requires a per-holder live-state protocol | 58 | 12.9% |
| Structurally unreachable | 27 | 6.0% |

**Reading these numbers correctly matters.**

- The 73.8% depend on exactly **one** shared subsystem — Dogwood's tagged `Modifier` — not on per-component hand-work. Building it once unlocks them all. That is the single highest-leverage deliverable in the project.
- The 12.9% take a **required** parameter that is a host-owned state holder (`LazyListState`, `SnackbarHostState`, `FocusRequester`, `TextFieldState`). Each needs a mirrored-state protocol with a conflict rule, hand-written per holder.
- Many composables in the 81.1% carry an **optional** live-state parameter — `MutableInteractionSource` appears throughout `material3`. They are generable because the guest may omit it and let the host supply the default, **but that parameter is unavailable to guest code** until a live-state protocol exists. Coverage of *functions* overstates coverage of *parameters*.
- The 6.0% unreachable are 27 in-frame lambdas (`DrawScope`, `LazyListScope`, `PointerInputScope`), 3 layout-engine types, and 2 generics. `LazyColumn` and `Canvas` are here.

Parameter-level distribution across all 450: deferred expression 29.5%, composition-time slot or discrete event 23.0%, value class 14.3%, primitive 12.7%, `Modifier` 10.7%, live state 6.8%, `String` 1.4%, remainder excluded.

**Overload pressure is real and confirmed.** Deterministic disambiguation is mandatory — an independent re-derivation at the time reproduced 88 of 244 distinct names carrying more than one overload (superseded with the rest of this measurement: the corrected classifier reports **106 of 256**, maximum 8 for `Icon` — see [ADR-005](ADR-005-corrected-coverage-and-bespoke-subsystem-list.md)). Note that the original rationale for this requirement ("WebAssembly imports cannot share a name") is void under the Zipline substrate; the requirement survives because the protocol addresses widgets by integer tag and a tag must identify exactly one signature.

**Why a published artifact beats independent recomputation.** Section 5 argues that a shared algorithm "guarantees that both sides generate perfectly matching strings without ever communicating." That holds only if both sides also see an identical input API surface. They do not: the client runner is compiled into an application binary shipped months earlier, while the server compiles payloads later against whatever Compose version the build server resolves. Publishing the client's generated dictionary as a versioned artifact makes the surface itself the contract, which is also what makes the section 7 version-skew routing strategy implementable — the server can hold one dictionary per shipped client version and route accordingly. Zipline solves the analogous problem with `SignatureHash.kt`, and Redwood generates both ends from one schema in one build step ([ADR-002](ADR-002-standalone-codegen-tool-not-ksp.md)).

## 4. Unstated Assumptions

- **Assumes the androidx Android surface is representative of Compose Multiplatform's common surface.** It is not identical — some measured functions are Android-only (for example `AndroidExternalSurface`), and Compose Multiplatform's common API is a subset. The measurement should be repeated against `compose-multiplatform-core` before the numbers are quoted as product commitments. The tier *proportions* are expected to hold; the absolute counts will change.
- **Assumes five modules generalise.** `material3` supplies 282 of the 381 functions measured. Adding `material`, `foundation-layout`, `ui-text`, and `ui-graphics` will grow both the surface and the handle-type count.
- **Assumes handle lifetime is manageable.** The host table must not leak handles across recompositions, and handle identity must be stable enough for Compose's positional memoization to behave. This is unspecified and interacts directly with the open recomposition-protocol question.
- **Assumes "nearly full access" means the bound surface, not all of Compose.** Approximately 1.3% of measured composables are unbindable, and anything requiring the guest to *inspect* a handle's contents rather than pass it through is out of scope by construction.
- Assumes generating roughly 650-plus binding pairs is tractable. This is a large generator, and its size is the real cost that the "zero-bridge" framing concealed.

## 5. Updated Documents

- [specs/layer-5-host.md](../../specs/layer-5-host.md) — parameter marshalling, the bindability rule, the bespoke-subsystem list, and the roadmap
- [high-level-tech-spec-final.md](../../high-level-tech-spec-final.md) — v4.0 sections 1, 5, and 7
- [specs/layer-1-authoring.md](../../specs/layer-1-authoring.md) — stub divergence from Compose signatures; the "use host default" sentinel
- [tools/measure-compose-surface.py](../../tools/measure-compose-surface.py) — the classifier, committed so every quoted figure is reproducible

**Open decisions this ADR does not settle, each requiring its own ADR before implementation:** the deferred-expression grammar and its version-skew rules; the `Modifier` tag space and scope-awareness; the live-state mirroring protocol; and the policy on binding `@Deprecated` Application Programming Interfaces.

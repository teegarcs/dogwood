# ADR-001: Compose Runs Natively on the Host; Accessibility and Text Input Are Inherited, Not Rebuilt

**Date:** 2026-08-30
**Status:** Accepted

## 1. Context & Problem Statement

`high-level-tech-spec-final.md` is internally inconsistent about where the Compose engine runs.

- Section 1 (line 21) states that "the dynamic module does not bundle the Compose runtime or rendering engine" and binds "against the full Compose Multiplatform engine already compiled into the native app binary." This describes Compose running **natively on the host**.
- Section 8.3 (line 161) states that "the **in-Wasm** Compose engine exports its `SemanticsNode` tree across shared memory, which the native host mirrors to `UIAccessibilityElement` (iOS) and `AccessibilityNodeInfo` (Android)." This describes Compose running **inside the sandbox**, rendering to a canvas.

These two models have opposite consequences for accessibility and text input, so the ambiguity had to be resolved before the Foreign Function Interface (FFI) boundary could be specified.

## 2. Decision

**Compose Multiplatform runs natively on the host and owns the layout tree.** The guest module holds application logic and User Interface (UI) state and drives host-side Compose through the generated binding surface. The guest never renders and never runs a Compose engine.

**Accessibility, text input, and platform integration are therefore inherited from Compose Multiplatform rather than rebuilt.** Section 8.2 and section 8.3 are deleted as risk items and replaced with a statement of what the host provides.

## 3. Rationale & Research

The in-sandbox model is not merely undesirable — it is unavailable. There are zero `-wasm-wasi` Compose artifacts on Maven Central, and Skiko has no WebAssembly System Interface (WASI) build. Compose's WebAssembly output is structurally bound to a browser: [`ComposeWindow.web.kt`](https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/ui/ui/src/webMain/kotlin/androidx/compose/ui/window/ComposeWindow.web.kt) hard-requires WebGL2, calls `document.createElement("canvas")` and `attachShadow`, and evaluates JavaScript source strings via `js("...")`. Skiko ships as a separate 8.6 MB Emscripten linear-memory module glued to the Kotlin module by JavaScript across two disjoint memories.

**The accessibility consequence is decisive and settles the design.** `SemanticsNode` is public but has no public constructor; every property is derived from the layout tree, and semantics are produced only by `SemanticsModifierNode.applySemantics()` attached to a `LayoutNode`. Under the in-sandbox model, one `Canvas` composable is one layout node, so **the entire dynamic UI would be a single opaque rectangle to VoiceOver and TalkBack**, regardless of how many buttons the guest painted inside it. Section 8.3's proposed mitigation describes work Compose already performs internally, and Android's bridge (`AndroidComposeViewAccessibilityDelegateCompat`, approximately 3,843 lines) is declared `internal`, constructed privately inside `AndroidComposeView`, and cannot accept a foreign tree.

Under the host-native model the problem does not arise. Host-side Compose constructs real `LayoutNode`s with real semantics, so the platform integrations apply automatically:

- **iOS accessibility is mature and on by default.** [`Accessibility.ios.kt`](https://github.com/JetBrains/compose-multiplatform-core) (approximately 2,129 lines) implements `AccessibilityMediator`, "responsible for mediating between the tree of specific SemanticsOwner and the iOS accessibility tree," using `UIAccessibilityElement`, `UIAccessibilityCustomAction`, and `UIAccessibilityTraits`. Introduced in Compose Multiplatform 1.6.0, made lazy in 1.8.0 with `AccessibilitySyncOptions` removed as unnecessary, and still gaining features in 1.12.0. Supports VoiceOver, Voice Control, AssistiveTouch, and Full Keyboard Access. Documented gap: high-contrast themes.
- **iOS text input is complete.** `ComposeTextInputView.ios.kt` and `NativeTextInputView.ios.kt` implement `UIKeyInputProtocol` and `UITextInputProtocol` including marked-text composition, with autocorrect, dictation, and hardware keyboard support. Compose Multiplatform 1.11.0 added opt-in fully native iOS text input via `PlatformImeOptions.usingNativeTextInput`, bringing the native magnifier, selection handles, context menu, and autofill.

**Correction to section 8.2 regardless of model.** `PlatformTextInputService` is deprecated — [`TextInputService.kt`](https://github.com/androidx/androidx) carries `@Deprecated("Use PlatformTextInputModifierNode instead.")`, applied in Compose Multiplatform 1.11.0 — and its only Android injection point is `internal`. Any host-side text input work uses `PlatformTextInputModifierNode` and `establishTextInputSession`. Note that `PlatformTextInputMethodRequest` is an `expect interface` with entirely different members per target (Android exposes `createInputConnection(EditorInfo): InputConnection`; the skiko/iOS variant has eleven members, all `@ExperimentalComposeUiApi`), so it cannot be written once in common code.

## 4. Unstated Assumptions

*Revised after the substrate decision in [Layer 4 ADR-002](../layer-4/ADR-002-adopt-zipline-quickjs-substrate.md). Earlier versions of this section reasoned from WebAssembly interpreter benchmarks, which no longer apply.*

- **Assumes the guest issues no per-node host calls.** All changes from one composition pass cross as a single batched list. This is the shape every comparable system converges on, and the shape the prior art uses: Redwood's [`ChangesSink.sendChanges(changes: List<Change>)`](https://github.com/cashapp/redwood/blob/trunk/redwood-protocol/src/commonMain/kotlin/app/cash/redwood/protocol/sinks.kt) delivers one change list rather than per-node calls. A design that crossed per node would not meet any frame budget.

- **Assumes the guest is not in the frame loop at all.** This is the load-bearing assumption, and it is what makes host-native rendering viable rather than merely tidy. Because animation targets, scroll offset, gesture recognition, and text-field edit state all live host-side (section 7 of the [specification](../../high-level-tech-spec-final.md)), the guest recomposes only in response to *semantic* events — a tap, a data arrival — at human frequencies rather than display frequencies. The guest therefore does not need to sustain 60 or 120 frames per second, and the frame-rate targets carried in earlier versions of this project are withdrawn.

- **Assumes Compose composition inside QuickJS is fast enough for responsive interaction. UNPROVEN, and the central open risk.** Nobody has published a measurement of Compose composition in a JavaScript interpreter. The requirement is tap-to-repaint within human-perceptible latency, not a frame-rate figure. Layout, measure, draw, and Skia all run natively under this decision, so only composition pays the interpreter cost, and Compose recomposition is incremental — only invalidated scopes re-execute. Both properties make the bar reachable, but neither makes it proven. See [`roadmap.md`](../../roadmap.md) Phase 0.2.

- **Assumes the host application is a Compose Multiplatform application.** Without that, none of the inherited accessibility and input-method behaviour applies. Recorded separately as [ADR-004](ADR-004-compose-multiplatform-sole-host-target.md).

## 5. Updated Documents

- [high-level-tech-spec-final.md](../../high-level-tech-spec-final.md) — v4.0 section 7; the accessibility risk is resolved, and the text-input conclusion is **corrected** (see below)
- [specs/layer-5-host.md](../../specs/layer-5-host.md) — Compose runs natively on the host and owns the layout tree

**Correction issued after adversarial review.** This ADR's conclusion holds for accessibility and does **not** hold for text input. A `TextField(value, onValueChange)` is a *controlled* component: the edit buffer is host-side, the value is guest-side, and between them sit a thread hop and one to two frames of latency, so fast typing drops and reorders characters. Inheriting the input method editor connection is not the same as inheriting correct text input. Text input is now listed as a hand-written subsystem in overview section 1 and [Layer 5](../../specs/layer-5-host.md), requiring a version vector and optimistic host state.

# ADR-002: Adopt Kotlin/JS on QuickJS (Zipline) as the Execution Substrate; Reject WebAssembly

**Date:** 2026-08-30
**Status:** Accepted

## 1. Context & Problem Statement

WebAssembly (Wasm) was selected as Project Dogwood's execution substrate on performance grounds. `high-level-tech-spec-final.md` names "High Latency (The Zipline/Javascript Bottleneck)" as one of three motivating problems, asserting that "attempting to drive 120 Frames Per Second (FPS) UI updates across a Javascript bridge via JSON or Byte Array serialization causes massive frame drops due to translation overhead and garbage collection pauses."

Three earlier decisions ([Layer 4 ADR-001](ADR-001-reject-dylink-dynamic-linking.md), [Layer 2 ADR-001](../layer-2/ADR-001-kotlin-wasm-requires-wasmgc.md), [Layer 2 ADR-002](../layer-2/ADR-002-generated-stub-api-replaces-ir-interception.md)) established that a bridge is required regardless of substrate. That removes the "zero-bridge" argument for Wasm and leaves only raw performance. We therefore had to test the performance premise directly.

## 2. Decision

**Adopt Kotlin/JS executed as bytecode inside QuickJS via [Cash App's Zipline](https://github.com/cashapp/zipline). Reject WebAssembly for the foreseeable term.**

The 120 Frames Per Second (FPS) framing is replaced with a batched, diff-based protocol: **one boundary crossing per frame carrying a change list**, not thousands of per-node calls. Under that protocol the substrate's per-crossing cost stops being architecturally significant, which is what makes this decision safe.

Kotlin/Wasm is placed on a watch list, not a roadmap. Revisit when Kotlin/Wasm reaches Stable **and** a fast, specification-compliant WasmGC interpreter exists for both iOS and Android.

## 3. Rationale & Research

**The performance premise fails in four independent ways.**

**1. Per-crossing engine cost is not the differentiator, because the design makes one crossing per frame.** Published empty-call costs are QuickJS host-to-guest 12 ns and Wasmtime host-to-wasm 27.50 ns / wasm-to-host 6.66 ns ([wasmtime#10643](https://github.com/bytecodealliance/wasmtime/pull/10643)).

**These figures do not support this decision and must not be cited as if they did.** They measure an empty foreign-function call. Dogwood's boundary is not an empty call: Zipline's `CallChannel.call(callJson: String): String` carries a serialized payload through five full passes — build the JavaScript object graph, `JSON.stringify`, UTF-8 encode in `JS_ToCStringLen`, UTF-16 decode across the Java Native Interface (JNI), then `kotlinx.serialization` parse allocating one object per change. The cost that matters is **per byte of payload**, and it is unmeasured on both substrates.

What survives is the weaker but sufficient claim: **with one batched crossing per frame, per-crossing engine overhead cannot plausibly dominate on either substrate**, so it is not a reason to prefer WebAssembly. The three arguments below carry this decision.

**2. WebAssembly is slower in the direction Dogwood uses most.** Host-to-guest is 27.50 ns for Wasmtime against QuickJS's 12 ns. Because Compose recomposition and every event callback are host-initiated, this is the hot edge, and Wasm carries a 2.3x deficit on it.

**3. WasmGC eliminates every fast WebAssembly interpreter.** Kotlin/Wasm hard-requires garbage collection plus exception handling ([Layer 2 ADR-001](../layer-2/ADR-001-kotlin-wasm-requires-wasmgc.md)). Of the interpreters that could host it: wasm3 lists garbage collection as "⛔ N/A"; wasmi and toywasm have it planned only; WAMR ships it off by default and self-describes as "not fully compliant with the Wasm GC proposal… `exn` and `noexn` types are not supported", which are precisely the types Kotlin emits; Chicory supports it fully but is a Java Virtual Machine (JVM) library and can never run on iOS. That leaves **Wasmtime's Pulley interpreter — measured as the slowest in the field** (23.5x Cranelift on the [wasmi-benchmarks](https://github.com/wasmi-labs/wasmi-benchmarks) geomean, against wasm3's 10.0x) **and last on startup** (132.6 ms to load `pulldown-cmark`, against wasm3's 4.89 ms). Wasmtime maintainer alexcrichton, [wasmtime#10102](https://github.com/bytecodealliance/wasmtime/issues/10102): "Pulley is by no means outstripping other wasm interpreters." A performance argument that terminates on the slowest available runtime is not a performance argument.

**4. Kotlin/Wasm cannot express the bridge; Zipline's already exists.** `@WasmImport` and `@WasmExport` accept primitive numbers and `Boolean` only, enforced by [`FirWasmImportAnnotationChecker.kt`](https://github.com/JetBrains/kotlin/blob/master/compiler/fir/checkers/checkers.wasm/src/org/jetbrains/kotlin/fir/analysis/wasm/checkers/declaration/FirWasmImportAnnotationChecker.kt). Under WasmGC, Kotlin objects live in the garbage-collected heap, so the host cannot read them at all. The one production Kotlin/Wasm bridge found (Golem's agent Software Development Kit, [golem#3696](https://github.com/golemcloud/golem/pull/3696)) hand-rolled codecs against the raw canonical Application Binary Interface (ABI) because no Kotlin binding generator exists. Zipline's boundary, by contrast, is a single generated method — [`CallChannel.kt`](https://github.com/cashapp/zipline/blob/trunk/zipline/src/commonMain/kotlin/app/cash/zipline/internal/bridge/CallChannel.kt) — carrying `kotlinx.serialization` payloads, and it supports arbitrary types, suspending functions, `Flow<T>`, and pass-by-reference services today.

**Supporting evidence on the remaining axes.**

- **Payload size mildly favours Kotlin/JS, on a comparison that does not represent the real guest.** An identical realistic module (data class, collections, `sortedBy`, `HashMap`) built for both targets in production configuration measured Kotlin/JS **14,718 bytes gzipped** against Kotlin/Wasm **16,448 bytes gzipped**. **Neither figure is a payload floor for Dogwood**, because that program links no Compose runtime, no coroutines, and no serialization — dead-code elimination removes everything it does not itself use. The real guest links `androidx.compose.runtime:runtime-js` (1,777,599 bytes of klib), `runtime-saveable-js`, `kotlinx-coroutines-core-js`, and `kotlinx-serialization-json-js`. **The true payload size and module-load time are unmeasured and are the cheapest way to falsify this architecture** — see [Layer 2](../../specs/layer-2-compiler.md) Milestone 1. The narrow claim that survives is directional only: WebAssembly compresses worse than minified JavaScript, so Wasm offers no size advantage.
- **Runtime size is a wash.** QuickJS measured directly from the shipping `zipline-android-1.27.0.aar`: **1,075,224 bytes** for arm64-v8a. A tuned Wasmtime/Pulley build reaches roughly 1–2.5 MB. Neither decides anything.
- **iOS execution risk strongly favours Zipline.** JavaScriptCore in-process on iOS is interpreter-only — [`ExecutableAllocator.cpp`](https://github.com/WebKit/WebKit/blob/main/Source/JavaScriptCore/jit/ExecutableAllocator.cpp) gates the just-in-time compiler on the `dynamic-codesigning` or `com.apple.developer.cs.allow-jit` entitlements, which ordinary App Store apps do not hold — so no substrate gets a just-in-time compiler on iOS. Zipline **ships to millions of iOS users today** (maintainer swankjesse, [zipline#1654](https://github.com/cashapp/zipline/issues/1654): "the release we've shipped to millions of our customers"). No App Store application shipping a WebAssembly interpreter was found — **UNPROVEN**. App Store guideline exposure (now Guideline 4.7 for downloaded scripting — see [ADR-003](ADR-003-treehouse-precedent-and-evidence-refresh.md)) is identical for both; the difference is precedent, and only one substrate has any.
- **Maturity favours Kotlin/JS.** Kotlin/JS has been Stable since 1.3.0. Kotlin/Wasm has been Beta since 2.2.20 with no announced Stable date, after roughly two and a half years in Alpha. Pulley's own README states "Pulley is very much still a work in progress! Expect the details of the bytecode to change."

**Verified project status.** `cashapp/zipline` is actively maintained — last push 2026-08-28, release 1.27.0 on 2026-04-02, 2,299 stars. Its own WebAssembly tracking issue, [zipline#717](https://github.com/cashapp/zipline/issues/717), has been open since 2022-08-30 with two comments and no pull requests, and its stated motivation is toolchain cleanliness (removing JavaScript runtime dependencies, intermediate `.js` files, and the absence of a debugger) — **not performance**.

## 4. Unstated Assumptions

- **Assumes a batched diff protocol keeps per-crossing cost irrelevant.** This is the load-bearing assumption of the whole decision. Redwood demonstrates the shape — [`ChangesSink.sendChanges(changes: List<Change>)`](https://github.com/cashapp/redwood/blob/trunk/redwood-protocol/src/commonMain/kotlin/app/cash/redwood/protocol/sinks.kt) sends one change list rather than per-node calls — but Dogwood's own protocol is not yet designed.
- **Assumes Zipline's marshalling cost is acceptable. UNPROVEN and must be measured.** Every crossing is a serialization round-trip through `CallChannel`. No published per-call latency exists. If it runs 1–2 microseconds for a small payload, 3,000 unbatched crossings would consume 3–6 ms, or 20–36% of a 60 Hz frame — which is precisely why batching is not optional. **Measure this on-device before Layer 3 work begins.**
- **Assumes QuickJS's known defects are tolerable.** Zipline vendors stock Bellard QuickJS pinned at version `2021-03-27` (verified: `zipline/native/quickjs/VERSION`). That release lacks rope strings, making `StringBuilder`-heavy code O(N²). Maintainer swankjesse in [zipline#1654](https://github.com/cashapp/zipline/issues/1654): this "has caused **real performance problems**." They cannot simply upgrade, because [zipline#747](https://github.com/cashapp/zipline/issues/747) documents that QuickJS bytecode encoding relies on generated identifiers that change between runtime versions. **Dogwood's generated guest code must avoid `StringBuilder`-heavy string construction.**
- **Assumes 120 FPS is not a hard requirement.** No published figure exists for any interpreter driving a retained-mode widget toolkit. Under this decision the guest performs composition only; layout, measure, draw, and Skia all run natively. The target should be restated as a measured budget, not an asserted number.
- **Assumes Redwood's discontinuation is not a verdict on the architecture. UNRESOLVED and material.** The [Redwood README](https://github.com/cashapp/redwood) now opens "**This project is no longer under active development**" (verified: last commit 2026-01-09, last release 0.19.0 on 2025-11-06, not archived). Redwood is structurally the architecture Dogwood proposes. Cash App published no rationale, and their July 2023 post stated "our usage of Redwood to date has been very limited," which suggests a business decision rather than a technical failure — but this is **not established**. Establishing it is the single highest-value open action, and it requires a conversation with Cash App, not a search.

## 5. Updated Documents

- [high-level-tech-spec-final.md](../../high-level-tech-spec-final.md) — v4.0 in full; the substrate change reframes the title, thesis, layer map, and risk register
- [specs/layer-2-compiler.md](../../specs/layer-2-compiler.md) — Kotlin/JS and QuickJS bytecode replace the WebAssembly toolchain
- [specs/layer-3-delivery.md](../../specs/layer-3-delivery.md) — the ZiplineLoader fork is no longer required; the layer now also owns interpreter instantiation
- [specs/layer-4-sandbox.md](../../specs/layer-4-sandbox.md) — written as the QuickJS guest runtime
- [specs/layer-5-host.md](../../specs/layer-5-host.md) — written
- [developer-experience.md](../../developer-experience.md) — written

**Correction after adversarial review.** Section 3 originally cited a 12 ns empty-call benchmark and a 14,718-byte payload figure. Both have been struck in place: the first measures an empty foreign-function call rather than Zipline's real `CallChannel.call(callJson: String): String` transport, and the second measures a program that links no Compose runtime. Neither supported the conclusion they were cited for.

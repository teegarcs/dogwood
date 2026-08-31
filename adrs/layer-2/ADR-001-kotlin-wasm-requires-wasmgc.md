# ADR-001: Kotlin/Wasm Requires WebAssembly Garbage Collection (WasmGC) on Every Target

**Date:** 2026-08-30
**Status:** Accepted — findings stand, but **moot** for the shipping architecture following [Layer 4 ADR-002](../layer-4/ADR-002-adopt-zipline-quickjs-substrate.md), which rejects WebAssembly. Retained because it documents why, and remains the gating analysis if WebAssembly is ever revisited.

## 1. Context & Problem Statement

`specs/layer-2-compiler.md` selects the `wasmWasi` compilation target and justifies it as follows: "We MUST use the `wasm-wasi` target, not `wasm-js`... The `wasm-wasi` target emits pure WebAssembly `(import)` instructions designed for embedded, standalone engines (like Wasm3 on iOS/Android)."

Separately, `high-level-tech-spec-final.md` section 8.1 acknowledges that "Standard Kotlin/Wasm relies on WebAssembly Garbage Collection (WasmGC), which lightweight mobile interpreters (like Wasm3) do not natively support yet," and proposes as mitigation that "dynamic screens compile to linear-memory WebAssembly (Wasm) with a lightweight internal allocator (`wee_alloc`)."

These two statements contradict each other. We needed to determine which is true, because the answer selects the runtime for Layer 4.

## 2. Decision

**We accept that Kotlin/Wasm requires WasmGC, exception handling, and typed function references on both `wasmJs` and `wasmWasi`. There is no linear-memory Kotlin/Wasm mode and no opt-out.** The `wee_alloc` mitigation in section 8.1 is deleted. Wasm3 is removed as a candidate runtime. Runtime selection is constrained to engines implementing the WasmGC proposal.

## 3. Rationale & Research

Five independent lines of evidence, three of them empirical:

1. **JetBrains' own templates enable the proposals explicitly.** [`kotlin-wasm-wasi-template/build.gradle.kts`](https://github.com/Kotlin/kotlin-wasm-wasi-template/blob/main/build.gradle.kts) passes `-W function-references,gc,exceptions` to Wasmtime. The [`sample-wasi-http-kotlin` Makefile](https://github.com/Kotlin/sample-wasi-http-kotlin/blob/main/Makefile) runs `wasmtime serve -S cli -W gc,exceptions,function-references`.
2. **The compiler has no non-GC object representation.** [`WasmTarget.kt`](https://github.com/JetBrains/kotlin/blob/master/wasm/wasm.config/src/org/jetbrains/kotlin/platform/wasm/WasmTarget.kt) declares exactly two values, `WASM_JS` and `WASM_WASI`, both feeding one garbage-collection-typed backend. In [`TypeTransformer.kt`](https://github.com/JetBrains/kotlin/blob/master/compiler/ir/backend.wasm/src/org/jetbrains/kotlin/backend/wasm/ir2wasm/codegenGenerators/TypeTransformer.kt), every non-primitive Kotlin type is routed through `toWasmGcRefType()`. There is not one target conditional.
3. **No flag disables it.** [`WasmCompilerArguments.kt`](https://github.com/JetBrains/kotlin/blob/master/compiler/arguments/src/org/jetbrains/kotlin/arguments/description/WasmCompilerArguments.kt) provides escape hatches for exceptions (`-Xwasm-use-traps-instead-of-exceptions`, `-Xwasm-use-new-exception-proposal`), stack switching, and tail calls — and nothing for garbage collection.
4. **Measured (empirical).** A `.wat` dump of a `wasm-wasi` hello-world contains 636 `(struct` declarations, 1,334 `struct.new` instructions, and 973 `ref.cast` instructions. Linear memory is declared as `(memory $____mem_0 0)` — **zero initial pages** — existing only as a scratch buffer for WebAssembly System Interface (WASI) marshalling. Proposal bisection, dropping one Binaryen feature flag at a time until validation failed, confirms that garbage collection, reference types, exception handling, and bulk memory are **all required** for a hello-world.
5. **Documentation agrees.** [Kotlin 1.9.20 release notes](https://kotlinlang.org/docs/whatsnew1920.html): "To run Kotlin/Wasm applications, you need a VM that supports Wasm Garbage Collection (GC)." [Wasm configuration docs](https://kotlinlang.org/docs/wasm-configuration.html): "Since Kotlin 1.9.20, the Kotlin toolchain uses the latest version of the Wasm garbage collection (WasmGC) proposal."

**There is no fallback.** The Kotlin/Native `wasm32` target — the only Kotlin toolchain that ever emitted linear-memory WebAssembly — was deprecated in 1.8.20 and removed in 1.9.20 ([Update Regarding Kotlin/Native Targets](https://blog.jetbrains.com/kotlin/2023/02/update-regarding-kotlin-native-targets/), [What's new in 1.9.20](https://kotlinlang.org/docs/whatsnew1920.html)). `wee_alloc` is additionally a Rust crate, archived 2025-08-25, and there is no Kotlin linear-memory backend for it to serve.

**Consequence for Layer 4.** [Wasm3's README](https://github.com/wasm3/wasm3#features) lists garbage collection under "⛔ N/A". Wasm3 is actively maintained (v0.9.0, 2026-08-24) but will never run a Kotlin/Wasm binary. Surveying the candidate runtimes against the three required proposals:

| Runtime | WasmGC | iOS | Android |
|---|---|---|---|
| Wasm3 | ⛔ N/A, not planned | ✅ | ✅ |
| WasmKit | ❌ "Not implemented" | ✅ iOS 12+ | ✅ |
| wasmi / wazero | ❌ planned / `gc: false` | unproven | unproven |
| WAMR | ⚠️ non-conformant; `exn` types unsupported | ❌ no `ios` platform port | ✅ |
| Chicory | ✅ full, since 1.7.0 | ❌ impossible (pure Java) | ✅ CI on API 28/35 |
| **Wasmtime + Pulley** | ✅ v47, default | ⚠️ Tier 3 | ⚠️ Tier 3 |

**Wasmtime with the Pulley interpreter is the only runtime clearing all three bars on both platforms.** Its maintainers classify both mobile targets as Tier 3, "not intended to be production-ready."

## 4. Unstated Assumptions

- **Assumes Kotlin/Wasm can execute on a real iOS arm64 device.** This has never been demonstrated by anyone. Chris Fallin confirmed the mechanism in [wasmtime#12251](https://github.com/bytecodealliance/wasmtime/issues/12251) — "Yes, Pulley supports GC and exception handling! And, as far as I know, it should work on iOS" — but the only project attempting it (wasmline) is unreleased, absent from Maven Central, and its iOS end-to-end test is disabled in continuous integration. **This is the highest-risk open item in the programme and must be settled by a device spike before Layer 4 is written.**
- Assumes the Chicory `exnref` nullability validation failure is resolvable. Measured: unoptimized Kotlin/Wasm runs on Chicory 1.7.5 when compiled with `-Xwasm-use-new-exception-proposal`, but every build optimized with `wasm-opt -Oz` or `-O3` — the release pipeline — fails with `type mismatch: instruction requires [ref[-23]] but stack has [refnull[-23]]`. Whether the fault lies with Chicory's validator or Binaryen's output is UNPROVEN, and no upstream issue exists.
- Assumes garbage-collection pause behaviour is acceptable for a 120 Frames Per Second (FPS) UI. Wasmtime's collector is new and, per the [Bytecode Alliance](https://bytecodealliance.org/articles/wasmtime-gc), "hasn't benefited from decades of performance engineering." C API embedders additionally receive `GC_DRC` (deferred reference counting), which **does not collect cycles** — and Kotlin object graphs routinely contain them.

## 5. Updated Documents

- [specs/layer-2-compiler.md](../../specs/layer-2-compiler.md) — rewritten as a Kotlin/JS build pipeline; the `wasmWasi` target selection and its justification are removed
- [high-level-tech-spec-final.md](../../high-level-tech-spec-final.md) — v4.0; the `wee_alloc` linear-memory mitigation is removed from the risk register
- [specs/layer-4-sandbox.md](../../specs/layer-4-sandbox.md) — written as a QuickJS runtime specification; no WebAssembly engine is selected

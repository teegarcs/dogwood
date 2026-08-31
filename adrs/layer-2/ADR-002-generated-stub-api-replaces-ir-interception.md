# ADR-002: Replace Compose Call Interception with a Generated Stub Application Programming Interface (API)

**Date:** 2026-08-30
**Status:** Proposed — **superseded in part** by [Layer 4 ADR-002](../layer-4/ADR-002-adopt-zipline-quickjs-substrate.md). The core decision stands: developers author against a generated stub library rather than having `androidx.compose` calls intercepted in the compiler backend. The `@WasmImport` and linear-memory mechanics in section 3 are replaced by the Zipline protocol and must be rewritten.

## 1. Context & Problem Statement

`specs/layer-2-compiler.md` specifies that the Dogwood Intermediate Representation (IR) compiler plugin "intercepts all function calls directed at the `androidx.compose.*` packages" and "replaces high-level Compose function calls with low-level WebAssembly (Wasm) `import` statements using the `@WasmImport` annotation."

We needed to establish whether a Kotlin IR plugin can rewrite `androidx.compose` calls into WebAssembly imports.

## 2. Decision

**We reject Intermediate Representation (IR) interception of `androidx.compose.*`.**

Instead, Layer 1 developers author against a **generated stub library** (`dogwood-compose-stubs`) that mirrors the supported Compose Application Programming Interface (API) surface. Each stub is an ordinary Kotlin function that marshals its arguments into WebAssembly linear memory and calls a primitives-only `@WasmImport` declaration. Layer 5 generates the matching host-side `@Composable` wrappers with Kotlin Symbol Processing (KSP). Both generators are driven by the same Compose API surface and the same `dogwood-symbol-naming` algorithm, so client and server agree without communicating.

Layer 2's compiler plugin is correspondingly reduced in scope: it no longer rewrites Compose calls. Its remaining responsibilities are marshalling support, the linear-memory arena, and lambda slot allocation.

## 3. Rationale & Research

Four independent facts make interception unworkable, three of them verified by compiling real code:

1. **`@Composable` and `external` are mutually exclusive.** The Compose compiler rewrites every `@Composable` function to take a synthetic `Composer` parameter and `$changed` bitmasks, wrapping the body in `startRestartGroup`/`endRestartGroup` (see [`ComposableFunctionBodyTransformer.kt`](https://github.com/androidx/androidx/tree/androidx-main/compose/compiler/compiler-hosted)). An `external` function has no body to rewrite. There is no valid declaration that is both.
2. **The core layout functions are `inline` and are gone before the IR plugin runs.** `Column`, `Row`, and `Box` are inline functions taking `content: @Composable () -> Unit`. They are inlined into the caller at compile time, so no `IrCall` node survives for `DogwoodIrTransformer` to match.
3. **`@WasmImport` accepts primitives only.** [`FirWasmImportAnnotationChecker.kt`](https://github.com/JetBrains/kotlin/blob/v2.2.0/compiler/fir/checkers/checkers.wasm/src/org/jetbrains/kotlin/fir/analysis/wasm/checkers/declaration/FirWasmImportAnnotationChecker.kt) returns `(type.isPrimitive && !type.isChar) || type.isUnsignedType`. Measured compiler output confirms: `String`, `Any`, `Char`, and `Function1<Int, Int>` are all rejected outright. Default parameter values and varargs are rejected as well. `Button` has ten parameters, of which one is primitive.
4. **No Compose artifacts exist for the target.** Maven Central publishes `ui-wasm-js`, `ui-graphics-wasm-js`, `runtime-wasm-js`, and `skiko-wasm-js`, and **zero `-wasm-wasi` artifacts**. Skiko has no WASI build. Compose cannot be resolved as a dependency on the target Layer 2 selects.

**Why the stub approach resolves each of these.** The guest never references `androidx.compose`, so there is nothing to intercept and nothing to inline away (1, 2, 4). Marshalling lives in ordinary Kotlin stub code, so the `@WasmImport` declaration itself only ever sees `i32` pairs (3). This is precisely how Kotlin's own WASI standard library is built: [`libraries/stdlib/wasm/wasi/src/kotlin/io.kt`](https://github.com/JetBrains/kotlin/blob/master/libraries/stdlib/wasm/wasi/src/kotlin/io.kt) declares `@WasmImport("wasi_snapshot_preview1", "fd_write")` taking `Int` pointers, with `kotlin.wasm.unsafe.withScopedMemoryAllocator` doing the marshalling. The module name is an arbitrary, unvalidated string, so `@WasmImport("dogwood", "compose_material_Button")` is legal.

**Lambdas are recovered, not lost.** Section 6.3 of the overview specification proposes passing lambdas as integer table slots. Passing a `Function1` to `@WasmImport` is rejected, but the design works when inverted: the stub allocates an `i32` slot identifier and passes that; the host calls back into an exported guest dispatcher, which invokes the lambda via `call_indirect`. This requires **host-to-guest reentrancy**, which is confirmed for Wasmtime core modules and WAMR, and **forbidden by the Wasmtime Component Model** ([wasmtime#9600](https://github.com/bytecodealliance/wasmtime/issues/9600) — a `B→A→B` chain is blocked in `runtime/component/func.rs`). **Layer 4 must therefore use core modules, not components.**

**This satisfies the primary product objective.** The intent behind "zero bridge" is the elimination of a hand-maintained component mapper. A generated bridge achieves that: the binding surface is derived automatically from the Compose API on both sides, so matching library versions between the shipped client runner and the server-compiled payload is what guarantees agreement. This is the mechanism already described in section 5 of the overview specification (`dogwood-symbol-naming`), which survives all findings intact and becomes the load-bearing idea of the architecture rather than a supporting detail.

## 4. Unstated Assumptions

- **Assumes the supported API surface is bounded, not total.** Generation cannot cover everything. Generic functions, custom `MeasurePolicy` implementations, and `SubcomposeLayout` are unlikely to be marshallable. The product promise is "no hand-written per-component mapping," **not** "all of Compose works." The supported surface must be explicitly enumerated and versioned.
- **Assumes `Modifier` chains can be flattened into a byte buffer.** This is the hardest marshalling problem in the design and is not yet solved. Cash App's [Redwood](https://github.com/cashapp/redwood) required a code-generated schema of specific modifier types rather than supporting arbitrary chains — a precedent that argues against generality here.
- **Assumes a recomposition protocol exists.** Host-side Compose must be able to re-invoke guest function bodies when state changes. The direction, ownership, and identity semantics of that protocol are unspecified and must be designed before the Foreign Function Interface (FFI) boundary is frozen.
- **Assumes `@ExperimentalWasmInterop` opt-in is acceptable.** `@WasmImport` and `@WasmExport` are annotated `@SinceKotlin("2.2")` with `RequiresOptIn.Level.WARNING`, and are applicable only to top-level `external` functions.
- Assumes Layer 1's `@Preview` support can be backed by a Java Virtual Machine (JVM) implementation of the same stub API surface, since the stubs are not real Compose.

## 5. Updated Documents

- [specs/layer-2-compiler.md](../../specs/layer-2-compiler.md) — the custom Intermediate Representation plugin is removed entirely; the layer is now a build pipeline
- [specs/layer-1-authoring.md](../../specs/layer-1-authoring.md) — developers author against generated stubs; the stub library is the interception point
- [high-level-tech-spec-final.md](../../high-level-tech-spec-final.md) — v4.0 sections 1, 2, and 5
- [specs/layer-5-host.md](../../specs/layer-5-host.md) — owns the generator. **Correction:** section 4 of this ADR referred to Kotlin Symbol Processing; that was superseded by [Layer 5 ADR-002](../layer-5/ADR-002-standalone-codegen-tool-not-ksp.md), which rejects it in favour of a standalone frontend-based tool.

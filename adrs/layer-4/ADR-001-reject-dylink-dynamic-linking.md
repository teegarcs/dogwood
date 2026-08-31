# ADR-001: Reject WebAssembly Dynamic Linking (`dylink.0`) as the Host Binding Mechanism

**Date:** 2026-08-30
**Status:** Accepted — findings stand, but **moot** for the shipping architecture following [ADR-002](ADR-002-adopt-zipline-quickjs-substrate.md), which rejects WebAssembly entirely.

## 1. Context & Problem Statement

The core thesis of `high-level-tech-spec-final.md` states that the dynamic WebAssembly (Wasm) module "uses Wasm's Shared Linear Memory Model and Dynamic Linking (`dylink.0`) to bind directly against the full Compose Multiplatform engine already compiled into the native app binary."

We needed to establish whether `dylink.0` can bind a Wasm module to native Compose Multiplatform code on a mobile device.

## 2. Decision

**We reject `dylink.0` entirely.** All references to WebAssembly dynamic linking are removed from the architecture. The guest module binds to the host exclusively through **host function imports resolved by the embedder Application Programming Interface (API)** at instantiation time.

## 3. Rationale & Research

`dylink.0` is defined by [WebAssembly/tool-conventions/DynamicLinking.md](https://github.com/WebAssembly/tool-conventions/blob/main/DynamicLinking.md). Every entity in that model is a WebAssembly construct:

- `dylink.0` is "a special custom section that indicates this is a dynamic library" — it describes a **WebAssembly binary**.
- `env.memory` is "a wasm memory that is shared between all wasm modules that make up the program."
- `env.__indirect_function_table` is "a wasm table that is shared between all wasm modules."
- `__memory_base` / `__table_base` are `i32` globals holding offsets into that shared **Wasm** memory and table.

Compose Multiplatform on iOS is not a WebAssembly module. It is Kotlin/Native compiled through LLVM to **ARM64 machine code** in a `.framework` ([Kotlin/Native overview](https://kotlinlang.org/docs/native-overview.html): "compiling Kotlin code to native binaries that can run without a virtual machine"). Native ARM code has no Wasm linear memory, no Wasm function table, and no Wasm type section. **There is nothing for `dylink.0` to resolve against.** This is a category error, not an unimplemented feature.

Three further independent facts each make the approach unavailable:

1. **Kotlin cannot emit `dylink.0`.** Repository-wide searches of [JetBrains/kotlin](https://github.com/JetBrains/kotlin) for `dylink`, `SIDE_MODULE`, `__memory_base`, and `GOT.mem` return zero results. [`WasmIrToBinary.kt`](https://github.com/JetBrains/kotlin/blob/master/wasm/wasm.ir/src/org/jetbrains/kotlin/wasm/ir/convertors/WasmIrToBinary.kt) emits only DWARF debug sections, three code-metadata annotations, and the name section. It writes the type section first, so it structurally cannot satisfy the requirement that `dylink.0` be the first section. A full enumeration of the shipped Kotlin 2.2.20 `-Xwasm*` flags (16 flags) contains no position-independent-code, side-module, shared-memory, or dylink option.
2. **`dylink.0` is inapplicable to WasmGC objects.** It relocates byte offsets into shared linear memory and indices into a shared table. A Kotlin object under Kotlin/Wasm is a `(ref $Struct)` in the garbage-collected heap with **no linear-memory address to relocate**. See [Layer 2 ADR-001](../layer-2/ADR-001-kotlin-wasm-requires-wasmgc.md).
3. **No embedded runtime implements it.** Wasmtime, wasmi, wazero, Chicory, and WasmKit have no implementation. Wasm3 declined it explicitly in [issue #278](https://github.com/wasm3/wasm3/issues/278): "Implementing Emscripten-specific linking is not interesting i guess". WebAssembly Micro Runtime (WAMR) has multi-module support, but it is load-time, Wasm-to-Wasm only.

A fourth, independent contradiction: the 15 KB–50 KB payload target is achieved only via Binaryen `--closed-world` (measured: 530,940 bytes unoptimized to 55 bytes for `fun main() {}`; 25,399 bytes for a `println` program). `--closed-world` asserts whole-program knowledge and **forbids dynamically adding types or modules**. The size target and dynamic linking are mutually exclusive.

The correct mechanism is documented by every candidate runtime: WAMR [`export_native_api.md`](https://github.com/bytecodealliance/wasm-micro-runtime/blob/main/doc/export_native_api.md) (`wasm_runtime_register_natives`), Wasmtime `Linker::func_wrap` / `wasmtime_func_new`, Chicory [`host-functions.md`](https://github.com/dylibso/chicory/blob/main/docs/docs/usage/host-functions.md), and WasmKit Swift host functions.

Two independent projects reached the same conclusion: LVGL's Wasm proposal ([lvgl#1523](https://github.com/lvgl/lvgl/issues/1523)) specified opaque handles to reduce serialization overhead, and a request to integrate a User Interface (UI) engine into Wasm3 ([wasm3#217](https://github.com/wasm3/wasm3/issues/217)) was closed as not planned.

## 4. Unstated Assumptions

- Assumes the host binding surface is resolved at **instantiation time**, not lazily at first call. Every candidate embedder API works this way.
- Assumes host function imports are cheap enough for per-frame use. Measured Wasm-to-host crossing cost is 3.06 ns (V8, Apple M3, local measurement) and published at "as little as 10 ns" for Wasmtime; a 120 Frames Per Second (FPS) frame budget of 8.33 ms accommodates roughly 231,000 crossings even at the worst measured 36 ns native-to-managed upcall cost. This assumption is considered safe.
- Assumes hand-written marshalling of all complex types into linear memory. Chicory in particular crosses everything as `long[]` with no marshalling layer; pointers are raw `i32` offsets decoded by hand. This is real, unavoidable engineering cost that the `dylink.0` framing concealed.

## 5. Updated Documents

- [high-level-tech-spec-final.md](../../high-level-tech-spec-final.md) — v4.0; the dynamic-linking thesis is removed from the title, core thesis, and layer map
- [specs/layer-4-sandbox.md](../../specs/layer-4-sandbox.md) — written; the boundary is a Zipline service, not a linked module
- [specs/layer-5-host.md](../../specs/layer-5-host.md) — written; marshalling is explicit and hand-specified, as this decision's assumptions required

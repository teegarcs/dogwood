# ADR-002: Generate the Binding Surface with a Standalone Frontend Intermediate Representation (FIR) Tool, Not Kotlin Symbol Processing (KSP)

**Date:** 2026-08-30
**Status:** Proposed

## 1. Context & Problem Statement

`high-level-tech-spec-final.md` specifies Kotlin Symbol Processing (KSP) as the generator for Layer 5: "Maps WebAssembly (Wasm) imports (raw numbers) to on-device Compose functions (Kotlin objects) using auto-generated Kotlin Symbol Processing (KSP) wrappers" (line 66), and "Layer 5: Kotlin Symbol Processing (KSP) Generator — Generates C-Wrappers at Compile Time" (line 80).

KSP is designed to process annotated symbols within a project's own source set. Layer 5 must instead generate wrappers from the Application Programming Interface (API) surface of an external, unannotated dependency (`androidx.compose.*`). We needed to check whether KSP is the right tool, and what prior art exists.

## 2. Decision

**Replace KSP with a standalone code-generation tool** consisting of two stages, following the architecture Cash App's Redwood uses for the same problem:

1. **A surface parser** that reads the Compose API surface and emits a versioned, serializable description of it.
2. **A generator** that consumes that description and emits both the guest-side stub library (Layer 1/2, see [Layer 2 ADR-002](../layer-2/ADR-002-generated-stub-api-replaces-ir-interception.md)) and the host-side `@Composable` wrappers (Layer 5), using KotlinPoet.

Both stages are invoked by a Gradle plugin. KSP is not used for the binding surface.

## 3. Rationale & Research

**Neither reference project uses KSP for its bridge, and this is the strongest available signal.**

- **Zipline uses no KSP whatsoever.** A code search for `ksp` across [cashapp/zipline](https://github.com/cashapp/zipline) returns **0 results**; no KSP module exists in the repository. Zipline instead ships `zipline-kotlin-plugin`, a Kotlin Intermediate Representation (IR) compiler plugin, whose sources include `ZiplineCompilerPluginRegistrar.kt`, `ZiplineIrGenerationExtension.kt`, `AdapterGenerator.kt`, `BridgedInterface.kt`, and `SignatureHash.kt`. It rewrites `bind`/`take` call sites to inject generated serialization adapters.
- **Redwood uses KSP only for build tooling.** A code search across [cashapp/redwood](https://github.com/cashapp/redwood) returns exactly **2 files**, both under `build-support-ksp-processor`. `RedwoodSymbolProcessor.process()` does one thing — invoke `SnapshotTestProcessor` — which is snapshot-test plumbing for Redwood's own build and unrelated to widget or schema generation.

**Redwood's actual pipeline is the pattern to copy.** Its schema is declared as annotated Kotlin interfaces in `*-schema` modules, and is processed by two published command-line applications:

- **`redwood-tooling-schema`** parses the schema. Its `build.gradle` declares `implementation libs.kotlin.compilerEmbeddable` and `api libs.kotlin.reflect`; its sources contain both `schemaParser.kt` (reflection-based) and **`schemaParserFir.kt`** (embedded Kotlin FIR frontend). It is published as the `redwood-schema` command-line application and includes a `JsonCommand` that serializes the parsed schema.
- **`redwood-tooling-codegen`** generates code with **KotlinPoet** (`implementation libs.kotlinPoet`), published as the `redwood-codegen` command-line application.

The decisive structural detail is that Redwood's generator files sit side by side in one module and generate **both ends of the bridge from one source of truth**:

```
protocolGuestGeneration.kt   protocolHostGeneration.kt
widgetGeneration.kt          widgetComposeUiGeneration.kt
composeGeneration.kt         modifierGeneration.kt
```

This is a stronger correctness guarantee than two independently-run generators agreeing via a shared algorithm, and it is directly applicable: Dogwood's guest stubs and host wrappers should be emitted by the same tool from the same parsed surface.

**Why KSP is the wrong tool specifically here.** KSP resolves declarations that a processor is pointed at within a compilation. Layer 5's job is to enumerate and reflect over the entire public surface of a third-party library that carries no Dogwood annotations, then emit code for a *different* compilation target (the guest stubs are compiled to WebAssembly on a build server; the host wrappers are compiled into the application binary). An out-of-band tool that produces a serializable surface description handles that split naturally; KSP does not.

**Note on the `redwood-tooling-schema` test configuration**, which is a realistic warning about cost: its Gradle test task sets `maxHeapSize = '3g'` and `forkEvery = 1`, with the comment "We create a lot of instances of the Kotlin frontend. Throw memory + forks at it to avoid OOM." Embedding the Kotlin frontend is effective but expensive.

## 4. Unstated Assumptions

- Assumes the Compose API surface can be parsed from a stable, machine-readable source. Two candidates exist and should be evaluated: the embedded Kotlin FIR frontend over the Compose sources or klibs (Redwood's approach), or the metalava signature dumps androidx already publishes as `api/current.txt` per module, which proved directly parseable during this research.
- Assumes the surface description can be versioned and published as an artifact, so the client's shipped dictionary and the server's compiler agree by construction rather than by recomputation. See [ADR-003](ADR-003-opaque-handle-binding-surface.md).
- Assumes KotlinPoet can express the generated host wrappers, including `@Composable` annotations and default-argument forwarding. Redwood generates `@Composable` code with KotlinPoet today, so this is low risk.
- **Assumes a Kotlin compiler plugin is still required for Layer 2.** This ADR replaces KSP for the *binding surface* only. Zipline's precedent suggests an IR plugin may still be warranted for guest-side concerns such as arena allocation and lambda slot assignment.

## 5. Updated Documents

- [specs/layer-5-host.md](../../specs/layer-5-host.md) — `dogwood-codegen` is a standalone tool; Kotlin Symbol Processing is not used
- [high-level-tech-spec-final.md](../../high-level-tech-spec-final.md) — v4.0 sections 2 and 5
- [specs/layer-2-compiler.md](../../specs/layer-2-compiler.md) — the build pipeline consumes generated output rather than hosting a processor

**Refinement after adversarial review.** Section 4 offered metalava dumps and the embedded Kotlin frontend as equal candidates. They are not: metalava signature files record only the keyword `optional` and carry **no default-value expressions**, while 73.9% of parameters in the measured surface are optional. Since the generator must reproduce defaults, the frontend (or klib metadata) is required. Metalava dumps remain useful for enumerating and measuring the surface — see [`tools/measure-compose-surface.py`](../../tools/measure-compose-surface.py).

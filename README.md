# Project Dogwood

**Zero-Bridge Server-Driven Compose via WebAssembly (Wasm) Dynamic Linking**

Project Dogwood is an architecture specification for a Server-Driven User Interface (SDUI)
engine that removes the component bridge entirely. Instead of shipping JavaScript Object
Notation (JSON) schemas that map onto a fixed catalog of native widgets, developers author
ordinary Kotlin Jetpack Compose code. That code is compiled into a compact WebAssembly
(Wasm) bytecode module (target: 15 KB – 50 KB), delivered Over-The-Air (OTA), and executed
inside an embedded Wasm interpreter on the device.

The key idea: the dynamic module **does not bundle the Compose runtime**. It uses Wasm's
shared linear memory model and dynamic linking (the `dylink.0` custom section) to bind
directly against the full Compose Multiplatform engine already compiled into the host
application binary.

**Target platforms:** Android (Application Programming Interface (API) 26+) and iOS (iOS 15+).

> **Status: specification, not an implementation.** This repository currently contains
> design documents only. No engine code has been written yet. Treat every performance
> number and binary-size target as a design goal to be validated, not a measured result.

## Why

Traditional SDUI hits what the specification calls the **Widget Bridge Bottleneck**:

1. **Maintenance burden** — every new Modifier, custom layout, or animation curve requires
   updating the bridge schema, the serialization protocol, and two client renderers.
2. **Feature lag** — dynamic screens are limited to a small subset of what Jetpack Compose
   and SwiftUI actually offer.
3. **Latency** — driving 120 Frames Per Second (FPS) updates across a JavaScript bridge with
   JSON or byte-array serialization causes frame drops from translation overhead and
   garbage collection pauses.

Dogwood's thesis is that a Wasm module linked directly against the native Compose engine
avoids all three, because there is no schema and no serialization boundary to cross for
layout mutations.

## Repository Layout

| Path | Contents |
| --- | --- |
| [`high-level-tech-spec-final.md`](high-level-tech-spec-final.md) | **Start here.** The v3.0 architecture blueprint and layer map. |
| [`specs/`](specs/) | One deep-dive specification per layer. |
| [`adrs/`](adrs/) | Architecture Decision Records, organized by layer. See [`adrs/README.md`](adrs/README.md). |
| [`archive/`](archive/) | Superseded drafts (v1.0 Zipline-based, v2.0 Wasm), kept for decision history. |
| [`AGENTS.md`](AGENTS.md) | Authoring rules that govern every document in this repository. |

## The Five Layers

| Layer | Name | Runs on | Spec |
| --- | --- | --- | --- |
| 1 | Developer Authoring Tier | Server (build time) | [layer-1-authoring.md](specs/layer-1-authoring.md) |
| 2 | Server Compiler (Kotlin Intermediate Representation (IR) Plugin) | Server (build time) | [layer-2-compiler.md](specs/layer-2-compiler.md) |
| 3 | Over-The-Air (OTA) Delivery & Security | Device | [layer-3-delivery.md](specs/layer-3-delivery.md) |
| 4 | Sandbox Engine | Device | _not yet written_ |
| 5 | Native Host Wrappers | Device | _not yet written_ |

Layers 4 and 5 are described in the high-level specification but do not yet have their own
layer documents. They are the next drafting priority.

## Contributing

Documentation in this repository follows strict authoring rules — no unverified API claims,
every acronym spelled out, a Mermaid diagram in every layer document, and adversarial peer
review before a layer is considered complete. Those rules live in [`AGENTS.md`](AGENTS.md)
and apply to human and Artificial Intelligence (AI) contributors alike.

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the workflow.

## License

[Apache License 2.0](LICENSE). See [`NOTICE`](NOTICE) for the copyright and attribution
notice that must be preserved in redistributions.

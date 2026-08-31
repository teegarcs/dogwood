# Project Dogwood

**Server-Driven Compose via a Generated Full-Surface Binding**

Project Dogwood is an architecture specification for a Server-Driven User Interface (SDUI)
engine that removes the *hand-maintained* component bridge. Instead of shipping JavaScript
Object Notation (JSON) schemas that map onto a fixed catalog of native widgets, developers
author ordinary Kotlin Jetpack Compose code. That code is compiled to Kotlin/JavaScript,
delivered Over-The-Air (OTA), and executed on-device inside a sandboxed QuickJS interpreter
running the **real Compose runtime**. Composition produces batched tree changes that a
**generated** binding layer replays against native Compose Multiplatform.

The key idea: the bridge is required, but it does not have to be written by hand. Dogwood
generates the declarative portion — the part that grows without bound as Compose grows —
from the Compose Application Programming Interface (API) surface itself, for both server
and client, from one source of truth.

**Target platforms:** Android (Application Programming Interface (API) 26+) and iOS (iOS 15+).

> **Status: specification, not an implementation.** This repository contains design
> documents only. No engine code has been written. Several load-bearing assumptions are
> unmeasured — see [section 7 of the technical specification](high-level-tech-spec-final.md)
> for the full risk register. Treat every performance number as a goal to be validated.

## Why

Traditional SDUI hits a **component mapper treadmill**:

1. **Maintenance burden** — every new Modifier, layout, or animation curve means editing the
   schema, the serialization protocol, and two client renderers, then shipping a release.
2. **Feature lag** — dynamic screens are limited to a small hand-curated subset of Compose.
3. **The registry never stops growing**, and it is maintained by hand.

Dogwood's thesis is that generating the binding from the Compose API surface removes the
unbounded part of that work. A bounded, enumerable set of hard subsystems — `Modifier`,
lazy layouts, text input, live state holders, the host environment, and node identity —
must still be engineered once. **The registry stops being a treadmill; it does not stop
existing.**

Dogwood renders exclusively through **Compose Multiplatform**, which is what makes the
generation approach pay off. Cash App's Redwood — the closest prior art — maps guest widgets
onto each platform's *native* widget system and consequently ships four host implementations
per widget set (`composeui`, `dom`, `uiview`, `view`). Every widget costs four hand-written
bindings. Targeting Compose Multiplatform instead means **one** binding implementation that
reaches Android, iOS, and Web:

| | Cost per widget | Platforms reached |
| --- | --- | --- |
| Native-widget mapping | 1 schema + **4 hand-written bindings** | Android Views, UIKit, Compose UI, DOM |
| Compose Multiplatform | **1 generated binding** | Android, iOS, Web |

The trade is that the host application must itself be a Compose Multiplatform application —
see [Layer 5 ADR-004](adrs/layer-5/ADR-004-compose-multiplatform-sole-host-target.md).

An earlier version of this project (v3.0) pursued a "zero-bridge" design using WebAssembly
(Wasm) dynamic linking. That approach was investigated and refuted; the evidence is
preserved in [`adrs/`](adrs/) and the drafts in [`archive/`](archive/).

## Repository Layout

| Path | Contents |
| --- | --- |
| [`high-level-tech-spec-final.md`](high-level-tech-spec-final.md) | **Start here.** The v4.0 architecture blueprint, layer map, and risk register. |
| [`roadmap.md`](roadmap.md) | The phased delivery plan, gates, and what would stop the project. |
| [`developer-experience.md`](developer-experience.md) | What this is like to use, with worked example code. |
| [`specs/`](specs/) | One deep-dive specification per layer. |
| [`adrs/`](adrs/) | Architecture Decision Records, by layer. See [`adrs/README.md`](adrs/README.md). |
| [`tools/`](tools/) | The committed Compose API-surface classifier and its pinned dumps. |
| [`archive/`](archive/) | Superseded drafts (v1.0 Zipline, v2.0 and v3.0 Wasm), kept for decision history. |
| [`AGENTS.md`](AGENTS.md) | Authoring rules governing every document here. |

## The Five Layers

| Layer | Name | Runs on | Spec |
| --- | --- | --- | --- |
| 1 | Developer Authoring Tier | Build time | [layer-1-authoring.md](specs/layer-1-authoring.md) |
| 2 | Server Build Pipeline | Build time | [layer-2-compiler.md](specs/layer-2-compiler.md) |
| 3 | Over-The-Air (OTA) Delivery & Security | Device | [layer-3-delivery.md](specs/layer-3-delivery.md) |
| 4 | Guest Runtime (QuickJS + Compose runtime) | Device | [layer-4-sandbox.md](specs/layer-4-sandbox.md) |
| 5 | Native Host & Generated Binding Layer | Device | [layer-5-host.md](specs/layer-5-host.md) |

`dogwood-codegen` is not a layer. It is the build-time tool that generates Layer 1's stubs
and Layer 5's bindings from one parsed API surface; it is specified in Layer 5.

## Measured Coverage

Of **450** public `@Composable` User Interface functions across ten Compose modules, pinned
to a commit hash and measured by [`tools/measure-compose-surface.py`](tools/measure-compose-surface.py):

| Verdict | Count | Share |
| --- | ---: | ---: |
| Generable once the `Modifier` subsystem exists | 365 | 81.1% |
| Requires a per-holder live-state protocol | 58 | 12.9% |
| Structurally unreachable | 27 | 6.0% |

Run the classifier yourself: `python3 tools/measure-compose-surface.py`.

## Contributing

Documentation here follows strict authoring rules — no unverified API claims, every acronym
spelled out, a Mermaid diagram in every layer document, and adversarial peer review before a
layer is considered complete. Those rules live in [`AGENTS.md`](AGENTS.md) and apply to human
and Artificial Intelligence (AI) contributors alike.

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the workflow.

## License

[Apache License 2.0](LICENSE). See [`NOTICE`](NOTICE) for the copyright and attribution
notice that must be preserved in redistributions.

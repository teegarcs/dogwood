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

**Target platforms:** Android (Application Programming Interface (API) 26+) first, then Web (Compose Multiplatform Web, Beta), then iOS (iOS 15+) — see the [roadmap's platform order](roadmap.md).

> **Status: a working implementation on Android, iOS and the web, from one set of screens.** The
> design documents remain the substance of this repository, but the load-bearing assumptions are no
> longer unmeasured. [`tools/phase0/`](tools/phase0/) measures the architecture and
> [`engine/`](engine/) implements it: real screens, authored as ordinary Kotlin Compose, executed in
> a sandbox — QuickJS on mobile, a Web Worker in a browser — rendered by native Compose
> Multiplatform, responding to taps. A generator emits both halves of the boundary from one surface;
> a product can [declare its own components](adrs/layer-5/ADR-046-a-product-registers-its-own-segment.md)
> and [consume the whole thing from outside this repository](adrs/layer-5/ADR-047-the-generator-ships-as-a-plugin.md);
> a bad publish is [survivable without a server](adrs/layer-5/ADR-049-surviving-a-bad-publish.md);
> and a payload published while a screen is open carries the user's state across on every client.
> Phase 5's page-weight gate is
> [measured](adrs/layer-5/ADR-030-web-page-weight-measured.md) rather than quoted.
>
> **What is left is not engineering.** [`plans/production-readiness.md`](plans/production-readiness.md)
> is closed; the two things between this and a first ship — production signing keys and somewhere to
> serve payloads from — are decisions, and they live in
> [`DECISIONS-FOR-THE-OWNER.md`](DECISIONS-FOR-THE-OWNER.md).
> **The Phase 0 gate is formally not closed**, and that is now a decision rather than a pending
> task: the low-end device it is defined on is not available and will not be acquired
> ([Layer 4 ADR-008](adrs/layer-4/ADR-008-gate-device-not-available.md)). Every number was taken
> on faster hardware and is a lower bound. The carried risk is recomposition speed on low-end
> silicon, and the harness runs on any Android device, so the first one it meets settles it. See
> [the roadmap's Phase 0 status](roadmap.md) and [section 7 of the technical
> specification](high-level-tech-spec-final.md) for the risk register.

## Why

Traditional SDUI hits a **component mapper treadmill**:

1. **Maintenance burden** — every new Modifier, layout, or animation curve means editing the
   schema, the serialization protocol, and two client renderers, then shipping a release.
2. **Feature lag** — dynamic screens are limited to a small hand-curated subset of Compose.
3. **The registry never stops growing**, and it is maintained by hand.

Dogwood's thesis is that generating the binding from the Compose API surface removes the
unbounded part of that work. A bounded, enumerable set of hard subsystems — nine of them:
`Modifier`, lazy layouts, text input, live state holders, the host environment, node
identity, animation, resources and assets, and host services with a component-extension
mechanism ([Layer 5 ADR-005](adrs/layer-5/ADR-005-corrected-coverage-and-bespoke-subsystem-list.md)) —
must still be engineered once, and they are the larger half of the work. **The registry
stops being a treadmill; it does not stop existing.**

Dogwood renders exclusively through **Compose Multiplatform**, which is what makes the
generation approach pay off. Cash App's Redwood — the closest prior art — maps guest widgets
onto each platform's *native* widget system and consequently ships four host implementations
per widget set (`composeui`, `dom`, `uiview`, `view`). Every widget costs four hand-written
bindings. Targeting Compose Multiplatform instead means **one** binding implementation that
reaches all three targets, delivered Android first, Web second (Compose Multiplatform for
Web is Beta), iOS after — see the [roadmap's platform order](roadmap.md):

| | Cost per widget | Platforms reached |
| --- | --- | --- |
| Native-widget mapping | 1 schema + **4 hand-written bindings** | Android Views, UIKit, Compose UI, DOM |
| Compose Multiplatform | **1 generated binding** | Android, then Web (Beta), then iOS |

The trade is that the host application must itself be a Compose Multiplatform application —
see [Layer 5 ADR-004](adrs/layer-5/ADR-004-compose-multiplatform-sole-host-target.md).

An earlier version of this project (v3.0) pursued a "zero-bridge" design using WebAssembly
(Wasm) dynamic linking. That approach was investigated and refuted; the evidence is
preserved in [`adrs/`](adrs/) and the drafts in [`archive/`](archive/).

## Repository Layout

| Path | Contents |
| --- | --- |
| [`high-level-tech-spec-final.md`](high-level-tech-spec-final.md) | **Start here.** The v4.0 architecture blueprint, layer map, and risk register. |
| [`roadmap.md`](roadmap.md) | The phased delivery plan, gates, and what would stop the project. **Phases 0–7 are closed.** |
| [`plans/adoption-audit.md`](plans/adoption-audit.md) | **What stops a production application from adopting this**, audited against the code with each finding verified. The successor to the readiness plan. |
| [`plans/production-readiness.md`](plans/production-readiness.md) | The engineering plan that got the engine here — closed, and superseded by the audit above. |
| [`DECISIONS-FOR-THE-OWNER.md`](DECISIONS-FOR-THE-OWNER.md) | Everything blocked on a person rather than on work — the Apple ruling, the merge gate, the unfiled upstream reports, the gate device. |
| [`docs/README.md`](docs/README.md) | **Start here for the manuals** — who should read what, in which order. |
| [`docs/`](docs/) | **The manuals.** [Getting started](docs/getting-started.md) for adding Dogwood to an application, [authoring](docs/authoring.md) for writing screens, [operating](docs/operating.md) for whoever is on call, and a [component reference](docs/api/) generated from the surface. |
| [`docs/checks.md`](docs/checks.md) | Every check this repository runs, what each proves, and when it fails. |
| [`developer-experience.md`](developer-experience.md) | What this is like to use, with worked example code. |
| [`specs/`](specs/) | One deep-dive specification per layer. |
| [`adrs/`](adrs/) | Architecture Decision Records, by layer. See [`adrs/README.md`](adrs/README.md). |
| [`engine/`](engine/) | **The implementation.** Guest runtime, host binding layer, and the Phase 1 vertical slice. |
| [`tools/`](tools/) | The Compose API-surface classifier, the Phase 0 measurement harness with its results, and the [web page-weight harness](tools/web-weight/). |
| [`plans/`](plans/) | Work plans for phases in flight, kept with their results rather than deleted when done. |
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

Of **445** public widget-shaped `@Composable` User Interface functions across ten Compose
modules, pinned to a commit hash and measured by
[`tools/measure-compose-surface.py`](tools/measure-compose-surface.py) (third revision of
the measurement — the first two were each found optimistically wrong under adversarial
review; see [Layer 5 ADR-005](adrs/layer-5/ADR-005-corrected-coverage-and-bespoke-subsystem-list.md)):

| Verdict | Count | Share |
| --- | ---: | ---: |
| Generable once the `Modifier` subsystem exists | 301 | 67.6% |
| Requires a bespoke subsystem (live state, callbacks, assets, text input) | 112 | 25.2% |
| Structurally unreachable | 32 | 7.2% |

Two caveats travel with these numbers: 76.4% of the generable tier also requires the
deferred-expression protocol, and the lowercase `@Composable` surface (defaults factories,
`remember*` state factories, animation functions — 458 functions, about the same size
again) sits outside the denominator and maps onto the bespoke subsystems.

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

# ADR-004: Compose Multiplatform Is the Sole Host Rendering Target

**Date:** 2026-08-31
**Status:** Accepted

## 1. Context & Problem Statement

A Server-Driven User Interface (SDUI) system must decide what the host renders *with*. Cash App's Redwood — the closest prior art to Project Dogwood — chose to render into each platform's native widget system, so that a guest-defined `Button` becomes a real `android.widget.Button`, a real `UIButton`, a Compose `Button`, or a Document Object Model (DOM) element depending on where it runs.

That choice determines the cost of every widget the system supports, and it is the single largest factor in whether a generated binding layer is viable. It had to be decided explicitly rather than inherited by default.

## 2. Decision

**Dogwood renders exclusively through Compose Multiplatform.** There is one host binding implementation. It runs on Android, iOS, and Web because Compose Multiplatform does.

Dogwood will not emit bindings for Android Views, UIKit, or the DOM, and will not support a host that is not Compose Multiplatform.

## 3. Rationale & Research

**Redwood pays a per-platform multiplier on every widget.** Verified against the repository structure at [cashapp/redwood](https://github.com/cashapp/redwood): each widget set ships **four** host implementations.

| Widget set | Host implementations |
|---|---|
| `redwood-layout-*` | `composeui`, `dom`, `uiview`, `view` |
| `redwood-ui-basic-*` | `composeui`, `dom`, `uiview`, `view` |
| `redwood-lazylayout-*` | `composeui`, `dom`, `uiview`, `view` |
| `redwood-ui-core-*` | `dom`, `uiview`, `view` |

So a Redwood widget costs one schema declaration plus **four hand-written host bindings**, each with its own layout semantics, its own bugs, and its own platform idioms to reconcile. That multiplier applies to every widget, forever, and it is the reason Redwood's supported catalog stayed small: `redwood-ui-basic` covers a handful of primitives, not the Compose surface.

**Compose Multiplatform removes the multiplier entirely.** One binding implementation reaches every target, because Compose Multiplatform itself is the portability layer. Verified on Maven Central: `org.jetbrains.compose.ui:ui-wasm-js`, `foundation-wasm-js`, and `material3-wasm-js` are all published through 1.9.3, so Web is a real target rather than an aspiration; iOS and Android are long established.

**This is what makes generation worth doing.** A generated binding layer is only valuable if the generated artifact is cheap to produce and cheap to keep correct. Under Redwood's model, generation would have to emit four divergent implementations per widget and reconcile their layout semantics — the hard part would remain hand-written, and the generator would multiply the maintenance surface rather than reduce it. Under a single Compose Multiplatform target, the generated host binding is a direct call to the very function the guest named, so generation is close to mechanical.

Stated as a cost model:

| | Cost per widget | Platforms reached |
|---|---|---|
| Redwood | 1 schema declaration + **4 hand-written host bindings** | Android Views, UIKit, Compose UI, DOM |
| Dogwood | **1 generated binding** | Android, iOS, Web |

**On why Redwood was discontinued.** Reported second-hand, and recorded here as context rather than as established fact: adoption stalled because iOS engineers were reluctant to take it on, and because mapping to native widget systems on each platform meant maintaining separate mappers rather than sharing one renderer. **This is unverified** — Cash App published no rationale, and [Layer 4 ADR-002](../layer-4/ADR-002-adopt-zipline-quickjs-substrate.md) still lists confirming it as an open action. What *is* verified is the structural fact above: the four-implementations-per-widget cost is visible in the repository, and it is a sufficient explanation for a small supported catalog and slow adoption regardless of what the actual reason was.

The relevant conclusion for Dogwood is narrow and does not depend on the hearsay: **the cost that constrained Redwood is one Dogwood does not pay.**

## 4. Unstated Assumptions

- **Assumes the host application is willing to be a Compose Multiplatform application.** This is a real constraint. An iOS application built in SwiftUI or UIKit cannot host Dogwood without adopting Compose Multiplatform for the surfaces Dogwood drives. That is a larger organisational commitment than adopting a library, and it should be confirmed with the iOS team before the programme is funded — it is precisely the adoption question reported to have stalled Redwood.
- **Assumes Compose Multiplatform on iOS is production-ready.** JetBrains declared iOS stable in 1.8.0, with accessibility on by default. This has been verified for accessibility and text input, but not for the specific rendering load Dogwood produces.
- **Assumes the Web target is viable for Dogwood's purposes.** Compose Multiplatform on Web renders through Skiko on WebGL2 and carries a large payload — the Skiko WebAssembly module alone is roughly 8.6 MB, with a measured demo application near 12.4 MB raw. Web is therefore *reachable* under this decision but may not be *appropriate* for every experience, and no Dogwood specification currently targets it. **UNPROVEN** that the Web host is practical at Dogwood's payload budget.
- **Assumes Web would use a different guest substrate.** On Web the browser supplies a just-in-time JavaScript engine, so a Dogwood guest would load directly rather than through QuickJS, and would run substantially faster than on mobile. The protocol is unchanged. This has not been designed.
- Assumes that not supporting native-widget hosts is acceptable. Any future requirement to render into UIKit or Android Views directly would reintroduce Redwood's multiplier and invalidate this decision.

## 5. Updated Documents

- [high-level-tech-spec-final.md](../../high-level-tech-spec-final.md) — section 1 motivation and the cost model
- [README.md](../../README.md) — the "Why" section
- [specs/layer-5-host.md](../../specs/layer-5-host.md) — the rendering target is fixed, and the binding layer is single-implementation
- [developer-experience.md](../../developer-experience.md) — platform reach

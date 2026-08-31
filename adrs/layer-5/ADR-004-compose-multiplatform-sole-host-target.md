# ADR-004: Compose Multiplatform Is the Sole Host Rendering Target

**Date:** 2026-08-31
**Status:** Accepted

## 1. Context & Problem Statement

A Server-Driven User Interface (SDUI) system must decide what the host renders *with*. Cash App's Redwood — the closest prior art to Project Dogwood — chose to render into each platform's native widget system, so that a guest-defined `Button` becomes a real `android.widget.Button`, a real `UIButton`, a Compose `Button`, or a Document Object Model (DOM) element depending on where it runs.

That choice determines the cost of every widget the system supports, and it is the single largest factor in whether a generated binding layer is viable. It had to be decided explicitly rather than inherited by default.

## 2. Decision

**Dogwood renders exclusively through Compose Multiplatform.** There is one host binding implementation. It reaches Android, Web, and iOS because Compose Multiplatform does, delivered in the roadmap's platform order — Android first, Web second (the Web target is Beta), iOS after.

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

**Compose Multiplatform removes the multiplier entirely.** One binding implementation reaches every target, because Compose Multiplatform itself is the portability layer. Verified on Maven Central ([Layer 4 ADR-003](../layer-4/ADR-003-treehouse-precedent-and-evidence-refresh.md)): `org.jetbrains.compose.ui:ui-wasm-js` and `foundation-wasm-js` are published at 1.12.0, `material3-wasm-js` at `1.12.0-alpha03`. iOS is Stable since 1.8.0; Android is long established; **Compose Multiplatform for Web is Beta** and its Material 3 artifact trails the stable line, so Web is a credible direction rather than a shipped equal.

**This is what makes generation worth doing.** A generated binding layer is only valuable if the generated artifact is cheap to produce and cheap to keep correct. Under Redwood's model, generation would have to emit four divergent implementations per widget and reconcile their layout semantics — the hard part would remain hand-written, and the generator would multiply the maintenance surface rather than reduce it. Under a single Compose Multiplatform target, the generated host binding is a direct call to the very function the guest named, so generation is close to mechanical.

Stated as a cost model:

| | Cost per widget | Platforms reached |
|---|---|---|
| Redwood | 1 schema declaration + **4 hand-written host bindings** | Android Views, UIKit, Compose UI, DOM |
| Dogwood | **1 generated binding** | Android, then Web (Beta), then iOS |

**On why Redwood was discontinued.** Now publicly characterised: announcing the final release (0.19.0), maintainer Jake Wharton wrote "The decision wasn't technical. Redwood works/worked great for its intended use cases" ([redwood discussion #2894](https://github.com/cashapp/redwood/discussions/2894); [Layer 4 ADR-003](../layer-4/ADR-003-treehouse-precedent-and-evidence-refresh.md)). The second-hand account previously recorded here — iOS engineers reluctant to adopt it, per-platform mappers compounding the cost — is consistent with that statement but remains unconfirmed in its specifics. What *is* verified is the structural fact above: the four-implementations-per-widget cost is visible in the repository.

The relevant conclusion for Dogwood is narrow: **the *engineering* cost that constrained Redwood is one Dogwood does not pay — but the *organisational* cost, iOS adoption, Dogwood pays in amplified form.** See the first assumption below.

## 4. Unstated Assumptions

- **Assumes the host application is willing to be a Compose Multiplatform application — and this decision asks *more* of an iOS team than Redwood did.** Redwood at least produced real `UIView`s; Dogwood asks the iOS organisation to accept the Kotlin/Native toolchain in their build, Xcode integration friction, and wholesale Compose rendering of the driven surfaces. Since iOS-team reluctance is precisely the adoption failure reported for Redwood, this decision **amplifies the known killer of its own prior art**, and the mitigation — a small first surface, plus the host-registered-component extension in [ADR-005](ADR-005-corrected-coverage-and-bespoke-subsystem-list.md) so guests can use the host's design system — is part of the decision, not an afterthought. Confirm with the iOS team before the programme is funded (roadmap Phase 0 parallel track).
- **Assumes the binary-size cost is acceptable.** JetBrains' published figure: Compose Multiplatform adds roughly **9 megabytes** to an iOS application versus a SwiftUI equivalent ([1.8.0 announcement](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/)); community measurements of real applications run higher. For a host already on Compose Multiplatform this is sunk cost; for a SwiftUI host it is a new, visible line item.
- **Assumes Skia-rendered widgets are "native enough" on iOS.** Compose Multiplatform renders through Skia over Metal — native *code*, not native *widgets*. Scroll physics, text selection, and context menus are Compose's emulations (1.11.0's opt-in native text input narrows this for text). On Android the rendered tree is identical to a statically compiled screen; on iOS "fully native rendering" must be read as "native-performance Skia rendering with mature platform integration," and any host mixing SwiftUI screens with Dogwood surfaces will have a felt seam in scroll and text behaviour. Documents quoting "fully native" have been aligned with this.
- **Assumes per-host-release version coupling is manageable.** The dictionary is pinned to the host's Compose Multiplatform version, so the server maintains one artifact per live dictionary version (overview section 6). The build-matrix cost — experiences × live dictionary versions — is real, unquantified, and grows with release cadence.
- **Assumes Compose Multiplatform on iOS is production-ready.** JetBrains declared iOS stable in 1.8.0, with accessibility on by default. This has been verified for accessibility and text input, but not for the specific rendering load Dogwood produces.
- **Assumes the Web target is viable for Dogwood's purposes.** Compose Multiplatform on Web renders through Skiko on WebGL2 and carries a large payload — the Skiko WebAssembly module alone is roughly 8.6 MB, with a measured demo application near 12.4 MB raw. Web is therefore *reachable* under this decision but may not be *appropriate* for every experience, and no Dogwood specification currently targets it. **UNPROVEN** that the Web host is practical at Dogwood's payload budget.
- **Assumes Web would use a different guest substrate.** On Web the browser supplies a just-in-time JavaScript engine, so a Dogwood guest would load directly rather than through QuickJS, and would run substantially faster than on mobile. The protocol is unchanged. This has not been designed.
- Assumes that not supporting native-widget hosts is acceptable. Any future requirement to render into UIKit or Android Views directly would reintroduce Redwood's multiplier and invalidate this decision.

## 5. Updated Documents

- [high-level-tech-spec-final.md](../../high-level-tech-spec-final.md) — section 1 motivation and the cost model
- [README.md](../../README.md) — the "Why" section
- [specs/layer-5-host.md](../../specs/layer-5-host.md) — the rendering target is fixed, and the binding layer is single-implementation
- [developer-experience.md](../../developer-experience.md) — platform reach

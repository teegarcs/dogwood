# ADR-008: The Registered Design System Is Audited Against Skyscanner Backpack

**Date:** 2026-08-31
**Status:** Accepted

## 1. Context & Problem Statement

[roadmap.md](../../roadmap.md) Phase 1 step 1 requires a half-day audit before the slice is
built: "list the ten most-used components in the company design system by call-site count;
hand-apply the bindability rule ([Layer 5](../../specs/layer-5-host.md), 'Bindability: The Real
Rule') to each signature." Until now that audit had not happened, because this project has no
company design system to audit, and the five design-system components in the slice were
plausible stand-ins rather than anything real.

Stand-ins are a poor test of a binding architecture. They are invented by the same person
writing the binder, so they are bindable by construction, and the audit's whole purpose — to
find out how often real signatures *fail* the rule and what the failures look like — is lost.

## 2. Decision

**The audit is performed against [Skyscanner Backpack](https://github.com/Skyscanner/backpack-android),
and the registered segment is modelled on it.**

Backpack was chosen over the alternatives because it satisfies four things at once: it is
public and Apache 2.0 licensed; it is explicitly built on atomic design; it ships **real Jetpack
Compose components** rather than only web ones, so the signatures under audit are Kotlin
`@Composable` functions of exactly the kind Dogwood must bind; and it is actively released
(version 84.0.0 on 2026-08-24). Orbit (Kiwi.com) and Vitamin (Decathlon) are also atomic-design
systems, but their component libraries are web-first, which would have made the audit a
translation exercise rather than a measurement.

**Dogwood does not depend on the Backpack library.** The registered components are Dogwood's
own, modelled on Backpack's signatures and using its published spacing and corner-radius tokens.
Depending on the library would tie the host layer to Android, and the host layer is common
Kotlin — which is the property the whole architecture rests on. The colours are Dogwood's, not
Backpack's, because Backpack's colour tokens are generated and not vendored here.

**One deviation from the roadmap's method, stated rather than hidden.** The roadmap says to rank
by call-site count. Call-site counts are an internal metric of the organisation that owns the
design system, and are not available for a third party's. The eleven components below were
chosen by what a real product screen needs instead. **A real adoption must use call-site counts**;
ranking by need is a substitute for a third-party audit, not a replacement for the method.

## 3. Rationale & Research

Signatures read from Backpack's published Compose sources at `main`. Abridged to the parameters
that decide the verdict.

| Component | Parameters that decide it | Verdict | Fix |
| --- | --- | --- | --- |
| `BpkDivider` | `modifier` only | **Bindable** | — |
| `BpkPrice` | `price`, `leadingText`, `previousPrice`, `trailingText`, `align`, `size`, `style`, `onPriceClicked: (() -> Unit)?` | **Bindable as declared** | None. Every parameter is a string or an enumerated token and its one lambda is a discrete event. |
| `BpkChip` | `text`, `selected`, `onSelectedChange: ((Boolean) -> Unit)?`, `style`, `icon: BpkIcon?` | **Bindable** | None. The event carries a serializable argument; `BpkIcon` is a named token. |
| `BpkBadge` | `text`, `type`, `icon: BpkIcon?` | **Bindable** (first overload) | The second overload takes `Painter` and is asset-gated. Bind the first, exclude the second. |
| `BpkSectionHeader` | `title`, … | **Bindable** | — |
| `BpkButton` | `text`, `size`, `type`, `enabled`, `loading`, **`interactionSource: MutableInteractionSource`**, `onClick` | Fails | Live-state holder. Thin wrapper without it. |
| `BpkCard` | `onClick`, `corner`, `padding`, `cardStyle`, `elevation`, **`interactionSource`**, `content: @Composable ColumnScope.() -> Unit` | Fails | Same. The content slot is fine; the scope receiver is a host-side concern. |
| `BpkText` | `text`, …, **`onTextLayout: (TextLayoutResult) -> Unit`**, **`style: TextStyle`** | Fails | Two reasons at once — see below. Wrapper takes a token-named style and drops `onTextLayout`. |
| `BpkStarRating` | `rating: Float`, **`contentDescription: ContentDescriptionScope.(Float, Int) -> String`**, `rounding`, `size` | Fails | Wrapper takes the finished string. |
| `BpkIcon` | **`icon: BpkIcon`** resolved through `painterResource`, `tint: Color` | Fails | Asset-gated. Needs the icon dictionary from the resources subsystem; deferred. |
| `BpkCarousel` | **`state: BpkCarouselState`**, **`content: @Composable BoxScope.(Int) -> Unit`** | **Fails structurally** | Not a wrapper job. See below. |

**Five of eleven bind as declared.** That is a better hit rate than the specification's tone
implies, and it is worth saying so: the components that pass are not trivial ones. `BpkPrice`
carries four strings, three enumerated tokens and a click, and needed nothing.

**The three failure classes the roadmap predicted all appeared**, exactly as written: a component
taking `Painter` (`BpkBadge`'s second overload), components taking `interactionSource`
(`BpkButton`, `BpkCard`), and a component taking a styles object (`BpkText`'s `TextStyle`).

**Two failure classes the roadmap did not name also appeared**, and both are worth adding to
[Layer 5](../../specs/layer-5-host.md)'s rule:

1. **A lambda the host invokes to obtain a value.** `BpkStarRating.contentDescription` is
   `(Float, Int) -> String`: the host calls it during composition and uses the result. It is
   neither a content slot nor a discrete event, and it cannot cross, because the guest would have
   to answer synchronously from inside the interpreter while the host is mid-frame — which the
   Layer 4 invariant forbids outright. `BpkText.onTextLayout` is the same shape in the other
   direction: it fires per layout pass, so binding it would tick the boundary per frame.
2. **An indexed content lambda.** `BpkCarousel.content` is `BoxScope.(Int) -> Unit` — "give me
   the content for page *n*, on demand". Together with its `BpkCarouselState` this is the lazy-
   layout subsystem in miniature, and no wrapper fixes it: a wrapper that eagerly materialised
   every page would discard the laziness that is the component's entire purpose.

The carousel is the load-bearing finding. It confirms, on a real design system rather than from
first principles, that **lazy layouts cannot be absorbed by registering a component** the way
[ADR-006](ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md) hoped images and
animation could be. The design-system-first path buys past `Painter`, `interactionSource` and
style objects; it does not buy past laziness.

## 4. Unstated Assumptions

- **Assumes ranking by product need approximates ranking by call-site count.** It probably does
  for the head of the distribution and certainly does not for the tail, which is where the
  expensive surprises live.
- **Assumes Backpack's signatures are representative of design systems generally.** One system,
  read once. A second audit against a differently-shaped system would be cheap and would test
  whether the two new failure classes are common or particular.
- **Assumes `BpkIcon` is a closed set of named tokens.** It resolves through `painterResource`,
  so binding it needs the host to hold the drawables — the icon dictionary the resources
  subsystem describes, which does not exist.
- **Assumes the registered wrappers stay faithful.** Dogwood's components are modelled on
  Backpack's, not generated from them, so nothing prevents drift. Generating them is what
  Phase 3 is for; until then a divergence is a review problem.

## 5. Updated Documents

- [specs/layer-5-host.md](../../specs/layer-5-host.md) — "Bindability: The Real Rule" gains the
  two failure classes this audit found
- [roadmap.md](../../roadmap.md) — Phase 1 step 1's audit is discharged, with its method deviation
- [engine/README.md](../../engine/README.md) — the registered segment and its provenance
- [`engine/dogwood-host/.../Bindings.kt`](../../engine/dogwood-host/src/commonMain/kotlin/dev/dogwood/host/Bindings.kt),
  [`Tokens.kt`](../../engine/dogwood-host/src/commonMain/kotlin/dev/dogwood/host/Tokens.kt) — the
  registered segment as built

# ADR-052: The skew containment drill runs on every client

**Date:** 2026-09-06
**Status:** Accepted

## 1. Context & Problem Statement

Section 6 of the [technical specification](../../high-level-tech-spec-final.md) makes three claims
about a client meeting a payload built against a **newer** dictionary than its own:

- **A2** — an unknown widget tag becomes a placeholder, and the indices after it stay correct.
- **A3** — an unknown property on a widget with no affordance is ignored, and the widget renders.
- **A4** — an unknown property on a widget that **owns** an affordance withholds the widget.

All three were unit-tested in shared code and all three were run end to end on **Android only**
([`tools/skew-drill`](../../tools/skew-drill/)). `plans/conformance.md` recorded them as "S ✅; C
Android only", and Part 7 named the gap precisely: what was missing was the **two-build procedure**
— a skewed payload served to an already-installed binary — on iOS and the web.

That gap is not cosmetic. The rules live in generated bindings and in `HostTree`, which every client
shares, but *reaching* them depends on each host's own composition: what it provides, what it wires,
what it registers. A shared rule with a per-client entry point is exactly the shape where a test that
passes everywhere can coexist with behaviour that works in one place. This decision is about whether
to find that out by running it.

## 2. Decision

**Port the drill to iOS and the web, keeping the same five steps and the same claim identifiers, and
read the outcome off each platform's own screen rather than off the host's log.**

- [`tools/skew-drill/run-ios.sh`](../../tools/skew-drill/run-ios.sh) installs the application at
  dictionary version N, patches the surface to N+1, rebuilds **only** the Zipline payload, serves it
  to the still-installed binary, and launches with `--dogwood-skew`.
  [`SkewDrill.kt`](../../engine/samples/slice-ios/src/iosMain/kotlin/dev/dogwood/slice/ios/SkewDrill.kt)
  walks the accessibility tree and asserts in-process, because `xcrun simctl` cannot dump a view
  hierarchy the way `uiautomator` can.
- [`tools/skew-drill/run-web.sh`](../../tools/skew-drill/run-web.sh) builds the whole distribution
  at N, rebuilds **only** `guest-kotlin.js` at N+1, copies it over the one in the distribution, and
  drives a real headless Chrome against the directory.
  [`check_web.py`](../../tools/skew-drill/check_web.py) reads the accessibility tree through
  `Accessibility.getFullAXTree` and each node's box model, which is the web's only positioned view
  of a canvas-drawn screen.

Both emit the `CONF` grammar, so `tools/conformance/aggregate.py` reads them like any other run, and
both restore the surface on every exit path — a permanently skewed surface is a permanently failing
lock.

**The web drill runs a fourth claim the mobile clients cannot make.** This client checks the
payload's declared dictionary versions before it creates the Worker, so the drill runs both halves:
undeclared skew must be contained at render time, and *declared* skew must be refused outright
(**B3**, now against a genuinely newer payload rather than a hand-written manifest).

## 3. Rationale & Research

**The two-build shape is the only honest way to produce the condition.** Host and guest are
generated from one surface, so a normal build cannot be skewed — both halves always agree. What
differs is *delivery*: the bindings are compiled into the installed binary and the payload is fetched
at run time. On the web that is at its most literal — `app.js` and `guest-kotlin.js` sit in one
directory, and the drill rebuilds one file and leaves the other alone. `run-web.sh` hashes `app.js`
before and after and **fails if it changed**, because a host rebuilt alongside the guest is a drill
that quietly tests nothing.

**Reading the screen rather than the log** is the same rule [AGENTS.md §1.5](../../AGENTS.md) states
and that this repository has twice been caught by: prefer the observable consequence to the property
that ought to imply it. A host line saying `withheld` is the host agreeing with itself. Both new
drills assert on the accessibility tree, which is what a screen reader consumes and — on both
platforms — the only positioned account of a Skia-drawn screen.

**It found two defects on its first run, one per platform, neither visible to any test.**

**The web host provided none of the composition locals its bindings read.**
`DogwoodWebExperience.Content()` rendered `RenderChildren` directly rather than through
`DogwoodTree`, and every local involved has a *default* — so the page rendered perfectly and was
wrong three ways. The drill saw one of them: `A4` passed (the control was correctly withheld) while
`A4-reported` failed, because `LocalSkewReport` defaulted to a throwaway `SkewReport()` that nothing
reads. Everything a binding recorded — withheld controls, unresolved colour tokens, unresolved text
styles, unknown icons, clamped values, rejected focus requests — went into that orphan. Only the
unknown *widget tag* showed, because `HostTree.apply` writes that one straight onto the tree.
The other two, fixed with it: the expression cache was keyed to nothing rather than to a guest, and
live-state mirrors had no `LocalGuestGeneration` to key on — which is
[ADR-044](ADR-044-scroll-position-is-a-declared-quantum.md)'s "a report is edge-triggered and a
replacement guest needs a level", unfixed on this client.

**iOS never registered the product design system.** The first iOS run reported
`widgets=[16777233, 33554435, 33554433, 33554434]`. The first is the drill's own unknown component;
the other three are segment 2, tags 1 to 3 — Acme's components, rendering as inert placeholders on
the one client of four that neither depended on `samples/product-design-system` nor called
`DogwoodRegistry.register`. That module's build file already carried the rule, written when the web
hit the identical problem: *"a product's design system has to target every platform its hosts run
on."* The rule was stated and not applied to the platform added after it.

**Both are host-integration defects in code no shared test covers**, found in the first minute of
running the thing rather than reasoning about it.

## 4. Unstated Assumptions

- **Compose Multiplatform publishes an accessibility tree with usable geometry on both platforms.**
  Verified rather than assumed: iOS reports `SKEW-BEFORE` at y=162 and `SKEW-AFTER` at y=202, the web
  at 83 and 123 — a 40-point gap on each, which is the placeholder occupying its slot.
- **An assistive technology must be active for that tree to exist.** Compose builds it only while one
  is running. `run-ios.sh` turns VoiceOver on and `SkewDrill.kt` **refuses rather than reports** if it
  is off; the web drill passes `--force-renderer-accessibility`. A forgotten switch cannot read as a
  passing run.
- **The skew report must be polled, not observed.** `SkewReport` is plain sets written during
  composition, so nothing invalidates when an entry lands. The web page now republishes it on a
  timer; reading it once reported the unknown tag and missed the withheld one.
- **Neither drill runs in continuous integration.** The web one could — it needs no device — and does
  not today, because it wants a real Chrome and a two-stage Gradle build, and the workflow grades
  tier S only. That is a gap, and it is named rather than implied.

## 5. Updated Documents

- [`plans/conformance.md`](../../plans/conformance.md) — A2–A4 promoted from "C Android only" to all
  four clients; Part 7's open item closed.
- [`plans/production-readiness.md`](../../plans/production-readiness.md) — item 8 closed.
- [`tools/skew-drill/README.md`](../../tools/skew-drill/README.md) — the three runs and what each
  found.
- [`engine/dogwood-web/src/wasmJsMain/kotlin/dev/dogwood/web/DogwoodWebExperience.kt`](../../engine/dogwood-web/src/wasmJsMain/kotlin/dev/dogwood/web/DogwoodWebExperience.kt)
- [`engine/samples/product-design-system/build.gradle.kts`](../../engine/samples/product-design-system/build.gradle.kts)
- [`engine/samples/slice-ios/`](../../engine/samples/slice-ios/) — the dependency, the registration,
  and `SkewDrill.kt`.
- [`engine/samples/web-slice/src/wasmJsMain/kotlin/dev/dogwood/slice/web/Main.kt`](../../engine/samples/web-slice/src/wasmJsMain/kotlin/dev/dogwood/slice/web/Main.kt)
  — the page polls and publishes its skew report.

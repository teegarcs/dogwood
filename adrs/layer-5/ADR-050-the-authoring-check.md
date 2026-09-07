# ADR-050: The Authoring Check Rejects What Compiles

**Date:** 2026-09-06
**Status:** Accepted

## 1. Context & Problem Statement

`specs/layer-1-authoring.md` has carried two **hard rejection obligations** since the adversarial
review that added them, and stated why neither could be left to good intentions:

> The failure mode of *not* rejecting them is the dangerous one: the guest has a working frame
> clock, so these APIs would compile and run — silently ticking the boundary every frame, which is
> exactly what the Layer 4 invariant forbids.

Nothing enforced it. `animateFloatAsState` in guest code compiles, runs, animates, and crosses the
boundary sixty times a second for as long as it is on screen. **The screen looks correct.** The bill
arrives months later as a battery complaint about a screen nobody changed, and by then the payload
has been published to every device.

That is the whole argument for a check rather than a paragraph. Every other rule in this
architecture degrades visibly — an unknown icon becomes a fallback glyph, an unreadable affordance
withholds a widget. This one degrades into a screen that works.

## 2. Decision

**A build-time check, applied to guest modules, that rejects two families of API and names the
replacement for each.**

`dev.dogwood.guest` is a second plugin in the generator's artifact. It is separate from
`dev.dogwood.codegen` because the two are applied to different modules by different people: a
product's design-system module *generates* bindings, and a product's *guest* module is checked. A
module doing both would be one whose guest code can see its host code, which is the confusion the
architecture exists to prevent. It joins `check`, so it runs where a person expects a rule to be
enforced rather than when somebody remembers to ask.

**Animation state** — `animateFloatAsState`, `animateDpAsState`, `animateColorAsState`,
`Animatable`, `updateTransition`, `rememberInfiniteTransition`, `withFrameNanos`, `withFrameMillis`
and relatives. **The rejection is permanent**, and
[ADR-020](ADR-020-animation.md) is what makes that acceptable rather than a limitation: declare a
target and the host runs the frames, so a whole animation costs one crossing whatever its duration.

**Resource loaders** — `painterResource`, `stringResource`, `imageResource`, `vectorResource`. There
is no file system in the sandbox, no stable host resource identifiers, and the payload ships months
apart from the host that renders it ([ADR-017](ADR-017-resources-and-assets.md)).

**Every rejection carries a replacement**, and that is asserted by a test rather than left to
whoever writes the next entry. A rejection with no alternative is a rejection somebody works around.

**A named list, not a package pattern.** Rejecting everything from `androidx.compose.animation`
would also reject a guest that merely named one of its enum values, and a check that cries wolf is
one people learn to suppress.

## 3. Rationale & Research

**Half the tests are about false positives, and that is the right proportion.** A check that rejects
a comment, a string, or a similarly-named helper is a check that gets suppressed within a week, and
a suppressed check enforces nothing at all. The concrete case is not hypothetical: the sample's
About screen contains the sentence "Compose's own `animateFloatAsState` does..." in a comment about
this very rule, so the first thing a naive scanner does is reject the documentation of the rule it
is enforcing.

Comments and string literals are therefore blanked before scanning, **character for character**, so
a violation's reported line is the line it is on. A scanner that collapsed the text would report the
right problem at the wrong place, which sends somebody to read a line that is fine.

**The pattern accepts a brace, and that was a real gap.** The first version matched `name(` and
`name<`. `withFrameNanos { … }` is a trailing-lambda call and is how that API is actually written —
so the check would have missed the most important entry on its own list, in its most common form.
The test that caught it wrote the call the way a person would.

**It also rejects a function reference.** `::rememberInfiniteTransition` is the same per-frame state
arriving by a longer road.

### Verified against the real guests, which is where a false positive would show

`slice-screens`, `slice-guest` and `web-guest` — seven files including the About screen with its
comment — pass. Adding one real call to a real screen fails the build with the file, the line, the
reason and the replacement:

```
src/jsMain/kotlin/dev/dogwood/slice/FeedScreen.kt:60  animateFloatAsState — per-frame state in
    the guest: it ticks the boundary every frame it animates
    instead: Modifier.alpha(animate(target, spec)) — declare a target, the host runs the frames
```

Twelve tests, and capability group **I** in the catalogue.

## 4. Unstated Assumptions

- **Best-effort by construction, and Layer 1 said so first.** A call assembled at runtime, aliased
  behind another name, or reached through reflection is invisible to a source scan. It catches a
  directly-named forbidden API — which is *the* case, because nobody reaches for
  `rememberInfiniteTransition` by accident through an alias — and it must never be described as a
  guarantee.
- **It does not check dependencies.** A guest that puts `androidx.compose.animation` on its
  classpath and calls nothing from it passes, correctly; one that calls something reachable only
  from there and not on this list passes too, incorrectly. A classpath check would catch the second
  and is not built.
- **The list is Layer 1's two obligations and nothing more.** The other things a guest cannot
  usefully do — computing from `MaterialTheme.colorScheme`, naming a `Painter` — are compile errors
  already, because the stubs a guest can call do not offer them.
- **It runs over `src`, not over a compilation.** Generated stubs are sources too, and are exactly
  what a guest is *supposed* to call; a check aimed at compilations would spend its time reading
  code nobody wrote.
- **The engine's own guest modules invoke it as a command**, because the plugin lives in the build
  that defines it. A product applies the plugin and never sees that shape.

## 5. Updated Documents

- [`specs/layer-1-authoring.md`](../../specs/layer-1-authoring.md) — Milestone 5, and the
  obligations marked delivered.
- [`developer-experience.md`](../../developer-experience.md) — §4c, where an author meets it.
- [`plans/conformance.md`](../../plans/conformance.md) — capability group **I**.
- [`plans/production-readiness.md`](../../plans/production-readiness.md) — §4.2 and the order.
- [`adrs/README.md`](../README.md) — index entry.

# ADR-050: The Authoring Check Rejects What Compiles

**Date:** 2026-09-06
**Status:** Accepted
**Amended:** 2026-09-16 — the controlled text fields joined the list, and §4's "it does not check
dependencies" stopped being true. Both changes are marked below.

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

`io.github.teegarcs.dogwood.guest` is a second plugin in the generator's artifact. It is separate from
`io.github.teegarcs.dogwood.codegen` because the two are applied to different modules by different people: a
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

### Amended 2026-09-16: the controlled text fields

**Controlled text fields** — `OutlinedTextField`, `BasicTextField`, `SecureTextField`,
`BasicSecureTextField`, `OutlinedSecureTextField`, `CoreTextField`, and the search bars that contain
one. `specs/layer-5-host.md` excludes them by name and generator v2's classifier excludes the same
set, because a controlled text field asks the guest what the text should be after every keystroke;
[ADR-019](ADR-019-text-input.md) replaced them with a versioned `TextInput`.

**These are on the list for a different reason from everything above them.** The animation APIs are
rejected because they compile and run. These do not compile at all — nothing a guest's classpath
declares is called `OutlinedTextField` — so the check is not stopping a build that would otherwise
succeed. It is replacing "unresolved reference", which names no replacement and reads like a missing
dependency, with the name of the component that does exist. That makes this half of the list a
*documentation* mechanism that happens to live in a build, and it is worth being honest that the
failure message says so.

**`TextField` is deliberately not on the list, and that was a correction to the plan.** The plan for
this work assumed a guest author reaching for `TextField` gets "unresolved reference" today. They do
not: `dev.dogwood.compose.TextField(state = …)` exists, is the supported wrapper over `TextInput`,
and is what `ExploreScreen.kt` and `AboutScreen.kt` call. A blanket rejection of the name was tried
and watched — it rejected the engine's own two guest screens, at `ExploreScreen.kt:332` and
`AboutScreen.kt:134`, for using the API the engine recommends. So the rejection is a name **plus an
argument**: a `TextField(...)` call whose argument list carries `onValueChange`, which Compose's
overloads all have and the wrapper does not. Balancing parentheses rather than reading one line is
required for that, because a real call spans five lines and the line naming `TextField` is never the
line naming `onValueChange`.

### Amended 2026-09-16: the classpath half

**`dogwoodGuestClasspathCheck`, a second task in the same plugin, reads the module's resolved
runtime configurations and refuses the artifacts these APIs live in** —
`androidx.compose.animation:animation-core`, its `animation` sibling, the Compose Multiplatform
coordinates for both, and `org.jetbrains.compose.components:components-resources`. It reads the
**resolved** graph rather than the declarations in the build file, because the declarations are the
set that was never the problem.

**The two halves do not subsume each other, in either direction.** `withFrameNanos` lives in
`androidx.compose.runtime:runtime`, which every guest must have — banning the artifact would ban the
guest — so the frame loop stays on the source list, where it can be rejected as a *call*. And
`animation-core` carries far more than the names on the source list; a list that tried to enumerate
them would be the package pattern this ADR refused, one name at a time.

**Group and module, never a group prefix.** `androidx.compose.animation:animation-graphics` draws
animated vector drawables and has no frame clock of its own; a group-wide ban would reject it for
the company it keeps.

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

Twelve tests, and capability group **I** in the catalogue. Thirty-five after the 2026-09-16
amendment: twenty over the source list, ten over the classpath decision and its report, and five
that run the fixture build.

### Watched to fail, 2026-09-16

**The text-field half.** The real check, run as a command over a copy of `samples/slice-screens`
with one file added that calls `OutlinedTextField`, the controlled `TextField` overload and
`SearchBar`, reports all three with their lines and their replacements. The same check over the
three real guest modules — `slice-screens`, `slice-guest`, `web-guest` — reports nothing, which is
the assertion that matters more: those screens call `TextField(state = …)` twice and contain the
comment about `animateFloatAsState` that this check must not trip on.

**The classpath half.** A fixture build lives in
`engine/dogwood-codegen/src/test/resources/guest-classpath-fixture`: a module with no source, a
repository of two Project Object Model (POM) files, and `-PfixtureDependency=` to add one
dependency. With `com.example:helper:1.0` — whose POM depends on `animation-core` — the build fails
with the route:

```
runtimeClasspath: androidx.compose.animation:animation-core:1.9.0 — the frame-clock animation
    APIs: a guest animating one of these crosses the boundary every frame
      reached by: com.example:helper:1.0 -> androidx.compose.animation:animation-core:1.9.0
      instead: declare a target and let the host run the frames; see ADR-020
```

With the graph walk neutered so the check inspected an empty resolution — the shape a check that
inspects nothing would have — the three failing-case tests reported `UnexpectedBuildSuccess` and
the two negative ones still passed. That is the gate watched failing without the fix, which is the
only thing that makes a green one mean anything. A hand-built graph was deliberately not used for
this: whether a `compile`-scoped POM dependency lands on `runtimeClasspath` is a fact about Gradle,
and asserting the author's belief about it would be a second thing able to be wrong.

## 4. Unstated Assumptions

- **Best-effort by construction, and Layer 1 said so first.** A call assembled at runtime, aliased
  behind another name, or reached through reflection is invisible to a source scan. It catches a
  directly-named forbidden API — which is *the* case, because nobody reaches for
  `rememberInfiniteTransition` by accident through an alias — and it must never be described as a
  guarantee.
- **~~It does not check dependencies.~~ Resolved 2026-09-16.** The original text read: "A guest that
  puts `androidx.compose.animation` on its classpath and calls nothing from it passes, correctly;
  one that calls something reachable only from there and not on this list passes too, incorrectly. A
  classpath check would catch the second and is not built." It is built, and it changed the first
  half of that sentence too: a guest that resolves `animation-core` and calls nothing from it now
  **fails**, on purpose. Calling nothing from it today is not a property anybody is maintaining, and
  the artifact is one import away from a hundred names the source list does not have.
- **The classpath check is only as good as what is resolved.** A dependency added at runtime, a jar
  on a flat file-dependency classpath, or a module with no coordinate contributes no group and name
  to match against. It reads runtime classpath configurations and skips test ones deliberately: a
  test source set is not shipped in a payload.
- **It fails when it finds no classpath at all.** A check that inspected nothing and said nothing
  would read as "no forbidden artifacts", which is a different claim.
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

Added by the 2026-09-16 amendment:

- [`specs/layer-1-authoring.md`](../../specs/layer-1-authoring.md) — the third rejected family, and
  the classpath half, in §3.
- [`docs/authoring.md`](../../docs/authoring.md) — §2's table of what the build refuses.
- [`docs/getting-started.md`](../../docs/getting-started.md) — §3, the two tasks the plugin adds.

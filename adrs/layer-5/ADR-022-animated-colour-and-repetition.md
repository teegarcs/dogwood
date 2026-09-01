# ADR-022: Animated Colour, and Motion That Repeats

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-020](ADR-020-animation.md) established the shape: the guest declares a target, the host runs
the frames, and a whole animation costs one crossing. It left two named gaps.

**No animated colour.** Only numbers animated, so a cross-fade between two design-system tokens —
a chip's background as it is selected, a card's tint as it becomes active — was not expressible.
`background(animate(1f))` did not compile, which was the correct outcome of the type system and
the wrong outcome for a product.

**No repeating or infinite animation.** No pulsing skeletons, no spinners from guest code. A
registered component owning its own spinner covered the need, which is exactly the workaround
[ADR-006](ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md) predicts and
exactly the one a guest-authored loading state cannot use.

## 2. Decision

### 2.1 An animated colour is still a `Color`

`Color.animate(spec)` returns a `Color` whose recipe carries the animation, wrapping the colour it
targets: `[14, <colour recipe>, <spec>]`.

**No new type, and that is the whole design.** Anything that accepts a `Color` accepts an animated
one — the `background` modifier, `Icon`'s tint, any component parameter — with no second signature
anywhere. That is a dividend of [ADR-021](ADR-021-host-resolved-values.md): typing `tint` as a
colour rather than as a token name made this free the day it landed.

**The target may itself be a token, so a palette flip mid-flight retargets** rather than jumping.
The host resolves the inner recipe against the palette first and hands the result to
`animateColorAsState`, so a theme change is a new target and the animation continues from wherever
it had reached. Verified on a controlled clock.

**No completion callback**, deliberately. A completion needs an event tag; a modifier element has
one, from its position in the chain, and a component property does not. Rather than support it in
one place and not the other, it is supported in neither until something needs it. Colour animation
is decorative; the number-valued form still reports.

### 2.2 An oscillation is a range, not a spec variant

```kotlin
.alpha(oscillate(0.35f, 1f))                                                   // a skeleton
.rotate(oscillate(0f, 360f, Animations.tween(1000, "linear"), reverse = false)) // a spinner
```

Wire: `[15, from, to, iterations, reverse, spec, notify]`. `iterations = 0` means forever.

**This is a separate recipe rather than a repeating spec on the existing one, because a spike
proved the obvious design silently does nothing.** Handing `repeatable(…)` to `animateFloatAsState`
type-checks, runs, and produces no motion and no completion: that API moves only when its *target*
changes, and a pulse's target is the value it already holds. Planning on paper would have shipped a
no-op. A repeat needs an explicit range, so it gets one.

Two host primitives, split on finiteness:

- **Infinite** — `rememberInfiniteTransition` + `animateFloat(from, to, infiniteRepeatable(…))`.
  Stops when it leaves the composition. Never reports a completion, and **the guest API refuses to
  let one be requested**: a callback that can never fire is worse than a compile error, because the
  guest would be waiting on something that cannot happen.
- **Finite** — an `Animatable` driven from an effect keyed on the recipe, so a changed recipe
  cancels the run. That is the interruption rule, inherited rather than invented, as in ADR-020.

**Stopping one is an ordinary composition change**: replace it with a plain number or a declared
target. No stop message exists, and none is needed.

### 2.3 A repeating spring degrades to a tween

Repetition needs a duration; a spring does not have one, it settles when the physics say so. A
repeating spring is therefore not expressible and falls back to a tween rather than failing. Stated
here because the degradation is silent and somebody will eventually wonder why their bouncy pulse
is not bouncy.

## 3. Rationale & Research

**Why the animated colour wraps rather than replaces.** `[14, [4, "primary"], spec]` keeps the
target as an ordinary colour recipe, so every existing resolution path — tokens, literals, unknown-
token skew — works inside it unchanged, and the animated form inherits all of it. A flat
`[14, "primary", spec]` would have duplicated the token vocabulary in a second place.

**Why oscillation is on the same seven modifier arguments** as ADR-020 rather than a new channel:
it is the same kind of value, differing only in how the host gets there.

### Verified

Fourteen tests added, and the interesting ones are the ones a screenshot cannot make:

Host, on a controlled clock — an animated colour **starts at its target** rather than fading in
from nothing on first composition; a theme flip mid-flight leaves it neither at the old colour nor
at the new one, then lands on the new one; a plain colour recipe still resolves without animating;
an infinite oscillation traverses its whole range and keeps going; a non-reversing one snaps back
rather than travelling back; a finite one settles at its end and reports exactly once; and one that
asks for nothing reports nothing.

Guest — an animated colour is still a colour and wraps the one it targets; it reaches a *generated*
component's parameter; an oscillation crosses its range and not its frames (sixty frames driven,
zero batches); an infinite oscillation with a callback is refused with a message that says why; and
each animated form has a distinct factory, so a client implementing only some cannot mistake one
for another.

On the emulator: the diagnostics screen's skeleton bar pulses, and four consecutive screenshots
hash differently while nothing crosses the boundary — the frames are entirely host work.

## 4. Unstated Assumptions

- **Only `background` animates colour among modifiers.** `Icon`'s tint animates because it is a
  typed parameter; a modifier like a hypothetical `tint` would need its own branch.
- **An infinite oscillation keeps the host's frame loop awake** for as long as it is on screen.
  The same cost as any native spinner, and no boundary traffic at all — but worth knowing before
  shipping a permanently pulsing badge, since nothing warns.
- **`oscillate` restarts from `from` when its recipe changes**, including when only the spec
  changes. For a decorative loop that is right; for one whose range is being tuned live it is a
  visible jump.
- **The colour memo is not consulted for animated colours.** Each frame resolves the target from
  the (memoised) inner recipe, then animates; the animated value itself is per-frame and not
  cached, which is correct and worth saying since everything else here is memoised.
- **Still no enter/exit or shared-element transitions.** Unchanged from ADR-020; `Presence` is the
  next record.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — the animation subsystem's entry.
- [`roadmap.md`](../../roadmap.md) — Phase 4's animation row.
- [`plans/text-and-animation.md`](../../plans/text-and-animation.md) — A1 and A3 marked done.

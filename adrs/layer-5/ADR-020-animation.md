# ADR-020: Animation — Declare the Target, Let the Host Run the Frames

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

`roadmap.md` calls animation "the largest addition from re-review" and lists what it needs:
"declarative targets, springs/easings, interruption semantics, completion events, time-varying
`Modifier` values". It also records the cost of not having it: "Until this ships, Layer 1 rejects
the `animate*` Application Programming Interfaces — the product promise **animation without a
release** is *not* true on day one."

The prohibition is not squeamishness. Layer 4's standing invariant is **no per-frame state in the
guest**, and the entire `animateFloatAsState` / `updateTransition` / `Animatable` /
`rememberInfiniteTransition` surface is per-frame state by construction. Worse, those APIs *would
compile and run* inside the guest — it has a working frame clock — while silently ticking the
boundary sixty times a second for the duration of every fade on the screen.

So the question this record answers is not "how do we bridge Compose's animation APIs". It is
"what does a guest get **instead**".

## 2. Decision

**An animated value is a declared target, evaluated by the host.**

A modifier argument carries either a number or a recipe: `[factory, target, spec, notify]`. The
host resolves the recipe with `animateFloatAsState`. Nothing else is added to the protocol — no
animation channel, no start/stop messages, no per-frame anything.

Seven modifier arguments accept a target: `alpha`, `rotate`, `scale`, `width`, `height`, `size`,
`padding`. That is the "time-varying `Modifier` values" the roadmap named, and it is the form
animation actually takes in Compose.

**Springs and easings are named**, like every other token in this project: `tween(durationMs,
easing, delayMs)`, `spring(stiffness, damping)`, `snap`. Named rather than numeric because
Compose's stiffness constants are physical units a guest has no way to check — and because an
unknown name can then degrade rather than produce nonsense.

**Interruption semantics are inherited, not invented.** `animateFloatAsState`'s defined behaviour
when its target changes mid-flight is to *retarget from the current value*. Because the guest
declares a target rather than starting an animation, that behaviour is what the protocol means by
construction. A "start an animation" message would have had to define retargeting itself, and would
have got it wrong — this is the single strongest argument for the shape.

**Completion events ride the element's position in the chain.** Both sides walk the same ordered
modifier chain, so the index is an identifier they already agree on: no allocation, no registry,
and nothing extra on the wire. A completion is sent only when the guest asked for one, and **a
retarget is not a completion** — Compose's finished listener does not fire for an interrupted
animation, and reporting one would make "finished" mean two different things. A guest that treated
a retarget as an arrival would advance a wizard, dismiss a dialogue, or fire analytics for
something that never finished.

**Layer 1's rejection of the `animate*` APIs becomes permanent rather than temporary.** That is the
right outcome and worth stating plainly: those APIs are not a thing this architecture will grow
into. The replacement is `alpha(animate(target, spec))`, and it does what the product promise
requires.

## 3. Rationale & Research

**What it costs.** One property set when the target changes. A fade is one crossing whatever its
duration; a sixty-frame animation is sixty frames of host work the guest never sees. The guest test
drives sixty frames after a target change and asserts the batch count did not move.

**Why completions are rebound on every recomposition rather than when the chain changes.** A
completion callback is a lambda, so it is a fresh instance every recomposition. If it were part of
`DogwoodModifier`'s equality, every chain would look changed on every frame and re-cross — exactly
the traffic this design exists to avoid. So it is excluded from equality, which means the chain
does *not* change when the callback's captured state does, which means binding the callback only on
change would leave it firing into the composition it was born in. That is
[ADR-016](ADR-016-leak-detection.md)'s defect in a new place, and it is why the guest stubs bind
completions through `reconcile` — which runs on every update — rather than `set`.

### The animation subsystem exposed a bug that had been there since the generator shipped

The first host-side test asserted one completion event and got two, reliably.

`RenderNode` calls the generated `bindDogwoodDesignSystem` first and falls through when it returns
false. But the generated dispatch computed `node.composeModifier(scope, events)` **before** looking
at the tag. So every node from another segment — every `Text`, `Column`, `Row`, `Box`, `Spacer` —
built its modifier chain twice: once inside the dispatch that was about to decline it, once in the
caller afterwards.

That had been true since [ADR-011](ADR-011-generator-emits-the-bridge.md) and was invisible, because
building a chain twice is merely wasteful when its arguments are numbers. The moment an argument
could be an animation, the duplicate call became a second `animateFloatAsState` — a second
animation, and a second completion event, for one declared target.

The generator now emits a membership check before the modifier. The fix removes duplicated work on
every layout primitive on every screen as well, which nothing was measuring.

### Verified end to end

Host tests run on a controlled clock, which is the only way to assert that something moved *between*
two crossings: a changed target is in flight halfway through a 300 ms linear tween and has arrived
at the end; the first composition starts **at** its target rather than animating in from zero;
completion fires once on arrival and not at all at 100 ms; an interrupted animation reports
nothing while the one that settles reports once; and a plain number still costs no animation at all.

On the emulator, through the diagnostics screen: one tap on "Expand" animates height with a bouncy
spring and alpha with a tween, and reports **arrived 1 times**. Tapping twice in quick succession —
interrupting mid-flight — reports **arrived 2 times**, not three. The interrupted animation
correctly reported nothing.

## 4. Unstated Assumptions

- **Only numbers animate.** Colour, shape and text-style targets are not animatable yet, so a
  cross-fade between two token colours is not expressible. The recipe form has room for it; the
  host resolution does not exist.
- **Animation is per modifier element, not per composable.** There is no `AnimatedVisibility`, no
  enter/exit transition, and no shared-element transition. A guest animates the *arguments* of what
  it already emits. Those are genuinely different features, and the ones that involve a node
  appearing or disappearing need the applier to hold a node past its removal — which is a design,
  not an addition.
- **No infinite or repeating animations.** Deliberate for now: an infinite animation is a host
  frame loop that nothing will ever stop, and the guest has no way to cancel it beyond changing the
  chain.
- **The completion tag base is 1000**, above any plausible declared event tag. A widget with a
  thousand lambda parameters would collide, and would have other problems.
- **Assumes the modifier chain is short.** Completions are rebound by index on every update, which
  is a handful of map writes per node per recomposition. For a chain of five that is nothing; for a
  chain of five hundred it would not be.
- **The `label` passed to `animateFloatAsState` is derived from the element index**, so animation
  inspector output identifies position rather than intent. Harmless, and worth knowing before
  someone reads a trace.

## 5. Updated Documents

- [`specs/layer-1-authoring.md`](../../specs/layer-1-authoring.md) — the `animate*` rejection
  becomes permanent, with the replacement named.
- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — bespoke subsystem 7 marked delivered.
- [`roadmap.md`](../../roadmap.md) — Phase 4's animation row.
- [`adrs/README.md`](../README.md) — index entry.

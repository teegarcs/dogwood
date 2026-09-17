# ADR-075: A Report Says Who Moved the Control, and Only the Code That Moved It Knows

**Date:** 2026-09-16
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-043](ADR-043-holders-are-declared-on-the-surface.md) gave a live-state holder a shape, a guest
object and a hand-written host mirror, and [ADR-072](ADR-072-the-compose-surface-is-generated-from-the-artifact-it-binds.md)
gave a *library* tier the same mechanism keyed by type name, because nobody can put `@Holder` on
`androidx.compose.material3.DrawerState`. One shape proved it end to end: `TimePickerState`.

This is the rest of the list — the state types the Material 3 coverage report named as the first
reason a component could not be bound. Nine types were on it. Filling them in surfaced three things
that are decisions rather than transcription, and this Architecture Decision Record (ADR) records
those three.

**The first is the `byUser` field.** Five of these holders report a *position the user can change*:
a navigation drawer, a swipe-to-dismiss row, a search bar, and the two sliders. Every one of them
carries a boolean saying whether the change was the user's doing or the guest's own request
landing, because a guest implementing "they closed it, stop offering it" cannot tell those apart
from the position alone and they mean opposite things. The pattern established by the design
system's `SheetMirror` **computes** that field: it collects a `snapshotFlow` of the control's
position and reports `byUser = (position != target)`. That is wrong in both directions, and neither
failure is visible in a build that compiles.

**The second is what the coverage report's reasons are.** A row's `reason` is the *first* parameter
the classifier refused, not the only one. [ADR-074](ADR-074-a-segment-version-has-two-authors.md) §3
records this project reading the first link of that chain as the whole story once already. Two of
the nine types on this list are behind a second blocker, and binding them would have cost a shape,
a guest class, a host mirror and a render test to move the bound count by zero.

**The third is smaller and is a classification question.** `ToggleableState` was on the holder list
because the library classifier's fall-through refuses any type whose name ends in `State`. It is
not a holder.

## 2. Decision

**A landing is reported by the code that caused it; everything else is the user.**

Each of the five position mirrors now has two reporters rather than one comparison:

- The effect that drives the control — the one keyed on the guest's request sequence — reports the
  landing itself, with `byUser = false`. It is the only code that knows a request landed.
- The `snapshotFlow` reports everything the effect did not cause, with `byUser = true`. It drops
  its first emission, because `snapshotFlow` hands a collector the position the control is already
  in and where something *starts* is not something that *moved*. It suppresses an emission equal to
  the position the driving effect recorded, which is that effect's own work arriving.

The driving effect records where it is going **before** it moves the control and re-records where
the control actually ended up **after**. Both halves are load-bearing and both were watched to
matter; §3 has the runs.

**`CarouselState` and `DrawerState`'s sheet overloads are measured, not bound on the strength of
the report's first reason.** `CarouselState` is left unbound with the evidence in the table below.
`DrawerState` *is* bound, but for a different reason than the report suggested, and the entry in
`LibraryHolders.kt` says so.

**`ToggleableState` crosses as a value, not as a holder** — a `SIMPLE_KINDS` entry, a guest
enumeration, a host reader. `TriStateCheckbox(state = …)` takes it the way `Checkbox(checked = …)`
takes its boolean: the caller decides, the control draws, nothing is host-owned and nothing is
reported back. Crossing it as a holder would have bought a report channel for a control with
nothing to report.

## 3. Rationale & Research

### Why computing `byUser` is wrong in both directions, watched twice

Both of these were observed as failing tests before they were reasoned about, which is the order
[`AGENTS.md`](../../AGENTS.md) §1.5 asks for.

**Too late, and the guest hears the user.** `rememberLibrarySearchBarState` first shipped with the
computed form and a first-emission drop. The render test asserted one report and got two:

```
expected:<[1:expanded,false]> but was:<[1:expanded,true, 1:expanded,false]>
```

`SearchBarState.currentValue` is a `derivedStateOf` over an animation's progress, so the bar reads
as expanded partway through expanding — *before* `animateToExpanded()` returns and before anything
records that the request landed. A guest watching that channel is told the user expanded a search
bar the guest itself had just asked to expand. The same shape is latent in the drawer, whose
`open()` also suspends.

**Too early, and the guest hears nothing at all.** The first-emission drop, added to stop exactly
that, silently deleted the *only* report for every holder whose target lands synchronously. A
`LaunchedEffect` declared before the reporting one runs first, so by the time the flow is collected
the slider's thumb is already at the guest's value; the one emission is the landing, and dropping
it left four render tests timing out at ten seconds each:

```
Condition (the thumb never moved to the guest's value) still not satisfied after 10000 ms
Condition (the thumbs never moved to the guest's selection) still not satisfied after 10000 ms
Condition (the drawer never opened) still not satisfied after 10000 ms
Condition (the row never went anywhere) still not satisfied after 10000 ms
```

There is no ordering of a single comparison that is right for both, because the two cases differ in
*when the control's position agrees with the target* — immediately, or several frames later. What
does not differ is which code knows a request landed. So the field is structural: the driving effect
reports `false`, the flow reports `true`.

**A third failure mode the structural form also closes.** A slider with steps snaps: asked for 0.7
with four stops, `SliderState.value` settles on 0.75. A mirror comparing the reported value against
0.7 reports a drag that never happened. Recording `state.value` *after* the assignment — what the
library actually chose — is what makes the comparison exact, and it is only possible in the driving
effect.

### What each type actually unlocked, measured rather than assumed

Bound count before this work: **105 of 260** overall, 90 of 186 in `material3`. After: **111 of
260**, 96 of 186. Every row below is from `tools/generator-v2/coverage.json`, re-measured rather
than taken from the plan.

| Type | Components the report blamed on it | Newly bound | What the report's reason was hiding |
|---|---|---|---|
| `ToggleableState` | 2 | **1** | `TriStateCheckbox~a32797bf` also takes two required `Stroke` parameters, which have nowhere to go |
| `SnackbarHostState` | 1 | **1** | — |
| `SwipeToDismissBoxState` | 1 | **1** | — |
| `SliderState` | 1 | **1** | — |
| `RangeSliderState` | 1 | **1** | — |
| `SearchBarState` | 1 | **1** | — |
| `DrawerState` | 2 | **0** | see below |
| `CarouselState` | 3 | **0** | see below |
| `SubcomposeLayoutState` | 1 | not attempted | a guest cannot subcompose; ADR-043's standing exclusion, and the report still says so |

**`CarouselState` unlocks nothing, and the second blocker was confirmed rather than inferred.** A
throwaway shape was registered for it and the coverage report re-run; all three carousels moved from
`state: live-state holder CarouselState` to:

```
content: slot receives Int the guest cannot read
```

Every carousel's content slot is `@Composable CarouselItemScope.(itemIndex: Int) -> Unit`, and it
has no default, so the component is unbindable whatever happens to its state. The probe was reverted
and no shape was registered. A carousel needs the generator to model an indexed slot — the same gap
that keeps lazy layouts hand-written ([ADR-011](ADR-011-generator-emits-the-bridge.md)) — and that
is a different piece of work, not a holder.

**`DrawerState` unlocks no new component either, and is bound anyway.** The two overloads the report
named — `ModalDrawerSheet~08f0067c` and `DismissibleDrawerSheet~08f0067c` — become *bindable* and are
then excluded by the overload dedupe, with a reason that is exactly right:

```
guest signature identical to ModalDrawerSheet after erasing host-default-only parameters
```

A holder is always optional on the generated stub (ADR-043: absence is the sentinel), so adding one
does not distinguish two overloads to a Kotlin caller — `ModalDrawerSheet { … }` would be ambiguous
between them, which is the defect the dedupe exists to prevent and which made `Slider` uncallable
once already. Binding it was still right, for a reason the bound count cannot show: `drawerState` is
a parameter of `ModalNavigationDrawer` and `DismissibleNavigationDrawer`, **both already bound**,
where it was previously host-default-only — frozen at `rememberDrawerState(DrawerValue.Closed)` and
unreachable from a payload. It is now a live target with a report. `aNavigationDrawerOpensOnTheGuestsTargetAndReportsWhereItLands`
is what says so; changing the target it sends from `open` to `closed` was watched to fail it.

The general shape, and it is ADR-074 §3's lesson pointed at a different table: **a coverage reason is
the head of a chain, and "what this unblocks" is a measurement, not a reading.**

### Why the snackbar and the time picker reuse the design system's guest class

`SnackbarHostState` and `TimePickerState` are one guest class each, shared between the design
system's holder and the library tier's, with only the host mirror new. A payload should have one
idea of "ask for a snackbar and find out what the user did with it" whether the host renders the
product's snackbar or Material 3's. The five other holders here needed new guest classes because
their vocabularies are their own: a drawer has two stops where a sheet has three, and folding them
together would give every drawer a `partial` position that means nothing.

Writing a second guest class with a name the design system already uses is not a neutral mistake —
it shadows the original in the same package and the design system's own stubs stop compiling, which
is how ADR-043 found this out.

### Verified where a holder can be verified

Seven render tests, one per bound holder, in the tier's shared `renderTest` source set — so each
runs on the Java Virtual Machine (JVM), on a real browser through Kotlin/WebAssembly (Wasm), and on
an iOS simulator. Each builds the wire bytes by hand, drives the holder's target across the
boundary and reads the report back. 35 tests green on all three targets.

Two assertions were watched to fail with their subject removed rather than trusted:

| Assertion | Broken how | Result |
|---|---|---|
| the drawer's target is carried, not a constant | test sends `closed` instead of `open` | fails, reporting `closed,false` |
| an unreadable `ToggleableState` name degrades to `Off` | reader maps the unknown name to `On` | fails |

The slider test carries a second question in the same run: its `steps` is **negative**. Material 3's
`Slider(state)` answers that with `require(state.steps >= 0)`, which is an exception inside
composition — the failure [ADR-035](ADR-035-hostile-property-values.md) exists to prevent, arriving
on every client that received the payload at the same moment. It is clamped, the slider renders, and
the value still crosses.

## 4. Unstated Assumptions

- **`byUser` is exact for a guest's own request and approximate for everything else.** The flow
  reports `true` for any movement the driving effect did not cause. A movement caused by the *host
  application* — a drawer closed by the client's own navigation code — is reported as the user's,
  because from this mirror's position there is nothing to tell them apart.
- **The sliders' state object is remembered on the track's shape, not on the value.** `steps` and
  `valueRange` are `val` on Material's `SliderState`, so changing either builds a new state object
  and the thumb returns to the track's start before the next request moves it. A payload that
  changes its step count every frame gets a slider that does not work, which is a payload bug this
  does not try to hide.
- **A snackbar request with an empty message is dropped, silently.** The mirror treats it as "not a
  request" rather than showing an empty bar. Nothing reports it, so a guest that built its message
  from a missing string sees nothing happen and has no record of why.
- **Three of these controls are `@ExperimentalMaterial3Api` upstream.** `SearchBarState`,
  `SwipeToDismissBoxState` and both slider state classes carry the opt-in, so a Compose Multiplatform
  upgrade that changes any of them breaks these mirrors and nothing else — which is the containment
  the mirror pattern is for, and the same statement `SheetMirror` already makes.
- **No catalogue entry and no device grading.** `plans/close-the-backlog.md` Group 3 asks for a
  catalogue witness per holder and a grade on a device; this pass delivered the shapes, the mirrors
  and the render tests. The device half is outstanding.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — bespoke subsystem 4, the library tier's
  shape table and what it now covers.
- [`adrs/README.md`](../README.md) — index entry.

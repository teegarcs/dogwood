# ADR-060: Bounds on a runaway payload

**Date:** 2026-09-07
**Status:** Accepted

## 1. Context & Problem Statement

The containment story was thorough about values (clamps), names (skew), crashes (`ReleaseGuard`)
and per-frame cost (the authoring check bans the APIs by name) — and silent about `while (true)`
and unbounded allocation, either of which a payload can ship over the air this afternoon
([`plans/adoption-audit.md`](../../plans/adoption-audit.md) A5). The authoring check cannot catch a
loop; only a runtime bound can.

## 2. Decision

**Every loaded guest runs under `GuestLimits`, on by default** — the same argument that removed the
release guard's default (ADR-058): protection that must be asked for is protection most hosts do
not have. `GuestLimits.none` is the written way out. Two bounds, both QuickJS's own, reached
through the engine Zipline exposes (`Zipline.quickJs` — established from the artifact, not the
documentation):

- **`memoryLimit`** (default 256 MiB — far above anything a screen needs, far below taking a phone
  down with it) refuses the allocation that would cross it.
- **An `InterruptHandler`** stops any single uninterrupted run of guest execution that exceeds the
  slice budget (default 5 s). The heuristic needs no second thread and no cooperation from call
  sites: `poll()` is invoked periodically *only while JavaScript executes*, so a long gap between
  polls means a new slice began, and a long unbroken run means one slice has not yielded. This is
  a tourniquet, not a frame budget — guest work yields by nature, and anything still running after
  whole seconds is not slow, it is stuck.

Applied in `DogwoodDelivery` on both paths — first load and the code-update stream — **before any
guest code composes**, because a bound applied after the first slice began is a bound the first
slice never had.

## 3. Rationale & Research

**Watched to hold, at two levels.** `GuestLimitsTest` drives a real QuickJS through the same
`applyGuestLimits` with hostile scripts: the infinite loop is interrupted inside its budget (with
elapsed-time bounds, so it fired *for the budget* and not for something else), the engine answers
again afterwards — the tourniquet, not the limb, and also the test of the gap heuristic — and the
allocation hoard hits the ceiling. The control runs ordinary work untouched under the defaults,
because bounds that fire on ordinary work teach a team to remove them.

**The negative control could not fail; it hung.** With the handler returning `false`, the loop test
does not go red — it never returns, and the test process had to be killed. That is the finding in
its purest form: without the bound, the loop is genuinely unbounded, and the only observer is
whoever notices the hang.

**End to end, on the production pipeline:** a payload with `while (true)` in an effect, size-
optimized, signed, served, loaded by the desktop host. The interrupt fired on schedule:

```
Exception in thread "zipline" app.cash.zipline.QuickJsException: interrupted
    at JavaScript.t1c(dev/dogwood/slice/ExploreScreen.kt)
```

— source-mapped to the payload's own file, and the host process survived.

## 4. Unstated Assumptions

- **An interrupt inside Zipline's own resumption bypasses `onGuestException`.** The drill's loop
  was resumed by Zipline's internal `CoroutineEventLoop.DelayedJob` — a call site Dogwood does not
  own — so the throw reached the zipline *thread's* uncaught-exception handler, stack attributed
  but outside the routed channel of ADR-059. Routing it means the engine owning the dispatcher
  thread's handler, which is five hosts' worth of API this decision does not take on. A host
  wanting it today sets an `UncaughtExceptionHandler` on its zipline thread.
- **The budget interrupts work, not sleep.** `delay()` does not execute JavaScript, so a guest
  waiting is never polled and never interrupted; only computation counts against the slice.
- **Memory-limit behaviour is exercised at the engine level**, not end-to-end; the interrupt got
  the full-pipeline drill because it is the failure that hangs rather than throws.
- **The web profile has neither bound.** A Worker has no `memoryLimit` knob and no interrupt; the
  browser's own process isolation is the containment there, as with its network policy.

## 5. Updated Documents

- [`plans/adoption-audit.md`](../../plans/adoption-audit.md) — A5 closed, asymmetries noted.
- [`engine/dogwood-host/.../GuestLimits.kt`](../../engine/dogwood-host/src/ziplineMain/kotlin/dev/dogwood/host/GuestLimits.kt) — new.
- [`engine/dogwood-host/.../Delivery.kt`](../../engine/dogwood-host/src/ziplineMain/kotlin/dev/dogwood/host/Delivery.kt) — both load paths apply it.
- [`engine/dogwood-host/src/jvmTest/.../GuestLimitsTest.kt`](../../engine/dogwood-host/src/jvmTest/kotlin/dev/dogwood/host/GuestLimitsTest.kt) — the watch.
- [`docs/operating.md`](../../docs/operating.md) — the bounds join the "what happens without you" list.

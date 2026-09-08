# ADR-059: A guest crash a host can read

**Date:** 2026-09-07
**Status:** Accepted

## 1. Context & Problem Statement

The adoption audit ([`plans/adoption-audit.md`](../../plans/adoption-audit.md) A4) held that a
production crash in a payload could not be read: minified JavaScript, no source maps anywhere a
crash reporter could use. The plan's instruction was to **run it before building anything** — throw
deliberately in a guest, on the production pipeline, and read what actually reaches the host.

## 2. Decision

What the probe found was worse than the finding in one way and better in another, and the decision
follows the evidence both ways.

**Worse: a crash in a guest effect never reached the host at all.** The composition's scope had no
`CoroutineExceptionHandler`, so a `LaunchedEffect` that threw — and an effect is where a screen's
real logic lives — fell to the platform default and surfaced as **one line in Zipline's internal
log**: minified type name, zero frames, `handleUncaughtException` never called.

```
SEVERE: Zt: A4-PROBE: a deliberate guest crash
```

**And the host's default handler was a trap that made it worse.** `onGuestException` defaulted to
`{ throw it }` — but this callback runs inside a Zipline service dispatch, and an exception thrown
there is returned to the **guest** as the call's failure. The host process never saw it; the crash
boomeranged back into the sandbox and vanished, leaving the host looking exactly as healthy as
before. Watched to spring, not deduced: the probe's second run produced
`Exception while trying to handle coroutine exception` and nothing else.

**Better: Zipline already applies source maps at build time.** No retrace step, no artifact to
upload — the frames that cross name **real Kotlin files from the payload**:

```
app.cash.zipline.ZiplineException: Zt: A4-PROBE: a deliberate guest crash
    at os (dev/dogwood/slice/ExploreScreen.kt)
    at Ye (dev/dogwood/slice/ExploreScreen.kt)
    at t1c (dev/dogwood/slice/ExploreScreen.kt)
```

Three changes, all now in place:

1. **The composition's scope carries an exception handler** that delivers the failure to
   `host.handleUncaughtException` — with a fallback print if the host's own handler fails, so the
   original stack is never lost to a secondary error.
2. **The host default prints the full stack and does not throw**, with the trap documented at the
   parameter. A product replaces it with its crash pipeline; a host that restores the rethrow has
   read the comment and disagreed.
3. **`GuestCrashRoutingTest` pins the seam** on the guest side, with a control: a healthy effect
   reports nothing, because a channel that fires without a crash teaches a team to ignore it.

## 3. Rationale & Research

The full chain was re-run on the production pipeline after the fix — `optimizeForSmallArtifactSize`,
signed, served, loaded by the desktop host — and the host printed the stack above through its own
channel. That is the claim: **file-level attribution into the payload's own sources, from a shipped
build, with nothing to deploy alongside it.**

## 4. Unstated Assumptions

- **Attribution is file-level, not line-level.** Function names stay minified (`os`, `Ye`) and line
  numbers do not survive `optimizeForSmallArtifactSize()`. For the screens this architecture
  encourages — one file per screen — a file name locates the crash; a product wanting more trades
  artifact size for it, which is Zipline's knob rather than Dogwood's.
- **This covers the Zipline profile.** The web guest is webpack-minified JavaScript in a Worker
  with its own error path (`WorkerBridge.onGuestError` carries a message, not a stack); its crash
  readability is unexamined and stays on the audit's page.
- **A crash *during composition* (rather than in an effect) travels the recompose loop's path**
  through the same scope, so the same handler sees it; the pinned test exercises the effect case,
  which is the one that was silent.

## 5. Updated Documents

- [`plans/adoption-audit.md`](../../plans/adoption-audit.md) — A4 closed; web remainder noted.
- [`engine/dogwood-compose/.../Runtime.kt`](../../engine/dogwood-compose/src/jsMain/kotlin/dev/dogwood/compose/Runtime.kt)
  — the scope's handler.
- [`engine/dogwood-host/.../Experience.kt`](../../engine/dogwood-host/src/ziplineMain/kotlin/dev/dogwood/host/Experience.kt)
  — the printing default, and the trap documented at the parameter.
- [`engine/dogwood-compose/src/jsTest/.../GuestCrashRoutingTest.kt`](../../engine/dogwood-compose/src/jsTest/kotlin/dev/dogwood/compose/GuestCrashRoutingTest.kt) — the pin.
- [`docs/operating.md`](../../docs/operating.md) — what a guest crash looks like and how to read it.

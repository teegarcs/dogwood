# ADR-013: Attributing a Pause Without Patching QuickJS

**Date:** 2026-09-06
**Status:** Accepted

## 1. Context & Problem Statement

Phase 0's harness appendix specified how to measure collection pauses:

> Zipline's public Application Programming Interface (API) exposes no garbage-collection hooks, so
> 0.4 uses a **locally patched Zipline native build**: wrap `JS_RunGC` in the vendored QuickJS with
> monotonic timestamps and a counter, exported through a debug method.

That hook was never built. Experiment 0.4 used the permitted fallback — force a collection from the
host and time it — which measures what a *forced* collection costs and cannot say whether a
naturally occurring outlier was one. The roadmap has carried the consequence since:

> Without the hook, a 22 ms outlier cannot be attributed to garbage collection rather than to the
> scheduler.

That outlier is the one number in Phase 0 outside a 60 Hz frame: a single forced collection reaching
**22.1 ms** on a Pixel 10 Pro. Whether it is a real hazard or a scheduling artefact decides whether
garbage collection is a risk this architecture carries.

## 2. Decision

**Attribute pauses from the public API, and do not patch QuickJS.**

`PauseWatcher` installs an `InterruptHandler` — which Zipline exposes — and records the interval
between successive callbacks, pairing each with the change in `memoryUsage.memoryAllocatedSize`
across it.

QuickJS calls the interrupt handler periodically while interpreting, and `JS_RunGC` runs to
completion without calling it, so a collection appears as a gap. A gap alone proves nothing, because
scheduler preemption looks identical from inside. The heap is what separates them: QuickJS is
primarily **reference-counted**, so the allocated size falls when the mark-and-sweep collector runs
and does not fall when the operating system merely takes the thread away.

| gap | heap fell | reading |
| --- | --- | --- |
| long | yes | a collection pause |
| long | no | something else — the scheduler, or a long uninterrupted native call |

## 3. Rationale & Research

**The specified patch could not answer the question it was specified for**, which is the finding
that changed the approach. The outlier is on a Pixel, so the hook would have to be built for
Android — which needs the Native Development Kit, absent here, along with CMake and Ninja. A patch
built for the development machine instead would instrument a host where the outlier has never
appeared. The appendix's fallback clause ("if the native build proves slow to stand up") anticipated
difficulty in standing it up; it did not anticipate that the build would be aimed at the wrong
machine.

**Measured on both hosts**, 20 rounds of churn over the reference screen:

| host | intervals | collections | worst collection | worst other | over a frame |
|---|---|---|---|---|---|
| MacBook Pro | 54 | 22 | 3.69 ms | **7.45 ms** | 0 |
| Android emulator | 59 | 21 | 5.80 ms | **7.48 ms** | 0 |

Two readings, and the second is the useful one. Collections are real and visible — 8.5 MB reclaimed
across 22 of them on the development host, so the instrument is measuring something rather than
reporting zeros. And on both hosts **the worst pause is not a collection**: the longest interval
attributable to the collector is shorter than the longest attributable to everything else. That is
precisely the statement experiment 0.4 could not make.

**What this does not settle, and it is the original question.** Neither host reproduced the 22.1 ms
outlier; nothing here exceeded a frame at all. So this ADR does not prove what that Pixel sample
was. What changes is that the question is now *answerable*: the instrument runs on Android from
`adb shell am start … --es experiment pauses`, and one run on that device decides it. The caveat
moves from "cannot be attributed" to "not yet run on the machine that produced it", which is a
smaller and closable thing.

## 4. Unstated Assumptions

- **A gap between interrupt callbacks bounds a pause; it does not time one.** Two collections inside
  one gap read as one, and a collection that begins and ends between two callbacks without moving
  the heap measurably is invisible. The patched hook would have timed `JS_RunGC` exactly. This is
  the precision given up for an instrument that runs on the target device.
- **The heap discriminator assumes reference counting reclaims continuously.** It does in QuickJS,
  which is why a fall across one interrupt interval reads as the mark-and-sweep collector. A build
  that batched reference-counted frees would blur the signal.
- **The probe costs a heap read per recorded interval**, which is a call into the interpreter.
  Nothing installs it by default, because a probe that changed what it measures would be worse than
  no probe. The threshold defaults to 1 ms so ordinary intervals are discarded before the read.
- **The emulator is not the Pixel.** It runs on the development machine's processor, so its
  scheduling behaviour is not a phone's. It is evidence the instrument works on Android, not
  evidence about Android hardware.

## 5. Updated Documents

- [Roadmap](../../roadmap.md) — Phase 0's outstanding hook item and Phase 7's deferred list.
- `tools/phase0/host-core/src/commonMain/kotlin/dev/dogwood/host/PauseAttribution.kt` (new)
- `tools/phase0/host-jvm/src/main/kotlin/dev/dogwood/host/PauseMain.kt` (new)
- `tools/phase0/host-android/.../Phase0Activity.kt`
- `tools/phase0/results/pauses-*.json`

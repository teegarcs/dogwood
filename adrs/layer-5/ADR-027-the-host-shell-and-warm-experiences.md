# ADR-027: The Host Shell and Warm Experiences

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

Project Dogwood supports two ways to compose an application out of guest code, and until now it
supported only one of them well.

**Path A, guest-owned navigation.** One experience owns its own tab bar and screens. Switching
tabs is ordinary Compose recomposition inside a single QuickJS runtime, so it is free, and the
tabs can share state directly because they are the same program. The reference example is
`AppShell.kt` in the slice guest.

**Path B, host-owned navigation.** Each tab is a separate experience — a separate entry point, a
separate QuickJS runtime, a separate deployable owned by a separate team. This is the path that
makes independent delivery possible, and it was the path that did not work well: the sample tore
the whole session down and cold-started a new one on every tab switch. Phase 0 measured a cold
start at 210 milliseconds for the interpreter and payload alone, before any network fetch the
guest performs on its first frame. A tab bar that costs a fifth of a second per tap is not a tab
bar anyone ships.

The architecture's answer has always been "keep the other experiences alive and just stop
composing them", on the reasoning that Compose's `BroadcastFrameClock` only requests a frame when
something is awaiting one, so an experience nobody looks at should ask for nothing. That is a
claim about behaviour, and the project's rule is that claims about behaviour get measured.

## 2. Decision

Introduce `DogwoodShell` (`engine/dogwood-host/src/jvmAndroidMain/kotlin/dev/dogwood/host/Shell.kt`),
a host-side owner of several `DogwoodSession` instances keyed by entry point.

1. **Warm switching.** `activate(entryPoint)` republishes an already-running experience
   synchronously. No load, no boundary traffic, no state transfer.
2. **A bounded warm pool.** `WarmPool` (`commonMain/.../WarmPool.kt`) is least-recently-used with
   a configurable capacity, defaulting to three. The active experience is never evicted, and the
   floor is one — a cap of zero would evict the screen the user is looking at.
3. **Eviction preserves state, not runtimes.** Evicting takes `snapshotState()` from the live
   guest, **then** closes it, keeping the snapshot. Returning to an evicted entry point is a cold
   start that passes the snapshot as `restoredState` — the same machinery a code update already
   uses, pointed at a second purpose. The user comes back to their scroll position and their
   half-typed text either way; only the latency differs.
4. **One delivery for all sessions.** The shell owns a single `DogwoodDelivery` and therefore a
   single `ZiplineCache`. Sessions are constructed with `ownsDelivery = false` so that closing one
   does not take its siblings' loader down with it.
5. **`trimMemory(keep)`** exposes the pool's `trim` for an Android `onTrimMemory` hook.
6. **The idle claim is instrumented, not asserted.** `DogwoodExperience.frameRequests` counts
   every `requestFrame` from its guest, and `DogwoodShell.frameRequests()` exposes the counter per
   entry point, so a hidden experience doing work is visible rather than inferred.

## 3. Rationale & Research

### The measurements

Taken on an Android emulator running the slice sample, four entry points (`app`, `explore`,
`about`, `feed`) against the same manifest, warm capacity three. The switch clock starts when the
tab is requested and is read at two points, because the two intervals have different owners:

- **experience ready** — until this entry point's experience is the published one. This is the
  shell's cost.
- **drawn** — until the first frame after that. This is Compose measuring, laying out and drawing
  a tree that is already fully applied. The shell cannot make it faster and a native tab switch of
  the same content would pay it too.

| Switch | Experience ready | Drawn |
| --- | --- | --- |
| Cold, first ever (`app`) | 647 ms | 716 ms |
| Cold (`explore`) | 161 ms | 191 ms |
| Cold (`about`) | 140 ms | 201 ms |
| Warm (`explore`) | **0 ms, synchronously** | 58–74 ms |
| Warm (`about`) | **0 ms, synchronously** | 37–49 ms |

The shell's own contribution to a warm switch is zero: `activate` publishes the experience before
it returns, and the awaited path is not taken at all. What remains is host draw cost, and it
tracks content rather than the shell — `explore` is a longer list than `about` and consistently
costs more to draw, across every repetition.

This does **not** meet the plan's stated target of "one frame" (16.7 ms at 60 Hz), and the target
was optimistic rather than the result being poor: it counted the swap and forgot the draw. What
the shell actually removes is the 140–650 milliseconds of guest cold start, which is the part it
is responsible for and the part that made Path B unusable.

**Memory, `dumpsys meminfo` total proportional set size:**

| Warm experiences | Total PSS | Delta |
| --- | --- | --- |
| 1 | 128.6 MB | — |
| 2 | 142.5 MB | +13.5 MB |
| 3 | 152.0 MB | +9.3 MB |

Roughly **9 to 14 megabytes per warm experience**, covering the QuickJS interpreter, its heap, the
loaded payload and the host-side widget tree. The spread is content-dependent, not fixed overhead.
A default capacity of three therefore costs on the order of 20 megabytes above a single
experience, which is why the cap is configurable and why `trimMemory` exists rather than trusting
the default.

**Idle cost:** with three experiences warm and the application untouched for twenty seconds,
hidden experiences requested **zero** frames. The `BroadcastFrameClock` reasoning holds. One
transient exception was observed and is expected: an experience requested six frames immediately
after being hidden, draining work that was already in flight when the user switched away. It
settled and did not recur. The audit is a rate check, not a total: an entry point whose count is
still climbing seconds after it left the screen is a bug in the guest, and now a visible one.

### Why eviction must close the session, not just cancel its job

`evict` originally cancelled the session's coroutine and stopped there. Cancelling the job stops
the code-update flow but leaves the guest alive — the interpreter, its heap, and the composition
inside it. An eviction that frees nothing accumulates exactly what the cap exists to bound, and it
does so silently, because the pool's bookkeeping is correct and only the memory is wrong. The fix
is one line, `session.close()`, and the leak detector then watches the closed session so that a
retained one is reported rather than merely suspected.

## 4. Unstated Assumptions

- **All sessions share one Zipline dispatcher.** They are separate heaps but a single thread, so a
  guest that blocks it blocks its siblings. Guest work is already required to be non-blocking;
  whether N sessions want N threads is E2's question, not this one.
- **The frame-request counter measures what it claims.** It counts `requestFrame` calls arriving
  at the host, which is where the cost of waking the Zipline thread is paid. A guest recomposing
  without ever asking for a frame would not be counted, but it also would not cost anything.
- **`dumpsys meminfo` deltas attribute to the right cause.** They are whole-process numbers taken
  around a single activation, on an idle emulator. They are the right order of magnitude for
  choosing a default pool size, not a precise per-instance figure.
- The numbers above are from an emulator. Draw costs on real hardware will differ; the shell's
  zero-millisecond contribution will not, because it is a synchronous republish.

## 5. Consequence: guest state that crosses the boundary

Verifying eviction found a defect that had nothing to do with the shell and everything to do with
Path A. `rememberSaveableStateHolder()` — the standard way to keep an off-screen screen's state
alive, and what a guest-owned tab bar is built on — registers a **single** provider whose value is
a nested `Map<key, Map<providerKey, List<Any?>>>`. The guest's `canBeSaved` predicate did not
admit maps, and `performSave` throws on the first value it rejects, so the holder did not merely
fail to save itself: it took the entire snapshot down with it. Every unrelated screen's state
vanished, with nothing on screen to say why.

The predicate now admits string-keyed maps, and `toJson`/`fromJson` carry them in a tagged
envelope alongside the existing `MutableState` envelope. Keys must be strings because the wire
form is a JSON object; a guest passing a non-string `SaveableStateProvider` key is told at the
call site.

This was invisible until an eviction asked for a snapshot, because nothing else in the sample used
a holder. It is the sixth Phase 4-and-later subsystem whose defect was only findable by running
the code.

## 6. Updated Documents

- [Layer 5: The Native Host & Generated Binding Layer](../../specs/layer-5-host.md) — new section "The Experience Shell: Several Guests, One Host", with diagram and node definitions.
- [Layer 4: The Guest Runtime](../../specs/layer-4-sandbox.md) — new section "Saveable State: What May Cross, and Why It Is Narrow".
- [Experience composition plan](../../plans/experience-composition.md)

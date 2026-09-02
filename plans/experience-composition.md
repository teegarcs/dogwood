# Plan: Experience Composition — Both Models, First-Class

**Date:** 2026-09-02
**Status:** Planned, not built. Slots between Phase 4 (complete) and Phase 5 (Web host) as
**Phase 4.5** — the Web host will reuse whatever shell model this settles, so it belongs first.

---

## Part 0 — Why this plan exists

The demo's tabs exposed a question the architecture had answered only implicitly. There are two
legitimate ways to structure a product:

- **Path A — one experience, many screens.** The guest owns navigation between its screens as
  ordinary Compose. One QuickJS instance, shared in-memory state, one payload. Right when the
  screens are one product surface.
- **Path B — many experiences.** Each surface is its own entry point (or payload), its own QuickJS
  instance, isolated by construction. Right when a second *team*, a second *release cadence*, or a
  hard *isolation* requirement appears — not when a second screen does.

Both must be supported well. Path A already works and costs nothing new. Path B works but pays a
**full runtime teardown and cold start on every switch** — and the demo revealed worse: the
sample's tab switch recreates the entire delivery stack and never closes the previous session, so
each switch leaks a QuickJS instance and opens a duplicate `ZiplineCache` on the same directory.
That is a sample defect (E1 fixes it), but the cost it papers over is real: Phase 0 measured cold
start to first composition at **~127 ms p50 on a development machine**, slower on a device, plus
delivery-layer setup.

The objective: **switching between live experiences should cost one frame, not one cold start** —
and both paths should have a reference example a product team can copy.

## Part 1 — The work

### E1. The host shell: retained experiences

A `DogwoodShell` (host common code) that owns sessions keyed by
`(applicationName, manifestUrl, entryPoint)`:

- **Warm switching.** The shell keeps inactive experiences alive; the host composes the active
  one's surface and simply stops composing the others. Switching between warm experiences is a
  composition swap — no runtime work at all. The architecture already made idle guests free by
  design (`BroadcastFrameClock` requests frames only when something awaits one), so a hidden tab
  should cost **zero boundary traffic and zero frames**; E1 verifies that claim with a counter
  rather than assuming it.
- **Bounded memory.** A configurable cap plus an Android `onTrimMemory` hook. Eviction is
  `snapshotState()` → close the runtime → keep the snapshot; returning is a cold start with
  `restoredState` — the machinery code updates already use, pointed at a new purpose. The user
  returns to their scroll position and half-typed text either way; only the latency differs.
- **One delivery, shared.** The shell owns a single `DogwoodDelivery`/`ZiplineCache` for all
  sessions, fixing the duplicate-cache hazard, and closes sessions deterministically.
- **Fixes the sample defect** as its first proof: tab switches in the demo go through the shell.

*Measurements to publish in the ADR:* warm-switch latency (target: one frame), cold+restore
latency, memory per warm instance (`dumpsys meminfo` deltas), idle-tab frame requests (target: 0).

*Estimate: ~2 days. ADR-027.*

✅ **Done.** [ADR-027](../adrs/layer-5/ADR-027-the-host-shell-and-warm-experiences.md). Verified on
an emulator with four entry points and a warm cap of three:

- **Warm switch: the shell's own cost is 0 ms, taken synchronously**, in every repetition — no
  reload, no boundary traffic. The 37–74 ms that remains is Compose drawing an already-applied
  tree, tracks content rather than the shell (the longer list consistently costs more), and a
  native tab switch of the same content would pay it too. The plan's "one frame" target counted
  the swap and forgot the draw; what the shell removes is the **140–650 ms of guest cold start**.
- **Cold + restore: 140–647 ms to ready, 191–716 ms to drawn**, worst on the first ever load.
- **Memory: +13.5 MB for the second warm experience, +9.3 MB for the third** (total proportional
  set size), so roughly 9–14 MB each and about 20 MB for the default cap of three.
- **Idle: zero frame requests** from hidden experiences over twenty untouched seconds. One
  transient burst was seen immediately after an experience was hidden — in-flight work draining —
  which is why the audit is a rate check rather than a total.

Two defects were found by running it, neither of them in the pool logic:

1. `evict` cancelled the session's coroutine but never called `session.close()`, leaving the
   interpreter, its heap and its composition alive. The bookkeeping looked correct and the memory
   was not freed.
2. **`rememberSaveableStateHolder()` state could not cross the boundary at all.** The holder
   registers one provider whose value is a nested map; the guest's `canBeSaved` rejected maps, and
   `performSave` throws on the first rejection — so the holder took the *entire* snapshot down,
   losing every unrelated screen's state with it. Fixed in the guest registry, with three tests in
   `SaveableHolderTest` that fail without the fix and pass with it. This was a Path A defect that
   only an eviction was ever going to surface.

### E2. Concurrent surfaces — two experiences visible at once

The side-by-side case: a host layout composing two independent `DogwoodSurface`s (nav rail from
one team, content pane from another). Believed to work today with two sessions; unproven. The
spike answers:

- Can two sessions **share one Zipline thread**? (Both take a dispatcher; a shared single-thread
  dispatcher should serialize them safely. If not: one thread each, and say so.)
- Does anything in the host locals (`SkewReport`, evaluator, palette) cross-contaminate? (Each
  `DogwoodTree` already scopes its own; verify.)

A **noted-not-built** optimisation: multiple mounts from *one* payload in *one* runtime (two
compositions in one QuickJS), for the same-team case where isolation is not wanted and memory is.
Only pursued if E1's memory numbers demand it — it touches `DogwoodGuest`'s
one-composition-per-service assumption, which is real work.

*Estimate: 0.5 day spike, folded into ADR-027.*

✅ **Done**, folded into [ADR-027](../adrs/layer-5/ADR-027-the-host-shell-and-warm-experiences.md).
Two experiences composed at once from two runtimes, launched with deliberately different
parameters, verified on device.

- **Two sessions can share one Zipline thread.** Zero thread-contract assertions fired across
  repeated switching, eviction and re-entry. Contention between two *simultaneously busy* guests
  is unmeasured and stated as such.
- **Nothing cross-contaminates.** The companion read its own launch parameters, dictionary
  versions, services and flags while the pane above it read different ones.
- **Two defects found, neither in the code the spike targeted.** The host environment was being
  derived per window rather than per surface, so both guests were told they had the whole screen;
  and the pool's recency ordering made a mounted companion the first thing the cap would evict,
  because "not tapped recently" and "not on screen" are not the same thing. Both fixed — explicit
  `mount`/`unmount` and `updateEnvironment(entryPoint, …)` — with pool tests for the new rule.

The noted-not-built optimisation (several mounts from one payload in one runtime) stays unbuilt:
E1's 9–14 MB per warm experience does not force it.

### E3. The navigation service

The missing piece for any multi-experience product: a guest cannot ask the host to go anywhere.

`DogwoodNavigation` joins the host service surface (ADR-013's pattern — optional, absence is
normal): `navigate(route: String, params: JsonObject)`. The **host** interprets routes — switch to
another experience, push a native screen, open a browser — because routing is app chrome, exactly
like the tab bar. Routes are strings for the same reason entry points are: a deep link is a string
the host already holds. Unknown routes degrade and report, per the standing skew rule.

*Estimate: ~1 day. ADR-028.*

✅ **Done.** [ADR-028](../adrs/layer-5/ADR-028-guest-initiated-navigation.md). `DogwoodNavigation`
joins the service surface as an ordinary optional service; the host interprets routes and the guest
never learns the outcome.

- **A guest asks before it draws.** `routes()` is read once at start and `canNavigate(route)`
  answers locally, so a control that would do nothing is never rendered — a dead button makes the
  user blame the product rather than the build. An empty route set means "this host does not
  enumerate", never "handles nothing", so hosts with deep-link tables are not silently stripped of
  every navigating control.
- **Unknown routes degrade.** Verified on device: the host logged the skew, stayed put, no crash.
- **The service-surface version gate finally has a customer.** Navigation is revision 2, so the
  guest checks the revision *before* calling the accessor rather than catching around it — the
  thing that fails is the call. The test asserts the accessor is not called on a revision 1 host.
- Verified end to end: the explore experience drew its control only because the host declared the
  route, sent `experience/feed {"from":"explore"}`, and the host swapped experiences — **0 ms of
  shell cost** when the destination was already warm.

Two limits, both stated on the interface rather than left to be discovered: a route is a request a
host may ignore, and **launch parameters are start-time**, so routing to an already-warm experience
delivers the route but not new parameters. Building it also surfaced a defect — `ShellEntry` held
the parameters it was first constructed with forever, so an experience evicted and cold-started
came back with parameters from several navigations ago. Newest now wins for the next start.

### EX-A. Reference example: Path A, guest-owned navigation

A payload whose tab bar is **inside the guest** — guest Compose state switches between three
screens that share in-memory state (a saved-stays count visible from every tab proves the point).
The host mounts one experience and does nothing else. Demonstrates: switches are pure composition
(zero runtime cost, measured), state flows freely, one payload ships the lot, and
`rememberSaveable` still survives code updates mid-flow.

*Estimate: ~1 day, independent of E1–E3 — can go first.*

### EX-B. Reference example: Path B, the multi-experience shell

The host-native tab bar backed by E1's shell: each tab an independent experience, switching warm
(instant, state alive), one tab evicted under a simulated memory trim and restored from snapshot
on return, and a cross-experience jump ("open About") through E3. Honest note in the example
itself: the tabs share one payload with different entry points as a stand-in for separate team
payloads — the mechanics at the boundary are identical, and a second manifest URL is
configuration, not architecture.

*Estimate: ~1.5 days.*

✅ **Done.** `samples/slice-android/.../TabsActivity.kt`, a self-contained Path B host that reads
end to end without the diagnostic harness around it — launch with
`adb shell am start -n dev.dogwood.slice.android/.TabsActivity`. It carries the honest caveat in
the file itself: four entry points in one payload stand in for four team payloads, the boundary
mechanics are identical, and what it therefore does *not* demonstrate is independent delivery,
which is the actual reason to choose Path B.

Verified on device, all of it visible on screen:

- **Four warm switches, zero reloads.** State intact throughout.
- **Cap of three over four tabs**, evicting least-recently-used with a snapshot each time;
  returning to an evicted tab cold-started and restored its four state keys.
- **Memory pressure, from the platform rather than a button.** `adb shell am send-trim-memory <pid>
  RUNNING_LOW` reached `onTrimMemory`, which dropped both hidden experiences (keeping 1 and 4 state
  keys) and left only the visible one warm; returning restored. This closes the plan's last open
  question with a caveat: it exercises the real callback at the real level, not genuine memory
  exhaustion.
- **A cross-experience jump requested by guest code**, not by the tab bar: the explore experience's
  control sent `experience/feed`, the host moved the tab bar to Stays, and the destination restored
  the state it had when it was last evicted.

Two defects found by building it, both invisible until the code ran:

1. **A null service crashes the guest at start.** `TabsActivity` is the first host in the repo to
   leave a service unwired, and `DogwoodServices` returning null for any accessor throws at the
   Zipline boundary. "Every service is optional and its absence is normal" is a documented,
   load-bearing property of the surface ([ADR-013](../adrs/layer-5/ADR-013-host-services-and-entry-points.md))
   that has never actually worked, because every host in the repo supplied every service. Tracked
   separately below.
2. **`onTrimMemory` called through a null reference and evicted nothing.** Publishing the shell
   from the effect that built it, while disposing from a `DisposableEffect` keyed on it, meant a
   stale `onShell(null)` landed *after* the fresh `onShell(built)` — Compose disposes the previous
   key before running the new body. Silent, because every other path read the shell from
   composition rather than from the field. Exactly the failure the plan's "under real pressure, not
   simulated" note was written to catch.

## Part 2 — Sequencing

| Order | Item | Depends on |
|---|---|---|
| 1 | EX-A (Path A example) | nothing — it is the cheap half, and it sets the contrast |
| 2 | E1 (the shell) | nothing |
| 3 | E2 (concurrency spike) | E1 |
| 4 | E3 (navigation service) | nothing, but wants E1's shell to route into |
| 5 | EX-B (Path B example) | E1 + E3 |

Total: **~6 days.** Phase 5's page-weight measurement (a one-day falsification test) can run in
parallel at any point; the Web host *build* should wait for this, since it will mount experiences
through the same shell.

## Part 3 — Open questions to vet at build time, not assume

- ✅ **Is a warm hidden guest truly zero-cost?** Yes, in steady state: zero frame requests over
  twenty idle seconds with three warm. Work already in flight when the user switches away does
  drain afterwards, so the counter is meaningful as a rate, not as a total. `frameRequests()` on
  the shell makes this checkable in any host, not just the sample.
- ✅ **Memory per warm instance on a device.** 9–14 MB. It set the default cap at three.
- One shared Zipline thread for N sessions, or one thread each? — **still open, E2.** The shell
  ships with one shared dispatcher, so a guest that blocks it blocks its siblings.
- ✅ **Does snapshot-evict-restore round-trip state through the shell path?** Yes — verified on
  device end to end: state set, entry point evicted by the cap, three keys snapshotted, three
  restored, value back on screen. Getting there required the `canBeSaved` fix above, which is
  exactly why ADR-014's "same machinery, verify anyway" note was right.
- `onTrimMemory` behaviour under real pressure, not simulated. — **still open**, folds into EX-B.

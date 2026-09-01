# ADR-007: Keep the Snapshot Mirror; Reject the Imperative Applier

**Date:** 2026-08-31
**Status:** Accepted

## 1. Context & Problem Statement

[roadmap.md](../../roadmap.md) Phase 1 step 7 requires the host rendering strategy to be decided
by measurement rather than by preference: "the snapshot mirror specified in
[Layer 5](../../specs/layer-5-host.md) against an imperative applier that mutates retained
nodes, which is what Redwood does. Compare apply-to-pixel latency at batch sizes 1 / 10 / 100 /
1,000."

The two differ in where they pay:

- The **snapshot mirror** makes every node property snapshot state. Applying a change writes to
  that state, and Compose invalidates exactly the bindings that read it. The cost is a state
  object per property, a state list per slot, and a recorded read per binding.
- The **imperative applier** retains nodes holding ordinary fields, mutates them in place, and
  signals a single generation counter at the root. Applying is a plain map write. But nothing is
  observed, so every applied batch recomposes the whole tree.

Both are implemented — `HostTree` and `PlainTree` in `dogwood-host` — behind a shared
`WidgetView` interface, so the bindings are identical under each and the comparison is of the
strategies rather than of two dictionaries.

## 2. Decision

**Keep the snapshot mirror. Reject the imperative applier.**

`PlainTree` is retained in the repository as the measured alternative, not as a live option; it
is what makes this decision reproducible rather than a claim.

The decision does **not** rest on measured latency, because at every cell tested neither
strategy was distinguishable at frame granularity. It rests on how each **scales**, which the
recomposition counts show directly.

## 3. Rationale & Research

Measured on an Android emulator (Google `sdk_gphone64_arm64`, Android 15), sixty iterations
after ten warm-ups, medians. Apply-to-frame is quantised to the refresh interval, so ~33 ms
means two vertical syncs and therefore that the work fitted inside one frame.

**At the reference screen's size, 160 widget nodes:**

| Strategy | Batch | Apply | Bindings recomposed | Apply to end of frame |
| --- | ---: | ---: | ---: | ---: |
| snapshot mirror | 1 | 120 µs | **1** | 33.4 ms |
| snapshot mirror | 1000 | 1451 µs | 69 | 32.6 ms |
| imperative | 1 | **48 µs** | 93 | 33.3 ms |
| imperative | 1000 | **344 µs** | 93 | 32.6 ms |

**At a stress size, 1,222 widget nodes:**

| Strategy | Batch | Apply | Bindings recomposed | Apply to end of frame |
| --- | ---: | ---: | ---: | ---: |
| snapshot mirror | 1 | 56 µs | **1** | 33.4 ms |
| snapshot mirror | 100 | 203 µs | 100 | 33.2 ms |
| snapshot mirror | 1000 | 1404 µs | 600 | **49.1 ms** |
| imperative | 1 | **40 µs** | 801 | 33.3 ms |
| imperative | 100 | **52 µs** | 801 | 33.3 ms |
| imperative | 1000 | **262 µs** | 801 | **49.3 ms** |

Three things follow.

**Applying is cheaper imperatively, and it does not matter.** The imperative tree applies two to
four times faster, because writing a plain map entry costs less than writing snapshot state. In
absolute terms the gap is about seventy microseconds at the batch size that actually occurs. The
guest-side encoding of the same batch was measured in Phase 0 at **1.14 milliseconds**
([Layer 4 ADR-007](../layer-4/ADR-007-v1-wire-format-positional-json.md)) — sixteen times the
saving. Optimising the host's apply is optimising the wrong end of the boundary.

**Recomposition is where the strategies genuinely part, and it is the part that scales.** The
snapshot mirror recomposes as many bindings as the batch changed: one for a one-property change,
at any tree size. The imperative applier recomposes 93 bindings on a 160-node tree and 801 on a
1,222-node tree — **for a single property change, every time**. That is per-frame work
proportional to tree size rather than to change size, and steady-state batches are one or two
changes, which is the case it handles worst.

**Neither failed at reference size, and both failed at the same place.** At 1,222 nodes with a
1,000-change batch both missed a frame (~49 ms, three vertical syncs). No tested cell separates
them on latency. The decision is therefore made on the scaling argument above and not on a
measured failure of one strategy — which is a weaker basis than the roadmap hoped for, and is
said plainly here rather than dressed up.

The measurement lives in
[`samples/slice-android/.../RenderBenchActivity.kt`](../../engine/samples/slice-android/src/main/kotlin/dev/dogwood/slice/android/RenderBenchActivity.kt)
and its output in
[`tools/phase0/results/render-strategy.md`](../../tools/phase0/results/render-strategy.md).

## 4. Unstated Assumptions

- **Assumes an emulator's ordering is a device's ordering.** It is not a gate device, and the
  Phase 0 finding that interpreted guest work is bound by single-core throughput does not
  transfer to host-side Compose work, which is compiled. Re-run on real hardware before treating
  the absolute numbers as anything.
- **Assumes the tree under test is representative.** It is synthetic — a column of rows of text,
  shaped and sized like the reference screen — so that the benchmark reproduces without a server.
  It exercises no design-system component, and a binding that does more work per invocation
  would widen the recomposition gap in the snapshot mirror's favour.
- **Assumes the imperative strategy cannot be made finer-grained.** A middle design exists that
  this did not measure: retained nodes with plain fields and a per-node invalidation signal
  rather than one at the root. It would recompose one subtree instead of the whole tree while
  keeping cheap applies. Nobody has built it, and the case for it is weak while the host's apply
  cost is a sixteenth of the guest's encoding cost.
- **Assumes recomposition counts are comparable across strategies.** Both were counted at the
  same point, the top of `RenderNode`, through a counter deliberately outside the snapshot
  system so counting cannot itself invalidate.

## 5. Updated Documents

- [specs/layer-5-host.md](../../specs/layer-5-host.md) — the snapshot mirror is now a measured
  decision rather than a proposal
- [roadmap.md](../../roadmap.md) — Phase 1 step 7 discharged
- [engine/README.md](../../engine/README.md) — the outstanding-work list loses this item

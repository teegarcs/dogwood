# ADR-008: Phase 0's Gate Will Not Be Closed on the Named Device

**Date:** 2026-08-31
**Status:** Accepted

## 1. Context & Problem Statement

The Phase 0 gate in [roadmap.md](../../roadmap.md) is defined on "one low-end Android device of
roughly 2022 entry tier (target: a Samsung Galaxy A14 or the nearest device the team owns)". No
such device is available and none will be acquired. Every measurement so far was taken on an
Apple silicon development machine, an Android emulator, and a Pixel 10 Pro — all faster than the
gate device, and all therefore lower bounds.

The project cannot sit indefinitely behind a gate nobody will open. It also must not pretend the
gate was met.

## 2. Decision

**Phase 0's gate is formally not closed, and work proceeds anyway, with the shortfall recorded as
a standing risk rather than discharged.**

Concretely:

1. Every gate leg is reported against the best hardware available, and every results file states
   at the top that it is not gate-valid. That stays true; nothing is relabelled.
2. **The specific carried risk is 0.2, recomposition** — not 0.3, which had the attention. Phase 0
   found that interpreted guest work is bound by single-core instruction throughput and is
   otherwise hardware-independent across the hosts measured, so the gate device's numbers will
   track its single-core performance close to linearly. Recomposition measures 1.58 ms at the
   95th percentile against an 8 ms budget: comfortable at a three-times-slower core, at the edge
   at five times, failing beyond that. **The multiplier is unmeasured**, and the roadmap already
   names slow recomposition as fatal rather than maskable.
3. 0.3, which looked worst, is no longer the concern:
   [ADR-007](ADR-007-v1-wire-format-positional-json.md) reduced the whole-screen crossing from
   24 ms to 1.2 ms, which leaves headroom a slow device can spend.
4. **The risk is discharged by the first real deployment, not by a purchase.** Whoever adopts this
   has a fleet; the harness in [`tools/phase0/`](../../tools/phase0/) runs on any Android device
   and writes a results file. The first low-end device it runs on closes or reopens the gate.

## 3. Rationale & Research

The alternative — halting until hardware appears — would trade a known, bounded, *measurable*
risk for certain schedule loss, and the risk is bounded in a specific way: the failure mode is
known (recomposition too slow), the leg is known (0.2), the mechanism is known (single-core
throughput), and the test is a five-minute run of an application-package that already builds. That
is a risk worth carrying. A risk worth halting for is one you cannot describe.

What makes carrying it honest rather than convenient is that nothing was reinterpreted to get
here. The 0.3 leg was reported failed as written until a human ruled on its ambiguity
([ADR-006](ADR-006-batch-crossing-is-guest-encoding.md) §2.4); the emulator's numbers are labelled
as not gate-valid in every file that contains them; and the Pixel's two anomalous bake-off rows
are called out rather than quoted.

Note also what the emulator cannot tell us and never will: it runs ARM code on the development
machine's processor, so it is a correctness check for the Android path and not a performance
measurement of a phone. Proceeding on emulator data means proceeding without a performance
measurement of any low-end hardware at all. That is the shape of the gap.

## 4. Unstated Assumptions

- **Assumes single-core throughput is the only thing that scales.** Phase 0 measured that
  interpreted guest work matches across two high-end cores while platform crossings do not. A
  low-end device also has less memory bandwidth and a smaller cache, and the Compose slot table
  is not small; the extrapolation could be optimistic for reasons this project has not measured.
- **Assumes the reference screen is representative of what will ship.** It is 160 nodes. A denser
  screen moves recomposition cost, and the 8 ms budget does not move with it.
- **Assumes a fleet exists at adoption time.** If the first deployment target is itself
  high-end-only, this risk is not discharged by shipping; it is merely postponed again.
- **Assumes garbage collection stays quiet on slower hardware.** 0.4 passed on p99 everywhere,
  but the Pixel showed a 22 ms outlier the harness could not attribute, because the patched-QuickJS
  hook was never built.

## 5. Updated Documents

- [roadmap.md](../../roadmap.md) — Phase 0 status: the gate's disposition and the carried risk
- [README.md](../../README.md) — the status paragraph
- [tools/phase0/README.md](../../tools/phase0/README.md) — what to run when a device appears

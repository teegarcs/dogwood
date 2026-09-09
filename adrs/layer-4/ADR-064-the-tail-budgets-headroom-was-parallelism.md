# ADR-064: The tail budgets' headroom was parallelism

**Date:** 2026-09-09
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-008](ADR-008-gate-device-not-available.md) defined the performance gate on named hardware and
recorded that the gate device was not available. Every Phase 0 number since has come from fast
hardware — Apple silicon, an emulator, a Pixel — and cleared every budget comfortably. The
framework-grade rubric caps performance at C+ for exactly that reason.

The owner asked a reasonable question: could a lower-quality emulator or simulator stand in for the
device? The answer was already no, and already written down — the iOS Simulator executes host-native
code, and an Android emulator borrows the development machine's single-core speed, which is the
precise dimension a 2022-tier Cortex-A53 lacks. An AVD profile can shed cores and memory; it cannot
honestly slow a core.

But a second question sat underneath it and had never been asked: **how much of the observed headroom
is real, and how much is the machine having cores to spare?** If the numbers barely move when the
runtime loses most of its parallelism, the margin is genuine. If they move several-fold, the margin
was scheduling luck and every conclusion drawn from it is weaker than it looks.

## 2. Decision

**The scaling-sensitivity experiment is the accepted use of an emulator for performance work, and
its result is that the tail budgets' headroom is parallelism.**

- `tools/phase0/scaling-sensitivity.sh` runs the Phase 0 harness on one emulator at four cores and
  at one, same toolchain, same harness binary, and refuses to record a run whose requested core
  count the guest did not report.
- `tools/phase0/scaling-sensitivity.py` prints the ratio per measurement.
- Results are committed with the core count in the filename and `not-gate-valid` in it.
- **No number from this experiment may be cited as gate evidence**, and the write-up says so three
  times, in the script, in the tool, and at the top of the results.

## 3. Rationale & Research

Same emulator (`Pixel_9_Pro`, Android 15, API 35), same pinned toolchain, `-cores 4` then `-cores 1`.
Median ratio **1.93×**, and the median is not the story:

| | 4 cores | 1 core | ratio |
|---|---|---|---|
| recompose, one-node diff, p50 | 1.72 ms | 1.39 ms | **0.81×** |
| recompose, two-node diff, p95 | 2.44 ms | 8.24 ms | 3.37× |
| recompose, two-node diff, p99 | 2.99 ms | 15.28 ms | 5.12× |
| recompose under load, p99 | 4.63 ms | 17.67 ms | 3.82× |
| module load, p50 | 39.2 ms | 126.5 ms | 3.23× |

**The steady state does not move at all** — `p50` is 0.81×–1.10×, in one case *faster* at one core.
Guest composition is single-threaded by construction, so removing cores does not slow the work it
does; it only removes the cross-core scheduling around it.

**Every tail moves two- to six-fold.** That is where the other three cores were going: garbage
collection, just-in-time compilation, and the platform's background work, which were running beside
the composition and now run instead of it.

The consequence is concrete rather than atmospheric. **`G1` — guest recomposition, 95th percentile,
budget 8.0 ms — is met at four cores and missed at one.** At four cores every measurement lands
between 1.65 and 3.98 ms; at one core they land between 5.64 and 15.13 ms, and two of the five
exceed the budget, one by nearly a factor of two. Nothing about the payload changed between the two
runs.

`G4` (cold start, 500 ms) survives comfortably: 39 → 127 ms.

**So the gate device becomes more urgent, not less.** This is the opposite of the conclusion an
emulator run is usually reached for. It does not predict a Cortex-A53 — that device is slower in a
different way, per-core rather than in parallelism — but it removes the reading under which the
current margins make the device optional. The `p50` margin is real; the `p95` and `p99` margin was
four cores.

## 4. Unstated Assumptions

- **That `-cores` is honoured by the hypervisor.** Not assumed: the drill reads
  `/proc/cpuinfo` inside the guest and refuses a run whose count does not match what was asked. A
  run that silently kept four cores would produce two identical curves and a confident wrong
  conclusion.
- **That core count is a reasonable proxy for "less machine".** It is a proxy for *less
  parallelism*, and that is all this claims. It says nothing about per-core speed, memory bandwidth,
  thermal behaviour, or storage — several of which are how a real low-end device differs, and none
  of which an emulator can vary honestly.
- **That the tails are dominated by work that was parallel.** Inferred from the shape (p50 flat, p95
  and p99 several-fold) and consistent with `PauseWatcher`'s attribution, but not directly
  attributed here. The 22.1 ms outlier recorded earlier remains unattributed, and this experiment
  does not resolve it.
- **That two runs are enough.** They are enough for a shape and not for a curve. Intermediate core
  counts were not run; the deliverable was whether the degradation is a few percent or a few
  multiples, and it is a few multiples.

## 5. Updated Documents

- [`tools/phase0/results/scaling-sensitivity.md`](../../tools/phase0/results/scaling-sensitivity.md)
- [Engineering backlog](../../plans/engineering-backlog.md) — P1 marked done
- [ADR-008](ADR-008-gate-device-not-available.md) — unchanged in decision; this is the measurement
  its §4 acquisition question was missing
- [Checks](../../docs/checks.md)

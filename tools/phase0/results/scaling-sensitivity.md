# Scaling sensitivity: the same emulator at four cores and at one

**Run 2026-09-09. `tools/phase0/scaling-sensitivity.sh`.**

> **This is sensitivity, not gate evidence.** Both runs are an Android emulator, which borrows the
> development machine's single-core speed — the exact dimension a 2022-tier Cortex-A53 lacks. Neither
> number is a device number and their ratio is not a prediction of one.
> [ADR-008](../../../adrs/layer-4/ADR-008-gate-device-not-available.md) still defines the gate on
> named hardware, and the framework-grade rubric's C+ cap on performance still applies.

## The question

The owner asked whether a lower-quality emulator could stand in for the gate device. It cannot, and
that was already recorded. But the follow-up question is a real one and an emulator *can* answer it:
**how much of the Phase 0 headroom is scheduling?** If the numbers barely move when the runtime
loses three quarters of its parallelism, the budgets have headroom that is genuinely there and the
gate device is a formality. If they move several-fold, the headroom was luck.

Core count is the only honest knob. An Audio Video Device (AVD) profile can shed cores and memory; it
cannot slow a core, and process-level throttling produces numbers that are neither the fleet's nor
reproducible — which the Phase 0 harness appendix exists to refuse.

Same emulator (`Pixel_9_Pro`, Android 15, API 35, arm64-v8a), same pinned toolchain, same harness
binary, launched with `-cores 4` and then `-cores 1`. The drill reads `/proc/cpuinfo` in the guest
and **refuses to record a run whose core count was not honoured** — a run that silently kept four
cores would produce two identical curves and a confident wrong conclusion.

## The answer

**Median ratio 1.93×. The median is not the story.**

| | 4 cores | 1 core | ratio |
|---|---|---|---|
| recompose, one-node diff, p50 | 1.72 ms | 1.39 ms | **0.81×** |
| recompose, one-node diff, p95 | 2.37 ms | 6.08 ms | 2.57× |
| recompose, two-node diff, p95 | 2.44 ms | 8.24 ms | 3.37× |
| recompose, two-node diff, p99 | 2.99 ms | 15.28 ms | 5.12× |
| recompose under load, p95 | 3.49 ms | 10.05 ms | 2.88× |
| recompose under load, p99 | 4.63 ms | 17.67 ms | 3.82× |
| module load, p50 | 39.2 ms | 126.5 ms | 3.23× |

**The steady state does not move.** `p50` recomposition is 0.81×–1.10× — flat, and in one case
faster at one core, which is what removing cross-core scheduling from a single-threaded interpreter
looks like. Guest composition is one thread by construction; taking cores away does not slow the
work it does.

**The tails move a great deal.** Every `p95` and `p99` degrades two- to six-fold. That is where the
other three cores were being spent: garbage collection, just-in-time compilation, and the platform's
own background work, all of which were running *beside* the composition and now run *instead of* it.

## What this changes

**The `G1` budget is met at four cores and missed at one.** `G1` is guest recomposition of the
reference screen at the 95th percentile, budget 8.0 ms:

* 4 cores: 1.65 – 3.98 ms across all five measurements. Two to five times of headroom.
* 1 core: 5.64 – 15.13 ms. **Two of the five exceed the budget**, one of them by nearly 2×.

Nothing about the payload changed between those two runs. The same bytes, the same screen, the same
interpreter — and a budget that looked comfortable stopped being met because the machine had less
parallelism to hide the tails in.

`G4` (cold start, 500 ms) survives: module load p50 moves 39 → 127 ms, still well inside.

**So the headroom on the tail budgets is scheduling, and the gate device becomes more urgent rather
than less.** This does not tell us what a Cortex-A53 will do — it is slower in a *different* way,
per-core rather than in parallelism — but it removes the reading under which the current numbers'
margin makes the device optional. The margin on `p50` is real; the margin on `p95` and `p99` was
four cores.

## What it does not change

Nothing about the gate. ADR-008's decision stands, the rubric's performance cap stands, and no
number here may be cited as device evidence. What this is for is the §4 acquisition decision, which
now has a measurement behind it instead of an intuition.

## Reproducing

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
tools/phase0/scaling-sensitivity.sh          # restarts the AVD at each core count
tools/phase0/scaling-sensitivity.py \
  tools/phase0/results/emulator-4core-sensitivity-not-gate-valid.json \
  tools/phase0/results/emulator-1core-sensitivity-not-gate-valid.json
```

The script restarts the developer's own emulator, twice, and passes `-cores` on the command line
rather than editing the AVD's `config.ini` — so the configuration it was left in is the one it
started in.

#!/usr/bin/env python3
"""Project Dogwood -- the shape of the degradation curve between two Phase 0 runs.

    tools/phase0/scaling-sensitivity.py <faster.json> <slower.json>

Prints one line per measurement: the two values and the ratio. The ratio is the deliverable.

**This compares two emulator configurations and says nothing about a device.** An Android emulator
borrows the development machine's single-core speed, which is the exact dimension a 2022-tier
Cortex-A53 lacks, so neither run is gate evidence and their ratio is not a prediction of one. What
the ratio answers is narrower and still worth having: whether the Phase 0 budgets are carrying a lot
of headroom or a little. A number that barely moves when the runtime loses three quarters of its
parallelism has headroom that is real; one that moves fourfold has headroom that was scheduling
luck. See `adrs/layer-4/ADR-008-gate-device-not-available.md`.
"""
import json
import sys


def walk(node, path=()):
    """Yields (dotted-path, value) for every `p50Ms`/`p95Ms`/`p99Ms` in the tree."""
    if isinstance(node, dict):
        for key, value in node.items():
            if key in ("p50Ms", "p95Ms", "p99Ms", "meanMs") and isinstance(value, (int, float)):
                yield ".".join(path + (key,)), value
            else:
                yield from walk(value, path + (key,))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from walk(value, path + (str(index),))


def main() -> int:
    fast = json.load(open(sys.argv[1]))
    slow = json.load(open(sys.argv[2]))
    fast_values = dict(walk(fast))
    slow_values = dict(walk(slow))

    print(f"{'measurement':<58} {'4 core':>10} {'1 core':>10} {'ratio':>8}")
    print("-" * 90)
    ratios = []
    for key, fast_value in fast_values.items():
        slow_value = slow_values.get(key)
        if slow_value is None or fast_value <= 0:
            continue
        ratio = slow_value / fast_value
        ratios.append((ratio, key))
        print(f"{key:<58} {fast_value:>10.3f} {slow_value:>10.3f} {ratio:>7.2f}x")

    if not ratios:
        print("no comparable measurements", file=sys.stderr)
        return 1
    ratios.sort()
    median = ratios[len(ratios) // 2][0]
    print("-" * 90)
    print(f"median ratio {median:.2f}x · worst {ratios[-1][0]:.2f}x on {ratios[-1][1]}")
    print()
    print("SENSITIVITY ONLY. Two emulator configurations, not a device. See ADR-008.")
    return 0


sys.exit(main())

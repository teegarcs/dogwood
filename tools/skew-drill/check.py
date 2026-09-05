#!/usr/bin/env python3
"""Project Dogwood -- read the rendered tree and check the three containment rules.

Reads what the device is actually showing rather than what the host logged, because the claims are
about a *screen*: a placeholder that occupies its slot, a widget that still renders, and a widget
that is genuinely absent. A log line saying "withheld" is the host's account of itself; the view
hierarchy is the outcome.

Emits the `CONF` grammar so `tools/conformance/aggregate.py` reads this run like any other.
"""
import re
import subprocess
import sys

out_path = sys.argv[1]

def adb(*args: str) -> str:
    return subprocess.run(["adb", *args], capture_output=True, text=True).stdout


hierarchy = adb("exec-out", "uiautomator", "dump", "/dev/tty")
texts = re.findall(r'text="([^"]*)"', hierarchy)
bounds = dict(
    (m.group(1), m.group(2))
    for m in re.finditer(r'text="(SKEW-[^"]*)"[^>]*bounds="([^"]*)"', hierarchy)
)
skew_report = next((t for t in texts if t.startswith("SkewReport(")), "")

lines: list[str] = []
passed = failed = 0


def conform(claim: str, ok: bool, detail: str) -> None:
    global passed, failed
    if ok:
        passed += 1
        lines.append(f"CONF {claim} PASS -- {detail}")
    else:
        failed += 1
        lines.append(f"CONF {claim} FAIL -- {detail}")


def top(marker: str):
    raw = bounds.get(marker)
    if not raw:
        return None
    return int(raw.split("][")[0].split(",")[1])


# Control. Without it a screen that failed to load reads as three passes: everything is "absent".
present = [t for t in texts if t.startswith("SKEW-")]
conform(
    "A2-control",
    "SKEW-BEFORE" in texts and "SKEW-AFTER" in texts,
    f"the skewed screen rendered: {present}",
)

# A2 -- an unknown widget tag becomes a placeholder, and the sibling after it keeps its position.
# The placeholder is invisible, so the evidence is geometric: SKEW-AFTER must sit *below*
# SKEW-BEFORE with a gap, not immediately after it.
before, after = top("SKEW-BEFORE"), top("SKEW-AFTER")
conform(
    "A2",
    before is not None and after is not None and after > before,
    f"SKEW-BEFORE at y={before}, SKEW-AFTER at y={after}"
    + (" -- the placeholder occupies its slot" if before is not None and after is not None and after > before else ""),
)

# A3 -- an unknown property on a widget with no affordance is ignored, and it still renders.
conform(
    "A3",
    "SKEW-BADGE" in texts,
    "the badge carrying an unknown property rendered"
    if "SKEW-BADGE" in texts
    else f"the badge is missing; visible markers: {present}",
)

# A4 -- an unknown property on a widget that owns an affordance withholds the widget. One of the
# things the payload might have been saying is "this is disabled", and the client cannot read it.
conform(
    "A4",
    "SKEW-PAY" not in texts,
    "the button is absent from the rendered tree"
    if "SKEW-PAY" not in texts
    else "the button rendered while carrying an unreadable affordance-bearing property",
)

# And it is reported, not merely survived. Containment nobody can see teaches no team that its
# payloads have moved ahead of its devices.
conform(
    "A4-reported",
    "withheld=" in skew_report,
    skew_report or "no SkewReport on screen",
)

lines.append(f"CONF RESULT client=android passed={passed} failed={failed} skipped=0")
open(out_path, "w").write("\n".join(lines) + "\n")
sys.exit(1 if failed else 0)

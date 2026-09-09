#!/usr/bin/env python3
"""Project Dogwood -- conformance claim B3 on Android: a too-new payload is refused before it runs.

The other half of `check.py`. Both drills serve a payload built against a dictionary this client
does not have; they differ in whether that payload *says so*. `check.py` grades what happens when it
does not -- placeholders, withheld affordances, reported skew, claims A2/A3/A4. This grades what
happens when it does: the client reads the declaration out of the manifest's signed metadata, and
refuses.

**What "before it runs" means on this platform, precisely, because it is not what it means on the
web.** The web host fetches and verifies the manifest itself, so it can refuse without ever creating
a Worker -- claim `B3` there asserts `workerCreated=false`, and nothing of the payload executes at
all. Zipline exposes no manifest-only fetch on mobile: `ZiplineLoader.loadOnce` fetches, verifies,
and evaluates the modules in one call, and `fetchManifestFromNetwork` and `LoadedManifest` are
`internal` (checked against zipline-loader 1.27.0). So the mobile check runs after module evaluation
and before `start`: no entry point is called, no service is bound, nothing composes, and the QuickJS
instance is closed. That is a weaker guarantee than the web's and it is stated rather than blurred.

The two assertions below are that pair, and the second is the one worth having: a client that
refuses and *also* shows the payload's screen has refused nothing.

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


# Read off the screen, not off logcat. The host logs the same sentence, and a log line saying
# "refused" is the host agreeing with itself; the note the user can see is the outcome.
refusal = next((t for t in texts if "refused:" in t), "")
markers = [t for t in texts if t.startswith("SKEW-")]

# The control, and it is the one that makes the rest mean anything. Every assertion here is
# satisfied by an application that failed to start, crashed, or showed a blank activity -- so first
# prove the shell is alive and talking. `note` is always rendered, and reads "starting…" before
# anything happens, so a live shell that has decided nothing is distinguishable from a dead one.
alive = any("refused:" in t or "loaded, restored" in t or "failed:" in t for t in texts)
conform("B3-control", alive, f"the shell reported a decision: {refusal or texts[:8]!r}")

# B3 -- the client refuses, and says which dictionary it is missing rather than "something is wrong".
# Both numbers, because "this client is behind" sends nobody anywhere: the version the payload wants
# and the version this build implements is the difference between a diagnosis and a shrug.
numbers = re.search(r"wants (\d+), this client implements (\d+)", refusal)
conform(
    "B3",
    bool(numbers) and int(numbers.group(1)) > int(numbers.group(2)),
    refusal or f"nothing on screen mentions a refusal; visible: {texts[:10]}",
)

# And nothing of the payload reached the screen. The skewed guest renders four SKEW- markers on the
# About screen; a refusal that still showed them would be a refusal in name only.
conform(
    "B3-absent",
    not markers,
    "no widget from the refused payload is on screen"
    if not markers
    else f"the refused payload rendered anyway: {markers}",
)

lines.append(f"CONF RESULT client=android passed={passed} failed={failed} skipped=0")
open(out_path, "w").write("\n".join(lines) + "\n")
sys.exit(1 if failed else 0)

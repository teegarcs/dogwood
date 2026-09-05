#!/usr/bin/env bash
# Project Dogwood -- assert on VoiceOver's surface without a person holding the phone.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/a11y-drill/run.sh
#
# The platform review left "accessibility interaction on iOS" as the one item marked *needs a
# human*. This closes the mechanical half: every control still reachable, still carrying the right
# traits, and still *operable* through the accessibility layer. It does not close the judgement
# half -- whether the words are the right words, whether the reading order makes sense, typing and
# selection -- and `AccessibilityDrill.kt` says which is which.
#
# Two things have to be true before the drill means anything, and both are done here rather than
# assumed:
#
#   1. **VoiceOver must be running.** Compose Multiplatform builds its accessibility tree only
#      while an assistive technology is active; with it off the walk finds the rendering view and
#      nothing under it, and every assertion would fail for one uninteresting reason. The drill
#      refuses to report in that case, and this script turns VoiceOver on first.
#   2. **The development server must be serving the guest**, because the screen the drill asserts
#      on is composed in the sandbox. That is the whole point: the labels it looks for were written
#      in guest code and crossed the wire.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LOG="${1:-$HERE/build/last-run.log}"
mkdir -p "$(dirname "$LOG")"
cd "$HERE/../../engine"

command -v xcrun >/dev/null || { echo "xcrun not found; this drill needs Xcode" >&2; exit 1; }
xcrun simctl list devices booted | grep -q "(Booted)" || {
  echo "no booted simulator; boot one first (xcrun simctl boot <device>)" >&2; exit 1; }

echo "==> enabling VoiceOver on the booted simulator"
# `defaults write` alone is not enough: the accessibility daemon caches these, and a process that
# has already read them keeps the old answer. The `notifyutil -p` posts are what make it re-read,
# and they are the reason this works on an already-running simulator.
xcrun simctl spawn booted defaults write com.apple.Accessibility ApplicationAccessibilityEnabled -int 1
xcrun simctl spawn booted defaults write com.apple.Accessibility VoiceOverTouchEnabled -int 1
xcrun simctl spawn booted notifyutil -p com.apple.accessibility.cache.app.ax
xcrun simctl spawn booted notifyutil -p com.apple.accessibility.cache.ax

echo "==> building and installing"
./gradlew :samples:slice-ios:iosApp --console=plain -q
xcrun simctl terminate booted dev.dogwood.slice.ios 2>/dev/null || true
xcrun simctl install booted samples/slice-ios/build/DogwoodSlice.app

echo "==> running the drill"
# Written raw, not piped through `tr`: a pipe would block-buffer and leave the log empty until the
# run ended, and the loop below reads it while it fills. The carriage returns `--console-pty` adds
# are stripped at parse time instead -- see the `tr -d` on each read. An anchored pattern that
# forgets them never matches, which once made a passing check look like a missing one.
xcrun simctl launch --console-pty booted dev.dogwood.slice.ios --dogwood-a11y > "$LOG" 2>&1 &
launcher=$!
# Bounded by the clock rather than by the launcher's exit: `--console-pty` stays attached to a
# running application, so waiting for it would wait forever.
for _ in $(seq 1 60); do
  tr -d '\r' < "$LOG" 2>/dev/null | grep -q "^A11Y DONE\|^A11Y REFUSED" && break
  sleep 2
done
kill "$launcher" 2>/dev/null || true
pkill -f "simctl launch --console-pty booted dev.dogwood.slice.ios" 2>/dev/null || true
xcrun simctl terminate booted dev.dogwood.slice.ios 2>/dev/null || true

tr -d '\r' < "$LOG" | grep -E "^A11Y (PASS|FAIL|NOTE|REFUSED|RESULT)" || true

if tr -d '\r' < "$LOG" | grep -q "^A11Y REFUSED"; then
  echo
  echo "REFUSED -- the drill could not run; see $LOG" >&2
  exit 2
fi
# The conformance grammar goes to its own file, so `tools/conformance/aggregate.py` reads this
# client exactly as it reads the other three.
CONF_OUT="${CONF_OUT:-$HERE/../conformance/build/ios.conf}"
mkdir -p "$(dirname "$CONF_OUT")"
tr -d '\r' < "$LOG" | grep -E "^CONF " > "$CONF_OUT" || true

# The network-policy claims come from an instrumented Gradle test rather than from the in-application
# drill, and they need the witness server. Appended to the same client file so the aggregator still
# reads one run per client.
if command -v python3 >/dev/null; then
  python3 "$HERE/../conformance/policy-server.py" "${POLICY_PORT:-8123}" >/dev/null 2>&1 &
  witness=$!
  sleep 1
  ./gradlew :dogwood-host:iosSimulatorArm64Test --console=plain >/dev/null 2>&1 || true
  kill "$witness" 2>/dev/null || true
  python3 - "$CONF_OUT" <<'EXTRACT'
import glob, html, re, sys
out = sys.argv[1]
claims = []
for path in glob.glob("dogwood-host/build/test-results/iosSimulatorArm64Test/*NetworkPolicy*.xml"):
    text = html.unescape(open(path).read())
    claims += [m.group(0) for m in re.finditer(r"CONF [A-G][0-9][^\n<]*", text)]
if claims:
    with open(out, "a") as f:
        f.write("\n".join(claims) + "\n")
EXTRACT
fi

failures=$(tr -d '\r' < "$LOG" | sed -n 's/^A11Y DONE failures=\([0-9-]*\)$/\1/p' | tail -1)
if [ -z "$failures" ]; then
  echo
  echo "the drill never reported; see $LOG" >&2
  exit 1
fi
echo
if [ "$failures" = "0" ]; then
  echo "PASS -- full log in $LOG"
else
  echo "FAIL -- $failures failed checks; full log in $LOG" >&2
  exit 1
fi

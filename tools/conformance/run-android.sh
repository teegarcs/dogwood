#!/usr/bin/env bash
# Project Dogwood -- conformance claims D1-D7 on Android.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/conformance/run-android.sh
#
# The drill itself is an instrumented test (`AccessibilityConformanceTest`), because on Android the
# faithful reader of the accessibility tree is `UiAutomation` -- which *is* an accessibility
# service, and therefore sees what TalkBack sees, out of process. That is the opposite of iOS,
# where no such access exists and the drill has to live inside the application with VoiceOver
# switched on. Same claims, different instrument; see plans/conformance.md.
#
# This script exists because an instrumented test's standard output reaches neither the Gradle
# console nor the result XML, so the run would otherwise produce a verdict with no lines behind it.
# The test logs each `CONF` line to logcat and this scrapes them into the aggregator's input.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/build"
# Resolved before the `cd` below, so a relative path means what the caller meant.
OUT="$(cd "$(dirname "${1:-$HERE/build/android.conf}")" 2>/dev/null && pwd)/$(basename "${1:-$HERE/build/android.conf}")"
cd "$HERE/../../engine"

command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "no device or emulator attached" >&2; exit 1; }

# Two prerequisites, both checked rather than assumed. The accessibility drill's screen is composed
# in the sandbox, so the payload has to be being served -- without it the drill fails on an empty
# screen, which looks like an accessibility defect and is not one. The network-policy drill needs
# its witness server, which this script starts.
curl -fs -m 5 http://localhost:8080/manifest.zipline.json >/dev/null 2>&1 || {
  echo "the guest is not being served on :8080 --" >&2
  echo "  ./gradlew :samples:slice-guest:serveProductionWebpackZipline" >&2
  exit 2
}

# The witness for the network-policy claims. A refusal is proved by the absence of a request here,
# which is a stronger claim than the client reporting that it refused.
python3 "$HERE/policy-server.py" "${POLICY_PORT:-8123}" >/dev/null 2>&1 &
witness=$!
trap 'kill $witness 2>/dev/null || true' EXIT
sleep 1

adb logcat -c
set +e
./gradlew :samples:slice-android:connectedDebugAndroidTest --console=plain > "$HERE/build/gradle.log" 2>&1
gradle_status=$?
set -e

adb logcat -d -s DogwoodConf:I 2>/dev/null | grep -oE "CONF .*" > "$OUT" || true

# The RESULT line is synthesised here rather than emitted by a test, because this client runs more
# than one conformance test class and each would otherwise declare its own -- the aggregator reads
# one run per client, and two RESULT lines make the second silently win.
p=$(grep -cE "^CONF [A-Z][0-9]+(-[a-z]+)* PASS" "$OUT" || true)
f=$(grep -cE "^CONF [A-Z][0-9]+(-[a-z]+)* FAIL" "$OUT" || true)
s=$(grep -cE "^CONF [A-Z][0-9]+(-[a-z]+)* SKIP" "$OUT" || true)
echo "CONF RESULT client=android passed=$p failed=$f skipped=$s" >> "$OUT"
grep -E "^CONF (D|A|B|C|E|F|G)[0-9]" "$OUT" || true
grep -E "^CONF RESULT" "$OUT" || {
  echo "the drill never reported; see $HERE/build/gradle.log" >&2
  exit 1
}

failed=$(sed -n 's/^CONF RESULT .*failed=\([0-9]*\).*/\1/p' "$OUT" | tail -1)
echo
if [ "${failed:-1}" = "0" ] && [ "$gradle_status" = "0" ]; then
  echo "PASS -- claims in $OUT"
else
  echo "FAIL -- $failed failed claims; see $OUT" >&2
  exit 1
fi

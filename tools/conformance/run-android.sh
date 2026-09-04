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

# The screen under test is composed in the sandbox, so the payload has to be being served. This is
# checked rather than assumed: without it the drill fails on an empty screen, which looks like an
# accessibility defect and is not one.
curl -fs -m 5 http://localhost:8080/manifest.zipline.json >/dev/null 2>&1 || {
  echo "the guest is not being served on :8080 --" >&2
  echo "  ./gradlew :samples:slice-guest:serveProductionWebpackZipline" >&2
  exit 2
}

adb logcat -c
set +e
./gradlew :samples:slice-android:connectedDebugAndroidTest --console=plain > "$HERE/build/gradle.log" 2>&1
gradle_status=$?
set -e

adb logcat -d -s DogwoodConf:I 2>/dev/null | grep -oE "CONF .*" > "$OUT" || true
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

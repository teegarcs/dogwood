#!/usr/bin/env bash
# Project Dogwood -- the generated Material 3 tier, operated through VoiceOver on a simulator.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   ./gradlew :samples:slice-guest:serveProductionWebpackZipline   # in another shell
#   tools/a11y-drill/run-material.sh
#
# The sibling of `run.sh`, which grades the D and J families on the Diagnostics screen. Everything
# that script's header says about the instrument applies here: the drill runs inside the
# application because `xcrun simctl` cannot dump a hierarchy, and it needs VoiceOver actually
# running because Compose Multiplatform builds no accessibility tree without one.
#
# What this one points the instrument at is `MaterialScreen.kt`, a screen composed entirely from
# bindings the generator wrote by reading Material 3's own sources. Claims M1-M7; see
# `samples/slice-ios/src/iosMain/kotlin/dev/dogwood/slice/ios/MaterialDrill.kt`.
set -euo pipefail

# Gradle refuses a Java it does not support, and reports it by printing the version and nothing
# else -- "25.0.2" as the entire "what went wrong". That is indistinguishable from a crash unless
# you already know, so it is checked here with a sentence instead. The header says to export
# JAVA_HOME; this is what happens when somebody does not.
java_version="$("${JAVA_HOME:-/usr}/bin/java" -version 2>&1 | head -1)"
case "$java_version" in
  *\"21*) ;;
  *) echo "this build needs a Java 21 toolchain; found: $java_version" >&2
     echo "  export JAVA_HOME=/opt/homebrew/opt/openjdk@21" >&2
     exit 2 ;;
esac
HERE="$(cd "$(dirname "$0")" && pwd)"
LOG="${1:-$HERE/build/material.log}"
mkdir -p "$(dirname "$LOG")"
cd "$HERE/../../engine"

command -v xcrun >/dev/null || { echo "xcrun not found; this drill needs Xcode" >&2; exit 1; }
xcrun simctl list devices booted | grep -q "(Booted)" || {
  echo "no booted simulator; boot one first (xcrun simctl boot <device>)" >&2; exit 1; }

# The screen is composed in the sandbox, so the payload has to be being served. Checked rather than
# assumed: without it the drill fails on an empty screen, which looks like a defect in the tier and
# is not one.
curl -fs -m 5 http://localhost:8080/manifest.zipline.json >/dev/null 2>&1 || {
  echo "no payload at http://localhost:8080; run :samples:slice-guest:serveProductionWebpackZipline" >&2
  exit 1
}

echo "==> enabling VoiceOver on the booted simulator"
xcrun simctl spawn booted defaults write com.apple.Accessibility ApplicationAccessibilityEnabled -int 1
xcrun simctl spawn booted defaults write com.apple.Accessibility VoiceOverTouchEnabled -int 1
xcrun simctl spawn booted notifyutil -p com.apple.accessibility.cache.app.ax
xcrun simctl spawn booted notifyutil -p com.apple.accessibility.cache.ax

# `--max-workers=2` is not a preference. An iOS framework link is the memory-hungriest task in this
# build, and on a machine already running an emulator and a simulator the Gradle daemon dies part
# way through -- reporting the Kotlin/Native version string as its error message, which is as
# unhelpful as it sounds. Two workers finishes.
echo "==> building and installing"
./gradlew :samples:slice-ios:iosApp --console=plain -q --max-workers=2
xcrun simctl terminate booted dev.dogwood.slice.ios 2>/dev/null || true
xcrun simctl install booted samples/slice-ios/build/DogwoodSlice.app

echo "==> running the drill"
xcrun simctl launch --console-pty booted dev.dogwood.slice.ios --dogwood-material > "$LOG" 2>&1 &
launcher=$!
# Generous: the drill walks ten sections and waits on a consequence after each activation.
for _ in $(seq 1 240); do
  tr -d '\r' < "$LOG" 2>/dev/null | grep -q "^MATERIAL DONE\|^CONF REFUSED" && break
  sleep 2
done
kill "$launcher" 2>/dev/null || true
pkill -f "simctl launch --console-pty booted dev.dogwood.slice.ios" 2>/dev/null || true
xcrun simctl terminate booted dev.dogwood.slice.ios 2>/dev/null || true

tr -d '\r' < "$LOG" | grep -E "^(CONF|A11Y) " || true

if tr -d '\r' < "$LOG" | grep -q "^CONF REFUSED"; then
  echo "the drill refused to report; see $LOG" >&2
  exit 2
fi
failures="$(tr -d '\r' < "$LOG" | grep -c "^CONF [A-Z0-9-]* FAIL" || true)"
[ "$failures" = "0" ]

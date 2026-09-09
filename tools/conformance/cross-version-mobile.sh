#!/usr/bin/env bash
# Project Dogwood -- today's mobile host runs an older payload.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/conformance/cross-version-mobile.sh android
#   tools/conformance/cross-version-mobile.sh ios
#
# `cross-version.sh`'s twin on the clients a product actually ships. Same fixture, same two claims:
#
#   K1  a payload from an earlier toolchain LOADS and VERIFIES against keys in this binary
#   K2  ...and RENDERS, which loading does not imply -- a guest that loads and then fails to compose
#       is the failure this pairing actually produces
#
# Why it needed engine work rather than just a script: the drill has to point an **installed** build
# at a payload the drill controls, and until now only the desktop sample could be told where to look
# (`-Ddogwood.manifest`, added when the reference-server check needed it). Android now takes
# `--es manifest`, iOS takes `--dogwood-manifest`. Without that, `K1`/`K2` could only ever be graded
# on the one client that happened to have the switch, and "hosts first, payloads after the fleet"
# stays a policy rather than a test (adoption audit A6).
#
# **The client is built from current sources and the payload is not rebuilt.** That is the entire
# point, and it is the thing easiest to get wrong: rebuilding the guest would test today's toolchain
# against itself, which is the pairing that already works.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/build"
CLIENT="${1:-android}"
OUT="${2:-$HERE/build/cross-version-$CLIENT.conf}"
PORT="${PORT:-8474}"
FIXTURE="$(ls -d "$HERE"/fixtures/payload-* 2>/dev/null | sort | tail -1)"
LOG="$HERE/build/cross-version-$CLIENT.log"

[ -n "$FIXTURE" ] || { echo "no frozen payload in $HERE/fixtures" >&2; exit 1; }
echo "==> serving $(basename "$FIXTURE") to a $CLIENT client built from current sources"

# `0.0.0.0`, not `127.0.0.1`: the Android emulator reaches the development machine at 10.0.2.2,
# which is a different interface. The desktop drill can bind to loopback because it runs in the same
# process namespace; this one cannot.
python3 -m http.server "$PORT" --directory "$FIXTURE" --bind 0.0.0.0 >/dev/null 2>&1 &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT
for _ in $(seq 1 40); do
  curl -fs -m 1 -o /dev/null "http://127.0.0.1:$PORT/manifest.zipline.json" && break
  sleep 0.25
done

lines=()
passed=0; failed=0
conform() { # id, condition(1|0), detail
  if [ "$2" = "1" ]; then passed=$((passed+1)); lines+=("CONF $1 PASS -- $3")
  else failed=$((failed+1)); lines+=("CONF $1 FAIL -- $3"); fi
}

case "$CLIENT" in
  android)
    command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }
    adb get-state >/dev/null 2>&1 || { echo "no device or emulator attached" >&2; exit 1; }
    ( cd "$HERE/../../engine" && ./gradlew :samples:slice-android:installRelease --console=plain -q ) || exit 1
    # A cold cache. Zipline serves modules it already has, so a warm cache makes a null run look
    # green -- the trap the reference-server check fell into once already.
    adb shell pm clear dev.dogwood.slice.android >/dev/null 2>&1 || true
    adb logcat -c
    # `TabsActivity`, the shell sample a product is told to copy, and the one whose swap report
    # names the version and the verifying key -- which is what `K1` is about.
    adb shell am start -n dev.dogwood.slice.android/.TabsActivity \
      --es entry explore \
      --es manifest "http://10.0.2.2:$PORT/manifest.zipline.json" >/dev/null
    sleep 25
    adb logcat -d > "$LOG" 2>/dev/null
    ;;
  ios)
    command -v xcrun >/dev/null || { echo "xcrun not found; this drill needs Xcode" >&2; exit 1; }
    xcrun simctl list devices booted | grep -q "(Booted)" || {
      echo "no booted simulator; boot one first" >&2; exit 1; }
    ( cd "$HERE/../../engine" && ./gradlew :samples:slice-ios:iosApp --console=plain -q ) || exit 1
    xcrun simctl terminate booted dev.dogwood.slice.ios >/dev/null 2>&1 || true
    xcrun simctl uninstall booted dev.dogwood.slice.ios >/dev/null 2>&1 || true
    xcrun simctl install booted "$HERE/../../engine/samples/slice-ios/build/DogwoodSlice.app" || exit 1
    xcrun simctl launch --console-pty booted dev.dogwood.slice.ios \
      --dogwood-manifest "http://localhost:$PORT/manifest.zipline.json" > "$LOG" 2>&1 &
    launcher=$!
    # Bounded by the clock: `--console-pty` stays attached to a running application, so waiting for
    # the launcher to exit would wait forever.
    for _ in $(seq 1 30); do
      tr -d '\r' < "$LOG" 2>/dev/null | grep -q "loaded version\|could not load" && break
      sleep 2
    done
    sleep 4
    kill "$launcher" 2>/dev/null || true
    pkill -f "simctl launch --console-pty booted dev.dogwood.slice.ios" 2>/dev/null || true
    tr -d '\r' < "$LOG" > "$LOG.clean" && mv "$LOG.clean" "$LOG"
    ;;
  *) echo "unknown client '$CLIENT'; expected android or ios" >&2; exit 2 ;;
esac

# K1 -- it loads and VERIFIES. The signature is the half that would break first on a protocol
# change, and it is checked against keys committed with the fixture rather than regenerated.
verified=$(grep -c "loaded version .*verified by dogwood-development" "$LOG" || true)
conform "K1" "$([ "$verified" -ge 1 ] && echo 1 || echo 0)" \
  "$(grep -m1 'loaded version' "$LOG" | tail -c 160 || echo 'the host never reported a load')"

# K2 -- it RENDERS. Loading proves the delivery path only; the guest's own log line is what shows a
# composition happened.
composed=$(grep -c "explore: loaded .* destinations" "$LOG" || true)
conform "K2" "$([ "$composed" -ge 1 ] && echo 1 || echo 0)" \
  "$(grep -m1 'explore: loaded' "$LOG" | tail -c 160 || echo 'the guest never composed its screen')"

# The control. A run where the application never started satisfies nothing above but also produces
# no evidence of having tried, and the two look identical in a summary.
started=$(grep -cE "loaded version|could not load" "$LOG" || true)
conform "K-control" "$([ "$started" -ge 1 ] && echo 1 || echo 0)" "the host reached its delivery path"

printf '%s\n' "${lines[@]}" > "$OUT"
printf '%s\n' "${lines[@]}"
echo "CONF RESULT client=$CLIENT passed=$passed failed=$failed skipped=0" >> "$OUT"
echo
if [ "$failed" = "0" ]; then
  echo "PASS -- a payload from $(basename "$FIXTURE" | sed 's/payload-//') runs on a $CLIENT host built today"
else
  echo "FAIL -- see $LOG" >&2; exit 1
fi

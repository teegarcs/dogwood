#!/usr/bin/env bash
# Project Dogwood -- claim `B6` on the mobile clients: a host without the generated Material 3 tier
# refuses a payload that declares it, before any guest code runs.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   ./gradlew :samples:slice-guest:serveProductionWebpackZipline   # in another shell
#   tools/skew-drill/run-material-preflight.sh android|ios
#
# The web already grades `B6` (`tools/conformance/web_material.py`), and this is not the same claim
# twice: the web host verifies its sidecar and compares versions in `WebDelivery`, while a mobile
# host reads the vector out of Zipline's *signed* manifest metadata and compares it in
# `DogwoodDelivery` before `start` (ADR-061). Two implementations, one promise, so two gradings.
#
# **What makes this drill honest is the control.** Every assertion below is satisfied by a client
# that was simply broken, so the same payload is run first against the ordinary build -- which has
# the tier and must render -- and only then against a client built without it.
#
# The two clients differ in how they leave the tier out, and each follows its platform's grain:
# Android takes a build flag (`-PdogwoodMaterial3=false`) because an Android host is reinstalled per
# drill anyway; iOS takes a launch argument (`--dogwood-no-material3`) because `xcrun simctl launch`
# is how anything reaches it and its other drills are already launch arguments. What is modelled --
# a client whose registry has never heard of the segment -- is identical.
set -uo pipefail

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
CLIENT="${1:-android}"
mkdir -p "$HERE/build"
OUT="$HERE/build/material-preflight-$CLIENT.conf"
: > "$OUT"
cd "$HERE/../../engine"

passed=0
failed=0
# `ok` is 1 for a pass, so every call site reads as the sentence it grades rather than as an exit
# status. The first version took 0 for a pass and the control inverted itself: a client that
# refused was reported as the control succeeding.
conform() {
  local id="$1" ok="$2" detail="$3"
  if [ "$ok" = "1" ]; then
    passed=$((passed + 1)); echo "CONF $id PASS -- $detail" | tee -a "$OUT"
  else
    failed=$((failed + 1)); echo "CONF $id FAIL -- $detail" | tee -a "$OUT"
  fi
}

# The payload has to be being served, and it has to declare the tier -- otherwise there is nothing
# for a client to refuse and the drill would pass by testing nothing.
declared="$(curl -fs -m 5 http://localhost:8080/manifest.zipline.json | python3 -c \
  'import json,sys; print(json.load(sys.stdin).get("metadata", {}).get("dogwood.segments", ""))' 2>/dev/null)"
case "$declared" in
  *androidx.material3:*) echo "==> the served manifest declares [$declared]" ;;
  *) echo "the served payload declares no Material 3 tier; nothing to refuse" >&2; exit 2 ;;
esac

case "$CLIENT" in
  android)
    command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }
    adb get-state >/dev/null 2>&1 || { echo "no device or emulator attached" >&2; exit 1; }
    PACKAGE=dev.dogwood.slice.android

    run_and_read() {
      adb shell am force-stop "$PACKAGE" >/dev/null 2>&1
      adb logcat -c >/dev/null 2>&1
      adb shell am start -n "$PACKAGE/.TabsActivity" --es entry material >/dev/null 2>&1
      # Polled to a decision rather than slept at. A fixed wait long enough on a warm emulator is
      # not long enough on the first launch after an install, when the runtime is still compiling
      # the application -- and the control failed that way, reporting "did not load the payload"
      # about a client that loaded it a few seconds later.
      for _ in $(seq 1 30); do
        if adb logcat -d 2>/dev/null | grep -qE "loaded version|refused|does not implement"; then break; fi
        sleep 2
      done
      sleep 2
      # Filtered to this application's own tags, not the tail of everything. An emulator's logcat
      # carries hundreds of lines a second from Google Play Services, and a `tail -400` of that
      # does not reach back to the line the drill is grading -- which reported the control as
      # "did not load the payload" about a client that had loaded it four seconds earlier.
      adb logcat -d 2>/dev/null | grep -iE "dogwood|refused" | tail -80
    }

    echo "==> the control: the ordinary client, which has the tier"
    ./gradlew :samples:slice-android:installDebug --console=plain -q --max-workers=2 || exit 1
    control="$(run_and_read)"
    # The control asserts a *positive*: the client loaded the release. "It did not refuse" is also
    # true of a client that never reached the server, which is the reading that makes a control
    # worthless.
    if echo "$control" | grep -q "loaded version" && ! echo "$control" | grep -q "androidx.material3"; then
      control_ok=1
    else
      control_ok=0
    fi
    conform "B6-control" "$control_ok" \
      "$(echo "$control" | grep -o 'loaded version[^"]\{0,60\}' | head -1 || echo 'the ordinary client did not load the payload')"

    echo "==> the client built WITHOUT the tier"
    ./gradlew :samples:slice-android:installDebug -PdogwoodMaterial3=false --console=plain -q --max-workers=2 || exit 1
    without="$(run_and_read)"
    if echo "$without" | grep -q "androidx.material3" && ! echo "$without" | grep -q "loaded version"; then
      named=1
    else
      named=0
    fi
    conform "B6" "$named" \
      "$(echo "$without" | grep -oE '(refused|does not have|does not implement)[^"]{0,140}' | head -1 | tr -d '\n')"

    echo "==> restoring the ordinary client"
    ./gradlew :samples:slice-android:installDebug --console=plain -q --max-workers=2 || true
    ;;

  ios)
    command -v xcrun >/dev/null || { echo "xcrun not found; this drill needs Xcode" >&2; exit 1; }
    xcrun simctl list devices booted | grep -q "(Booted)" || {
      echo "no booted simulator; boot one first (xcrun simctl boot <device>)" >&2; exit 1; }
    APP=dev.dogwood.slice.ios

    ./gradlew :samples:slice-ios:iosApp --console=plain -q --max-workers=2 || exit 1
    xcrun simctl terminate booted "$APP" >/dev/null 2>&1 || true
    xcrun simctl install booted samples/slice-ios/build/DogwoodSlice.app || exit 1

    run_and_read() {
      local log="$HERE/build/material-preflight-ios-$1.log"
      xcrun simctl terminate booted "$APP" >/dev/null 2>&1 || true
      shift
      xcrun simctl launch --console-pty booted "$APP" "$@" > "$log" 2>&1 &
      local launcher=$!
      sleep 20
      kill "$launcher" 2>/dev/null || true
      xcrun simctl terminate booted "$APP" >/dev/null 2>&1 || true
      tr -d '\r' < "$log"
    }

    echo "==> the control: the ordinary client, which has the tier"
    control="$(run_and_read control)"
    if echo "$control" | grep -q "loaded version" && ! echo "$control" | grep -q "androidx.material3"; then
      control_ok=1
    else
      control_ok=0
    fi
    conform "B6-control" "$control_ok" \
      "$(echo "$control" | grep -o 'loaded version[^"]\{0,60\}' | head -1 || echo 'the ordinary client did not load the payload')"

    echo "==> the client launched WITHOUT the tier"
    without="$(run_and_read without --dogwood-no-material3)"
    if echo "$without" | grep -q "androidx.material3" && ! echo "$without" | grep -q "loaded version"; then
      named=1
    else
      named=0
    fi
    conform "B6" "$named" \
      "$(echo "$without" | grep -oE '(refused|does not have|does not implement)[^"]{0,140}' | head -1 | tr -d '\n')"
    ;;

  *) echo "usage: $0 android|ios" >&2; exit 2 ;;
esac

echo "CONF RESULT client=$CLIENT passed=$passed failed=$failed skipped=0" | tee -a "$OUT"
[ "$failed" = "0" ]

#!/usr/bin/env bash
# Project Dogwood -- every Android drill, in order, accumulating rather than stopping.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/conformance/android-suite.sh <conf-dir> <status-file> [log-dir]
#
# **Why this is a file and not a `script:` block**, because the reason is not obvious and cost a
# nightly run to find. `reactivecircus/android-emulator-runner` executes its `script` input **one
# line at a time**, each line in its own `sh -c`. The first tier-C run showed it plainly:
#
#     [command]/usr/bin/sh -c set +e
#     [command]/usr/bin/sh -c status="$RUNNER_TEMP/status"
#     [command]/usr/bin/sh -c conf="$RUNNER_TEMP/conf"
#
# Nothing a line assigns survives into the next one. So `$conf` was empty on every line that used
# it, `"$conf/android-a11y.conf"` became `/android-a11y.conf`, and `run-android.sh` resolved that to
# `//android-a11y.conf` and got "Permission denied" writing to the root of the filesystem. The
# emulator was up and healthy, the instrumented tests ran for seven minutes and passed, and every
# result was thrown at a path nothing could write. `set +e` did not survive either, so the first
# non-zero line ended the block and the remaining drills never ran at all.
#
# A file is one shell. Variables persist, `set +e` means what it says, and -- the part that matters
# more -- a person can run this sequence on a development machine, which is the rule `tier-c.yml`
# states in its own header and could not follow while the sequence lived in YAML.
#
# Each drill's exit code is appended to the status file rather than failing here, because one red
# drill must not take the rest of the matrix down with it. The caller reads that file and decides.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

CONF="${1:?usage: android-suite.sh <conf-dir> <status-file> [log-dir]}"
STATUS="${2:?usage: android-suite.sh <conf-dir> <status-file> [log-dir]}"
LOGS="${3:-}"

mkdir -p "$CONF"
[ -n "$LOGS" ] && mkdir -p "$LOGS"
: > "$STATUS"

command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }
adb wait-for-device
adb devices

# **Hide the system's "isn't responding" dialogs, and tap one away if it got there first.**
#
# Six nightly runs between 2026-09-22 and 2026-10-07 failed every accessibility claim with the same
# two announcements on screen -- "Close app" and "Wait" -- under the title "Pixel Launcher isn't
# responding". The launcher, not this application: a hosted emulator on two shared cores stalls its
# own home screen while the first drill is installing and launching, and the application-not-
# responding dialog that follows sits above every window, including ours. The drills then read a
# screen with nothing of theirs on it and reported five claims that had not failed, and the skew
# and pre-flight drills behind them read the same dialog and failed too.
#
# `hide_error_dialogs` is the platform's own switch for exactly this; instrumentation runners set
# it for the same reason. Nothing here sets it on a development machine's device, because a person
# should see a crash dialog -- this script is the hosted sequence. The tap below handles a dialog
# that was already up before the setting took effect; it is run again before each drill, because
# the setting hides dialogs that appear from now on and says nothing about one that is showing.
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
dismiss_system_dialog() {
  local dump at
  dump="$(adb shell uiautomator dump /sdcard/dogwood-dialog.xml >/dev/null 2>&1 \
    && adb shell cat /sdcard/dogwood-dialog.xml 2>/dev/null)" || return 0
  case "$dump" in
    *"isn't responding"*|*"keeps stopping"*|*"has stopped"*) ;;
    *) return 0 ;;
  esac
  at="$(printf '%s' "$dump" | python3 -c '
import re, sys
xml = sys.stdin.read()
for label in ("Wait", "Close app", "Open app again", "Close"):
    m = re.search(r"text=\"%s\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"" % re.escape(label), xml)
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
')"
  if [ -n "$at" ]; then
    echo "    (a system dialog was on screen before the drill; tapping it away at $at)" >&2
    # shellcheck disable=SC2086 -- two numbers, deliberately split
    adb shell input tap $at
    sleep 1
  fi
}
dismiss_system_dialog

record() { # exit-code, description
  echo "$1 $2" >> "$STATUS"
  # A red drill on a machine nobody can look at is a red drill nobody can diagnose. The first run
  # here failed three drills with empty screens and one system message -- "Pixel Launcher isn't
  # responding" -- and nothing said whether the application had crashed, never drawn, or drawn
  # something else. So a failure takes the screen and the log with it.
  if [ "$1" != "0" ] && [ -n "$LOGS" ]; then
    local slug
    slug="$(printf '%s' "$2" | tr -cs 'a-zA-Z0-9' '-' | cut -c1-40)"
    adb shell uiautomator dump /sdcard/dogwood-failure.xml >/dev/null 2>&1 &&
      adb shell cat /sdcard/dogwood-failure.xml > "$LOGS/screen-$slug.xml" 2>/dev/null
    adb logcat -d -t 2000 > "$LOGS/logcat-$slug.txt" 2>/dev/null
    adb shell dumpsys activity activities 2>/dev/null | head -60 > "$LOGS/activities-$slug.txt"
    echo "    (failed; screen and log captured as $slug)" >&2
  fi
}

cd "$ROOT"

dismiss_system_dialog
"$HERE/run-android.sh" "$CONF/android-a11y.conf"
record "$?" "android accessibility (tools/conformance/run-android.sh)"

dismiss_system_dialog
"$ROOT/tools/skew-drill/run.sh"
record "$?" "android skew (tools/skew-drill/run.sh)"
cp "$ROOT/tools/skew-drill/build/skew.conf" "$CONF/android-skew.conf" 2>/dev/null

dismiss_system_dialog
"$ROOT/tools/skew-drill/run-preflight.sh"
record "$?" "android pre-flight (tools/skew-drill/run-preflight.sh)"
cp "$ROOT/tools/skew-drill/build/preflight.conf" "$CONF/android-preflight.conf" 2>/dev/null

# `B6`: a client built without the generated Material 3 tier, meeting a payload that declares it.
# Not `B3` again -- that is a client meeting a dictionary *version* it does not have, this is a
# client meeting a whole *segment* it has never heard of. It reinstalls the application twice, with
# `-PdogwoodMaterial3=false` and then without, so it runs after the drills that assume the ordinary
# build is installed.
dismiss_system_dialog
"$ROOT/tools/skew-drill/run-material-preflight.sh" android
record "$?" "android material pre-flight (run-material-preflight.sh)"
cp "$ROOT/tools/skew-drill/build/material-preflight-android.conf" \
  "$CONF/android-materialpreflight.conf" 2>/dev/null

dismiss_system_dialog
"$HERE/cross-version-mobile.sh" android "$CONF/android-crossversion.conf"
record "$?" "android cross-version (cross-version-mobile.sh)"

dismiss_system_dialog
"$ROOT/tools/reference-server/quarantine-drill.sh" "$CONF/android-quarantine.conf"
record "$?" "android quarantine (quarantine-drill.sh)"

if [ -n "$LOGS" ]; then
  cp "$ROOT"/tools/skew-drill/build/*.log "$LOGS/" 2>/dev/null
  cp "$ROOT"/tools/conformance/build/*.log "$LOGS/" 2>/dev/null
fi

# Always zero. The verdict is the status file, read by whoever called this.
echo
echo "==> what each drill exited with"
cat "$STATUS"
exit 0

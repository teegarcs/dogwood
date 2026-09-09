#!/usr/bin/env bash
# Project Dogwood -- a bad publish, quarantined on a device, and recovered.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/reference-server/quarantine-drill.sh
#
# Claim `H2`: a release that fails to start repeatedly is quarantined, and the client stops running
# it. Until this drill, every test of that had **simulated** the failure -- calling `starting`
# without `succeeded` -- which tests the bookkeeping and not the mechanism. `plans/engineering-
# backlog.md` V3 asked for the device half, and the reason it had not been done is that it needs
# three things at once: a payload that genuinely fails on launch, a server that can publish and roll
# back, and a client that can be pointed at that server.
#
# All three now exist:
#
#   * `CrashOnLaunchScreen` throws from the composable body, before the host mounts anything. That
#     matters more than it sounds: `CrashScreen` -- the other fixture -- renders and *then* throws
#     from an effect, and the guard correctly counts that a success, because quarantining a payload
#     that worked and then broke would strand a fleet for a bug a user might never hit.
#   * `tools/reference-server/server.py` publishes, rolls out, and resumes.
#   * The Android client takes `--es manifest`, added for the cross-version drill.
#
# The shape is a real incident, in order: publish a good release, prove it runs; publish a bad one
# to 100%; watch two launches burn the guard's attempts and the third be refused **without running
# the payload**; then `resume` the good version and watch the client recover on its own -- which is
# the operational point of ADR-049. A client that quarantines and never comes back has traded an
# outage for a longer one.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ENGINE="$HERE/../../engine"
ROOT="$(mktemp -d)"
PORT="${PORT:-8472}"
OUT="${1:-$HERE/build/quarantine.conf}"
mkdir -p "$HERE/build"
LOG="$HERE/build/quarantine.log"
: > "$LOG"

passed=0; failed=0
lines=()
conform() { # id, condition(1|0), detail
  # Built into a variable and echoed, rather than read back out of the array. `${lines[-1]}` is a
  # bash 4 feature and macOS ships bash 3.2, where it is a fatal "bad array subscript" -- which took
  # this drill down mid-run on its first attempt, after the Android install.
  local line
  if [ "$2" = "1" ]; then passed=$((passed+1)); line="CONF $1 PASS -- $3"
  else failed=$((failed+1)); line="CONF $1 FAIL -- $3"; fi
  lines+=("$line")
  echo "$line"
}

cleanup() { kill "${server:-0}" 2>/dev/null || true; rm -rf "$ROOT"; }
trap cleanup EXIT

command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "no device or emulator attached" >&2; exit 1; }

# Each release is BUILT with its version, never stamped afterwards.
#
# The first attempt at this drill copied one build twice and rewrote `version` in the manifest, which
# invalidates the Ed25519 signature over it -- every launch failed with "manifest signature for key
# dogwood-development did not verify". That is the signature doing precisely its job, and it is why
# `-PdogwoodVersion` exists: a release identity is a build input, not something a publishing step
# can edit afterwards.
#
# The two payloads are otherwise identical, and what makes one of them *bad* is the entry point the
# launching intent names. That is a faithful exercise of the mechanism rather than a shortcut -- the
# guard's only input is "this version was started and never reported successful", which is exactly
# what happens three times below -- and it is said here rather than left to be noticed.
publish() { # version
  rm -rf "$ROOT/src"
  ( cd "$ENGINE" && ./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline \
      -PdogwoodVersion="$1" --console=plain -q ) || exit 1
  cp -R "$ENGINE/samples/slice-guest/build/zipline/ProductionWebpack" "$ROOT/src"
  "$HERE/server.py" publish --root "$ROOT" --from "$ROOT/src" --version "$1" >/dev/null
  "$HERE/server.py" rollout --root "$ROOT" --version "$1" --percent 100 >/dev/null
}

echo "==> publishing 1.0.0-good and serving it"
publish 1.0.0-good
"$HERE/server.py" serve --root "$ROOT" --port "$PORT" >/dev/null 2>&1 &
server=$!
for _ in $(seq 1 80); do
  curl -fsS -D - -o /dev/null -m 1 "http://127.0.0.1:$PORT/manifest.zipline.json" 2>/dev/null \
    | grep -q "X-Dogwood-Release" && break
  sleep 0.25
done

MANIFEST="http://10.0.2.2:$PORT/manifest.zipline.json"
( cd "$ENGINE" && ./gradlew :samples:slice-android:installRelease --console=plain -q ) || exit 1
# A clean slate: the guard's memory is a file in the application's own storage, and a leftover one
# from an earlier run would decide this drill's outcome before it started.
adb shell pm clear dev.dogwood.slice.android >/dev/null 2>&1 || true

launch() { # entry
  adb shell am force-stop dev.dogwood.slice.android >/dev/null 2>&1 || true
  adb logcat -c
  adb shell am start -n dev.dogwood.slice.android/.TabsActivity \
    --es entry "$1" --es manifest "$MANIFEST" >/dev/null
  sleep 16
  adb logcat -d >> "$LOG" 2>/dev/null
  adb logcat -d
}

echo "==> the negative control: a healthy release runs, twice"
# The control runs FIRST and it is not decoration. Every assertion below is satisfied by a client
# that cannot reach the server at all, and the two look identical in a summary.
control_one="$(launch explore)"
control_two="$(launch explore)"
healthy=$(printf '%s' "$control_one$control_two" | grep -c "loaded version 1.0.0-good" || true)
conform "H2-control" "$([ "$healthy" -ge 2 ] && echo 1 || echo 0)" \
  "a healthy release ran twice under the same procedure and was never refused ($healthy loads)"

echo "==> publishing 1.1.0-bad, which throws before the host can mount anything"
publish 1.1.0-bad

first="$(launch crash-launch)"
second="$(launch crash-launch)"
third="$(launch crash-launch)"

# H2 -- the third launch is REFUSED. Two failed starts is the guard's threshold: `maxFailures` is
# two rather than one because a single failure is as likely to be a bad network as a bad payload.
refused=$(printf '%s' "$third" | grep -c "refused: quarantined after 2 failed starts" || true)
# No pipe into `grep -m1`, and no pipe out of it. `-m1` exits on its first match, which SIGPIPEs
# whatever feeds it, and `set -o pipefail` then reports the success as a failure -- so the `||`
# fallback fired *alongside* a matched line and the detail said both "here is the refusal" and "the
# third launch was not refused". The skew drill's header records the same trap in two other
# spellings; this is the third.
detail_refused="$(printf '%s' "$third" | grep 'refused: quarantined' | head -1)"
conform "H2" "$([ "$refused" -ge 1 ] && echo 1 || echo 0)" \
  "${detail_refused:-the third launch was not refused}"

# And the payload did not run. A quarantine that still runs the release is bookkeeping, not
# protection -- the guest's own log line is what shows whether it composed.
ran=$(printf '%s' "$third" | grep -c "DogwoodTabs/" || true)
conform "H2-absent" "$([ "$ran" = "0" ] && echo 1 || echo 0)" \
  "the quarantined payload produced no guest output ($ran lines)"

# H3 -- and it names the last release known to have worked, so an operator knows what to go back to.
names=$(printf '%s' "$third" | grep -c "last good: 1.0.0-good" || true)
detail_good="$(printf '%s' "$third" | grep 'last good' | head -1)"
conform "H3" "$([ "$names" -ge 1 ] && echo 1 || echo 0)" \
  "${detail_good:-no last-known-good version was named}"

echo "==> resuming 1.0.0-good, which is what an operator actually does"
"$HERE/server.py" resume --root "$ROOT" --version 1.0.0-good >/dev/null
recovered="$(launch explore)"
# The recovery claim, and the reason `resume` exists as a server command rather than a client
# feature: the quarantined clients are not waiting for a signal. They are refusing a *version*, and
# serving a different one is all it takes.
back=$(printf '%s' "$recovered" | grep -c "loaded version 1.0.0-good" || true)
conform "H2-recovered" "$([ "$back" -ge 1 ] && echo 1 || echo 0)" \
  "the client came back on its own once the server served a good release again ($back loads)"

printf '%s\n' "${lines[@]}" > "$OUT"
echo "CONF RESULT client=android passed=$passed failed=$failed skipped=0" >> "$OUT"
echo
if [ "$failed" = "0" ]; then
  echo "PASS -- a bad publish was quarantined on a device and recovered through the server"
else
  echo "FAIL -- see $LOG" >&2; exit 1
fi

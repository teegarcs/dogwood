#!/usr/bin/env bash
# Project Dogwood -- how much of the Phase 0 headroom is the emulator's core count?
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/phase0/scaling-sensitivity.sh
#
# **This is sensitivity, not gate evidence, and the distinction is the entire reason the script says
# so in three places.** [Layer 4 ADR-008](../../adrs/layer-4/ADR-008-gate-device-not-available.md)
# defines the performance gate on named hardware, and the framework-grade rubric caps performance at
# C+ for fast-hardware-only numbers. Nothing here changes either. An Android emulator borrows the
# development machine's single-core speed, which is the exact dimension a 2022-tier Cortex-A53
# lacks, so no emulator configuration is a stand-in for a device.
#
# What an emulator *can* answer honestly is a different and still useful question: **what is the
# shape of the degradation curve?** If halving the parallelism available to the runtime moves the
# numbers by a few percent, the budgets have real headroom and the gate device is a formality. If it
# moves them by 4x, the headroom is illusory and acquiring the device becomes urgent. That is a
# decision input, and it is the one the owner asked for.
#
# The knob is core count, and it is the only honest one available. An Audio Video Device (AVD)
# profile can shed cores and memory; it cannot slow a core, and process-level throttling produces
# numbers that are neither the fleet's nor reproducible -- which is what the Phase 0 harness appendix
# exists to refuse.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
AVD="${AVD:-Pixel_9_Pro}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
EMULATOR="$SDK/emulator/emulator"
export PATH="$SDK/platform-tools:$PATH"

[ -x "$EMULATOR" ] || { echo "no emulator at $EMULATOR; set ANDROID_HOME" >&2; exit 1; }
command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }

echo "==> building the harness once, so both runs measure the same bytes"
( cd "$HERE" && ./gradlew :host-android:assembleDebug --console=plain -q ) || exit 1
APK="$HERE/host-android/build/outputs/apk/debug/host-android-debug.apk"
[ -f "$APK" ] || { echo "no harness apk at $APK" >&2; exit 1; }

run_at() { # cores
  local cores="$1"
  echo
  echo "==> restarting $AVD with $cores core(s)"
  adb emu kill >/dev/null 2>&1 || true
  for _ in $(seq 1 40); do adb get-state >/dev/null 2>&1 || break; sleep 2; done

  # `-cores` on the command line rather than an edit to config.ini: the AVD the developer configured
  # is left exactly as it was, which matters because this script restarts *their* emulator.
  "$EMULATOR" -avd "$AVD" -cores "$cores" -no-snapshot-load -no-boot-anim -netdelay none -netspeed full \
    >/dev/null 2>&1 &
  for _ in $(seq 1 90); do
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 4
  done
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || {
    echo "the emulator never finished booting at $cores cores" >&2; return 1; }

  # What the guest OS actually sees, read rather than assumed. `-cores` is a request to the
  # hypervisor and this is the only way to know it was honoured -- a run that silently kept four
  # cores would produce two identical curves and a confident wrong conclusion.
  local seen
  seen=$(adb shell "cat /proc/cpuinfo | grep -c ^processor" 2>/dev/null | tr -d '\r')
  echo "    the guest reports $seen processor(s)"
  if [ "$seen" != "$cores" ]; then
    echo "    REFUSED -- asked for $cores, the guest sees $seen; this run would be mislabelled" >&2
    return 1
  fi

  adb install -r "$APK" >/dev/null 2>&1 || { echo "install failed" >&2; return 1; }
  adb shell am force-stop dev.dogwood.host.android >/dev/null 2>&1 || true
  adb shell am start -n dev.dogwood.host.android/.Phase0Activity \
    --es label "emulator-${cores}core-sensitivity-not-gate-valid" >/dev/null
  echo "    measuring (this takes a few minutes)"
  for _ in $(seq 1 120); do
    adb shell "ls /sdcard/Android/data/dev.dogwood.host.android/files/results" 2>/dev/null \
      | tr -d '\r' | grep -q "emulator-${cores}core" && break
    sleep 5
  done
  adb pull "/sdcard/Android/data/dev.dogwood.host.android/files/results" "$HERE/build/pull-$cores" \
    >/dev/null 2>&1
  find "$HERE/build/pull-$cores" -name "*emulator-${cores}core*" -exec cp {} "$HERE/results/" \; 2>/dev/null
  ls "$HERE/results" | grep "emulator-${cores}core" || {
    echo "    no result file for $cores cores" >&2; return 1; }
}

mkdir -p "$HERE/build"
run_at 4 || exit 1
run_at 1 || exit 1

echo
echo "==> the curve"
python3 "$HERE/scaling-sensitivity.py" \
  "$HERE/results/emulator-4core-sensitivity-not-gate-valid.json" \
  "$HERE/results/emulator-1core-sensitivity-not-gate-valid.json"

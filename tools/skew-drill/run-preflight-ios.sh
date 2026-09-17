#!/usr/bin/env bash
# Project Dogwood -- the pre-flight dictionary check on iOS, end to end.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   ./gradlew :samples:slice-guest:serveProductionWebpackZipline   # in another shell
#   tools/skew-drill/run-preflight-ios.sh
#
# `run-ios.sh` with one flag removed, and `run-preflight.sh`'s twin on the other mobile client. Both
# serve this binary a payload built against a dictionary it does not have; this one lets the payload
# *declare* that in its manifest's signed metadata, which is what a real published payload does since
# S1. `run-ios.sh` grades what the client does when nothing is declared -- claims A2, A3 and A4.
# This grades what it does when something is: it refuses, and conformance claim `B3` is graded on
# this client rather than on the web alone.
#
#   1. Build and install the iOS application at the committed dictionary version N.
#   2. Patch the surface to N+1, exactly as the containment drill does.
#   3. Rebuild **only the guest payload**, declaring N+1, and serve it to the version N binary.
#   4. Run the client and read the accessibility tree: a refusal naming both versions, and none of
#      the payload's own widgets.
#   5. Restore the surface, always.
#
# Two things differ from Android, and both are platform facts rather than choices:
#
#   * **VoiceOver has to be on.** Compose Multiplatform builds its accessibility tree only while an
#     assistive technology is running, and that tree is this platform's view hierarchy dump -- there
#     is no `uiautomator` for a simulator. `SkewDrill.kt` refuses rather than reports if it is off,
#     so a forgotten switch cannot read as a passing run.
#   * **The drill runs inside the application.** `xcrun simctl` cannot dump a hierarchy, so the
#     assertions are made in-process and printed, exactly as the accessibility drill's are.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/build"
OUT="$HERE/build/preflight-ios.conf"
LOG="$HERE/build/ios-preflight.log"
cd "$HERE/../../engine"

SURFACE="surface/dev/dogwood/surface/DesignSystemSurface.kt"
LOCK="surface/dogwood.designsystem.lock.json"
CODEGEN="dogwood-codegen/build.gradle.kts"
ABOUT="samples/slice-screens/src/jsMain/kotlin/dev/dogwood/slice/AboutScreen.kt"
TOUCHED=("$SURFACE" "$LOCK" "$CODEGEN" "$ABOUT")

command -v xcrun >/dev/null || { echo "xcrun not found; this drill needs Xcode" >&2; exit 1; }
xcrun simctl list devices booted | grep -q "(Booted)" || {
  echo "no booted simulator; boot one first (xcrun simctl boot <device>)" >&2; exit 1; }
curl -fs -m 5 http://localhost:8080/manifest.zipline.json >/dev/null 2>&1 || {
  echo "the guest is not being served on :8080 --" >&2
  echo "  ./gradlew :samples:slice-guest:serveProductionWebpackZipline" >&2; exit 2; }

# Saved before anything is patched, and restored from these copies rather than from git -- the
# distinction the Android drill records: `git checkout --` restores the *committed* content and
# silently discards uncommitted work in the same files. It did once, mid-review.
SAVED="$(mktemp -d)"
for file in "${TOUCHED[@]}"; do
  mkdir -p "$SAVED/$(dirname "$file")"
  cp "$file" "$SAVED/$file"
done

restore() {
  for file in "${TOUCHED[@]}"; do
    cp "$SAVED/$file" "$file" 2>/dev/null || true
  done
  rm -rf "$SAVED"
  # Rebuild from the restored surface so the served guest is not left skewed for the next person.
  ./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline --console=plain -q >/dev/null 2>&1 || true
  xcrun simctl terminate booted dev.dogwood.slice.ios >/dev/null 2>&1 || true
}
trap restore EXIT

echo "==> enabling VoiceOver on the booted simulator"
# The `notifyutil -p` posts are not optional: the accessibility daemon caches these preferences and
# a process that has already read them keeps the old answer. See `tools/a11y-drill/run.sh`.
xcrun simctl spawn booted defaults write com.apple.Accessibility ApplicationAccessibilityEnabled -int 1
xcrun simctl spawn booted defaults write com.apple.Accessibility VoiceOverTouchEnabled -int 1
xcrun simctl spawn booted notifyutil -p com.apple.accessibility.cache.app.ax
xcrun simctl spawn booted notifyutil -p com.apple.accessibility.cache.ax

echo "==> installing the client at the committed version"
./gradlew :samples:slice-ios:iosApp --console=plain -q --max-workers=2 || exit 1
xcrun simctl terminate booted dev.dogwood.slice.ios 2>/dev/null || true
xcrun simctl install booted samples/slice-ios/build/DogwoodSlice.app || exit 1

echo "==> skewing the surface to N+1"
python3 "$HERE/skew.py" "$SURFACE" "$CODEGEN" || exit 1
# No `-PdogwoodDeclareSegments=false` here, and that single omission is the entire difference from
# `run-ios.sh`. The payload declares the dictionary it was built against -- read out of the
# generator's own output, never restated -- and the client is expected to refuse it on that basis.
./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline --console=plain -q || exit 1

echo "==> waiting for the skewed payload to be served"
# No pipe in this check: `grep -q` exits on its first match, SIGPIPEs whatever feeds it, and
# `pipefail` then reports the success as a failure. The Android drill lost an hour to that.
served="$HERE/build/served-ios-preflight.zipline"
markers="$HERE/build/served-ios-preflight.strings"
has_marker() {
  [ -s "$served" ] || return 1
  strings "$served" > "$markers"
  grep -q "SKEW-CALLOUT" "$markers"
}
for _ in $(seq 1 30); do
  # The module address carries the first sixteen hex digits of its own SHA-256 now (ADR-077), so it
  # is READ out of the manifest the server is offering rather than spelled out here. A literal name
  # would 404 on every attempt and this loop would then report "the skewed payload never reached the
  # server" -- a product failure that did not happen, which is the most expensive kind of wrong.
  address="$(curl -fs -m 3 http://localhost:8080/manifest.zipline.json 2>/dev/null \
    | python3 -c 'import json,sys; print(next(iter(json.load(sys.stdin)["modules"].values()))["url"])' \
    2>/dev/null || true)"
  [ -n "$address" ] && curl -fs -m 3 -o "$served" "http://localhost:8080/$address" 2>/dev/null
  if has_marker; then break; fi
  sleep 2
done
if ! has_marker; then
  echo "the skewed payload never reached the server" >&2
  exit 1
fi

# And the *manifest* carries the declaration. Without this, a build that silently stopped emitting
# the field would produce a run in which the client contains the payload instead of refusing it,
# `B3` fails, and the reported cause -- "the client did not refuse" -- points at the client rather
# than at the publisher.
declared="$(curl -fs -m 5 http://localhost:8080/manifest.zipline.json | python3 -c \
  'import json,sys; print(json.load(sys.stdin).get("metadata", {}).get("dogwood.segments", ""))')"
case "$declared" in
  *dogwood.designsystem:*) echo "==> the served manifest declares [$declared]" ;;
  *) echo "the served manifest declares no dictionary; nothing to refuse" >&2; exit 1 ;;
esac

echo "==> running the client, which was NOT reinstalled"
# The payload is cached on disk by `ZiplineCache`, keyed by content, so a fresh fetch is what makes
# the client meet the *new* one rather than the one it already had.
xcrun simctl launch --console-pty booted dev.dogwood.slice.ios --dogwood-preflight > "$LOG" 2>&1 &
launcher=$!
# Bounded by the clock rather than by the launcher's exit: `--console-pty` stays attached to a
# running application, so waiting for it would wait forever.
for _ in $(seq 1 45); do
  tr -d '\r' < "$LOG" 2>/dev/null | grep -q "^PREFLIGHT DONE\|^PREFLIGHT REFUSED" && break
  sleep 2
done
kill "$launcher" 2>/dev/null || true
pkill -f "simctl launch --console-pty booted dev.dogwood.slice.ios" 2>/dev/null || true

tr -d '\r' < "$LOG" | grep -E "^PREFLIGHT |^CONF " || true

if tr -d '\r' < "$LOG" | grep -q "^PREFLIGHT REFUSED"; then
  echo
  echo "REFUSED -- the drill could not run; see $LOG" >&2
  exit 2
fi

tr -d '\r' < "$LOG" | grep -E "^CONF " > "$OUT"
if [ ! -s "$OUT" ]; then
  echo "the client never reported; see $LOG" >&2
  exit 1
fi
grep -q "^CONF .* FAIL" "$OUT" && exit 1
exit 0

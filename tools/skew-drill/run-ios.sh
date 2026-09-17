#!/usr/bin/env bash
# Project Dogwood -- skew containment on iOS, end to end, without hand-editing anything.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   ./gradlew :samples:slice-guest:serveProductionWebpackZipline   # in another shell
#   tools/skew-drill/run-ios.sh
#
# The Android drill's twin, and deliberately the same five steps -- because the claim being made is
# that containment is a property of the shared host code rather than of one platform's bindings,
# and the only way to say that honestly is to put each client in the same condition and read the
# same three outcomes off its own screen.
#
#   1. Build and install the iOS application at the committed dictionary version N.
#   2. Patch the surface with three additions, one per claim, and bump to N+1.
#   3. Rebuild **only the guest payload** and serve it to the still-installed version N binary.
#   4. Run the client and read the accessibility tree.
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
OUT="$HERE/build/skew-ios.conf"
LOG="$HERE/build/ios-run.log"
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
# The skewed payload declares nothing, deliberately, and that is the claim boundary.
#
# Since the pre-flight dictionary check landed (S1), a payload built at N+1 *declares* N+1 in its
# signed metadata and a version N client refuses it before composing anything -- which is the point
# of that check and would make every containment claim below fail for a reason that is not a
# containment defect. `-PdogwoodDeclareSegments=false` publishes the payload the containment rules
# are actually for: one built before the field existed, or by a team that has not adopted it. The
# refusal itself is graded separately, as `B3`, by `run-preflight.sh` on the same skewed surface.
./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline -PdogwoodDeclareSegments=false --console=plain -q || exit 1

echo "==> waiting for the skewed payload to be served"
# No pipe in this check: `grep -q` exits on its first match, SIGPIPEs whatever feeds it, and
# `pipefail` then reports the success as a failure. The Android drill lost an hour to that.
served="$HERE/build/served-ios.zipline"
markers="$HERE/build/served-ios.strings"
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

echo "==> running the client, which was NOT reinstalled"
# The payload is cached on disk by `ZiplineCache`, keyed by content, so a fresh fetch is what makes
# the client meet the *new* one rather than the one it already had.
xcrun simctl launch --console-pty booted dev.dogwood.slice.ios --dogwood-skew > "$LOG" 2>&1 &
launcher=$!
# Bounded by the clock rather than by the launcher's exit: `--console-pty` stays attached to a
# running application, so waiting for it would wait forever.
for _ in $(seq 1 45); do
  tr -d '\r' < "$LOG" 2>/dev/null | grep -q "^SKEW DONE\|^SKEW REFUSED" && break
  sleep 2
done
kill "$launcher" 2>/dev/null || true
pkill -f "simctl launch --console-pty booted dev.dogwood.slice.ios" 2>/dev/null || true

tr -d '\r' < "$LOG" | grep -E "^SKEW |^CONF " || true

if tr -d '\r' < "$LOG" | grep -q "^SKEW REFUSED"; then
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

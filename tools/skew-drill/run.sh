#!/usr/bin/env bash
# Project Dogwood -- skew containment, end to end, without hand-editing anything.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/skew-drill/run.sh
#
# Section 6 of the technical specification makes three claims about a client meeting a payload built
# against a **newer** dictionary than its own. They are conformance claims A2, A3 and A4, and the
# only way to test them honestly is with two builds: a client at version N, and a payload at N+1.
#
# That used to be a manual procedure -- edit the surface, bump the version, rebuild the guest, do
# not reinstall the application -- and the README said so. A drill that needs a person to edit
# source before it runs is a drill that runs approximately never, which is what the platform review
# observed about this one. Everything it asked for is mechanical, so this does it:
#
#   1. Build and install the Android client at the committed version N.
#   2. Patch the surface with three additions, one per claim, and bump to N+1.
#   3. Rebuild **only the guest payload** and serve it to the still-installed version N client.
#   4. Read the rendered tree and check the three containment rules.
#   5. Restore the surface, always -- a permanently skewed surface is a permanently failing lock.
#
# The restore runs on any exit path, including a failure or an interrupt. That is the reason this is
# a script with a trap rather than a list of steps in a document.
#
# **The restore copies files back; it does not `git checkout` them.** It used to, and that is a
# different operation wearing the same clothes: `git checkout --` restores the *committed* content,
# so it silently discarded whatever uncommitted work happened to be in those four files. It did --
# to the surface change that added `@Holder`, mid-review, with no message. The drill cannot tell its
# own patch from a developer's, so it saves the four files it is about to touch and puts those
# copies back.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/build"
OUT="$HERE/build/skew.conf"
cd "$HERE/../../engine"

SURFACE="surface/dev/dogwood/surface/DesignSystemSurface.kt"
LOCK="surface/dogwood.designsystem.lock.json"
CODEGEN="dogwood-codegen/build.gradle.kts"

command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "no device or emulator attached" >&2; exit 1; }
curl -fs -m 5 http://localhost:8080/manifest.zipline.json >/dev/null 2>&1 || {
  echo "the guest is not being served on :8080 --" >&2
  echo "  ./gradlew :samples:slice-guest:serveProductionWebpackZipline" >&2; exit 2; }

ABOUT="samples/slice-screens/src/jsMain/kotlin/dev/dogwood/slice/AboutScreen.kt"
TOUCHED=("$SURFACE" "$LOCK" "$CODEGEN" "$ABOUT")

# Saved before anything is patched, and restored from these copies rather than from git. The
# distinction is the whole point: git would restore the committed content and throw away any
# uncommitted work in these files, which is not what "restore" means to the person running this.
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
  # Rebuild the payload from the restored surface so the served guest is not left skewed for the
  # next person who opens the sample.
  ./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline --console=plain -q >/dev/null 2>&1 || true
}
trap restore EXIT

echo "==> installing the client at the committed version"
./gradlew :samples:slice-android:installDebug --console=plain -q || exit 1

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
# No pipe anywhere in this check, deliberately. `grep -q` exits on its first match, which SIGPIPEs
# whatever feeds it, and `set -o pipefail` reports that as a failed pipeline -- so the check kept
# saying the payload had not arrived when it had, twice, in two different spellings. Shell plumbing
# reporting a product failure is the same trap the `--console-pty` carriage returns were.
served="$HERE/build/served.zipline"
markers="$HERE/build/served.strings"
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
adb logcat -c
adb shell am force-stop dev.dogwood.slice.android
adb shell am start -n dev.dogwood.slice.android/.TabsActivity --es entry about >/dev/null
sleep 18

python3 "$HERE/check.py" "$OUT"
status=$?
cat "$OUT"
exit "$status"

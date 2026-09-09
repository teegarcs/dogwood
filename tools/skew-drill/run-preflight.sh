#!/usr/bin/env bash
# Project Dogwood -- the pre-flight dictionary check, end to end, on Android.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/skew-drill/run-preflight.sh
#
# The sibling of `run.sh`, and the two differ in exactly one flag. Both build a payload against a
# dictionary the installed client does not have; this one lets the payload *declare* that in its
# manifest's signed metadata, which is what a real published payload does since S1. `run.sh` grades
# what the client does when nothing is declared -- placeholders, withheld affordances, reported
# skew, claims A2/A3/A4. This grades what it does when something is: it refuses, and conformance
# claim `B3` -- which the web has had since ADR-032 -- is finally graded on a mobile client too.
#
# Keeping them as two scripts rather than one with a mode is deliberate. They assert opposite
# outcomes on the same screen, and a single script whose meaning inverts on an argument is one whose
# failures are read wrong.
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
#   2. Patch the surface to N+1, exactly as the containment drill does.
#   3. Rebuild **only the guest payload**, declaring N+1, and serve it to the version N client.
#   4. Read the rendered tree: the client says which dictionary it is missing, and shows none of
#      the payload's widgets.
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
OUT="$HERE/build/preflight.conf"
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
# No `-PdogwoodDeclareSegments=false` here, and that single omission is the entire difference from
# `run.sh`. The payload declares the dictionary it was built against -- read out of the generator's
# own output, never restated -- and the client is expected to refuse it on that basis.
./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline --console=plain -q || exit 1

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
  curl -fs -m 3 -o "$served" http://localhost:8080/slice-guest.zipline 2>/dev/null
  if has_marker; then break; fi
  sleep 2
done
if ! has_marker; then
  echo "the skewed payload never reached the server" >&2
  exit 1
fi

# And the *manifest* carries the declaration. Without this check a build that silently stopped
# emitting the field would produce a run in which the client renders the payload with containment,
# `B3` fails, and the reported cause -- "the client did not refuse" -- points at the client rather
# than at the publisher. Two minutes of confusion for one `curl`.
declared="$(curl -fs -m 5 http://localhost:8080/manifest.zipline.json | python3 -c \
  'import json,sys; print(json.load(sys.stdin).get("metadata", {}).get("dogwood.segments", ""))')"
case "$declared" in
  *dogwood.designsystem:*) echo "==> the served manifest declares [$declared]" ;;
  *) echo "the served manifest declares no dictionary; nothing to refuse" >&2; exit 1 ;;
esac

echo "==> running the client, which was NOT reinstalled"
adb logcat -c
adb shell am force-stop dev.dogwood.slice.android
adb shell am start -n dev.dogwood.slice.android/.TabsActivity --es entry about >/dev/null
sleep 18

python3 "$HERE/check_preflight.py" "$OUT"
status=$?
cat "$OUT"
exit "$status"

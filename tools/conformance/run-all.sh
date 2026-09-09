#!/usr/bin/env bash
# Project Dogwood -- every conformance run this machine can make, then the matrix, then a verdict.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/conformance/run-all.sh
#
# This is the tier-C gate. It needs a booted iOS simulator, an attached Android device or emulator,
# Chrome, and the guest being served on :8080 -- which is why it runs here and not in continuous
# integration, where only tier S can be graded honestly (`.github/workflows/conformance.yml`).
#
# Each drill refuses rather than fails when its prerequisite is missing, so a partial run reports
# what it could not do instead of reporting green.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/build"
rm -f "$HERE"/build/*.conf
status=0

split_by_client() {
  python3 - "$HERE/build" "$1" "$2" <<'PY'
import pathlib, sys
out, raw, suffix = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
cur, runs = [], {}
for line in raw.read_text().splitlines():
    cur.append(line)
    if line.startswith('CONF RESULT client='):
        runs[line.split('client=')[1].split()[0]] = cur
        cur = []
for client, lines in runs.items():
    (out / f'{client}{suffix}.conf').write_text('\n'.join(lines) + '\n')
raw.unlink()
PY
}

echo "==> shared-code test evidence"
"$HERE/../../engine/gradlew" -p "$HERE/../../engine" build \
  -x :samples:slice-android:lintDebug --console=plain -q || status=1
python3 "$HERE/from_tests.py" > "$HERE/build/tests.raw" || status=1
split_by_client "$HERE/build/tests.raw" ""

echo "==> android accessibility and network policy"
"$HERE/run-android.sh" "$HERE/build/android-a11y.conf" >/dev/null 2>&1 || status=1

echo "==> android skew containment"
# Two builds by construction -- a client at version N and a payload at N+1 -- so it is its own
# script rather than another test class. It restores the surface on every exit path.
"$HERE/../skew-drill/run.sh" >/dev/null 2>&1 || status=1
if [ -f "$HERE/../skew-drill/build/skew.conf" ]; then
  grep -E "^CONF [A-G][0-9]" "$HERE/../skew-drill/build/skew.conf" > "$HERE/build/android-skew.conf" || true
fi

echo "==> ios skew containment"
# The same two-build procedure as Android's, on a simulator: a client at version N and a payload at
# N+1. It restores the surface on every exit path.
"$HERE/../skew-drill/run-ios.sh" >/dev/null 2>&1 || status=1
if [ -f "$HERE/../skew-drill/build/skew-ios.conf" ]; then
  grep -E "^CONF [A-Z][0-9]" "$HERE/../skew-drill/build/skew-ios.conf" > "$HERE/build/ios-skew.conf" || true
fi

echo "==> desktop skew containment"
# The desktop's instrument is the render transcript rather than an accessibility tree: that client
# has none to walk from outside the process. Same two builds, same three claims.
"$HERE/../skew-drill/run-desktop.sh" >/dev/null 2>&1 || status=1
if [ -f "$HERE/../skew-drill/build/skew-desktop.conf" ]; then
  grep -E "^CONF [A-Z][0-9]" "$HERE/../skew-drill/build/skew-desktop.conf" > "$HERE/build/desktop-skew.conf" || true
fi

echo "==> mobile pre-flight dictionary refusal"
# The same two builds again, with the payload *declaring* the dictionary it was built against, so
# the client refuses before it starts rather than degrading through it (`B3`, ADR-061). Two scripts
# rather than one with a mode: they assert opposite outcomes on the same screen.
for client in "" "-ios"; do
  "$HERE/../skew-drill/run-preflight${client}.sh" >/dev/null 2>&1 || status=1
done
for pair in "android:preflight" "ios:preflight-ios"; do
  name="${pair%%:*}"; file="${pair##*:}"
  if [ -f "$HERE/../skew-drill/build/${file}.conf" ]; then
    grep -E "^CONF [A-Z][0-9]" "$HERE/../skew-drill/build/${file}.conf" > "$HERE/build/${name}-preflight.conf" || true
  fi
done

echo "==> a bad publish, quarantined on a device and recovered"
# `H2`'s device half. Every earlier test of the crash-loop quarantine simulated the crash by
# recording a start without a success; this one publishes a payload that genuinely fails to mount.
"$HERE/../reference-server/quarantine-drill.sh" "$HERE/build/android-quarantine.conf" >/dev/null 2>&1 || status=1

echo "==> web skew containment"
# Needs no device, so this one also runs in continuous integration (`conformance.yml`). It is kept
# here too because this script is the whole-matrix run, and a client graded in one place and not the
# other is how a matrix starts lying.
"$HERE/../skew-drill/run-web.sh" >/dev/null 2>&1 || status=1
if [ -f "$HERE/../skew-drill/build/skew-web.conf" ]; then
  grep -E "^CONF [A-Z][0-9]" "$HERE/../skew-drill/build/skew-web.conf" > "$HERE/build/web-skew.conf" || true
fi

echo "==> web accessibility"
"$HERE/run-web.sh" "$HERE/build/web-a11y.conf" >/dev/null 2>&1 || status=1

echo "==> ios accessibility and network policy"
CONF_OUT="$HERE/build/ios-a11y.conf" "$HERE/../a11y-drill/run.sh" >/dev/null 2>&1 || status=1

echo "==> compatibility across the over-the-air gap"
# A committed payload fixture from an earlier toolchain, against a host built from current
# sources -- the pairing every deployment has and nothing else here exercises.
"$HERE/cross-version.sh" "$HERE/build/desktop-crossversion.conf" >/dev/null 2>&1 || status=1
# And on the two clients a product ships. Until the manifest address became overridable on Android
# and iOS, `K1`/`K2` could only be graded on the one client that had the switch.
for client in android ios; do
  "$HERE/cross-version-mobile.sh" "$client" "$HERE/build/${client}-crossversion.conf" \
    >/dev/null 2>&1 || status=1
done

echo "==> performance budgets"
python3 "$HERE/from_phase0.py" > "$HERE/build/phase0.raw" || status=1
split_by_client "$HERE/build/phase0.raw" "-perf"
python3 "$HERE/from_web_weight.py" > "$HERE/build/web-perf.conf" || status=1

# Fold every per-drill file into its client's, so the aggregator sees one run per client.
python3 - "$HERE/build" <<'PY'
import glob, pathlib, sys
out = pathlib.Path(sys.argv[1])
for extra in sorted(glob.glob(str(out / '*-*.conf'))):
    name = pathlib.Path(extra).name[:-len('.conf')]
    client = name.rsplit('-', 1)[0]
    target = out / f'{client}.conf'
    lines = pathlib.Path(extra).read_text().splitlines()
    body = [l for l in lines if not l.startswith('CONF RESULT')]
    if target.exists():
        existing = target.read_text().splitlines()
        keep = [l for l in existing if not l.startswith('CONF RESULT')]
        # Deduplicated by claim: a duplicated verdict is harmless to the aggregator and misleading
        # to a person reading the file.
        seen = {l.split(' -- ')[0] for l in keep if l.startswith('CONF ')}
        body = [l for l in body if l.split(' -- ')[0] not in seen]
        target.write_text('\n'.join(
            keep + body + [l for l in existing if l.startswith('CONF RESULT')]) + '\n')
    else:
        target.write_text('\n'.join(lines) + '\n')
    pathlib.Path(extra).unlink()
PY

echo
python3 "$HERE/aggregate.py" "$HERE"/build/*.conf || status=1

echo
if [ "$status" = "0" ]; then
  echo "PASS -- every claim this machine can grade is met"
else
  echo "FAIL -- see the matrix above; a red cell blocks the merge" >&2
fi
exit "$status"

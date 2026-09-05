#!/usr/bin/env bash
# Project Dogwood -- every conformance run, then the matrix.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/conformance/run-all.sh
#
# Needs a booted simulator, an attached Android device or emulator, Chrome, and the guest being
# served on :8080. Each drill refuses rather than fails when its prerequisite is missing, so a
# partial run reports what it could not do instead of reporting green.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/build"
status=0

echo "==> shared and per-client test evidence"
"$HERE/../../engine/gradlew" -p "$HERE/../../engine" build \
  -x :samples:slice-android:lintDebug --console=plain -q || status=1
python3 "$HERE/from_tests.py" > "$HERE/build/tests.raw" || status=1
python3 - "$HERE/build" <<'PY'
import pathlib, sys
out = pathlib.Path(sys.argv[1])
cur, runs = [], {}
for line in (out / 'tests.raw').read_text().splitlines():
    cur.append(line)
    if line.startswith('CONF RESULT client='):
        runs[line.split('client=')[1].split()[0]] = cur
        cur = []
for client, lines in runs.items():
    (out / f'{client}.conf').write_text('\n'.join(lines) + '\n')
PY
rm -f "$HERE/build/tests.raw"

echo "==> android accessibility"; "$HERE/run-android.sh" "$HERE/build/android-a11y.conf" >/dev/null 2>&1 || status=1
echo "==> web accessibility";     "$HERE/run-web.sh"     "$HERE/build/web-a11y.conf"     >/dev/null 2>&1 || status=1
echo "==> ios accessibility";     CONF_OUT="$HERE/build/ios-a11y.conf" "$HERE/../a11y-drill/run.sh" >/dev/null 2>&1 || status=1

# Fold each drill's claims into its client's file, so the aggregator sees one run per client.
python3 - "$HERE/build" <<'PY'
import pathlib, sys, glob
out = pathlib.Path(sys.argv[1])
for extra in glob.glob(str(out / '*-a11y.conf')):
    client = pathlib.Path(extra).name.split('-a11y')[0]
    target = out / f'{client}.conf'
    body = [l for l in pathlib.Path(extra).read_text().splitlines() if not l.startswith('CONF RESULT')]
    if target.exists():
        ex = target.read_text().splitlines()
        keep = [l for l in ex if not l.startswith('CONF RESULT')]
        # Deduplicated by claim, so re-running the merge cannot double a line -- a duplicated
        # verdict is harmless to the aggregator and misleading to a person reading the file.
        seen = {l.split(' -- ')[0] for l in keep if l.startswith('CONF ')}
        body = [l for l in body if l.split(' -- ')[0] not in seen]
        target.write_text('\n'.join(
            keep + body + [l for l in ex if l.startswith('CONF RESULT')]) + '\n')
    else:
        target.write_text(pathlib.Path(extra).read_text())
    pathlib.Path(extra).unlink()
PY

echo
python3 "$HERE/aggregate.py" "$HERE"/build/*.conf || status=1
exit "$status"

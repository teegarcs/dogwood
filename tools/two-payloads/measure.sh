#!/usr/bin/env bash
# Project Dogwood -- what a second, independently shipped payload actually costs.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/two-payloads/measure.sh
#
# `DogwoodShell` takes a single `manifestUrl`, so two teams shipping on their own schedules means two
# shells: two caches, two guards, two dispatchers, two interpreters, and no shared warm pool. The
# adoption audit (B3) recorded that the cost of that had never been examined; this examines it, and
# `docs/multi-team.md` §2 records the numbers.
#
# Two servers, on two ports, because that is what "published independently" means. The same static
# file server the development loop already uses -- nothing here needs the reference server, and using
# it would measure its behaviour alongside the thing under test.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ENGINE="$HERE/../../engine"
PORT_A="${PORT_A:-8080}"
PORT_B="${PORT_B:-8081}"

echo "==> building both payloads"
( cd "$ENGINE" && ./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline \
    :samples:second-guest:jsBrowserProductionWebpackZipline --console=plain -q ) || exit 1

A="$ENGINE/samples/slice-guest/build/zipline/ProductionWebpack"
B="$ENGINE/samples/second-guest/build/zipline/ProductionWebpack"
[ -f "$A/manifest.zipline.json" ] && [ -f "$B/manifest.zipline.json" ] || {
  echo "one of the payloads was not built" >&2; exit 1; }

servers=()
# Port A may already be taken by the ordinary development server, and that is fine -- it serves the
# same payload. Starting a second one on the same port would fail silently and leave the drill
# measuring whatever was already there.
if curl -fs -m 2 -o /dev/null "http://127.0.0.1:$PORT_A/manifest.zipline.json" 2>/dev/null; then
  echo "==> a server is already answering on :$PORT_A; using it"
else
  python3 -m http.server "$PORT_A" --directory "$A" --bind 127.0.0.1 >/dev/null 2>&1 &
  servers+=($!)
fi
python3 -m http.server "$PORT_B" --directory "$B" --bind 127.0.0.1 >/dev/null 2>&1 &
servers+=($!)
trap 'for s in "${servers[@]:-}"; do kill "$s" 2>/dev/null || true; done' EXIT
for _ in $(seq 1 40); do
  curl -fs -m 1 -o /dev/null "http://127.0.0.1:$PORT_B/manifest.zipline.json" && break
  sleep 0.25
done

# Cold caches. A warm one would report a second shell as free, which is the opposite of the number
# being asked for -- and it is the same trap the cross-version and reference-server checks each fell
# into once.
rm -rf "${TMPDIR:-/tmp}"/dogwood-two-*

echo "==> running one application with two shells"
mkdir -p "$HERE/build"
LOG="$HERE/build/measure.log"
( cd "$ENGINE" && ./gradlew :samples:two-payloads:run --console=plain -q \
    -PdogwoodMeasure=true \
    -PdogwoodManifestA="http://127.0.0.1:$PORT_A/manifest.zipline.json" \
    -PdogwoodManifestB="http://127.0.0.1:$PORT_B/manifest.zipline.json" ) > "$LOG" 2>&1

grep -E "^CONF |^MEASURE " "$LOG" || { echo "the application never reported; see $LOG" >&2; exit 1; }
grep -q "^CONF .* FAIL" "$LOG" && exit 1
exit 0

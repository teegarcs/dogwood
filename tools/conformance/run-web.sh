#!/usr/bin/env bash
# Project Dogwood -- conformance claims D1-D5 on the web client.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/conformance/run-web.sh
#
# Compose draws to a canvas through Skiko, and a canvas has no intrinsic accessibility, so whatever
# a screen reader reads has to be published separately. It is: Compose Multiplatform builds a live
# DOM of elements carrying roles and names -- inside a shadow root, which is why the drill reaches
# them through `Accessibility.getFullAXTree` and node handles rather than through selectors.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/build"
# Resolved before the `cd` below, so a relative path means what the caller meant.
OUT="$(cd "$(dirname "${1:-$HERE/build/web.conf}")" 2>/dev/null && pwd)/$(basename "${1:-$HERE/build/web.conf}")"
PORT="${PORT:-8799}"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
cd "$HERE/../../engine"

[ -x "$CHROME" ] || { echo "Chrome not found at $CHROME; set CHROME=..." >&2; exit 1; }

./gradlew :samples:web-slice:wasmJsBrowserDistribution --console=plain -q
DIST="samples/web-slice/build/dist/wasmJs/productionExecutable"
[ -f "$DIST/app.js" ] || { echo "no distribution in $DIST" >&2; exit 1; }

python3 -m http.server "$PORT" --directory "$DIST" --bind 127.0.0.1 >/dev/null 2>&1 &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT
sleep 1

set +e
python3 "$HERE/web_accessibility.py" "http://127.0.0.1:$PORT/index.html" "$CHROME" $((PORT + 500)) \
  | tee "$OUT"
status=${PIPESTATUS[0]}

# The host-services claims, against the same distribution and the same real guest. A second script
# and a second browser rather than more assertions in the first: they answer a different question,
# and a drill named for accessibility that also graded services would be the kind of file nobody
# can tell whether they have finished reading.
python3 "$HERE/web_services.py" "http://127.0.0.1:$PORT/index.html" "$CHROME" $((PORT + 700)) \
  | tee -a "$OUT"
services=${PIPESTATUS[0]}
set -e
[ "$status" = "0" ] || exit "$status"
exit "$services"

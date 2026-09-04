#!/usr/bin/env bash
# Project Dogwood -- how long until the first frame, on a connection somebody actually has?
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/web-ttff/run.sh [loads-per-preset]
#
# ADR-030 measured what the web profile weighs. Bytes are not latency: what a person waits for is
# the first frame, and that depends on the connection carrying those bytes, on brotli
# decompression, and on WebAssembly compilation -- none of which a byte table shows.
#
# Three presets, ten cold loads each by default. The throttling is Chrome's own, applied through
# the DevTools Protocol, and the page is the shipped `index.html` rather than an instrumented copy:
# it already publishes `firstFrameMs` on `globalThis.__dogwoodReport`.
set -euo pipefail
cd "$(dirname "$0")"

LOADS="${1:-10}"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
ENGINE="../../engine"
DIST="$ENGINE/samples/web-slice/build/dist/wasmJs/productionExecutable"
STAGE="build/stage"
RESULTS="build/results"
PORT="${PORT:-8797}"

[ -x "$CHROME" ] || { echo "Chrome not found at $CHROME; set CHROME=..." >&2; exit 1; }
command -v brotli >/dev/null || { echo "brotli not on PATH (brew install brotli)" >&2; exit 1; }

echo "==> building the production distribution"
(cd "$ENGINE" && ./gradlew :samples:web-slice:wasmJsBrowserDistribution --console=plain -q)
[ -f "$DIST/app.js" ] || { echo "no distribution in $DIST" >&2; exit 1; }

echo "==> staging and precompressing"
rm -rf "$STAGE" "$RESULTS"
mkdir -p "$STAGE" "$RESULTS"
cp -R "$DIST"/. "$STAGE/"
# Quality 11 to match `tools/web-weight/measure.sh`, so the bytes on the wire here are the bytes
# ADR-030's table reports rather than a cheaper approximation of them.
find "$STAGE" -type f ! -name '*.br' -print0 | while IFS= read -r -d '' f; do
  brotli -q 11 -f -o "$f.br" "$f"
done
echo "    $(find "$STAGE" -name '*.br' | wc -l | tr -d ' ') files precompressed"

python3 serve.py "$STAGE" "$PORT" &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT
sleep 1

# Proves the brotli path is actually being taken before ten minutes of measuring depend on it.
encoding=$(curl -s -o /dev/null -D - -H 'Accept-Encoding: br' "http://127.0.0.1:$PORT/app.js" \
  | tr -d '\r' | awk -F': ' '/^[Cc]ontent-[Ee]ncoding/ {print $2}')
[ "$encoding" = "br" ] || { echo "the server did not serve brotli (got '${encoding:-none}')" >&2; exit 1; }
echo "    serving brotli"

python3 cdp.py "http://127.0.0.1:$PORT/index.html" "$CHROME" $((PORT + 100)) "$LOADS" \
  "$RESULTS/time-to-first-frame.json"

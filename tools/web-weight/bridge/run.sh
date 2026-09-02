#!/usr/bin/env bash
# Project Dogwood -- run the JavaScript/WebAssembly bridge benchmark in a real browser.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # Gradle 8.14 will not run on JDK 25
#   ./bridge/run.sh
#
# Writes results/bridge-default.json and results/bridge-gufa-safe.json. `results/bridge.md` is
# written from them.
#
# Two builds are measured because Kotlin 2.3.20's own production optimiser pass list miscompiles
# `String.toCharArray()`; see the "probeBulkCopy" comment in bridge/src/wasmJsMain/kotlin/Main.kt.
#   - default:   exactly what `wasmJsBrowserDistribution` produces. What ships today.
#   - gufa-safe: the same pass list with `--gufa` removed. Correct, and the build whose numbers
#                can be compared row against row.
#
# The page is served over HTTP rather than opened as a file:// URL for two reasons:
# `WebAssembly.instantiateStreaming` requires an `application/wasm` content type, and the
# cross-origin isolation headers the server sets are what give `performance.now()` 5-microsecond
# resolution instead of 100.
set -euo pipefail
cd "$(dirname "$0")/.."

CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
SRC="bridge/build/compileSync/wasmJs/main/productionExecutable/kotlin"
OPT="bridge/build/compileSync/wasmJs/main/productionExecutable/optimized"
STAGE="bridge/build/harness"

[ -x "$CHROME" ] || { echo "Chrome not found at $CHROME; set CHROME=..." >&2; exit 1; }

echo "==> building"
./gradlew :bridge:wasmJsBrowserDistribution --console=plain -q
[ -f "$OPT/web-weight-bridge.wasm" ] || { echo "no optimised output in $OPT" >&2; exit 1; }

WASM_OPT="$(ls -d "$HOME"/.gradle/binaryen/binaryen-version_*/bin/wasm-opt 2>/dev/null | tail -1)"
[ -x "$WASM_OPT" ] || { echo "wasm-opt not found under ~/.gradle/binaryen" >&2; exit 1; }

# Kotlin's own pass list, minus `--gufa`. Kept in sync by hand with the command the Gradle task
# logs under `--info`; re-check it after any Kotlin upgrade.
GUFA_SAFE_ARGS=(--enable-gc --enable-reference-types --enable-exception-handling
  --enable-bulk-memory --enable-nontrapping-float-to-int --closed-world
  --no-inline=kotlin.wasm.internal.throwValue
  --no-inline=kotlin.wasm.internal.getKotlinException
  --no-inline=kotlin.wasm.internal.jsToKotlinStringAdapter
  --inline-functions-with-loops --traps-never-happen --fast-math --type-ssa
  -O3 -O3 -O3 --type-merging -O3 -Oz)

rm -rf "$STAGE"
mkdir -p "$STAGE/default" "$STAGE/gufa-safe" results

for v in default gufa-safe; do
  cp "$SRC"/web-weight-bridge.mjs "$SRC"/web-weight-bridge.import-object.mjs \
     "$SRC"/web-weight-bridge.js-builtins.mjs "$STAGE/$v/"
  cp bridge/harness/bench.html bridge/harness/bench.js "$STAGE/$v/"
done
cp "$OPT/web-weight-bridge.wasm" "$STAGE/default/"
"$WASM_OPT" "${GUFA_SAFE_ARGS[@]}" "$SRC/web-weight-bridge.wasm" -o "$STAGE/gufa-safe/web-weight-bridge.wasm"

port="${PORT:-8731}"
for v in default gufa-safe; do
  out="results/bridge-$v.json"
  rm -f "$out"
  echo "==> measuring '$v' on 127.0.0.1:$port"
  python3 bridge/harness/serve.py "$STAGE/$v" "$port" "$out" &
  server=$!
  sleep 1
  profile="$(mktemp -d)"
  # No graphics context is needed: this module deliberately links neither Compose nor Skiko, which
  # is what makes a headless run possible at all. The throttling flags matter because Chrome slows
  # timers in windows it believes are not visible, and a headless window always looks that way.
  "$CHROME" \
    --headless=new --disable-gpu --no-sandbox --no-first-run --no-default-browser-check \
    --disable-extensions --disable-background-timer-throttling \
    --disable-backgrounding-occluded-windows --disable-renderer-backgrounding \
    --user-data-dir="$profile" \
    "http://127.0.0.1:$port/bench.html?variant=$v" >/dev/null 2>&1 &
  browser=$!
  wait "$server" || { kill "$browser" 2>/dev/null || true; echo "'$v' did not report" >&2; exit 1; }
  kill "$browser" 2>/dev/null || true
  # Chrome keeps writing to its profile for a moment after SIGTERM, so removing it can race.
  # Losing a temporary directory is not worth failing a measurement run over.
  sleep 1
  rm -rf "$profile" 2>/dev/null || true
  port=$((port + 1))
done

for v in default gufa-safe; do
  echo
  echo "############ $v"
  python3 bridge/harness/report.py "results/bridge-$v.json"
done

#!/usr/bin/env bash
# Project Dogwood -- build the web slice and prove, in a real browser, that a guest's tree renders.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # Gradle 8.14 will not run on JDK 25
#   engine/samples/web-slice/run.sh
#
# Two runs, because two different things need proving and one page cannot show both:
#
#   render   -- the whole path. Sidecar check passes, Worker starts, guest composes, the host
#               decodes and applies the positional batch, Compose Multiplatform lays it out, an
#               event goes back and produces a new batch, and a correlated snapshot request is
#               answered.
#   refusal  -- the sidecar names a dictionary this client does not implement. The host must refuse
#               and the guest script must never be fetched, let alone executed. The server records
#               both, because "nothing rendered" is not evidence of a refusal -- it is also what a
#               broken guest looks like.
#
# The page is served over HyperText Transfer Protocol rather than opened as a `file://` URL because
# `WebAssembly.instantiateStreaming` requires an `application/wasm` content type and a Worker
# cannot be constructed from a `file://` origin at all.
#
# `--enable-unsafe-swiftshader` is what makes this runnable headless. Compose Multiplatform draws
# through Skiko, which needs a WebGL context; headless Chrome has no graphics processor, and the
# flag lets it fall back to SwiftShader's software renderer. Without it the page is blank and the
# run proves nothing.
set -euo pipefail
cd "$(dirname "$0")"

CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
DIST="build/dist/wasmJs/productionExecutable"
STAGE="build/harness"
RESULTS="build/results"

[ -x "$CHROME" ] || { echo "Chrome not found at $CHROME; set CHROME=..." >&2; exit 1; }

echo "==> building"
(cd ../.. && ./gradlew :samples:web-slice:wasmJsBrowserDistribution --console=plain -q)
[ -f "$DIST/app.js" ] || { echo "no distribution in $DIST" >&2; exit 1; }

# The staged copy carries the harness probe alongside the shipped page. `index.html` itself stays
# free of it: a sample that has to be instrumented to run is not a sample.
rm -rf "$STAGE" "$RESULTS"
mkdir -p "$STAGE" "$RESULTS"
cp -R "$DIST"/. "$STAGE/"
cp harness/probe.js "$STAGE/"
# `harness.html` is `index.html` with the probe appended, so it keeps the sample's own Content
# Security Policy. Verifying a page with its protections removed verifies a page nobody ships.
sed 's#<script src="app.js"></script>#<script src="app.js"></script><script src="probe.js"></script>#' \
  "$DIST/index.html" > "$STAGE/harness.html"
grep -q 'probe.js' "$STAGE/harness.html" || { echo "failed to stage the harness page" >&2; exit 1; }

port="${PORT:-8791}"
status=0

# Killing the Chrome process is not enough: it leaves its renderer and graphics-process helpers
# alive, and enough of them accumulating across runs starves SwiftShader -- at which point the page
# stops painting and the screenshot comes out uniformly white, which looks exactly like a broken
# host. The `--user-data-dir` is unique per launch, so matching on it kills that instance's helpers
# and nothing else, including a real browser the person running this has open.
stop_browser() {
  local pid="$1" profile="$2"
  kill "$pid" 2>/dev/null || true
  pkill -f -- "$profile" 2>/dev/null || true
  sleep 1
}

run() {
  local mode="$1" query="$2"
  local out="$RESULTS/$mode.json"
  echo
  echo "==> $mode  (http://127.0.0.1:$port/harness.html$query)"
  python3 harness/serve.py "$STAGE" "$port" "$out" &
  local server=$!
  sleep 1
  local profile
  profile="$(mktemp -d)"
  "$CHROME" \
    --headless=new --no-sandbox --no-first-run --no-default-browser-check \
    --disable-extensions --enable-unsafe-swiftshader \
    --disable-background-timer-throttling --disable-backgrounding-occluded-windows \
    --disable-renderer-backgrounding --window-size=900,700 \
    --user-data-dir="$profile" \
    "http://127.0.0.1:$port/harness.html$query" >/dev/null 2>&1 &
  local browser=$!
  # Detached from job control, so that stopping it later does not print a "Terminated" line into
  # the middle of the verification output.
  disown "$browser" 2>/dev/null || true
  if ! wait "$server"; then
    stop_browser "$browser" "$profile"
    echo "  the page never reported" >&2
    status=1
  else
    stop_browser "$browser" "$profile"
    python3 harness/check.py "$mode" "$out" || status=1
  fi
  # Chrome keeps writing to its profile for a moment after SIGTERM, so removing it can race.
  sleep 1
  rm -rf "$profile" 2>/dev/null || true
  port=$((port + 1))
}

run render ""
run refusal "?manifest=dogwood-manifest-too-new.json"

# ---------------------------------------------------------------------------------------------
# The pixel check.
#
# The two runs above prove the protocol path: the tree decoded, the bindings composed, and the
# layout pass measured non-zero glyph boxes for the guest's strings. None of that is a pixel. This
# takes a screenshot of the shipped `index.html` -- not the instrumented harness page -- and
# asserts that the exact colour the guest asked for is on it.
#
# `--timeout` is what makes a screenshot possible at all here. Compose runs a permanent
# `requestAnimationFrame` loop, so the page never reaches the idle state a plain `--screenshot`
# waits for and `--virtual-time-budget` never advances past it; `--timeout` captures after a fixed
# wall-clock delay instead.
# ---------------------------------------------------------------------------------------------
echo
echo "==> screenshot  (http://127.0.0.1:$port/index.html)"
shot="$RESULTS/slice.png"
python3 -m http.server "$port" --directory "$STAGE" --bind 127.0.0.1 >/dev/null 2>&1 &
shotserver=$!
disown "$shotserver" 2>/dev/null || true
sleep 1

# Retried, and the retry is not superstition. Chrome leaves renderer and graphics helpers behind
# when it is stopped, they do not carry the `--user-data-dir` that would let them be matched and
# killed, and once enough of them are resident a fresh headless instance comes up with a WebGL
# context that never paints. The symptom is a uniformly white screenshot, which is indistinguishable
# from a host that rendered nothing -- so a single attempt would turn a browser resource problem
# into a false report about Dogwood.
shot_ok=0
for attempt in 1 2 3; do
  rm -f "$shot"
  profile="$(mktemp -d)"
  # The three throttling flags are not optional, and their absence is silent: Chrome slows
  # renderers in windows it believes are not visible, a headless window always looks that way, and
  # the result is a page that never paints.
  #
  # Waited on by the clock rather than by Chrome's exit, because `--timeout` makes Chrome take the
  # capture after ten seconds but does not reliably make it exit afterwards, and a verification step
  # that hangs is worse than one that is merely slow.
  "$CHROME" \
    --headless=new --no-sandbox --no-first-run --no-default-browser-check --disable-extensions \
    --enable-unsafe-swiftshader --disable-background-timer-throttling \
    --disable-backgrounding-occluded-windows --disable-renderer-backgrounding \
    --window-size=900,700 --user-data-dir="$profile" \
    --screenshot="$shot" --timeout=10000 "http://127.0.0.1:$port/index.html" >/dev/null 2>&1 &
  shotbrowser=$!
  disown "$shotbrowser" 2>/dev/null || true
  sleep 14
  stop_browser "$shotbrowser" "$profile"
  rm -rf "$profile" 2>/dev/null || true
  if [ -f "$shot" ]; then
    # 008080 is the swatch the guest paints: `[COLOR_ARGB, 4278222976]` is 0xFF008080.
    if python3 harness/pixels.py "$shot" 008080 5000; then
      shot_ok=1
      break
    fi
  else
    echo "  attempt $attempt produced no screenshot"
  fi
  echo "  attempt $attempt did not paint; retrying"
  sleep 3
done
kill "$shotserver" 2>/dev/null || true
[ "$shot_ok" -eq 1 ] || status=1

echo
if [ "$status" -eq 0 ]; then
  echo "PASS -- reports in $RESULTS"
else
  echo "FAIL -- reports in $RESULTS" >&2
fi
exit "$status"

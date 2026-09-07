#!/usr/bin/env bash
# Project Dogwood -- skew containment on the web, end to end, without hand-editing anything.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/skew-drill/run-web.sh
#
# The Android drill's other twin. The two-build shape is the same and, on this client, it is the
# most literal of the three: the host bindings are compiled into `app.js` and its WebAssembly
# chunks, the guest is `guest-kotlin.js` fetched by the page at run time, and both sit in one
# directory. So "serve a newer payload to an older client" is exactly what it sounds like --
# rebuild one file in that directory and leave the rest alone.
#
#   1. Build the whole distribution at the committed dictionary version N.
#   2. Patch the surface with three additions, one per claim, and bump to N+1.
#   3. Rebuild **only** the guest Worker script and copy it over the one in the distribution. The
#      WebAssembly module is not rebuilt, and that is the entire experiment.
#   4. Serve the directory and drive a real browser against it.
#   5. Restore the surface, always.
#
# This client also checks the payload's declared dictionary versions before it creates the Worker,
# which the mobile clients do not, so the drill runs that half too -- see `check_web.py`.
#
# No device is needed, which makes this the one skew drill that could run in continuous
# integration. It does not today: it wants a real Chrome and a two-stage Gradle build, and
# `.github/workflows/conformance.yml` grades tier S only.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/build"
OUT="$HERE/build/skew-web.conf"
PORT="${PORT:-8897}"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
cd "$HERE/../../engine"

SURFACE="surface/dev/dogwood/surface/DesignSystemSurface.kt"
LOCK="surface/dogwood.designsystem.lock.json"
CODEGEN="dogwood-codegen/build.gradle.kts"
ABOUT="samples/slice-screens/src/jsMain/kotlin/dev/dogwood/slice/AboutScreen.kt"
TOUCHED=("$SURFACE" "$LOCK" "$CODEGEN" "$ABOUT")

DIST="samples/web-slice/build/dist/wasmJs/productionExecutable"
GUEST="samples/web-guest/build/kotlin-webpack/js/productionExecutable/guest-kotlin.js"

[ -x "$CHROME" ] || { echo "Chrome not found at $CHROME; set CHROME=..." >&2; exit 1; }

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
  rm -f "$DIST/dogwood-manifest-skewed.json"
  # Rebuild the guest from the restored surface, so the distribution is not left holding a skewed
  # payload for whoever opens the sample next. The Android drill does the same for the same reason.
  ./gradlew :samples:web-guest:jsBrowserProductionWebpack --console=plain -q >/dev/null 2>&1 \
    && cp "$GUEST" "$DIST/guest-kotlin.js" 2>/dev/null || true
}
trap restore EXIT

echo "==> building the whole distribution at the committed version"
./gradlew :samples:web-slice:wasmJsBrowserDistribution --console=plain -q || exit 1
[ -f "$DIST/app.js" ] || { echo "no distribution in $DIST" >&2; exit 1; }
# The client's identity, recorded before it can be disturbed. If this changes across the run, the
# host was rebuilt and there was no skew -- the one way this drill could quietly test nothing.
host_before="$(shasum -a 256 "$DIST/app.js" | cut -c1-16)"

echo "==> skewing the surface to N+1"
python3 "$HERE/skew.py" "$SURFACE" "$CODEGEN" || exit 1
version="$(grep -o '"--version", "[0-9]*"' "$CODEGEN" | grep -o '[0-9]*')"

echo "==> rebuilding only the guest Worker script"
./gradlew :samples:web-guest:jsBrowserProductionWebpack --console=plain -q || exit 1
grep -q "SKEW-CALLOUT" "$GUEST" || { echo "the rebuilt guest carries no skew markers" >&2; exit 1; }
cp "$GUEST" "$DIST/guest-kotlin.js" || exit 1

# The declared-skew sidecar, for the second half. Written here rather than committed because the
# version it names is whatever the patch bumped to, and a committed copy would go stale silently --
# which is exactly what happened to `dogwood-manifest-kotlin.json`, which still names 9.
python3 - "$DIST/dogwood-manifest-skewed.json" "$version" <<'PY'
import json, sys
path, version = sys.argv[1], int(sys.argv[2])
json.dump({
    "_comment": "Written by tools/skew-drill/run-web.sh. The same skewed payload as "
                "dogwood-manifest-kotlin.json, with its dictionary version declared honestly, so "
                "the client must refuse it before creating the Worker.",
    "envelopeRevision": 1,
    "guestScript": "guest-kotlin.js",
    "segmentVersions": {"androidx.layout": 1, "dogwood.designsystem": version},
    "guestScriptSha256": None,
}, open(path, "w"), indent=2)
PY

host_after="$(shasum -a 256 "$DIST/app.js" | cut -c1-16)"
if [ "$host_before" != "$host_after" ]; then
  echo "the host module was rebuilt ($host_before -> $host_after); there is no skew to test" >&2
  exit 1
fi
echo "==> host module unchanged at $host_after; guest is at dictionary version $version"

python3 -m http.server "$PORT" --directory "$DIST" --bind 127.0.0.1 >/dev/null 2>&1 &
server=$!
trap 'kill $server 2>/dev/null || true; restore' EXIT
sleep 1

python3 "$HERE/check_web.py" "http://127.0.0.1:$PORT/index.html" "$CHROME" $((PORT + 500)) "$OUT"
exit $?

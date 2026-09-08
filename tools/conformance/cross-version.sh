#!/usr/bin/env bash
# Project Dogwood -- today's host runs an older payload.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/conformance/cross-version.sh
#
# The over-the-air pairing every real deployment has and nothing here exercised (adoption audit A6):
# a payload compiled by an EARLIER toolchain meeting a host built from current sources. Users update
# applications slowly, so this is not an edge case, it is the normal case — and the drill exists
# because "hosts first, payloads after the fleet" is a policy, and a policy is not a test.
#
# The payload is a committed fixture rather than a rebuild, for the reason `fixtures/README.md`
# gives: a rebuild is today's toolchain, which is the pairing that already works.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/build"
OUT="${1:-$HERE/build/cross-version.conf}"
PORT="${PORT:-8474}"
FIXTURE="$(ls -d "$HERE"/fixtures/payload-* 2>/dev/null | sort | tail -1)"

[ -n "$FIXTURE" ] || { echo "no frozen payload in $HERE/fixtures" >&2; exit 1; }
echo "==> serving $(basename "$FIXTURE") to a host built from current sources"

python3 -m http.server "$PORT" --directory "$FIXTURE" --bind 127.0.0.1 >/dev/null 2>&1 &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT
for _ in $(seq 1 40); do curl -fs -m 1 -o /dev/null "http://127.0.0.1:$PORT/manifest.zipline.json" && break; sleep 0.25; done

# A cold cache, or Zipline serves the modules it already has and the fixture is never fetched --
# the same trap the reference-server check fell into, where a warm cache made a null run look green.
rm -rf "${TMPDIR:-/tmp}/dogwood-cache"

log="$HERE/build/cross-version.log"
( cd "$HERE/../../engine" && ./gradlew :samples:slice-desktop:run --console=plain -q \
    -Ddogwood.manifest="http://127.0.0.1:$PORT/manifest.zipline.json" > "$log" 2>&1 ) &
client=$!
sleep 55
kill $client 2>/dev/null || true
pkill -f "slice.desktop.MainKt" 2>/dev/null || true

lines=()
passed=0; failed=0
conform() { # id, condition, detail
  if [ "$2" = "1" ]; then passed=$((passed+1)); lines+=("CONF $1 PASS -- $3")
  else failed=$((failed+1)); lines+=("CONF $1 FAIL -- $3"); fi
}

# K1 -- it loads and VERIFIES. The signature is the half that would break first on a protocol
# change, and it is checked against keys committed with the fixture rather than regenerated.
verified=$(grep -c "loaded version .*verified by dogwood-development" "$log" || true)
conform "K1" "$([ "$verified" -ge 1 ] && echo 1 || echo 0)" \
  "$(grep -m1 'loaded version' "$log" || echo 'the host never reported a load')"

# K2 -- it RENDERS. Loading proves the delivery path; a guest that loads and then fails to compose
# is the failure this pairing actually produces, and only the guest's own log line shows it.
composed=$(grep -c "explore: loaded .* destinations" "$log" || true)
conform "K2" "$([ "$composed" -ge 1 ] && echo 1 || echo 0)" \
  "$(grep -m1 'explore: loaded' "$log" || echo 'the guest never composed its screen')"

# The control: a run where the host never started at all would satisfy nothing above, but would
# also produce no evidence of having tried. This distinguishes the two.
started=$(grep -cE "loaded version|could not load" "$log" || true)
conform "K-control" "$([ "$started" -ge 1 ] && echo 1 || echo 0)" \
  "the host reached its delivery path"

printf '%s\n' "${lines[@]}" > "$OUT"
printf '%s\n' "${lines[@]}"
echo "CONF RESULT client=desktop passed=$passed failed=$failed skipped=0" >> "$OUT"
echo
if [ "$failed" = "0" ]; then
  echo "PASS -- a payload from $(basename "$FIXTURE" | sed 's/payload-//') runs on a host built today"
else
  echo "FAIL -- see $log" >&2; exit 1
fi

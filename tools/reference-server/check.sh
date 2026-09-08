#!/usr/bin/env bash
# Project Dogwood -- the reference server does the four things that are expensive to get wrong.
#
#   tools/reference-server/check.sh
#
# Each assertion is an OBSERVED response, not a reading of the code: the headers a client actually
# receives, the release a given cohort actually gets, and the rollback actually taking effect. The
# four behaviours are the ones `docs/operating.md` §5 says a deployment owes you, and the reason
# this file exists is that "the server does the right thing" is not checkable by inspection.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(mktemp -d)"
# Not 8123: the Android network-policy drill's witness server uses that, and a leftover one
# answered this check's readiness probe with a 200 that had none of our headers -- two assertions
# failed against a server that was never ours. Readiness now looks for OUR marker header.
PORT="${PORT:-8471}"
pass=0; fail=0
trap 'kill $server 2>/dev/null || true; rm -rf "$ROOT"' EXIT

check() { # name, condition-output, expectation
  if [ "$2" = "$3" ]; then pass=$((pass+1)); echo "  ok   $1"
  else fail=$((fail+1)); echo "  FAIL $1 -- got '$2', wanted '$3'" >&2; fi
}

# Two releases, each a plausible payload directory.
for v in 1.0.0 1.1.0; do
  mkdir -p "$ROOT/src-$v"
  printf '{"version":"%s","modules":{}}' "$v" > "$ROOT/src-$v/manifest.zipline.json"
  printf 'payload bytes for %s' "$v" > "$ROOT/src-$v/module-$v.zipline"
done

echo "==> publish"
"$HERE/server.py" publish --root "$ROOT" --from "$ROOT/src-1.0.0" --version 1.0.0 >/dev/null
"$HERE/server.py" publish --root "$ROOT" --from "$ROOT/src-1.1.0" --version 1.1.0 >/dev/null
# A publish must NOT go live: a release that met every device the moment it was built would make
# the rollout controls decorative.
check "a new publish is staged, not live" \
  "$("$HERE/server.py" status --root "$ROOT" | python3 -c 'import json,sys; s=json.load(sys.stdin); print(s["live"], s["staged"], s["percent"])')" \
  "1.0.0 1.1.0 0"

"$HERE/server.py" serve --root "$ROOT" --port "$PORT" >/dev/null 2>&1 &
server=$!
ready=0
for _ in $(seq 1 80); do
  if curl -fsS -D - -o /dev/null -m 1 "http://127.0.0.1:$PORT/manifest.zipline.json" 2>/dev/null | grep -q "X-Dogwood-Release"; then
    ready=1; break
  fi
  sleep 0.25
done
[ "$ready" = "1" ] || { echo "FAIL -- the reference server never answered on :$PORT" >&2; exit 1; }

echo "==> the cache split"
manifest_cache=$(curl -fsS -D - -o /dev/null "http://127.0.0.1:$PORT/manifest.zipline.json" | tr -d '\r' | awk -F': ' '/^Cache-Control/{print $2}')
module_cache=$(curl -fsS -D - -o /dev/null "http://127.0.0.1:$PORT/module-1.0.0.zipline" | tr -d '\r' | awk -F': ' '/^Cache-Control/{print $2}')
# The manifest is the only mutable thing here. A cached one is a fleet that can neither be updated
# nor rolled back -- the failure that outlasts the outage.
check "the manifest is never cached" "$manifest_cache" "no-store"
check "modules are immutable forever" "$module_cache" "public, max-age=31536000, immutable"

echo "==> content encoding"
encoding=$(curl -fsS -D - -o /dev/null -H 'Accept-Encoding: br, gzip' "http://127.0.0.1:$PORT/manifest.zipline.json" | tr -d '\r' | awk -F': ' '/^Content-Encoding/{print $2}')
if python3 -c 'import brotli' 2>/dev/null; then
  check "brotli when the client offers it" "$encoding" "br"
else
  echo "  note brotli module absent; the server warns and serves gzip"
  check "gzip when brotli is unavailable" "$encoding" "gzip"
fi

echo "==> staged rollout by cohort"
"$HERE/server.py" rollout --root "$ROOT" --version 1.1.0 --percent 10 >/dev/null
inside=$(curl -fsS -D - -o /dev/null "http://127.0.0.1:$PORT/manifest.zipline.json?cohort=3" | tr -d '\r' | awk -F': ' '/^X-Dogwood-Release/{print $2}')
outside=$(curl -fsS -D - -o /dev/null "http://127.0.0.1:$PORT/manifest.zipline.json?cohort=42" | tr -d '\r' | awk -F': ' '/^X-Dogwood-Release/{print $2}')
# The control is the second line: without it, "cohort 3 got the new release" is also what a server
# ignoring the parameter and serving everyone the new release looks like.
check "a cohort inside the rollout gets the staged release" "$inside" "1.1.0"
check "a cohort outside it does not" "$outside" "1.0.0"

echo "==> resuming a previous release"
"$HERE/server.py" resume --root "$ROOT" --version 1.0.0 >/dev/null
resumed=$(curl -fsS -D - -o /dev/null "http://127.0.0.1:$PORT/manifest.zipline.json?cohort=3" | tr -d '\r' | awk -F': ' '/^X-Dogwood-Release/{print $2}')
# Rolling back needs no client cooperation: a device quarantines a *version*, and a different
# version is not quarantined. That is why this is server state rather than a protocol.
check "the cohort that had the new release is served the old one" "$resumed" "1.0.0"

# ---------------------------------------------------------------------------------------------
# And a real client, because everything above is the server agreeing with itself.
#
# Skipped rather than failed when the payload has not been built: this check's own subject is the
# server, and a missing payload is a missing prerequisite, not a defect. When it runs, it is the
# only assertion here that involves signature verification and a real Zipline load.
# ---------------------------------------------------------------------------------------------
payload="$HERE/../../engine/samples/slice-guest/build/zipline/ProductionWebpack"
if [ -f "$payload/manifest.zipline.json" ] && [ -n "${JAVA_HOME:-}" ]; then
  echo "==> a real client loads through it"
  live="$(mktemp -d)"
  "$HERE/server.py" publish --root "$live" --from "$payload" --version 1.0.0 >/dev/null
  "$HERE/server.py" serve --root "$live" --port "$((PORT + 1))" > "$live/serve.log" 2>&1 &
  client_server=$!
  for _ in $(seq 1 80); do
    curl -fsS -D - -o /dev/null -m 1 "http://127.0.0.1:$((PORT + 1))/manifest.zipline.json" 2>/dev/null | grep -q "X-Dogwood-Release" && break
    sleep 0.25
  done
  # A cold cache, so the modules are actually fetched: Zipline caches by content digest, and a warm
  # cache made the first run of this look like a success while serving nothing but the manifest.
  rm -rf "${TMPDIR:-/tmp}/dogwood-cache"
  ( cd "$HERE/../../engine" && ./gradlew :samples:slice-desktop:run --console=plain -q       -Ddogwood.manifest="http://127.0.0.1:$((PORT + 1))/manifest.zipline.json" > "$live/client.log" 2>&1 ) &
  client=$!
  sleep 50
  kill $client 2>/dev/null || true
  pkill -f "slice.desktop.MainKt" 2>/dev/null || true
  kill $client_server 2>/dev/null || true
  loaded=$(grep -c "loaded version 1.0.0, verified by" "$live/client.log" || true)
  modules=$(grep -c "GET /slice-guest.zipline" "$live/serve.log" || true)
  check "the client loaded and verified the signed manifest" "$loaded" "1"
  check "the client fetched a module from this server" "$modules" "1"
  rm -rf "$live"
else
  echo "  note no built payload or no JAVA_HOME; the real-client leg was skipped"
fi

echo
if [ "$fail" = "0" ]; then echo "PASS -- $pass behaviours observed"; else echo "FAIL -- $fail of $((pass+fail))" >&2; exit 1; fi

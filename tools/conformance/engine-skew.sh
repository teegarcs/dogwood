#!/usr/bin/env bash
# Project Dogwood -- claims `K3` and `K4`: a payload and a host built from *different engine
# versions*, meeting.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/conformance/engine-skew.sh [tag]      # default: the newest v* tag
#
# This is the drill the adoption audit's `A6` asked for and could not have. `cross-version.sh`
# beside it serves a **frozen payload artifact** to a host built today, which covers a payload that
# is merely old. What it cannot cover is the pairing a real deployment reaches the other way: a
# payload built from *today's* engine meeting a host shipped from an *older* one, still installed on
# somebody's machine because users update applications slowly.
#
# That needs two engine versions to exist. Until 2026-09-16 only one ever had, which is why the
# audit cell read "blocked until two engine versions exist" rather than "not done". The tag is the
# second one.
#
# **Two claims, opposite directions, and the second is the one that matters.**
#
#   K3  A host from the older engine REFUSES a payload built today that declares more than it has.
#       Not "renders badly" -- refuses, before `start`, naming the segment (ADR-061). The generated
#       Material 3 tier makes this concrete: today's payload declares a segment version the older
#       host has never implemented.
#   K4  A host built today RUNS a payload built by the older engine. The compatibility direction a
#       fleet actually lives in.
#
# The desktop client, because it needs no device and `-Ddogwood.manifest` already points it
# anywhere. Both halves are real builds of real trees; nothing here is simulated.
set -uo pipefail

# Gradle refuses a Java it does not support, and reports it by printing the version and nothing
# else -- "25.0.2" as the entire "what went wrong". That is indistinguishable from a crash unless
# you already know, so it is checked here with a sentence instead. The header says to export
# JAVA_HOME; this is what happens when somebody does not.
java_version="$("${JAVA_HOME:-/usr}/bin/java" -version 2>&1 | head -1)"
case "$java_version" in
  *\"21*) ;;
  *) echo "this build needs a Java 21 toolchain; found: $java_version" >&2
     echo "  export JAVA_HOME=/opt/homebrew/opt/openjdk@21" >&2
     exit 2 ;;
esac
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TAG="${1:-$(git -C "$ROOT" tag -l 'v*' | sort -V | tail -1)}"
PORT="${PORT:-8477}"
OUT="$HERE/build/engine-skew.conf"
mkdir -p "$HERE/build"

[ -n "$TAG" ] || { echo "no v* tag to compare against; tag an engine version first" >&2; exit 2; }
echo "==> comparing HEAD against $TAG"

WORKTREE="${TMPDIR:-/tmp}/dogwood-engine-$TAG"
cleanup() {
  # Never `kill ${server:-0}`: unset, that is `kill 0` and signals this shell's whole process
  # group, which ends the drill with exit 144 and no output.
  [ -n "${server:-}" ] && kill "$server" 2>/dev/null
  git -C "$ROOT" worktree remove --force "$WORKTREE" 2>/dev/null || true
}
trap cleanup EXIT
git -C "$ROOT" worktree remove --force "$WORKTREE" 2>/dev/null || true
git -C "$ROOT" worktree add --detach "$WORKTREE" "$TAG" >/dev/null || exit 1

passed=0; failed=0
: > "$OUT"
conform() { # id, ok(1/0), detail
  local line
  if [ "$2" = "1" ]; then passed=$((passed+1)); line="CONF $1 PASS -- $3"
  else failed=$((failed+1)); line="CONF $1 FAIL -- $3"; fi
  # Printed and appended as it happens, not collected. `${lines[-1]}` is a bash 4 spelling and this
  # runs under the bash 3.2 macOS ships, where it is an unbound variable under `set -u` -- so the
  # drill died at its first verdict with "bad array subscript" and no result.
  printf '%s\n' "$line" | tee -a "$OUT"
}

serve() { # directory
  # Never `kill ${server:-0}`: unset, that is `kill 0` and signals this shell's whole process
  # group, which ends the drill with exit 144 and no output.
  [ -n "${server:-}" ] && kill "$server" 2>/dev/null
  python3 -m http.server "$PORT" --directory "$1" --bind 127.0.0.1 >/dev/null 2>&1 &
  server=$!
  for _ in $(seq 1 40); do
    curl -fs -m 1 -o /dev/null "http://127.0.0.1:$PORT/manifest.zipline.json" && return 0
    sleep 0.25
  done
  return 1
}

# A cold cache each time, or Zipline serves the modules it already has and the drill grades nothing
# -- the trap the reference-server check fell into once and records.
run_host() { # engine directory, log
  rm -rf "${TMPDIR:-/tmp}/dogwood-cache" "${TMPDIR:-/tmp}/dogwood-release-desktop.json"
  ( cd "$1" && ./gradlew :samples:slice-desktop:run --console=plain -q --max-workers=2 \
      -Ddogwood.manifest="http://127.0.0.1:$PORT/manifest.zipline.json" > "$2" 2>&1 ) &
  local runner=$!
  for _ in $(seq 1 90); do
    grep -qE "loaded version|could not load|refused" "$2" 2>/dev/null && break
    sleep 2
  done
  kill "$runner" 2>/dev/null || true
  pkill -f "slice-desktop" 2>/dev/null || true
}

echo "==> building today's payload"
( cd "$ROOT/engine" && ./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline \
    --console=plain -q --max-workers=2 ) || exit 1
NEW_PAYLOAD="$ROOT/engine/samples/slice-guest/build/zipline/ProductionWebpack"

echo "==> building the payload as $TAG built it"
( cd "$WORKTREE/engine" && ./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline \
    --console=plain -q --max-workers=2 ) || exit 1
OLD_PAYLOAD="$WORKTREE/engine/samples/slice-guest/build/zipline/ProductionWebpack"

declared_new="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("metadata",{}).get("dogwood.segments",""))' "$NEW_PAYLOAD/manifest.zipline.json")"
declared_old="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("metadata",{}).get("dogwood.segments",""))' "$OLD_PAYLOAD/manifest.zipline.json")"
echo "==> today declares [$declared_new]"
echo "==> $TAG declares  [$declared_old]"

# The drill is only meaningful if the two payloads declare different dictionaries. If the engine's
# vocabulary did not move between the two versions there is nothing to refuse, and saying so is
# better than grading a pair that cannot disagree.
if [ "$declared_new" = "$declared_old" ]; then
  echo "the two engine versions declare the same dictionary; there is no skew to grade" >&2
  echo "CONF K3 SKIP -- $TAG and HEAD declare the same dictionary" | tee "$OUT"
  echo "CONF RESULT client=desktop passed=0 failed=0 skipped=1" >> "$OUT"
  exit 0
fi

echo "==> K3: today's payload, to the host $TAG built"
serve "$NEW_PAYLOAD" || exit 1
run_host "$WORKTREE/engine" "$HERE/build/engine-skew-k3.log"
if grep -qE "refused|does not implement" "$HERE/build/engine-skew-k3.log"; then k3=1; else k3=0; fi
conform "K3" "$k3" \
  "$(grep -oE '(refused|does not implement)[^\"]{0,120}' "$HERE/build/engine-skew-k3.log" | head -1 || echo "the older host did not refuse today's payload")"

echo "==> K4: the payload $TAG built, to the host built today"
serve "$OLD_PAYLOAD" || exit 1
run_host "$ROOT/engine" "$HERE/build/engine-skew-k4.log"
if grep -q "loaded version" "$HERE/build/engine-skew-k4.log"; then k4=1; else k4=0; fi
conform "K4" "$k4" \
  "$(grep -o 'loaded version[^\"]\{0,80\}' "$HERE/build/engine-skew-k4.log" | head -1 || echo "today's host did not load the older payload")"

echo "CONF RESULT client=desktop passed=$passed failed=$failed skipped=0" >> "$OUT"
[ "$failed" = "0" ]

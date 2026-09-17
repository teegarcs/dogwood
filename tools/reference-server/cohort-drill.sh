#!/usr/bin/env bash
# Project Dogwood -- a bad release published to ten buckets, and the other ninety untouched.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/reference-server/cohort-drill.sh
#
# ADR-049 gave every installation a stable bucket 0-99 and called it "the client's half" of staged
# rollout. It was less than half: the client computed the number and **sent it nowhere**, so the
# other end could not have used it if it had wanted to. Two things closed that, and this drill is
# where they meet:
#
#   * `DogwoodDelivery` appends `?cohort=N` to every manifest request (`withCohort`, and
#     `CohortRequestTest.theRequestActuallyCarriesTheBucket`, which asserts the address the client
#     actually asked for rather than the string the function returned);
#   * `server.py` reads `cohorts.json` -- bucket ranges pinned to releases -- and serves the
#     matching manifest.
#
# The claim under test is the one a staged rollout exists for and the one that is easy to believe
# without checking: **a release published to buckets 0-9 reaches buckets 0-9 and nobody else.** A
# server that ignored the parameter entirely would satisfy "bucket 3 got the new release"; what it
# cannot satisfy is the ninety-row half, which is why the sweep below asks all one hundred buckets
# rather than a sample.
#
# The "bad" release is bad only in name. What makes a release bad -- crashing on launch -- is the
# quarantine drill's subject, and a device quarantining itself would confuse the two mechanisms:
# this one is about who is *offered* which bytes, which is the decision that happens before any
# device has an opinion.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"
ENGINE="$ROOT_DIR/engine"
SERVE_ROOT="$(mktemp -d)"
PORT="${PORT:-8475}"
mkdir -p "$HERE/build"
LOG="$HERE/build/cohort.log"
: > "$LOG"

# The blast radius, and the flag that widens it. `--range 0-99` publishes the bad release to every
# bucket, which is what a rollout control that does not work looks like: `C1` still passes and `C2`
# goes red. Every other claim here is satisfied by that run, which is the reason `C2` exists.
RANGE="0-9"
OUT="$HERE/build/cohort.conf"
while [ $# -gt 0 ]; do
  case "$1" in
    --range) RANGE="$2"; shift 2 ;;
    *) OUT="$1"; shift ;;
  esac
done
RANGE_LOW="${RANGE%-*}"
RANGE_HIGH="${RANGE#*-}"

passed=0; failed=0
lines=()
conform() { # id, condition(1|0), detail
  local line
  if [ "$2" = "1" ]; then passed=$((passed+1)); line="CONF $1 PASS -- $3"
  else failed=$((failed+1)); line="CONF $1 FAIL -- $3"; fi
  lines+=("$line")
  echo "$line"
}

# `kill "${server:-0}"` is what the other drills in this directory write, and it is a trap: when the
# variable is unset -- every path that fails before the server starts -- it expands to `kill 0`,
# which signals the whole process GROUP, taking the drill and its caller down with exit 144 and no
# output. Watched: a guest-build failure in this script produced exactly that, twice, and looked
# like the drill hanging rather than like a build error.
cleanup() { [ -n "${server:-}" ] && kill "$server" 2>/dev/null; rm -rf "$SERVE_ROOT"; return 0; }
trap cleanup EXIT

[ -n "${JAVA_HOME:-}" ] || { echo "JAVA_HOME is not set" >&2; exit 1; }

build() { # version
  ( cd "$ENGINE" && ./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline \
      -PdogwoodVersion="$1" --max-workers=2 --console=plain -q ) >>"$LOG" 2>&1 || {
    echo "the guest build failed for $1; see $LOG" >&2; exit 1; }
  rm -rf "$SERVE_ROOT/src"
  cp -R "$ENGINE/samples/slice-guest/build/zipline/ProductionWebpack" "$SERVE_ROOT/src"
  "$HERE/server.py" publish --root "$SERVE_ROOT" --from "$SERVE_ROOT/src" --version "$1" >>"$LOG"
}

echo "==> publishing 1.0.0-good to everyone"
build 1.0.0-good
"$HERE/server.py" rollout --root "$SERVE_ROOT" --version 1.0.0-good --percent 100 >>"$LOG"

echo "==> publishing 1.1.0-bad, and pinning it to buckets $RANGE and nothing else"
build 1.1.0-bad
# Explicitly at zero percent. The pin is then the ONLY thing that can deliver this release, so a
# bucket seeing it is evidence about `cohorts.json` rather than about the percentage -- and `C5`
# below says so directly.
"$HERE/server.py" rollout --root "$SERVE_ROOT" --version 1.1.0-bad --percent 0 >>"$LOG"

"$HERE/server.py" serve --root "$SERVE_ROOT" --port "$PORT" >>"$LOG" 2>&1 &
server=$!
URL="http://127.0.0.1:$PORT/manifest.zipline.json"
ready=0
for _ in $(seq 1 80); do
  curl -fsS -D - -o /dev/null -m 1 "$URL" 2>/dev/null | grep -q "X-Dogwood-Release" && { ready=1; break; }
  sleep 0.25
done
[ "$ready" = "1" ] || { echo "the reference server never answered on :$PORT" >&2; exit 1; }

release_for() { # bucket
  curl -fsS -D - -o /dev/null "$URL?cohort=$1" | tr -d '\r' | awk -F': ' '/^X-Dogwood-Release/{print $2}'
}

# ---------------------------------------------------------------------------------------------
# The control FIRST, before anything is pinned: with no `cohorts.json`, all one hundred buckets see
# the live release. Without this line, a sweep that found 100 buckets on the good release after the
# pin and a sweep that found 100 before it would be indistinguishable -- and so would a server that
# had crashed and a curl that was reading a cached header.
# ---------------------------------------------------------------------------------------------
echo
echo "==> the control: nothing pinned yet"
before_good=0
for bucket in $(seq 0 99); do
  [ "$(release_for "$bucket")" = "1.0.0-good" ] && before_good=$((before_good+1))
done
conform "C-control" "$([ "$before_good" = "100" ] && echo 1 || echo 0)" \
  "with no cohorts.json, all 100 buckets see the live release ($before_good of 100)"

"$HERE/server.py" cohorts --root "$SERVE_ROOT" --range "$RANGE" --version 1.1.0-bad >>"$LOG"
echo "==> pinned; sweeping all 100 buckets"

inside_bad=0; inside_total=0; outside_good=0; outside_total=0; wrong=()
for bucket in $(seq 0 99); do
  got="$(release_for "$bucket")"
  if [ "$bucket" -ge "$RANGE_LOW" ] && [ "$bucket" -le "$RANGE_HIGH" ]; then
    inside_total=$((inside_total+1))
    if [ "$got" = "1.1.0-bad" ]; then inside_bad=$((inside_bad+1)); else wrong+=("$bucket=$got"); fi
  else
    outside_total=$((outside_total+1))
    if [ "$got" = "1.0.0-good" ]; then outside_good=$((outside_good+1)); else wrong+=("$bucket=$got"); fi
  fi
done

conform "C1" "$([ "$inside_bad" = "$inside_total" ] && echo 1 || echo 0)" \
  "every bucket in $RANGE is served 1.1.0-bad ($inside_bad of $inside_total)"
# The half a broken rollout control cannot satisfy, and the reason this drill exists.
#
# `outside_total` is asserted to be non-zero, and that is not belt and braces. The first run of
# this drill under `--range 0-99` -- the deliberate break -- left nothing outside the range, and
# this claim reported "0 of 0" and PASSED: a released-to-everybody rollout graded green by a check
# whose subject had been emptied out. A claim that can be satisfied by having nothing to check is
# not a claim.
conform "C2" "$([ "$outside_total" -gt 0 ] && [ "$outside_good" = "$outside_total" ] && echo 1 || echo 0)" \
  "every bucket outside $RANGE is untouched on 1.0.0-good ($outside_good of $outside_total)$([ "$outside_total" = "0" ] && echo " -- the range covers the whole fleet, so nothing was spared")$([ ${#wrong[@]} -gt 0 ] && echo "; wrong: ${wrong[*]:0:6}")"

# A client that sends no cohort at all -- every static server, and every host that has not opted in
# -- must be unaffected by a policy it never asked about.
no_cohort="$(curl -fsS -D - -o /dev/null "$URL" | tr -d '\r' | awk -F': ' '/^X-Dogwood-Release/{print $2}')"
conform "C4" "$([ "$no_cohort" = "1.0.0-good" ] && echo 1 || echo 0)" \
  "a request carrying no cohort gets the live release ($no_cohort)"

# The pin, not the percentage. 1.1.0-bad is staged at 0%, so the percentage rule would give it to
# nobody; `cohorts.json` is what put it in front of the pinned buckets.
staged_percent="$("$HERE/server.py" status --root "$SERVE_ROOT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["percent"])')"
conform "C5" "$([ "$staged_percent" = "0" ] && [ "$inside_bad" -gt 0 ] && echo 1 || echo 0)" \
  "the pinned buckets got 1.1.0-bad while its rollout percentage was $staged_percent -- the range decided, not the percentage"

# ---------------------------------------------------------------------------------------------
# And a real client, because everything above is a header this server chose to send. The client
# fetches the manifest for the bucket it is in and verifies its Ed25519 signature, which is the
# whole of what "this installation was offered this release" means.
#
# **It stops at `verified` rather than `updated`, and the reason is a defect this drill found.** A
# module request carries no release identity -- the loader resolves each module address relative to
# the manifest, and the address is whatever the build wrote, which is `slice-guest.zipline` in
# every release this project produces. With two releases live at once, the server cannot know which
# release's bytes a module request wants, and the first run of this drill watched a client pinned to
# 1.1.0-bad fetch 1.0.0-good's module and refuse the load on the digest the signed manifest named.
# The signature caught it, which is the right direction; the fix belongs in publishing, and
# `server.py`'s `publish` now warns loudly with what a real deployment must do instead.
#
# So the module half of the claim is graded where the question is well posed:
# `publish-check.sh` `P6` fetches every module against a server holding ONE release and checks each
# digest. Asserting it here would be asserting the ambiguity rather than the routing.
# ---------------------------------------------------------------------------------------------
echo
echo "==> a real client, in a pinned bucket and outside one"
CLIENT_OUT="$HERE/build/rotation-client"
TRUST="$ENGINE/dogwood-wire/src/commonMain/kotlin/dev/dogwood/protocol/Trust.kt"
OLD_PUB="$(grep -o "DEVELOPMENT to \"[0-9a-f]*\"" "$TRUST" | head -1 | grep -o '[0-9a-f]\{64\}')"
CP="$(cd "$ENGINE" && ./gradlew --max-workers=2 -q \
  -I "$HERE/rotation-client/classpath.init.gradle.kts" \
  :dogwood-host:dogwoodPrintZiplineClasspath 2>>"$LOG" | grep '^CLASSPATH=' | sed 's/^CLASSPATH=//')"
mkdir -p "$CLIENT_OUT"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$CLIENT_OUT" "$HERE/rotation-client/RotationClient.java" \
  >>"$LOG" 2>&1 || { echo "the client did not compile; see $LOG" >&2; exit 1; }

client_at() { # bucket
  "$JAVA_HOME/bin/java" -cp "$CP:$CLIENT_OUT" RotationClient --url "$URL" --cohort "$1" \
    --key "dogwood-development=$OLD_PUB" 2>&1 | tee -a "$LOG"
}
pinned_client="$(client_at "$RANGE_LOW")"
spared_client="$(client_at 99)"

conform "C3" \
  "$(printf '%s' "$pinned_client" | grep -q "CLIENT verified version=1.1.0-bad" && echo 1 || echo 0)" \
  "a real client in bucket $RANGE_LOW fetched and verified the pinned release: $(printf '%s' "$pinned_client" | grep -E 'CLIENT (verified|refused)' | head -1)"
conform "C3-spared" \
  "$(printf '%s' "$spared_client" | grep -q "CLIENT verified version=1.0.0-good" && echo 1 || echo 0)" \
  "a real client in bucket 99 fetched and verified the good release: $(printf '%s' "$spared_client" | grep -E 'CLIENT (verified|refused)' | head -1)"

printf '%s\n' "${lines[@]}" > "$OUT"
echo "CONF RESULT client=cohort passed=$passed failed=$failed skipped=0" >> "$OUT"
echo
if [ "$failed" = "0" ]; then
  echo "PASS -- a release published to buckets $RANGE reached those and no others. Log: $LOG"
else
  echo "FAIL -- $failed of $((passed+failed)); see $LOG" >&2; exit 1
fi

#!/usr/bin/env bash
# Project Dogwood -- a signing key rotated, end to end, with a client on each side of it.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/reference-server/rotation-drill.sh
#
# `docs/keys.md` writes the rotation down as three steps. This runs them, because the middle step
# of a rotation is the one that is easy to describe and easy to get wrong:
#
#   1. publish manifests carrying BOTH signatures;
#   2. roll every client forward, so each one trusts the new key;
#   3. THEN drop the old signature -- which is the step to *finish*, not the step to start, because
#      at that instant every client still holding only the old key stops accepting updates.
#
# `KeyRotationTest` already grades steps 1 and 2 (conformance `B1`/`B2`) against the manifest the
# build produces. What nobody had run is step 3: a real publish that drops a signature, met by two
# clients that differ only in which key they hold. This drill runs it against the reference server,
# over Hypertext Transfer Protocol (HTTP), on payloads the Zipline build actually signed.
#
# **Two facts this drill establishes that the prose asserted.**
#
#   * Dropping a signature is a publish, not a rebuild. Zipline signs the manifest with its
#     `unsigned` object -- which is where the signatures live -- **excluded** from the signed bytes
#     (`ZiplineManifest.signaturePayload`). So removing one signature leaves the others valid, and
#     `R3` below asserts the surviving signature is byte-identical to the one the build emitted.
#     Had it not been, step 3 would mean re-signing and re-publishing every artifact, which is a
#     different runbook.
#   * The client that stops is the client holding only the OLD key, and it stops at exactly the
#     moment the old signature goes live -- not before. `R1` and `R5` are the same client under the
#     same procedure, one release apart.
#
# **The two clients.** Every host in this repository compiles in `DogwoodTrust.DEVELOPMENT_KEYS`,
# which holds both keys, so no sample can be either side of a rotation. `rotation-client/` is the
# delivery path's decision with the key set as an argument, using Zipline's own `ManifestVerifier`
# -- the class `DogwoodDelivery` constructs -- rather than a reimplementation of its rules. It does
# not run the guest; `check.sh` and `quarantine-drill.sh` run real hosts for the claims where
# running the payload is the claim.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"
ENGINE="$ROOT_DIR/engine"
SERVE_ROOT="$(mktemp -d)"
PORT="${PORT:-8473}"
mkdir -p "$HERE/build"
LOG="$HERE/build/rotation.log"
: > "$LOG"

# `--dual-at-step-3` publishes the second release WITHOUT dropping the old signature. It is the
# gate's own negative: with it, `R5` must fail, because a client holding only the old key goes on
# updating happily. Every claim below is satisfied by a drill that never dropped anything, and this
# is the flag that proves the drill can tell.
SKIP_DROP=0
OUT="$HERE/build/rotation.conf"
for arg in "$@"; do
  if [ "$arg" = "--dual-at-step-3" ]; then SKIP_DROP=1; else OUT="$arg"; fi
done

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

# ---------------------------------------------------------------------------------------------
# The keys, read out of the source of truth rather than retyped.
#
# `DogwoodTrust` exists because three hand-typed copies of a security-relevant map is three chances
# to rotate two of them. A fourth copy in this file would be the fourth chance, and a drill whose
# keys had drifted from the host's would fail for a reason that has nothing to do with rotation.
# ---------------------------------------------------------------------------------------------
TRUST="$ROOT_DIR/engine/dogwood-wire/src/commonMain/kotlin/dev/dogwood/protocol/Trust.kt"
OLD_NAME="dogwood-development"
NEW_NAME="dogwood-development-2"
OLD_PUB="$(grep -o "DEVELOPMENT to \"[0-9a-f]*\"" "$TRUST" | head -1 | grep -o '[0-9a-f]\{64\}')"
NEW_PUB="$(grep -o "DEVELOPMENT_2 to \"[0-9a-f]*\"" "$TRUST" | head -1 | grep -o '[0-9a-f]\{64\}')"
# A valid Ed25519 public key that signed nothing here. `KeyRotationTest` uses the same one to stand
# in for a stranger's key.
STRANGER_PUB="e90183dab09a31fb3f41e3723b0bd20e17a01d28d66da5f5870f76e2cebb20da"
[ -n "$OLD_PUB" ] && [ -n "$NEW_PUB" ] || { echo "could not read the trusted keys from $TRUST" >&2; exit 1; }
echo "==> old key $OLD_NAME=${OLD_PUB:0:12}...  new key $NEW_NAME=${NEW_PUB:0:12}..."

# ---------------------------------------------------------------------------------------------
# The client, compiled against the pinned Zipline.
# ---------------------------------------------------------------------------------------------
echo "==> building the two-key client against the engine's own Zipline"
CLIENT_OUT="$HERE/build/rotation-client"
mkdir -p "$CLIENT_OUT"
CP="$(cd "$ENGINE" && ./gradlew --max-workers=2 -q \
  -I "$HERE/rotation-client/classpath.init.gradle.kts" \
  :dogwood-host:dogwoodPrintZiplineClasspath 2>>"$LOG" | grep '^CLASSPATH=' | sed 's/^CLASSPATH=//')"
[ -n "$CP" ] || { echo "could not resolve the Zipline classpath; see $LOG" >&2; exit 1; }
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$CLIENT_OUT" "$HERE/rotation-client/RotationClient.java" \
  >>"$LOG" 2>&1 || { echo "the client did not compile; see $LOG" >&2; exit 1; }

client() { # label, url, key=hex...
  local label="$1"; shift
  local url="$1"; shift
  local args=()
  for pair in "$@"; do args+=(--key "$pair"); done
  echo "--- client $label" >> "$LOG"
  "$JAVA_HOME/bin/java" -cp "$CP:$CLIENT_OUT" RotationClient --url "$url" "${args[@]}" 2>&1 | tee -a "$LOG"
}

# ---------------------------------------------------------------------------------------------
# Two releases. Each is BUILT with its version, never stamped afterwards -- the version sits inside
# the signed body, so rewriting it in a published manifest invalidates every signature over it. The
# quarantine drill learned that the expensive way and its header says so.
# ---------------------------------------------------------------------------------------------
build() { # version -> prints the built directory
  ( cd "$ENGINE" && ./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline \
      -PdogwoodVersion="$1" --max-workers=2 --console=plain -q ) >>"$LOG" 2>&1 || {
    echo "the guest build failed for $1; see $LOG" >&2; exit 1; }
  echo "$ENGINE/samples/slice-guest/build/zipline/ProductionWebpack"
}

echo "==> step 1: publishing 1.0.0, signed by BOTH keys"
src="$(build 1.0.0)"
rm -rf "$SERVE_ROOT/src-dual"; cp -R "$src" "$SERVE_ROOT/src-dual"
"$HERE/server.py" publish --root "$SERVE_ROOT" --from "$SERVE_ROOT/src-dual" --version 1.0.0 >>"$LOG"
"$HERE/server.py" rollout --root "$SERVE_ROOT" --version 1.0.0 --percent 100 >>"$LOG"

echo "==> step 3, prepared: building 1.1.0 and dropping the old signature from it"
src="$(build 1.1.0)"
rm -rf "$SERVE_ROOT/src-new"; cp -R "$src" "$SERVE_ROOT/src-new"
built_new_signature="$(python3 -c '
import json,sys
m = json.load(open(sys.argv[1]))
print(m["unsigned"]["signatures"][sys.argv[2]])
' "$SERVE_ROOT/src-new/manifest.zipline.json" "$NEW_NAME")"

if [ "$SKIP_DROP" = "0" ]; then
  python3 -c '
import json,sys
path, drop = sys.argv[1], sys.argv[2]
m = json.load(open(path))
del m["unsigned"]["signatures"][drop]
open(path, "w").write(json.dumps(m, separators=(",", ":")))
' "$SERVE_ROOT/src-new/manifest.zipline.json" "$OLD_NAME"
else
  echo "    (--dual-at-step-3: the old signature was NOT dropped)"
fi
served_signatures="$(python3 -c '
import json,sys
print(",".join(json.load(open(sys.argv[1]))["unsigned"]["signatures"]))
' "$SERVE_ROOT/src-new/manifest.zipline.json")"
served_new_signature="$(python3 -c '
import json,sys
print(json.load(open(sys.argv[1]))["unsigned"]["signatures"].get(sys.argv[2], ""))
' "$SERVE_ROOT/src-new/manifest.zipline.json" "$NEW_NAME")"
"$HERE/server.py" publish --root "$SERVE_ROOT" --from "$SERVE_ROOT/src-new" --version 1.1.0 >>"$LOG"

"$HERE/server.py" serve --root "$SERVE_ROOT" --port "$PORT" >>"$LOG" 2>&1 &
server=$!
URL="http://127.0.0.1:$PORT/manifest.zipline.json"
ready=0
for _ in $(seq 1 80); do
  curl -fsS -D - -o /dev/null -m 1 "$URL" 2>/dev/null | grep -q "X-Dogwood-Release" && { ready=1; break; }
  sleep 0.25
done
[ "$ready" = "1" ] || { echo "the reference server never answered on :$PORT" >&2; exit 1; }

# ---------------------------------------------------------------------------------------------
# The rotation window: 1.0.0 is live, dual-signed, and both clients are on it.
# ---------------------------------------------------------------------------------------------
echo
echo "==> the window: a dual-signed release, met by a client on each side of the rotation"
old_before="$(client "old-key-only, before" "$URL" "$OLD_NAME=$OLD_PUB")"
new_before="$(client "new-key-only, before" "$URL" "$NEW_NAME=$NEW_PUB")"
stranger="$(client "stranger-key" "$URL" "dogwood-stranger=$STRANGER_PUB")"

conform "R1" \
  "$(printf '%s' "$old_before" | grep -q "CLIENT updated version=1.0.0" && echo 1 || echo 0)" \
  "a client that has NOT rolled forward updates on the dual-signed release: $(printf '%s' "$old_before" | grep -E 'CLIENT (updated|refused)' | head -1)"
conform "R2" \
  "$(printf '%s' "$new_before" | grep -q "CLIENT updated version=1.0.0" && echo 1 || echo 0)" \
  "a client that HAS rolled forward updates on the same artifact: $(printf '%s' "$new_before" | grep -E 'CLIENT (updated|refused)' | head -1)"
# The control, and it is not decoration: every assertion here is also satisfied by a client that
# verifies nothing at all, and that client looks identical in a summary.
conform "R-control" \
  "$(printf '%s' "$stranger" | grep -q "CLIENT refused" && echo 1 || echo 0)" \
  "a client holding neither key refuses: $(printf '%s' "$stranger" | grep -E 'CLIENT (updated|refused)' | head -1)"

# ---------------------------------------------------------------------------------------------
# Step 3: the old signature is dropped, and the drop goes live.
# ---------------------------------------------------------------------------------------------
echo
echo "==> step 3: the release with the old signature dropped goes live"
conform "R3" \
  "$([ -n "$served_new_signature" ] && [ "$served_new_signature" = "$built_new_signature" ] && echo 1 || echo 0)" \
  "dropping a signature did not disturb the surviving one -- served signatures {$served_signatures}, new-key signature ${served_new_signature:0:16}... identical to the build's"

"$HERE/server.py" rollout --root "$SERVE_ROOT" --version 1.1.0 --percent 100 >>"$LOG"

old_after="$(client "old-key-only, after" "$URL" "$OLD_NAME=$OLD_PUB")"
new_after="$(client "new-key-only, after" "$URL" "$NEW_NAME=$NEW_PUB")"

conform "R4" \
  "$(printf '%s' "$new_after" | grep -q "CLIENT updated version=1.1.0" && echo 1 || echo 0)" \
  "the rolled-forward client goes on updating: $(printf '%s' "$new_after" | grep -E 'CLIENT (updated|refused)' | head -1)"
# The whole point of the runbook's ordering. This client updated one release ago, under the same
# procedure, against the same server. It stops here and nowhere else.
conform "R5" \
  "$(printf '%s' "$old_after" | grep -q "CLIENT refused" && echo 1 || echo 0)" \
  "the client holding only the old key stops at exactly this moment: $(printf '%s' "$old_after" | grep -E 'CLIENT (updated|refused)' | head -1)"
# And it stops by refusing rather than by failing to reach anything. A drill that could not tell
# those apart would report a network outage as a successful rotation.
conform "R5-reachable" \
  "$(printf '%s' "$old_after" | grep -q "CLIENT fetched" && echo 1 || echo 0)" \
  "...and it refused a release it had FETCHED, rather than failing to reach the server: $(printf '%s' "$old_after" | grep 'CLIENT fetched' | head -1)"

printf '%s\n' "${lines[@]}" > "$OUT"
echo "CONF RESULT client=rotation passed=$passed failed=$failed skipped=0" >> "$OUT"
echo
if [ "$failed" = "0" ]; then
  echo "PASS -- a key was rotated through a real server, and the client that had not rolled forward"
  echo "        stopped at the publish that dropped its signature, and not before. Log: $LOG"
else
  echo "FAIL -- $failed of $((passed+failed)); see $LOG" >&2; exit 1
fi

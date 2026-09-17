#!/usr/bin/env bash
# Project Dogwood -- the checks a publish owes you, run on the bytes that would ship.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/reference-server/publish-check.sh \
#       --payload engine/samples/slice-guest/build/zipline/ProductionWebpack \
#       --version 1.4.0 \
#       --signing-key <hex> --rotation-key <hex>
#
# `check.sh` grades the reference server against payloads it makes up, which is the right subject
# when the server is what you are testing. This grades **one particular build**, and it is what
# `.github/workflows/publish-payload.yml` runs between building a payload and uploading it: the
# publish is only reviewable if the thing being reviewed has been measured.
#
# Six claims, and each one is a failure that is silent in production:
#
#   P1  the release identity is the one that was asked for. The version sits inside the SIGNED body,
#       so it is a build input; a publishing step that stamped it afterwards would invalidate every
#       signature over it, which is how the quarantine drill learned this.
#   P2  every key that was supposed to sign did sign, and a client holding ONLY that key accepts
#       the result. A manifest with a signature under a name nobody can verify is indistinguishable
#       from a signed one until a device refuses it.
#   P3  the manifest is never cached. A cached manifest is a fleet that can neither be updated nor
#       rolled back -- the failure that outlasts the outage.
#   P4  modules are immutable for a year, because they are addressed by content.
#   P5  `Content-Encoding: br` when the client offers it. Serving gzip instead costs 27% and about
#       five seconds on a slow connection (ADR-045) and NOTHING on the device can detect it.
#   P6  a real client fetches the manifest, verifies its Ed25519 signature, and gets every module
#       the manifest names with the digest the manifest names.
#
# The public keys are DERIVED from the private ones rather than supplied: a raw Ed25519 private key
# determines its public half, and asking for both would let a publish pass with a transposed pair.
#
# `PYTHON=... ` overrides the interpreter that runs the server, because `P5` needs the `brotli`
# module and a machine without it cannot grade `P5` at all -- which this treats as a failure rather
# than a shrug, for the reason `P5` exists.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"
ENGINE="$ROOT_DIR/engine"
PYTHON="${PYTHON:-python3}"
PORT="${PORT:-8477}"
mkdir -p "$HERE/build"
LOG="$HERE/build/publish-check.log"
OUT="$HERE/build/publish-check.conf"
: > "$LOG"

PAYLOAD=""; VERSION=""; SIGNING_KEY=""; ROTATION_KEY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --payload) PAYLOAD="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --signing-key) SIGNING_KEY="$2"; shift 2 ;;
    --rotation-key) ROTATION_KEY="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    *) echo "unknown argument $1" >&2; exit 64 ;;
  esac
done
[ -n "$PAYLOAD" ] && [ -n "$VERSION" ] && [ -n "$SIGNING_KEY" ] || {
  echo "usage: publish-check.sh --payload DIR --version V --signing-key HEX [--rotation-key HEX]" >&2
  exit 64
}
[ -f "$PAYLOAD/manifest.zipline.json" ] || { echo "no manifest in $PAYLOAD" >&2; exit 1; }
[ -n "${JAVA_HOME:-}" ] || { echo "JAVA_HOME is not set" >&2; exit 1; }

passed=0; failed=0
lines=()
conform() { # id, condition(1|0), detail
  local line
  if [ "$2" = "1" ]; then passed=$((passed+1)); line="CONF $1 PASS -- $3"
  else failed=$((failed+1)); line="CONF $1 FAIL -- $3"; fi
  lines+=("$line")
  echo "$line"
}
SERVE_ROOT="$(mktemp -d)"
# Not `kill "${server:-0}"`: unset, that expands to `kill 0`, which signals the whole process group.
cleanup() { [ -n "${server:-}" ] && kill "$server" 2>/dev/null; rm -rf "$SERVE_ROOT"; return 0; }
trap cleanup EXIT

# The public half of a raw Ed25519 private key. The constant is the PKCS#8 wrapper Ed25519 keys
# carry -- algorithm identifier and lengths -- and everything after it is the raw scalar, which is
# why `tail -c 32` gets the key back out at the other end.
public_of() { # private-hex
  printf "302e020100300506032b657004220420%s" "$1" \
    | xxd -r -p | openssl pkey -inform DER -pubout -outform DER 2>/dev/null | tail -c 32 | xxd -p -c 32
}

echo "==> the manifest this build produced"
manifest="$PAYLOAD/manifest.zipline.json"
built_version="$("$PYTHON" -c 'import json,sys; print(json.load(open(sys.argv[1])).get("version"))' "$manifest")"
conform "P1" "$([ "$built_version" = "$VERSION" ] && echo 1 || echo 0)" \
  "the release identity is a build input: manifest says '$built_version', the publish asked for '$VERSION'"

echo "==> serving it through the reference server"
cp -R "$PAYLOAD" "$SERVE_ROOT/src"
"$PYTHON" "$HERE/server.py" publish --root "$SERVE_ROOT/serve" --from "$SERVE_ROOT/src" --version "$VERSION" >>"$LOG"
"$PYTHON" "$HERE/server.py" serve --root "$SERVE_ROOT/serve" --port "$PORT" >>"$LOG" 2>&1 &
server=$!
URL="http://127.0.0.1:$PORT/manifest.zipline.json"
ready=0
for _ in $(seq 1 80); do
  curl -fsS -D - -o /dev/null -m 1 "$URL" 2>/dev/null | grep -q "X-Dogwood-Release" && { ready=1; break; }
  sleep 0.25
done
[ "$ready" = "1" ] || { echo "the reference server never answered on :$PORT" >&2; exit 1; }

manifest_cache=$(curl -fsS -D - -o /dev/null "$URL" | tr -d '\r' | awk -F': ' '/^Cache-Control/{print $2}')
conform "P3" "$([ "$manifest_cache" = "no-store" ] && echo 1 || echo 0)" \
  "the manifest is never cached: Cache-Control: $manifest_cache"

module_name="$("$PYTHON" -c '
import json,sys
m = json.load(open(sys.argv[1]))
print(next(iter(m["modules"].values()))["url"])
' "$manifest")"
module_cache=$(curl -fsS -D - -o /dev/null "http://127.0.0.1:$PORT/$module_name" | tr -d '\r' | awk -F': ' '/^Cache-Control/{print $2}')
conform "P4" "$([ "$module_cache" = "public, max-age=31536000, immutable" ] && echo 1 || echo 0)" \
  "modules are immutable for a year: $module_name -> Cache-Control: $module_cache"

encoding=$(curl -fsS -D - -o /dev/null -H 'Accept-Encoding: br, gzip' "$URL" | tr -d '\r' | awk -F': ' '/^Content-Encoding/{print $2}')
# Not tolerated when the module is missing. `check.sh` degrades to grading gzip because its subject
# is the server; here the subject is a publish, and an ungraded `P5` is exactly the silent 27% this
# claim exists to catch.
conform "P5" "$([ "$encoding" = "br" ] && echo 1 || echo 0)" \
  "brotli when the client offers it: Content-Encoding: ${encoding:-none}$([ "$encoding" != "br" ] && echo " -- install the python 'brotli' module, or set PYTHON to an interpreter that has it")"

echo "==> a client that holds only one key at a time"
CLIENT_OUT="$HERE/build/rotation-client"
CP="$(cd "$ENGINE" && ./gradlew --max-workers=2 -q \
  -I "$HERE/rotation-client/classpath.init.gradle.kts" \
  :dogwood-host:dogwoodPrintZiplineClasspath 2>>"$LOG" | grep '^CLASSPATH=' | sed 's/^CLASSPATH=//')"
[ -n "$CP" ] || { echo "could not resolve the Zipline classpath; see $LOG" >&2; exit 1; }
mkdir -p "$CLIENT_OUT"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$CLIENT_OUT" "$HERE/rotation-client/RotationClient.java" \
  >>"$LOG" 2>&1 || { echo "the client did not compile; see $LOG" >&2; exit 1; }

verify_with() { # key-name, private-hex
  "$JAVA_HOME/bin/java" -cp "$CP:$CLIENT_OUT" RotationClient --url "$URL" \
    --key "$1=$(public_of "$2")" 2>&1 | tee -a "$LOG"
}

signing_out="$(verify_with dogwood-development "$SIGNING_KEY")"
conform "P2" \
  "$(printf '%s' "$signing_out" | grep -q "CLIENT verified" && echo 1 || echo 0)" \
  "a client holding only the signing key accepts it: $(printf '%s' "$signing_out" | grep -E 'CLIENT (verified|refused)' | head -1)"
conform "P6" \
  "$(printf '%s' "$signing_out" | grep -q "CLIENT updated version=$VERSION" && echo 1 || echo 0)" \
  "...and every module the manifest names arrived with the digest it names: $(printf '%s' "$signing_out" | grep -cE '^CLIENT module .* ok') module(s)"

if [ -n "$ROTATION_KEY" ]; then
  rotation_out="$(verify_with dogwood-development-2 "$ROTATION_KEY")"
  conform "P2-rotation" \
    "$(printf '%s' "$rotation_out" | grep -q "CLIENT verified" && echo 1 || echo 0)" \
    "a client holding only the ROTATION key accepts the same artifact -- which is what makes a key rotation possible: $(printf '%s' "$rotation_out" | grep -E 'CLIENT (verified|refused)' | head -1)"
fi

printf '%s\n' "${lines[@]}" > "$OUT"
echo "CONF RESULT client=publish passed=$passed failed=$failed skipped=0" >> "$OUT"
echo
if [ "$failed" = "0" ]; then
  echo "PASS -- $passed checks on the bytes that would ship. Results: $OUT"
else
  echo "FAIL -- $failed of $((passed+failed)); see $LOG" >&2; exit 1
fi

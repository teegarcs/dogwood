#!/usr/bin/env bash
# Project Dogwood -- generating a payload signing key, and proving the pair before trusting it.
#
#   tools/reference-server/new-payload-key.sh            # generate and print
#   tools/reference-server/new-payload-key.sh --prove    # ...then build and verify a payload with it
#
# `docs/keys.md` is the runbook; this is the one command in it, so the procedure is executable
# rather than three lines a reader retypes. It generates an Ed25519 pair in the form Zipline's
# `signingKeys { privateKeyHex }` and `DogwoodDelivery(trustedPublicKeys)` take: raw 32-byte keys,
# hexadecimal, no container.
#
# **`--prove` is the part that matters.** A key pair is two hexadecimal strings and a transposition
# is invisible: a private key pasted into the build and a *different* public key pasted into the
# host produces artifacts that look right and a fleet that refuses every update. So the flag builds
# the sample payload signed by the generated private key, serves it, and points a client holding
# only the derived public key at it. If the pair is wrong the client refuses and this script fails.
# That is a different claim from "openssl printed two strings", and it is the claim worth having.
#
# **What this does NOT do is hold the key.** It prints it once, to a terminal. Where a production
# key lives -- a password manager, a hardware token, a cloud key-management service -- is
# `OPEN-DECISIONS.md` section 6 and it is the owner's, because it is a question about who can be
# trusted with it rather than about how to make one.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"
ENGINE="$ROOT_DIR/engine"
PROVE=0
for arg in "$@"; do [ "$arg" = "--prove" ] && PROVE=1; done

command -v openssl >/dev/null || { echo "openssl is not on the path" >&2; exit 1; }

work="$(mktemp -d)"
# Not `kill "${server:-0}"`: unset, that expands to `kill 0`, which signals the whole process group
# and takes the caller down with exit 144 and no output. Watched here before it was written.
cleanup() { [ -n "${server:-}" ] && kill "$server" 2>/dev/null; rm -rf "$work"; return 0; }
trap cleanup EXIT

openssl genpkey -algorithm ed25519 -out "$work/key.pem" 2>/dev/null
# The last 32 bytes of the DER encoding are the raw key in both directions. That is not a trick:
# an Ed25519 PKCS#8 private key and a SubjectPublicKeyInfo public key each end with the raw scalar,
# and every other byte is the algorithm identifier wrapper Zipline does not want.
private_hex="$(openssl pkey -in "$work/key.pem" -outform DER | tail -c 32 | xxd -p -c 32)"
public_hex="$(openssl pkey -in "$work/key.pem" -pubout -outform DER | tail -c 32 | xxd -p -c 32)"

echo
echo "  private (build only, never commit):  $private_hex"
echo "  public  (compiled into every host):  $public_hex"
echo
echo "  ./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline -PdogwoodSigningKey=$private_hex"
echo "  DogwoodDelivery(trustedPublicKeys = mapOf(\"your-key-name\" to \"$public_hex\"), ...)"
echo

if [ "$PROVE" = "0" ]; then
  echo "  (run again with --prove to build a payload with this key and verify it with a client)"
  exit 0
fi

[ -n "${JAVA_HOME:-}" ] || { echo "JAVA_HOME is not set; --prove needs it" >&2; exit 1; }
PORT="${PORT:-8476}"
LOG="$HERE/build/new-payload-key.log"
mkdir -p "$HERE/build"
: > "$LOG"

echo "==> building the sample payload signed by the generated private key"
( cd "$ENGINE" && ./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline \
    -PdogwoodSigningKey="$private_hex" -PdogwoodVersion=key-proof \
    --max-workers=2 --console=plain -q ) >>"$LOG" 2>&1 || {
  echo "the guest build failed; see $LOG" >&2; exit 1; }

cp -R "$ENGINE/samples/slice-guest/build/zipline/ProductionWebpack" "$work/src"
"$HERE/server.py" publish --root "$work/serve" --from "$work/src" --version key-proof >>"$LOG"
"$HERE/server.py" serve --root "$work/serve" --port "$PORT" >>"$LOG" 2>&1 &
server=$!
URL="http://127.0.0.1:$PORT/manifest.zipline.json"
for _ in $(seq 1 80); do
  curl -fsS -D - -o /dev/null -m 1 "$URL" 2>/dev/null | grep -q "X-Dogwood-Release" && break
  sleep 0.25
done

echo "==> pointing a client that holds ONLY the derived public key at it"
CP="$(cd "$ENGINE" && ./gradlew --max-workers=2 -q \
  -I "$HERE/rotation-client/classpath.init.gradle.kts" \
  :dogwood-host:dogwoodPrintZiplineClasspath 2>>"$LOG" | grep '^CLASSPATH=' | sed 's/^CLASSPATH=//')"
mkdir -p "$HERE/build/rotation-client"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$HERE/build/rotation-client" \
  "$HERE/rotation-client/RotationClient.java" >>"$LOG" 2>&1 || {
  echo "the client did not compile; see $LOG" >&2; exit 1; }
# The payload's first signing key keeps the sample's key NAME; what changed is the key behind it.
out="$("$JAVA_HOME/bin/java" -cp "$CP:$HERE/build/rotation-client" RotationClient \
  --url "$URL" --key "dogwood-development=$public_hex" 2>&1 | tee -a "$LOG")"

if printf '%s' "$out" | grep -q "CLIENT updated version=key-proof"; then
  echo
  echo "PASS -- a payload signed by this private key was accepted by a client holding only the"
  echo "        derived public key. The pair is real, in Zipline's own verifier."
else
  echo
  echo "FAIL -- the client did not accept a payload signed by this key:" >&2
  printf '%s\n' "$out" | grep -E 'CLIENT (refused|unreachable)' >&2
  exit 1
fi

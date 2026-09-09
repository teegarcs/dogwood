#!/usr/bin/env bash
# Project Dogwood -- conformance claims D1-D5 on the web client.
#
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21
#   tools/conformance/run-web.sh
#
# Compose draws to a canvas through Skiko, and a canvas has no intrinsic accessibility, so whatever
# a screen reader reads has to be published separately. It is: Compose Multiplatform builds a live
# DOM of elements carrying roles and names -- inside a shadow root, which is why the drill reaches
# them through `Accessibility.getFullAXTree` and node handles rather than through selectors.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/build"
# Resolved before the `cd` below, so a relative path means what the caller meant.
OUT="$(cd "$(dirname "${1:-$HERE/build/web.conf}")" 2>/dev/null && pwd)/$(basename "${1:-$HERE/build/web.conf}")"
PORT="${PORT:-8799}"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
cd "$HERE/../../engine"

[ -x "$CHROME" ] || { echo "Chrome not found at $CHROME; set CHROME=..." >&2; exit 1; }

./gradlew :samples:web-slice:wasmJsBrowserDistribution --console=plain -q
DIST="samples/web-slice/build/dist/wasmJs/productionExecutable"
[ -f "$DIST/app.js" ] || { echo "no distribution in $DIST" >&2; exit 1; }

# The signature fixtures, derived from the signed artifacts rather than signed again.
#
# Deriving them is the point. A fixture produced by a second signer would be testing that two
# signers agree; these are the build's own output with one thing changed each, so what the drill
# grades is the property under test and nothing else.
#
#   tampered  -- the real signature, over bytes that have since been altered
#   rotated   -- signed by the rotation key ALONE, which a client trusting both must still accept
#   unsigned  -- no `.sig` beside it at all, which a client holding keys must refuse
python3 - "$DIST" <<'FIXTURES'
import pathlib, shutil, sys

dist = pathlib.Path(sys.argv[1])
base = dist / "dogwood-manifest-kotlin.json"
signature = dist / "dogwood-manifest-kotlin.json.sig"
if not signature.exists():
    sys.exit(f"{signature} is missing; the distribution was not signed")

# Tampered: the manifest edited after signing, in the field an attacker would actually want. The
# guest script's address is what a signed sidecar is *for* -- a document that can be rewritten can
# point a page at any code on the origin -- so that is the byte the fixture changes.
text = base.read_text()
assert '"guestScript": "guest-kotlin.js"' in text, "the fixture needs the guest script to alter"
tampered = dist / "dogwood-manifest-tampered.json"
tampered.write_text(text.replace('"guestScript": "guest-kotlin.js"', '"guestScript": "guest.js"', 1))
shutil.copy(signature, dist / "dogwood-manifest-tampered.json.sig")

# Rotated: the same bytes, signed only by the key being rotated *to*. A client holding both keys
# must accept it -- that is what makes a rotation a roll-forward rather than an outage.
rotated = dist / "dogwood-manifest-rotated.json"
shutil.copy(base, rotated)
lines = [l for l in signature.read_text().splitlines()
         if l.startswith("dogwood-development-2 ")]
assert len(lines) == 1, f"expected exactly one rotation signature, got {lines}"
(dist / "dogwood-manifest-rotated.json.sig").write_text(lines[0] + "\n")

# Unsigned: the manifest with no signature document beside it.
shutil.copy(base, dist / "dogwood-manifest-unsigned.json")
print("conformance: wrote 3 signature fixtures")
FIXTURES

python3 -m http.server "$PORT" --directory "$DIST" --bind 127.0.0.1 >/dev/null 2>&1 &
server=$!
trap 'kill $server 2>/dev/null || true' EXIT
sleep 1

set +e
python3 "$HERE/web_accessibility.py" "http://127.0.0.1:$PORT/index.html" "$CHROME" $((PORT + 500)) \
  | tee "$OUT"
status=${PIPESTATUS[0]}

# The host-services claims, against the same distribution and the same real guest. A second script
# and a second browser rather than more assertions in the first: they answer a different question,
# and a drill named for accessibility that also graded services would be the kind of file nobody
# can tell whether they have finished reading.
python3 "$HERE/web_services.py" "http://127.0.0.1:$PORT/index.html" "$CHROME" $((PORT + 700)) \
  | tee -a "$OUT"
services=${PIPESTATUS[0]}
set -e
[ "$status" = "0" ] || exit "$status"
exit "$services"

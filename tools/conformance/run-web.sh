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
#   canary    -- a SECOND release, live at the same time as the first (B8)
python3 - "$DIST" <<'FIXTURES'
import hashlib, json, pathlib, re, shutil, sys

dist = pathlib.Path(sys.argv[1])
base = dist / "dogwood-manifest-kotlin.json"
signature = dist / "dogwood-manifest-kotlin.json.sig"
if not signature.exists():
    sys.exit(f"{signature} is missing; the distribution was not signed")

# Tampered: the manifest edited after signing, in the field an attacker would actually want. The
# guest script's address is what a signed sidecar is *for* -- a document that can be rewritten can
# point a page at any code on the origin -- so that is the byte the fixture changes.
#
# The address is read out of the manifest rather than spelled out, because since ADR-078 it names
# the script's own SHA-256 and therefore changes whenever the payload does. A fixture that spelled
# it out would stop matching on the next guest build and this drill would silently write an
# unaltered "tampered" manifest -- which would PASS nothing while looking like it graded B1.
text = base.read_text()
named = re.search(r'"guestScript"\s*:\s*"([^"]+)"', text)
assert named, "the fixture needs a guest script address to alter"
tampered = dist / "dogwood-manifest-tampered.json"
altered = text.replace(f'"guestScript": "{named.group(1)}"', '"guestScript": "guest.js"', 1)
assert altered != text, f"nothing was altered; the address is {named.group(1)!r}"
tampered.write_text(altered)
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

# ---------------------------------------------------------------------------------------------
# Two releases, live at the same time. Claim B8, and the web's `B7`.
#
# A canary on this profile is served by returning different sidecar *content* at one sidecar
# address -- that is what `tools/reference-server` does with `?cohort=N` -- and `WebDelivery`
# resolves `guestScript` against the sidecar address it was given. So the two releases' scripts
# land wherever their addresses say, and if both releases name their script the same string they
# land on top of each other and one of the two cohorts is handed bytes its signed sidecar never
# named.
#
# **In `b8/`, deliberately.** The whole point of the condition is that a second release's publish
# can land on the first release's script, and the two are modelled as two real publishes into one
# directory: whatever collision the addressing scheme permits, happens. Done in the distribution
# root that collision would take the *drill's own* guest with it, and every claim downstream --
# the accessibility screen, the services screen, Material -- would go red on a payload that had
# been overwritten. A red drill is not a wrong answer, but it is one that says nothing about
# which claim found what. Contained here, `B8` is the claim that goes red, and it says why.
#
# Both sidecars are UNSIGNED and are loaded with `?trust=none`, because signing them would need
# the publisher's private key and this drill has none. That is not a hole: `B8` asks whether one
# address can name two payloads, and `B1`, `B2` and `B5` already grade the signature posture. The
# digest is stamped and still enforced -- `integrityRequirement` verifies a digest whatever the
# key posture -- and the digest is the whole of the assertion here.
# ---------------------------------------------------------------------------------------------
live_script = dist / named.group(1)
assert live_script.is_file(), f"{live_script} is missing; the distribution names a script it lacks"
payload = live_script.read_bytes()
addressing = bool(re.fullmatch(r"guest-kotlin-[0-9a-f]{16}\.js", named.group(1)))
cohorts = dist / "b8"
cohorts.mkdir(exist_ok=True)
for older in cohorts.iterdir():
    older.unlink()

# Published in this order, so that when the two releases collide it is the canary's bytes that
# survive and the release cohort that is left holding a sidecar naming something else. That is the
# way round a staged rollout actually goes: the canary is the newer publish.
for release, version, marker in (("release", "1.0.0-b8", b"A"), ("canary", "1.1.0-b8", b"B")):
    bytes_ = payload + (
        b"\n// Dogwood release " + marker + b": two releases live at once (claim B8). One comment's\n"
        b"// worth of difference is enough -- what B8 grades is the address, not what it draws.\n")
    digest = hashlib.sha256(bytes_).hexdigest()
    # The address each release's own build would give its script. Without content addressing that
    # is the same string for both, which is the defect stated as an assignment.
    script = f"guest-kotlin-{digest[:16]}.js" if addressing else "guest-kotlin.js"
    (cohorts / script).write_bytes(bytes_)
    (cohorts / f"dogwood-manifest-{release}.json").write_text(json.dumps({
        "_comment": f"Release {version}, written by tools/conformance/run-web.sh for claim B8. "
                    f"Two releases published into one directory, each naming the script its own "
                    f"build would have named. Unsigned on purpose; loaded with ?trust=none.",
        "envelopeRevision": json.loads(text)["envelopeRevision"],
        "guestScript": script,
        "segmentVersions": json.loads(text)["segmentVersions"],
        "releaseVersion": version,
        "guestScriptSha256": digest,
    }, indent=2))

print(f"conformance: wrote 3 signature fixtures and 2 live releases in b8/ "
      f"({'content-addressed' if addressing else 'both at guest-kotlin.js'})")
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

# The generated Material 3 tier, operated. Its own module and its own browser because it drives a
# different screen for a long time, and a claim that has to scroll past nine other sections to
# reach its control is a claim nobody can read the failure of.
python3 "$HERE/web_material.py" "http://127.0.0.1:$PORT/index.html" "$CHROME" $((PORT + 900)) \
  | tee -a "$OUT"
material=${PIPESTATUS[0]}
set -e
[ "$status" = "0" ] || exit "$status"
[ "$services" = "0" ] || exit "$services"
exit "$material"

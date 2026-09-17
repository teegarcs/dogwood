# ADR-078: The web guest script names its own bytes, and the build writes the name before it signs

**Date:** 2026-09-17
**Status:** Accepted — group 1, item 5 of [`plans/close-the-open-items.md`](../../plans/close-the-open-items.md).

## 1. Context & Problem Statement

[ADR-077](../layer-3/ADR-077-a-module-address-names-its-bytes.md) closed a delivery defect on the
Zipline (mobile and desktop) path: every release named its module `slice-guest.zipline`, so with two
releases live at once a module request could not say which release it belonged to. The plan for that
work recorded an open question rather than an answer for the fourth client — *"the web guest is
signed with detached sidecars over `guest-kotlin.js` rather than by a Zipline manifest, so it may or
may not have the same ambiguity. Establish which."*

**It has the same ambiguity, and this was established by reproducing it rather than by reading for
it.** The web profile does not use a Zipline manifest. It fetches a sidecar document, verifies a
detached Ed25519 signature over that document's exact bytes, and resolves `manifest.guestScript`
relative to the sidecar's own address
([`WebDelivery.kt`](../../engine/dogwood-web/src/wasmJsMain/kotlin/dev/dogwood/web/WebDelivery.kt),
ADR-032, ADR-062). Every sidecar in this repository named `guest-kotlin.js`, which is the webpack
output name of `samples/web-guest` and varies with nothing.

A canary on this profile is arranged the way ADR-049 arranges one: **one sidecar address answering
with different content per cohort.** That is what `tools/reference-server/server.py` does for the
mobile manifest with `?cohort=N`, and it is the only shape the delivery code supports — `start`
takes a single manifest address, and the script is resolved against it. So two live releases whose
sidecars both say `guest-kotlin.js` resolve to **one** absolute script address, and only one file
can be there.

Watched, in headless Chrome, against the distribution this repository builds, before anything was
changed. The signed sidecar for the release everyone else has, left exactly as the build produced
it; a second release's script published at the one address there is:

```
workerCreated=false refused=IntegrityRefused
delivery refused: the bytes at http://127.0.0.1:8931/guest-kotlin.js hash to
  2bda3a2c18e96c7336ebd7416af3d979404fd99051f3070844c1bee13195ea1c;
  the signed manifest says
  ad1688104b0f621ab1cfd9fa04c3415b67a857d59b0227c7e09751e157ce176a.
  The script was changed after the manifest was signed, and it will not run.
```

The control, the same page against the same sidecar with only one release published, reported
`workerCreated=true refused=None`.

This is the mobile failure one step later and one layer up. The integrity check (claim `B5`,
ADR-062) catches it, which is the right direction — a refused load rather than a wrong screen — but
a refused load is a page that cannot start, and which visitors it happens to is decided by which
cohort somebody pinned and which release published last.

## 2. Decision

**The guest script is renamed for its own content, and the new name is written into every sidecar
before the sidecar is signed.** `guest-kotlin.js` becomes
`guest-kotlin-<first 16 hex of its SHA-256>.js`, matching
[`engine/gradle/content-addressed-modules.gradle.kts`](../../engine/gradle/content-addressed-modules.gradle.kts)
digit for digit so the two profiles address a payload the same way.

The step lives in **`signWebSidecars`**
([`engine/samples/web-slice/build.gradle.kts`](../../engine/samples/web-slice/build.gradle.kts)),
which already stamps the real digest and the declared dictionary into every sidecar immediately
before signing it, and which is already `upToDateWhen { false }` for the reason this step needs too.

**A new claim, `B8`, with a control and a distinctness guard**, graded in a real browser by
`tools/conformance/web_services.py`: with two releases live at once, a visitor in each cohort loads
its own release's script.

**The hand-written `guest.js` is deliberately left alone.** It is a committed sample fixture that
proves the protocol is an interface rather than an artefact of having Kotlin on both ends; it is not
an over-the-air payload and does not vary per release, so it has no two releases to be ambiguous
between. `engine/samples/web-slice/harness/serve.py` also records fetches of `/guest.js` by name as
its evidence that a refused payload was never requested, and renaming it would cost that evidence to
buy nothing.

## 3. Rationale & Research

**Why the build, and not the deployment — checked, because this is exactly where ADR-077's first
reading went wrong.** The question the plan asked was whether the detached signature covers
`guestScript`, because if it did not the fix would be different and possibly unnecessary. It does.
The signature is over the manifest file's whole bytes:
`signWebSidecars` signs `manifest.readBytes()`, and `WebDelivery.verifySidecar` verifies over the
whole document string it fetched, deliberately before that string is parsed. `guestScript` is a
field in that document, so a deployment that renamed the script and rewrote the address would
invalidate the signature and every client holding keys would refuse.

That is not an inference from reading. It is **claim `B1`**, which has been green since ADR-062:
`tools/conformance/run-web.sh` takes the build's own signed sidecar, alters `guestScript` and
nothing else, serves the original signature beside it, and the browser reports
`SignatureRefused`. The one thing a deployment would have to do to fix this is the one thing an
existing claim proves a client refuses. So the address has to be written before the signature —
which means in the build, which is where `signWebSidecars` already is.

**Why content addressing rather than a per-release path.** The same three reasons ADR-077 gives, and
one more that is specific to this profile: the web sidecar resolves its script *relative to itself*,
so a per-release path would have to be produced by a deployment that already knows its release
identity, and the build does not. A digest already knows what it names. It also makes
`Cache-Control: immutable` honest for the one file on this profile that is re-fetched per publish —
`G6` exists precisely because the guest is paid per visitor per release while the host is paid once
per visitor.

**Why in `signWebSidecars` rather than in `copyKotlinGuest`.** `copyKotlinGuest` is a `Copy` task
and content addressing is not a copy: it needs the digest of the bytes that actually landed, it has
to delete the addresses the same script used to have, and the address has to be in the document
before the signature. `signWebSidecars` is the task that already reads the built script, already
rewrites the sidecars, already runs last, and is already `upToDateWhen { false }` because
`tools/skew-drill/run-web.sh` swaps a script in behind Gradle's back and runs it again. Everything
this step needs, that task already had.

**The integrity fixture, preserved as a property rather than as an implementation.**
`dogwood-manifest-tampered-script.json` is claim `B5`: a sidecar whose signature is *valid* and
whose digest is wrong by construction, modelling a script swapped at the origin after signing.
`signWebSidecars` used to skip that file entirely by name. It can no longer skip it entirely,
because a fixture naming `guest-kotlin.js` in a distribution that ships
`guest-kotlin-ad1688104b0f621a.js` would be refused with `ManifestUnavailable` for a 404 — and `B5`
would be grading a missing file rather than a swapped script, passing while measuring nothing. So
the skip was narrowed to what it was actually protecting: **the digest is left alone, the address is
rewritten like every other sidecar's.** The fixture's own comment now says which half is which. `B5`
verified after the change, in the browser, against the content-addressed name:

```
CONF B5 PASS -- refused=IntegrityRefused workerCreated=false -- the bytes at
  .../guest-kotlin-ad1688104b0f621a.js hash to ad168810…; the signed manifest says 0000…
```

**The budget, which is the other thing that could have passed while measuring nothing.**
`tools/conformance/from_guest_weight.py` looked the guest script up by the literal string
`guest-kotlin.js`. An address that changes with every payload would have made it stop finding its
file — the exact failure its own comment already records, from the run where `brotli` was absent and
the workflow went green having graded nothing. It now matches `guest-kotlin(-<16 hex>)?.js` and
**refuses when there is not exactly one**, because two addresses in one directory means a build step
left a stale payload behind and picking one would report a confident wrong number. Both refusals
were watched: with a second address present, and with none.

**`B8`, and why it is not run in the distribution root.** The claim needs two releases published
into one directory so that whatever collision the addressing scheme permits actually happens. Done
in the distribution root, the collision takes the drill's own guest with it: the first attempt did
exactly that and the run came back with `D1`, `D3`, `D4`, `D5`, `D7` and `B6-control` red and
`web_services.py` refusing before it reached `B8` at all. A red drill is not a wrong answer, but it
is one that says nothing about which claim found what. The two releases are now published into
`b8/`, which `WebDelivery` resolves correctly because it resolves the script against the sidecar's
address; the blast radius is one directory and `B8` is the claim that goes red.

Both `b8` sidecars are unsigned and loaded with `?trust=none`, because this drill has no private
key. That is not a hole in the claim: `B8` asks whether one address can name two payloads, `B1`,
`B2` and `B5` grade the signature posture, and the *digest* is still stamped and still enforced —
`integrityRequirement` verifies a digest whatever the key posture, which is what makes "it got its
own script" an assertion rather than an assumption.

**Watched to fail.** With the addressing disabled — one line in `signWebSidecars` returning the
plain name — the two releases were published at one address and the drill reported:

```
conformance: wrote 3 signature fixtures and 2 live releases in b8/ (both at guest-kotlin.js)
CONF B8-distinct FAIL -- 1.0.0-b8 at guest-kotlin.js, 1.1.0-b8 at guest-kotlin.js
CONF B8 FAIL -- workerCreated=false refused=IntegrityRefused -- the bytes at
  .../b8/guest-kotlin.js hash to d73aefc1…; the signed manifest says 599a39ad…
CONF B8-canary PASS
```

`B8-canary` passing there is the whole shape of the defect in one line: the cohort that keeps
working is the one that published last, and the other one is simply out.

**A hollow pass found on the way, in `B3`.** `tools/skew-drill/run-web.sh` wrote its
`dogwood-manifest-skewed.json` *after* the only signing pass, so that sidecar had no `.sig` and the
page — which holds keys by default — refused it for the missing signature. `B3` asserts
`workerCreated == false`, which that satisfies. The repository's own recorded results show it: web
`B3` reported `refused=DictionarySkew` on 2026-09-08, and `refused=SignatureRefused` on 2026-09-09
and 2026-09-14, after sidecar signing arrived. For two recorded runs the claim *the client refuses a
payload built against a dictionary it does not implement* was passing on the signature check. The
drill now runs `signWebSidecars` a second time over the written sidecar, which stamps it, addresses
it and signs it, and `B3` reads `refused=DictionarySkew` again — which also means the drill's
skewed sidecar gets its address from the build rather than from a second copy of the scheme kept in
a shell script.

## 4. Unstated Assumptions

- **Assumes sixteen hex digits of SHA-256 distinguish two payloads.** Sixty-four bits, and the same
  assumption ADR-077 makes. The full digest is still in `guestScriptSha256` and still checked before
  a Worker exists, so a collision would cost a refused load rather than wrong code.
- **Assumes a canary on the web is served by varying sidecar *content* at one sidecar address.** This
  is the shape `tools/reference-server` implements and the only one `WebDelivery.start` supports,
  since it takes one manifest address. A deployment that instead gave each release its own sidecar
  *path* would have had no ambiguity to begin with, because the script resolves relative to the
  sidecar — but it would also re-download every unchanged payload per release, and nothing in this
  repository arranges it. Said out loud here so the next reader can check it against their own
  deployment rather than inherit it.
- **Assumes the hand-written `guest.js` does not vary per release.** True by construction today — it
  is a committed resource, not a build output — and it is the reason this decision leaves it at a
  fixed name. A deployment that started publishing `guest.js` over the air would need the same
  treatment, and `signWebSidecars` addresses only `guest-kotlin`.
- **Assumes `signWebSidecars` is the last thing to touch the distribution.** It is, in this build:
  it is a finalizer of `wasmJsBrowserDistribution` and `mustRunAfter(copyKotlinGuest)`. A step added
  after it that wrote a guest script would produce a distribution whose sidecars name bytes that are
  no longer there — caught by `B5`'s control and by `B8`, but worth writing down.
- **Assumes the `b8` fixtures model two releases and not two *cohorts of one release*.** They differ
  by two lines of comment rather than by a rebuild, which is enough for an address and a digest and
  is not enough for a screen. `B8` grades delivery, not rendering; `B6`, `M1`–`M7` and the skew
  drill grade what a payload draws.

## 5. Updated Documents

- [`engine/samples/web-slice/build.gradle.kts`](../../engine/samples/web-slice/build.gradle.kts) — the addressing step in `signWebSidecars`.
- [`engine/samples/web-slice/src/wasmJsMain/resources/`](../../engine/samples/web-slice/src/wasmJsMain/resources/) — `dogwood-manifest-kotlin.json`, `dogwood-manifest-disabled.json` and `dogwood-manifest-tampered-script.json` say what the build rewrites and what it leaves alone.
- [`tools/conformance/run-web.sh`](../../tools/conformance/run-web.sh) — the `B1` fixture reads the address instead of spelling it out; the two `b8/` releases.
- [`tools/conformance/web_services.py`](../../tools/conformance/web_services.py) — `B8`, `B8-canary`, `B8-distinct`.
- [`tools/conformance/from_guest_weight.py`](../../tools/conformance/from_guest_weight.py) — `G6` finds the script by pattern and refuses when there is not exactly one.
- [`tools/skew-drill/run-web.sh`](../../tools/skew-drill/run-web.sh) — the skewed sidecar is stamped, addressed and signed by the build, and `B3` grades the dictionary check again.
- [`adrs/layer-3/ADR-077`](../layer-3/ADR-077-a-module-address-names-its-bytes.md) — the same decision on the Zipline path; this is its fourth client.
- [`adrs/layer-3/ADR-062`](../layer-3/ADR-062-a-signed-web-sidecar.md) — the signed sidecar and the digest this address is written beside.
- [`adrs/README.md`](../README.md) — index entry.

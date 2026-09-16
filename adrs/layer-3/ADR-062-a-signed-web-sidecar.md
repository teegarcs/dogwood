# ADR-062: A signed web sidecar

> **2026-09-15.** The sidecar now also carries `guestScriptSha256`; the client fetches the script
> bytes, verifies the digest before any Worker exists, and builds the Worker from a `Blob` of the
> verified bytes. The gap this record left open — "the guest script is still fetched without an
> integrity check" — is closed and graded as `B5`. See ADR-032's note of the same date.

**Date:** 2026-09-08
**Status:** Accepted

## 1. Context & Problem Statement

The mobile profile verifies an Ed25519 signature over the Zipline manifest against public keys
compiled into the binary. That is the trust anchor that makes downloading and running code
defensible at all: a device will not execute a payload the build pipeline's private key did not
approve, and a server that is compromised or substituted cannot change that.

The web profile had no equivalent. [ADR-032](../layer-5/ADR-032-the-web-profile.md) replaced the
signed manifest with a **sidecar** — a small JavaScript Object Notation (JSON) document fetched from
the guest script's own origin and checked on the main thread before the Worker is created — and
explicitly recorded that HyperText Transfer Protocol Secure (HTTPS) authenticates the *server*
rather than the *payload*. The gap was written down honestly, in the code, twice: the file header
said the parity path was "still unbuilt", and `DogwoodWebManifest.disabled` — the publisher's kill
switch — carried a comment saying that on this profile the switch was "only as trustworthy as the
origin serving it".

That is a fine thing to write down and a poor thing to leave. Everything the sidecar decides is
security-relevant: the address of the script the page will execute, the dictionary vector, the
release identity the crash-loop guard remembers, and the kill switch. All of it was being taken on
the word of whoever answered the request.

## 2. Decision

**The sidecar carries a detached Ed25519 signature, and a host that passes trusted keys refuses a
sidecar that does not verify — before the document is parsed.**

- `<manifest>.json.sig` sits beside the manifest and holds `keyName hexSignature`, one line per key.
  Blank lines and `#` comments are ignored.
- `WebDelivery` takes `trustedPublicKeys: Map<String, String>` with **no default**. `emptyMap()` is
  the written way to say "believe the origin", which is what every web host did before this.
- Verification happens on the manifest text **already fetched**, before `decodeFromString`.
- A **missing** signature document is a refusal, not a pass, when the host holds keys.
- The rotation rule is Zipline's, mirrored rather than reinvented: iterate the signatures the
  publisher offered, skip key names this client does not recognise, and let the **first recognised
  name decide** — verified or not. If no offered name is recognised, refuse.
- `signWebSidecars` in `samples/web-slice/build.gradle.kts` signs every sidecar in the distribution
  with the same two throwaway development seeds the mobile sample uses, and **verifies each
  signature against the public keys in `DogwoodTrust`** before writing it.

## 3. Rationale & Research

**Why detached rather than embedded.** Zipline's manifest carries its signatures inside itself and
excludes them from the signed body with an `unsigned` section. That works, but it means signer and
verifier must agree byte-for-byte on which subset of a JSON document was signed — a canonicalisation
problem, and canonicalisation problems fail silently and late. A detached signature covers the
file's exact bytes, whatever they are, and there is nothing to agree about. It also means the
manifest format did not change at all, so an existing deployment adds a file rather than migrating.

**Why before parsing, not merely before the Worker.** Parsing first would run this client's JSON
parser over bytes of unproven origin, and every field read out of them — `guestScript` above all —
would be a value an unverified document chose. The point of a signature is that nothing downstream
has to be careful.

**Why the same bytes.** The verifier checks `text`, the string already fetched and about to be
parsed. A verifier that fetched the document a second time would verify one copy and use another,
and a server that answered differently on the second request would defeat it entirely.

**Why the first recognised key decides, even when it fails.** Falling through to try the next
signature would mean a document carrying one bad signature and one good one is accepted — so an
attacker able to *add* a signature could simply add a good one for a key they hold, and the check
would pass. This is the rule `ManifestVerifier` already follows on mobile, and it is the reason the
rule reads the way it does.

**The primitive, verified rather than assumed.** `crypto.subtle` gained Ed25519 relatively recently
(Chrome 137, Safari 17, Firefox 129). Rather than assume, a probe generated a key pair, signed,
imported the bare 32-byte public key — the form a manifest carries — and verified both a good and a
tampered signature in the same headless Chrome the conformance drill uses:

```
UA ... HeadlessChrome/152.0.0.0
Ed25519 generate+sign+verify=true rawPublicKeyBytes=32
importKey raw -> verify=true
tampered signature verify=false
```

Two browser facts follow, and both are refusals rather than assumptions. `crypto.subtle` exists only
in a **secure context**, so a page served over plain HTTP on a non-localhost origin cannot check a
signature at all — a host that configured keys and cannot reach the primitive is refused rather than
silently downgraded, because the alternative is a page that stops checking precisely when its
transport is weakest. And an older browser throws on `importKey`, which lands in the same refusal.

**The build-time check on the committed key pair.** The seeds are committed (they are throwaway
development keys, as in the mobile sample) and the public keys live in `DogwoodTrust`. Two copies of
a key pair can drift, and the symptom of drift here — "the signature does not verify" — looks
exactly like an attack. So the signing task signs with the private seed and then verifies with the
public key the clients actually hold, and fails the build if they disagree.

## 4. Unstated Assumptions

- **That `crypto.subtle` is reachable.** True for HTTPS and for `localhost`; false for plain HTTP on
  any other origin. Handled as a refusal, but a deployment that serves over plain HTTP will find
  that its pages refuse everything, which is correct and will be surprising.
- **That `Response.text()` followed by `encodeToByteArray()` reproduces the bytes the signer
  signed.** True for any UTF-8 document, which the manifest is; a document that was not valid UTF-8
  could not have been parsed as a manifest either. A deployment serving the manifest with a
  different charset would break this, and would be broken for other reasons first.
- **That the guest script is not yet protected.** It is not, and this ADR does not claim otherwise.
  A signed sidecar naming a script does not stop a server from serving different bytes at that
  address. `guestScriptSha256` still exists, is still read only to report that it was ignored, and
  the enforcement step remains ADR-032's recorded gap. What signing the sidecar buys is that the
  hash, once enforced, will arrive on a document an attacker cannot rewrite — which is the order
  these two steps have to be taken in.
- **That publishers can run a signing step.** The sample's is a Gradle task using the Java
  Development Kit's own Ed25519 (Java 15 and later). A deployment whose pipeline cannot sign passes
  `emptyMap()` and is exactly where it was before.

## 5. Updated Documents

- [Layer 3: Over-The-Air Delivery and Security](../../specs/layer-3-delivery.md)
- [Layer 5 ADR-032: the web profile](../layer-5/ADR-032-the-web-profile.md) — its recorded gap is
  half closed; the guest-script half is not
- [Conformance plan](../../plans/conformance.md) — `B1` and `B2` graded on the web by a drill
- [`tools/conformance/claims.tsv`](../../tools/conformance/claims.tsv) — the web cell for `B1`/`B2`
  moved off a Java Virtual Machine test of the mobile verifier
- [Engineering backlog](../../plans/engineering-backlog.md) — S2 marked done
- [Checks](../../docs/checks.md)

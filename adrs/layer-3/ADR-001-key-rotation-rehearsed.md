# ADR-001: Key Rotation, Rehearsed

**Date:** 2026-09-02
**Status:** Accepted

## 1. Context & Problem Statement

The Ed25519 manifest signature is the reason downloaded code is acceptable at all. Everything else
in Layer 3 — the cache, the hash checks, the topological module order — assumes it. And the key
behind it is a single point of failure with an ordinary lifecycle: keys leak, laptops are lost,
people who held them leave.

Replacing one cannot mean bricking every client in the field. Zipline's design for that is a
manifest carrying several signatures, so that clients on either side of a change can verify the
same artifact while the fleet rolls forward.

[`specs/layer-3-delivery.md`](../../specs/layer-3-delivery.md) Milestone 4 is explicit that this
must be *exercised*, and when:

> Ship a manifest carrying two signatures, confirm an old client validates against the old key and
> a new client against the new, then retire the old key. This must be exercised **before the first
> production payload ships** — under the design-system-first path that is during Phase 4, not
> deferred to the roadmap's Phase 5 hardening pass.

It had never been exercised. Every signature test built a verifier with exactly one key; the sample
build declared exactly one signing key; each host carried a single-entry trusted map; and
`SignatureTest.theBuildActuallySignsTheManifest` actively asserted there was exactly **one**
signature — so the first attempt at a rotation would have failed the test suite before it failed
anything else. `DogwoodDelivery`, which holds the multi-key loop, was never instantiated in a test
at all.

A rotation that has never been rehearsed is a rotation nobody will attempt until it is an
emergency, which is the worst moment to discover its semantics.

## 2. Decision

**Rehearse the whole sequence, against the manifest the build actually produces, and keep the
sample in the middle step of a rotation permanently.**

1. The slice guest is signed by **two** keys, `dogwood-development` and `dogwood-development-2`.
2. Both sample hosts trust both. This is the state a real fleet spends weeks in.
3. `KeyRotationTest` covers all three steps — ship both signatures, roll clients forward, retire
   the old key — including what retirement does to a client that never updated.
4. `SignatureTest` now pins the signature **order**, because the order is part of the contract.

Keeping the sample mid-rotation rather than returning it to one key is deliberate: the multi-key
path is the one that carries risk, and a path exercised only by a test that someone might delete is
weaker than a path every run of the sample exercises.

## 3. Rationale & Research

### The mechanism, confirmed rather than quoted

`specs/layer-3-delivery.md` describes Zipline's verifier as skipping key names it does not
recognise, requiring the **first recognised** name to verify, and throwing if it recognises none.
That was a documented claim; the drill turns it into a measurement, against the real two-signature
manifest.

Two consequences follow, and both are load-bearing:

**Signature order decides which key is actually used.** A client trusting *both* keys verifies
against whichever the manifest lists first — here the old one. "We have rolled the client forward to
the new key" therefore does not mean the new key is being used, and a team that assumed otherwise
would not discover it until they retired the old key and found the new signature had never been
exercised in production. `aClientTrustingBothUsesTheFirstSignatureTheManifestOffers` pins it, and
the sample confirms it end to end: an Android host trusting both, loading a manifest signed by both,
reports `signed by dogwood-development`.

**A recognised name that fails does not fall through.** This is the property that makes the scheme
safe rather than merely convenient. If a recognised-but-invalid signature caused the verifier to try
the next one, an attacker who could add a signature under a trusted *name* would need the manifest
only to carry one valid signature somewhere. It rejects instead, even when a signature it would have
accepted sits directly behind the failing one.

### Retirement has a blast radius, and that is the point of rehearsing it

Step three is dropping the old signature and shipping. A client still holding only the old key then
stops accepting updates — not loudly, but by falling back to its cached payload indefinitely.
`retiringAKeyIsWhatEndsTheRotationAndItHasABlastRadius` asserts both halves: the rolled-forward
client keeps updating, and the one left behind stops. That asymmetry is what makes the roll-forward
a step to *finish* rather than start, and it is the thing a team needs to have seen before an
incident forces it.

### The keys in the repository

Both are **throwaway development keys**, committed on purpose so the slice builds for anyone who
clones this. They sign nothing anyone should trust. A real signing key never lives in a repository:
pass `-PdogwoodSigningKey=<hex>` and `-PdogwoodRotationKey=<hex>`, or set them in
`~/.gradle/gradle.properties`, and rotate the public keys in the hosts to match.

## 4. Unstated Assumptions

- **The verifier's behaviour is Zipline's, not ours.** These tests pin the behaviour the delivery
  path depends on so that a Zipline upgrade that changed it fails here rather than in production.
- **Retirement is simulated by removing a signature from the manifest text.** Sound because
  signatures live in the manifest's `unsigned` block and are not covered by each other's signature —
  which is also why removing one does not invalidate the other. The remaining signature is verified,
  not assumed, in that same test.
- **Two keys is the rehearsal, not a recommendation.** Nothing here says a production deployment
  should hold two keys indefinitely; the point of the middle step is to leave it.

## 5. Updated Documents

- [Layer 3: Delivery](../../specs/layer-3-delivery.md) — Milestone 4 is done, and the verifier's
  ordering semantics are recorded as measured rather than described.

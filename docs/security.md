# Security: what "sandbox" means here, and the threat model

*Found something? [`SECURITY.md`](../SECURITY.md) says how to report it privately, and what is in
scope. This page is the model; that one is the channel.*

For whoever reviews this before it ships. It answers the question a security reviewer asks first —
**what stops a downloaded payload from doing harm?** — and it does so by naming the boundaries that
exist, the ones that do not, and the residual risks a deployment accepts.

The one-paragraph version: **Dogwood is trust-by-signature, not isolation.** A payload is code the
application's own team published and signed; the device verifies the signature before a byte of it
runs, and everything the payload can touch is a service the host chose to hand it. The interpreter
it runs in has no ambient capabilities, which bounds what a *buggy* payload can do; it is not a
security boundary against a *hostile* one, and no document in this repository should be read as
claiming it is.

---

## 1. The word "sandbox"

The specifications and the README use "sandbox" for the guest runtime: QuickJS on mobile, a Web
Worker in a browser. The word is doing one job — **the guest has no ambient access to anything.**
No file system, no network, no platform APIs, no reflection into the host process. What it can
reach is exactly the set of services the host binds for it, and nothing else exists in its world.

It is *not* doing the other job the word usually does. Zipline's own documentation states that it
"does not offer a sandbox or process-isolation and should not be used to execute untrusted code",
and that is the correct reading here too: the guest runs in the application's process, on a thread
the application owns, in an interpreter that is a C library linked into the binary. A memory-safety
defect in QuickJS is a defect in the application. **The guest must therefore be trusted code**, and
the whole delivery layer exists to make "trusted" mean something specific: signed by a key the
application was built to trust, or refused.

A reader who wants a synonym: *capability-confined*, not *isolated*.

## 2. Assets, and who is trusted with them

| Asset | Where it lives | Who may touch it |
|---|---|---|
| The user's data on the device | the host application | the host, and the guest only through services the host binds |
| The user's credentials and session | the host application | the host only; a guest sees an `HttpResponse`, never a cookie jar or token store |
| The application's integrity | the installed binary | the store; a payload cannot add native code (ADR-046: a segment cannot arrive over the air) |
| The signing keys | wherever the owner keeps them — never this repository | the publish pipeline |
| The manifest and payload | the owner's server | the publish pipeline writes; every device reads and verifies |

**Trusted:** the application binary, the signing keys, the build server that compiles payloads,
and the team that publishes them. **Not trusted:** the network path (including the payload host's
CDN once bytes have left the pipeline), any other origin on the web, and the *values* inside a
payload — a signed payload can still be wrong, and the host treats every number and name it carries
as something to validate.

## 3. What exists, boundary by boundary

Each row names the protection, where it is enforced, and the record with the evidence.

| Boundary | Protection | Enforced | Evidence |
|---|---|---|---|
| Code authenticity | Ed25519 signature on the manifest; a second rotation key so a key can be replaced without stranding installed clients | before a byte runs, on every client; on the web via a detached sidecar verified **before the manifest is parsed**, so no field of an unverified document — including the script address — is ever read | `B1`, `B2` in `plans/conformance.md`; [ADR-062](../adrs/layer-3/ADR-062-a-signed-web-sidecar.md) |
| Code authenticity, the metadata | the kill switch and the declared dictionary ride the manifest's **signed** `metadata`, not its unsigned section | a network attacker cannot rewrite either to stop a client running its own payload | [ADR-061](../adrs/layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md) §3 |
| Network egress | `DogwoodNetwork` refuses everything by default; hosts are allowed by name; cleartext is opted into per host, and names `http` rather than excusing "not HTTPS"; the rule is re-applied on every redirect hop; the response body is capped on the declared length and mid-stream | the host, on Android and iOS, with contract tests on both | [ADR-033](../adrs/layer-5/ADR-033-the-ios-host-profile.md) §3, which records the three holes the port found and closed, including `file://` reaching the app container |
| Image loading | a separate origin policy for images, because an image request is a network channel with a different reviewer | the host's image loader | [ADR-034](../adrs/layer-5/ADR-034-images-are-a-network-channel.md) |
| Resource exhaustion | `GuestLimits` on by default: a 256 MiB heap cap and a 5-second interrupt on any unbroken run of guest execution, applied before the first slice composes | QuickJS's own limits, through the engine Zipline exposes | [ADR-060](../adrs/layer-5/ADR-060-bounds-on-a-runaway-payload.md); the negative control *hung* rather than failed |
| Hostile or buggy values | ranges Compose enforces by throwing are clamped and reported; `@Range` on the surface makes the clamp generator-wide | every host, inside the binding | [ADR-035](../adrs/layer-5/ADR-035-hostile-property-values.md); `HostileValueTest` |
| Version skew | an unknown widget is an inert placeholder; an unknown cosmetic property is ignored; an unknown property on a widget that owns an affordance **withholds the widget**, because one of the things the payload may have said is "this is disabled"; a payload declaring a dictionary the client lacks is refused before `start` | every host | [ADR-031](../adrs/layer-5/ADR-031-safety-relevant-parameters.md), [ADR-061](../adrs/layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md); `A2`–`A4`, `B3` |
| A bad publish | crash-loop quarantine persisted **before** a release runs, so a version that repeatedly fails is never launched a third time; a signed kill switch; the last known-good payload keeps serving on delivery failure | every host, on by default | [ADR-049](../adrs/layer-5/ADR-049-surviving-a-bad-publish.md), [ADR-058](../adrs/layer-5/ADR-058-protection-by-default.md); `H2`, `B4` |
| Batch integrity | a change batch applies whole or not at all; a batch whose grammar disagrees is refused and the previous tree stays up | every host | [Layer 4 ADR-011](../adrs/layer-4/ADR-011-a-batch-applies-whole-or-not-at-all.md) |
| Host services | a guest calls only what `DogwoodServices.available()` names; a null service cannot cross; a lambda cannot close over a host object because the boundary carries data | the protocol | [ADR-029](../adrs/layer-5/ADR-029-a-null-service-cannot-cross.md); `docs/authoring.md` §6 |
| Source exposure on the web | the guest's source map is deliberately not served; symbolication is offline | deployment | [ADR-063](../adrs/layer-5/ADR-063-a-web-crash-carries-its-frames.md) |
| Code shrinking | the engine needs no keep rules; the conformance suite runs against the R8 build | Android | [ADR-056](../adrs/layer-5/ADR-056-the-engine-survives-code-shrinking.md) |

## 4. What does not exist, stated rather than glossed

- **No process isolation.** The guest shares the application's process and address space. A
  memory-safety defect in QuickJS, or in a native library it reaches, is exploitable by a payload
  that can trigger it. The mitigation is that only signed payloads run; there is no second line.
- **No integrity check on the web guest script.** The sidecar manifest is signed and verified; the
  script it names is fetched by `new Worker(url)`, which supports no Subresource Integrity
  attribute. HTTPS and same-origin are what protect it ([ADR-032](../adrs/layer-5/ADR-032-the-web-profile.md)
  states this as the profile's weakest part). Closing it means constructing the Worker from a
  verified blob, which is recorded as open.
- **No origin isolation on the web.** A Worker shares the page's origin and carries its credentials
  on requests the Content Security Policy permits. The mobile allow-list is the host's; on the web
  it is the page's `connect-src`, and a page that sets none has no allow-list at all.
- **A web page may choose to believe its origin.** `WebDelivery(trustedPublicKeys = emptyMap())`
  skips signature verification. It is a decision somebody writes rather than a default, and a
  reviewer should look for it.
- **The saved-state store is not encrypted.** It is private storage, excluded from backup on both
  platforms, and the `sensitive` marking keeps a field out of it; it is not a vault
  ([Layer 4 ADR-010](../adrs/layer-4/ADR-010-state-that-outlives-the-process.md)).
- **Nothing here reviews what a payload *does*.** A signed payload that navigates a user somewhere
  harmful, or renders a misleading screen, is a publishing-process failure. Staged rollout,
  cohorts and the kill switch are the tools; the review before publish is the owner's.
- **The build server is in the trusted computing base.** A compromised compiler produces a
  correctly signed hostile payload. That is true of the application binary too; it is named here
  because the payload pipeline is newer and less likely to have inherited the binary's controls.

## 5. Threats, and the answer to each

| Threat | Answer |
|---|---|
| An attacker on the network replaces the payload | refused: the signature does not verify. On the web, refused before the document is parsed |
| An attacker on the network deletes the web sidecar signature | refused when keys are passed: a missing signature is a refusal, because an attacker who can replace a manifest can delete the file beside it |
| An attacker rewrites the kill switch or the declared dictionary to stop clients running their own payload | cannot: both ride signed metadata |
| The signing key leaks | rotate: publish manifests carrying both signatures until every client trusts the new key, then remove the old. The procedure is the owner's to write down (`OPEN-DECISIONS.md` §6) |
| A published payload has a bug that crashes on launch | quarantined on the device after repeated failure, without a server; killed fleet-wide by the switch; rolled back by serving the previous manifest |
| A payload sends a value Compose throws on | clamped and reported |
| A payload tries to reach a host it should not | refused by the allow-list, on every hop, on mobile; refused by the page's policy on the web |
| A payload allocates or loops without bound | the heap cap refuses the allocation; the interrupt stops the slice; both reported |
| A payload uses a component or entry this client does not have | placeholder, ignored, withheld or refused, by the containment rules; every case reported |
| A payload exfiltrates user data through an allowed host | **not prevented.** Anything the guest can read through a bound service it can send to an allowed host. Bind narrowly; allow narrowly |
| A defect in QuickJS is triggered by a crafted payload | **not prevented**, beyond the signature. This is the residual risk of running an interpreter in-process, and it is why "trusted" is a precondition rather than a goal |

## 6. What a deployment owes this document

- Production keys generated and held outside any repository, with a named owner and a written
  rotation and revocation procedure.
- A publish pipeline where build, sign and upload are one reviewed step, and where the manifest is
  served uncacheable while payload files are served immutable.
- On the web: HTTPS, a `connect-src` policy, and `trustedPublicKeys` that is not empty.
- Network and image allow-lists that name the hosts the product actually uses and nothing wider.
- The skew drain and the release guard's report wired to telemetry, so every containment above is
  seen rather than inferred from a support ticket.

Everything in this list is recorded as an owner decision or an operating step elsewhere; this
document exists so a reviewer can find the whole picture in one place and check each row against
the record it cites.

# ADR-077: A module address names its own bytes, and only the build can write it

**Date:** 2026-09-17
**Status:** Accepted — group 1 of [`plans/close-the-open-items.md`](../../plans/close-the-open-items.md).

## 1. Context & Problem Statement

A Zipline manifest names each module by a relative address. For every release this project has ever
produced that address is the same string, `slice-guest.zipline`, because it is the output file name
and nothing varies it.

That is invisible while one release is live. [ADR-049](../layer-5/ADR-049-surviving-a-bad-publish.md)
made two releases live at once — a canary pinned to a bucket range beside the release everyone else
has — and `tools/reference-server/cohort-drill.sh` is the only place in this repository that
arranges it. The first run of that drill watched a client pinned to `1.1.0-bad` fetch
`1.0.0-good`'s module and refuse the load on the digest its own signed manifest named.

Nothing in the request said which release it belonged to, so the server answered with the newest
bytes it held. The signature caught the mismatch, which is the right direction: the outcome is a
refused load rather than a wrong screen. But a refused load is a device that cannot start, and which
devices it happens to is decided by which cohort somebody pinned.

The defect went unclosed for one release because it was **misdiagnosed**. `server.py`'s comment said
"the fix belongs in publishing", `publish` printed a warning describing what a real deployment should
do instead, and `quarantine-drill.sh` published straight past that warning. A warning that everything
ignores is a defect with documentation.

## 2. Decision

**A module file is renamed for its own content, and the new name is written into the manifest before
the manifest is signed.** `slice-guest.zipline` becomes
`slice-guest-<first 16 hex of its SHA-256>.zipline`.

**The reference server keeps one content-addressed pool shared by every release**, instead of
searching releases newest-first for a name every release reused. A module request names the bytes
it wants, so there is no guess left to make.

**`publish` refuses a payload whose module addresses do not name their bytes**, where it used to
warn and publish anyway.

## 3. Rationale & Research

**Why this cannot be a publishing step, which is where the first reading put it.** A module's `url`
sits beside its `sha256` inside the signed region of the manifest — `ZiplineManifest` holds `modules`
alongside `unsigned`, and `getSignaturePayload()` covers everything but `unsigned`. A publishing
step that renamed modules would invalidate every signature it touched, and every client would refuse
to start. That is now
`SignatureTest.movingTheModuleAddressIsRejected`, asserted against the manifest the build actually
produces, so the next person to reach for the publishing fix finds out in a second rather than in an
outage. The test sits beside `reformattingTheManifestIsDeliberatelyNotTampering`, which is its
negative control: a change *outside* the signed region is accepted.

**Why the server cannot route around it either.** Two readings of `zipline-loader-jvm-1.27.0`,
because this is the kind of claim that is easy to assume and expensive to assume wrongly:

- `HttpFetcher.withBaseUrl` **removes** any `unsigned.baseUrl` the server sent and replaces it with
  the URL the client requested. So a server cannot declare where its modules live.
- `ZiplineHttpClient.download` returns bytes, not a final URL. So a redirect from a well-known
  manifest address to a release-scoped one cannot move where modules resolve either — the loader
  never learns it was redirected.

The address the client asks for is the address the build wrote. So the build writes a better one.

**Why content addressing rather than a per-release path prefix**, which is the other thing the old
warning suggested:

| | content address | release prefix |
|---|---|---|
| Uniqueness | by construction; a digest already knows what it names | needs the release threaded into the build |
| `Cache-Control: immutable` | true — an address can only hold the bytes it is named for | true, but every release re-downloads every module |
| A canary changing one module | one new file, one download | a full second copy |
| Collision | impossible; equal names mean equal bytes | possible, and detectable only by comparing |

**Why the step is attached to the Zipline task rather than being a task of its own.** A separate task
would have to be a finalizer, and a finalizer runs *after* tasks that merely `dependsOn` the task it
finalizes — so `dogwood-host`'s tests, `slice-desktop` and `slice-ios` could all have read the
manifest before it was rewritten. Hooking the producer means every existing `dependsOn` already waits
for the right bytes. The plugin's own `signingKeys` block is left in place so a manifest that somehow
skipped this step is still signed rather than unsigned; the re-signing replaces those signatures with
ones over the rewritten manifest, using the same keys in the same order, which
`SignatureTest` and `KeyRotationTest` both pin.

**The claim, and the hollow pass it nearly was.** `B7` is *with two releases live, each cohort loads
its own release's modules* — `updated` rather than `verified`, because `verified` says the manifest
was authentic and `updated` says every module it named arrived with the digest it named, which is the
step that used to fail.

`B7` passed on its first run and was testing nothing. `-PdogwoodVersion` is a build input that
reaches the manifest and not the bundle, so the drill's two releases had **byte-identical modules**
and therefore one shared content address; `quarantine-drill.sh` says as much about its own two
payloads. The claim was only saved by `B7-distinct`, added at the same time out of the same
suspicion: *the two live releases publish their modules at different addresses*. It failed, printing
the same digest twice. The canary is now built from genuinely different source, patched and restored
the way `tools/skew-drill/run.sh` does it, and `B7-distinct` is what will fail if anybody removes
that.

**Watched to fail.** With the content-addressing step commented out, `publish` refuses both releases
by name and the drill stops. The first time that was watched, the drill reported "the reference
server never answered on :8475" — the symptom three steps downstream of the cause — because `build`
ignored `publish`'s exit status. It no longer does.

## 4. Unstated Assumptions

- **Assumes sixteen hex digits of SHA-256 is enough to distinguish two modules.** Sixty-four bits.
  A collision would have to be found rather than met, and the full digest is still in the manifest
  and still checked by the client, so a collision costs a refused load rather than wrong code.
- **Assumes the manifest lists every file that must be uniquely addressed.** The sample's data files
  are published beside their manifest rather than into the pool, and are served from the live
  release. That is a choice rather than a fact, said out loud in the server: a deployment serving
  assets that way with two releases live has the problem the modules no longer have.
- **Assumes `ManifestSigner.sign` replaces signatures rather than adding to them.** Read from the
  bytecode — it builds its signature map from its signer set — and asserted by `SignatureTest`,
  which checks both key names and both verifications on the produced manifest.
- **Assumes the guest's bundle is not byte-reproducible across unrelated builds.** Not assumed
  anywhere that matters: `B7-distinct` measures it rather than trusting it.

## 5. Updated Documents

- [`plans/close-the-open-items.md`](../../plans/close-the-open-items.md) — group 1 is this decision's plan.
- [`plans/conformance.md`](../../plans/conformance.md) — `B7`, `B7-distinct` and `B7-spared` in the B family.
- [`docs/operating.md`](../../docs/operating.md) — what a deployment must do about module addresses.
- [`adrs/layer-5/ADR-049`](../layer-5/ADR-049-surviving-a-bad-publish.md) — the staged rollout that made two live releases reachable at all.
- [`adrs/README.md`](../README.md) — index entry.

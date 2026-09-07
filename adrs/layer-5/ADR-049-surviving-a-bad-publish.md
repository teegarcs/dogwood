# ADR-049: Surviving a Bad Publish

**Date:** 2026-09-06
**Status:** Accepted

## 1. Context & Problem Statement

The architecture's selling point is shipping a screen without a store review.
[`plans/production-readiness.md`](../../plans/production-readiness.md) §4.2 named the half that was
missing:

> No rollout, no rollback. A payload is published and clients fetch it. There is no staged rollout,
> no canary, no kill switch, and no "go back to the previous manifest" that is not "publish the old
> bytes again by hand". For a delivery mechanism whose selling point is *shipping without a store
> review*, the ability to un-ship is the other half and it is missing.

`DogwoodDelivery.updates` already survived a failure to **fetch**: a failed poll leaves the previous
guest running and the next poll tries again. Nothing survived a payload that fetched perfectly and
then did not work — threw on its first composition, produced no tree, wedged.

**The worst shape of that is self-concealing.** A payload that crashes the application on launch
erases, by crashing, every recovery mechanism that lives in memory. The application relaunches,
loads the same payload, and crashes again — on every device that fetched it, forever. A store review
would not have caught it and cannot fix it either. The fix has to be on the device.

## 2. Decision

**Three small things, and the ordering of one of them is the whole design.**

**An attempt is recorded before the payload runs, and written to disk.** That single ordering is
what makes a crash loop countable, and everything else here is bookkeeping. It costs one small file
write per launch.

**A version that fails to work twice is quarantined**, and the guard names the last version known to
have worked. Two rather than one, because a single failure can be the device's fault — a process
killed while backgrounded during the first composition looks exactly like a payload that crashed.
Two consecutive failures on one version is a payload.

**A publisher can stop a release from the manifest.** `dogwood.disabled = "true"` in the manifest's
**signed** `metadata`. Signed matters: an attacker who could set it in an unsigned field could
disable an application through the very channel that exists to secure its updates.

Three consequences that are decisions rather than details:

- **"It loaded" is not success.** A payload that throws on its first composition has loaded, so
  `ReleaseGuard.succeeded` is called when a guest has started, produced a tree, and the host has
  mounted it. The call sits at the end of the swap, not next to `starting`.
- **The kill switch does not fall back.** A publisher disabling a release may be disabling the
  *feature*, not the build; falling back to the last good version would run the thing they just
  stopped. A quarantine does name a fallback, because a quarantine is the device's own judgement
  that this build is broken.
- **A quarantined version that later works is forgiven.** A truncated download quarantines a version
  that was never broken, and refusing it forever would make the guard the outage.

**Naming a fallback is not running one.** Resuming a previous *payload* means fetching a manifest
that still serves it, which is a server's job. What a host can always do without a server is refuse,
say why, and show something of its own — which is the difference between a bad publish being a bad
hour and a bad week.

**Staged rollout is explicitly not built.** `InstallCohort` gives a device a stable bucket, derived
once from a random value, so a server *could* stage. Choosing which cohorts get which manifest is a
server's decision and there is no server. The absence is a missing server rather than a missing
capability on the device, and that distinction is worth keeping because only one of the two is
engineering here.

## 3. Rationale & Research

**Why the policy is transport-free.** `ReleaseGuard` lives in `dogwood-host`'s common core and knows
nothing about manifests. What a manifest *is* differs between a Zipline payload and a web sidecar;
what "the publisher said stop" means does not. The Zipline layer reads `manifest.version` and
`manifest.metadata` and passes two plain values in.

**Two defects the tests found, and one of them was an off-by-one that mattered.** `maxFailures = 2`
ran a crashing payload **three** times, because the guard quarantined on `next > maxFailures` — that
is, *during* the third start, after it had already been allowed to run. The test that caught it is
written as the crash loop it models, launching and rebuilding the guard from disk each time; a test
written as three calls in a row would have asserted the counter and agreed with itself. The second:
a store that throws took the launch down with it. A guard whose job is making a bad payload
survivable must not turn an unwritable file into a crash, so it degrades to useless — no memory, so
no quarantine — and reports.

### Verified on a device, because persistence and refusal are both only real there

Pixel emulator, API 35, against the live delivery path.

| Observation | Result |
|---|---|
| A normal launch | `{"lastKnownGood":"1.0.0","quarantined":[],"attempts":{}}` on disk |
| Republish with `-PdogwoodDisabled=true` | banner: **"app refused: the publisher disabled this release"**, and the running guest keeps rendering |
| Cold launch under the kill switch | host chrome and the reason, `load #0` — nothing downloaded runs |
| Remove the switch and republish | `load #1 · v1.0.0`, the screen is back |

The recovery run also showed a defect worth keeping in the record: the refusal banner outlived the
refusal, so "the publisher disabled this release" sat above a screen the guest had just successfully
rendered. A swap now clears it.

**The crash-loop half is not provoked on a device, deliberately.** Quarantine is arithmetic over
state persisted before a payload runs; the device evidence is that the state *is* persisted, and the
arithmetic is unit-tested as the loop it models. Making a real payload crash on launch on demand
would mean shipping a deliberately broken guest in the sample — a thing that can be published by
accident, which is the accident this exists to survive.

## 4. Unstated Assumptions

- **Nothing resumes a previous payload.** The guard refuses and names a version; running it needs a
  server that still serves its manifest. A host with no previous guest shows its own screen, and
  what that screen is belongs to the host — the sample shows its shell.
- **A payload without a version cannot be tracked.** It is named for its main module rather than
  refused, which distinguishes two payloads from each other and nothing more. Publishing without
  `version` is not an error and probably should become one.
- **The guard counts starts, not crashes.** It cannot tell a payload that crashed from one the user
  dismissed in under a second, which is why the threshold is two rather than one and why forgiveness
  exists.
- **The kill switch is as fresh as the manifest.** A device that cannot reach the network keeps
  running what it has. That is correct — the alternative is an application that stops working when
  offline — and it means the switch is not instantaneous.
- **Nothing yet reports this anywhere.** Refusals and quarantines are surfaced to the host through
  callbacks and shown in the sample; `SkewReport` does not carry them, and a publisher learns that a
  release was refused across a fleet only if the host wires it to telemetry.

## 5. Updated Documents

- [`plans/conformance.md`](../../plans/conformance.md) — capability group **H**, release control.
- [`plans/production-readiness.md`](../../plans/production-readiness.md) — §4.2 and the order.
- [`adrs/README.md`](../README.md) — index entry.

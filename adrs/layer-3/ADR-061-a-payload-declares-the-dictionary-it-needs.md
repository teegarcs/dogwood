# ADR-061: A payload declares the dictionary it needs

**Date:** 2026-09-08
**Status:** Accepted

## 1. Context & Problem Statement

A Dogwood payload is downloaded and replaced over the air, and a client is a binary somebody
installed weeks ago from a store. The two move at different speeds, so a payload built against
dictionary version N+1 reaching a client that implements N is not an edge case: it is the normal
consequence of shipping faster than a store review, which is the entire point of the architecture.

Section 6 of the specification answers that with **containment** at render time. An unknown widget
tag becomes a placeholder that holds its slot; an unknown property on a widget that owns no
affordance is ignored; an unknown property on a widget that *does* own one withholds the widget
entirely, because one of the things the payload might have been saying is "this is disabled" and the
client cannot read it (ADR-031). All three are graded end to end on Android and iOS as conformance
claims `A2`, `A3` and `A4`, by `tools/skew-drill`.

The web profile has a second line as well, and has had one since ADR-032: the manifest carries a
segment-version vector, `WebDelivery` compares it against what the client implements, and refuses
**before creating the Worker**. That refusal is conformance claim `B3`.

The mobile clients had no equivalent. `plans/adoption-audit.md` recorded the asymmetry under A3, and
it is a real gap rather than a cosmetic one. Containment degrades gracefully by design, and graceful
degradation is exactly what a user cannot tell from a bug: a screen missing its "Pay" button because
the client could not read an affordance-bearing property looks, to the person holding the phone,
like a screen missing its "Pay" button. Refusing outright and saying which dictionary is missing is
the kinder failure, and it is available to the client for free — the payload knows what it was built
against.

## 2. Decision

**The payload declares the dictionary it was built against, in the manifest's signed metadata, and
a mobile client compares that declaration before it starts the guest.**

Concretely:

- The manifest carries `dogwood.segments`, a comma-separated list of `wireName:version` pairs,
  beside the existing `dogwood.disabled` kill switch. Both ride **signed** metadata.
- `DogwoodDelivery.loadGuarded` and `DogwoodSession`'s update path both call
  `checkDeclaredDictionary(declared, clientVersions)` and refuse on a non-null result, closing the
  Zipline instance and reporting a `GuardedRelease` to the host. The check runs **before** the
  release-guard verdict, because it is the more specific answer: telling a host "quarantined" when
  the truth is "your client is a release behind" sends somebody to look at the wrong thing.
- The comparison is the web's, deliberately identical. A payload may name **fewer** segments than
  the client implements — a guest that uses no design-system component says nothing about that
  segment — so absence is never a refusal. The refusals are the other direction: a segment the
  client has never heard of, and a segment the client is behind on.
- **A payload that declares nothing still runs.** That is the ordinary case for everything built
  before this field existed, and containment remains what protects those. This is a second line, not
  a new requirement; refusing on absence would turn an engine upgrade into a fleet-wide outage on
  the next poll.
- The declaration is **read out of the generator's own output** — `DogwoodSegments.kt` and each
  product's dictionary JavaScript Object Notation (JSON) file — never restated in a build script.

## 3. Rationale & Research

**Why signed metadata.** Zipline's `ZiplineManifest` has both a `metadata` map inside the signature
payload and an `unsigned` section outside it. An attacker who can rewrite an unsigned field and
thereby stop a client running its own payload has a denial of service delivered through the channel
that exists to secure updates. `dogwood.disabled` already rides `metadata` for this reason
(ADR-046); this joins it.

**What "before it runs" means here, and why it is weaker than the web's.** The web host fetches and
verifies the manifest itself, so it can refuse without ever creating a Worker — nothing of the
payload executes at all, and claim `B3` on the web asserts `workerCreated=false`. Zipline exposes no
manifest-only fetch to a mobile client. `ZiplineLoader.loadOnce` fetches, verifies and evaluates the
modules in one call; `fetchManifestFromNetwork` and `LoadedManifest` are `internal`. Checked against
`zipline-loader` 1.27.0 by decompiling the published artifact:

```
$ javap -cp . app.cash.zipline.loader.ZiplineLoader | grep -i manifest
  public final java.lang.Object loadFromManifest$zipline_loader(...)
  public static final java.lang.Object access$fetchManifestFromNetwork(...)
```

Both carry the `$zipline_loader` mangling that marks an `internal` declaration, and `LoadedManifest`
lives in `app.cash.zipline.loader.internal.fetcher`. Reaching either means either duplicating
Zipline's signature verification — the security boundary that makes downloaded code acceptable at
all — or depending on an internal symbol that can change without notice. Neither is worth it for a
check that is already a second line of defence.

So the mobile check runs after module evaluation and before `start`: no entry point is called, no
service is bound, nothing composes, and the QuickJS instance is closed. That is written down in the
drills and in the claim's own detail line rather than blurred into "before any guest code runs",
which would be false on this platform.

**Why the declaration is derived rather than written.** The first version of this scraped a number
into the build script by hand. A hand-typed version is a second copy of the truth, and the failure a
second copy causes — a manifest describing a payload that no longer exists — is precisely the
failure the declaration exists to prevent, arriving through the declaration itself. The project has
been bitten by exactly this once already, when a hand-typed `6` disagreed with a lock that said `7`.
Every number now comes from a file `dogwood-codegen` wrote.

**And it must resolve late.** The second version read those generated files inside
`metadata.set(...)`, which Gradle evaluates while configuring — so it read whatever the *previous*
build left behind. This was not hypothetical: the first drill run moved the design system to version
15, and the manifest went out declaring 14. `metadata.putAll(provider)` defers the whole map to task
execution, after generation. The fix was verified by bumping the generator's version and watching
the manifest follow it in the same build, then watching it follow back.

**The same hazard, on the client side.** `DogwoodDictionary.segmentVersions` is a property with a
getter precisely because a product's segments join it when they register, and its own documentation
warns that a value computed at class-initialisation time "would have told every guest that the
product's own components did not exist". A default parameter capturing that map at construction
reintroduces the bug in a worse form: a client that built its delivery before registering its own
bindings would *refuse* every payload naming them. `clientSegmentVersions` is therefore a
`() -> Map<String, Int>`, read at check time.

## 4. Unstated Assumptions

- **That `metadata` is inside the signature payload.** Assumed on the basis that `dogwood.disabled`
  already relies on it and `SignatureTest`/`KeyRotationTest` pin the manifest's signing behaviour
  against the real signed artifact. If Zipline moved `metadata` outside the signature, this field
  and the kill switch would both silently become attacker-controlled.
- **That segment versions are monotonic and dictionaries are append-only.** The refusal rule "wanted
  > have" is only correct under the lock's append-only discipline, which is what makes a client one
  version ahead able to render everything an older payload can name. `dogwood-codegen`'s lock
  enforces it; a project that bypassed the lock would break this check's premise.
- **That a product registers its bindings before it constructs a delivery.** Now much weaker than it
  was — the lambda means registration merely has to happen before the first *load*, not before
  construction — but a product that never registers a segment its payload declares is refused, which
  is correct and will look like a configuration error until read.
- **That refusing is better than degrading, for a declaring payload.** This is a product judgement,
  not a technical one. It is the judgement the web already made in ADR-032, and making the two
  profiles disagree would be worse than either answer.

## 5. Updated Documents

- [Layer 3: Over-The-Air Delivery and Security](../../specs/layer-3-delivery.md)
- [Layer 5: Host Runtime and Session Management](../../specs/layer-5-host.md)
- [Conformance plan](../../plans/conformance.md) — `B3` extended to `android` and `ios`
- [Adoption audit](../../plans/adoption-audit.md) — A3's mobile remainder closed
- [Engineering backlog](../../plans/engineering-backlog.md) — S1 marked done
- [Skew drill README](../../tools/skew-drill/README.md)
- [Checks](../../docs/checks.md)

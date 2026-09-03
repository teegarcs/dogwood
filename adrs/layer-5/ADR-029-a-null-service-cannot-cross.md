# ADR-029: A Null Service Cannot Cross the Boundary

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

The host service surface has carried one property since [ADR-013](ADR-013-host-services-and-entry-points.md),
stated at the top of `dogwood-protocol/HostServices.kt` and relied on everywhere:

> **Every service is optional and its absence is normal.** A host that does not implement analytics
> returns null, and guest code written against a host that does still runs — it simply records
> nothing.

It was false. Returning null from any `DogwoodServices` accessor throws at the Zipline boundary and
takes the whole experience down at `start`, before a single frame is composed.

It went unnoticed for the entire life of the project because **every host in the repository wires
every service.** The slice, the desktop sample and the render bench all supply logging, a clock,
analytics, feature flags and the network. The first host that did not — `TabsActivity`, written as
the Path B reference example — crashed on launch with `ZiplineException: InternalError: Java
Exception` and no cause attached.

Reproduced twice, with two different accessors, to establish it was the null and not the service:
omitting `featureFlags` crashed, supplying it fixed it, and then omitting `analytics` instead
crashed the same way.

## 2. Decision

**A guest must call `DogwoodServices.available()` first, and must only call the accessors it
names.** `HostServices.resolve` on the guest side gates every accessor on that set, including the
version-gated `navigation()`, which is now guarded twice for two different reasons.

`available()` therefore stops being the convenience its own documentation called it. The comment
that said it was "redundant with the accessors returning null, and deliberately so" is now the
opposite of the truth, and has been corrected: it is the **only** safe way to ask.

The accessors stay nullable in Kotlin, because a host genuinely holds null for a service it was not
given and `DogwoodServiceHost` must be able to represent that. The nullability describes what a
*host* may hold, not what a *guest* may safely request, and both the interface and every accessor
now say so.

## 3. Rationale & Research

### Why the null cannot cross

Zipline's compiler plugin chooses a serializer for every parameter and return type in
[`BridgedInterface.serializerExpression`](https://github.com/cashapp/zipline/blob/1.27.0/zipline-kotlin-plugin/src/main/kotlin/app/cash/zipline/kotlin/BridgedInterface.kt#L209-L223).
The branch for service types is tested first and uses `IrType.isSubtypeOfClass`, which compares
classifiers and **ignores nullability** — so `DogwoodLog?` matches it and is given the plain,
non-null `ZiplineServiceAdapter`. The nullable-wrapping helper that exists for `@Contextual`, `Flow`
and generic types is never reached for a service type, and annotating the parameter `@Contextual`
does not help because the service branch is evaluated first.

The value then meets `Intrinsics.checkNotNullParameter` at the top of
[`ZiplineServiceAdapter.serialize`](https://github.com/cashapp/zipline/blob/1.27.0/zipline/src/commonMain/kotlin/app/cash/zipline/internal/bridge/ZiplineServiceAdapter.kt#L51-L53),
which is the exception we saw — surfacing to Kotlin as an opaque `InternalError: Java Exception`
because it is thrown on the host side of the bridge.

**Nothing warns about any of this.** It compiles clean. The nearest thing to a signal is the
generated API lock: `api/zipline-api.toml` records these members as
`fun featureFlags(): dev.dogwood.protocol.DogwoodFeatureFlags`, **without** the question mark the
source declares — which is, as it turns out, an honest rendering of what Zipline actually does with
them.

Nullable *data* returns are unaffected and work correctly; `HttpResponse.failure: String?` and
friends are fine. The defect is specific to service-typed returns and parameters.

### Whether to wait for an upstream fix

No. Zipline 1.27.0 is the current release, and the plugin file above is unchanged on trunk. The one
related change upstream — the pull request that added nullable-serializer wrapping — was explicitly
scoped to contextual, `Flow` and generic types and did not touch service types. An open upstream
issue in the same area notes that changing nullability handling would be "extremely
backwards-incompatible", so this is not something to design around waiting for.

### Why this shape rather than the alternatives

Three patterns work with Zipline as it is:

1. **Probe, then fetch** — ask a non-service-typed question first, then call the accessor. This is
   what was chosen, and `available()` already existed to be that probe; adopting it changed no
   public signature and no host implementation.
2. **Null object** — return a no-op service instead of null. Rejected: every accessor would then
   allocate and bind a real service proxy across the boundary for services nobody uses, and the
   guest would lose the ability to tell "this client has no analytics" from "this client's analytics
   do nothing", which is the distinction `available()` exists to preserve.
3. **Throw for absence** — rejected outright. Absence is the normal case, and normal cases are not
   exceptions. It is also what already happens, and is precisely the behaviour being fixed.

### A hazard left standing

The throw was verified on the host side, where the null originates and where Kotlin/JVM emits the
null check. Kotlin/JavaScript generally does not emit those checks, so a **guest** returning a null
service would likely not throw at all — it would bind a live-but-broken proxy and fail later,
somewhere else. Dogwood has no guest-implemented service that returns another service today, so
nothing is exposed to this; it is recorded because the first one to be added would be walking into
a silent version of the same defect.

## 4. Unstated Assumptions

- **A host's `available()` agrees with its accessors.** `DogwoodServiceHost` derives the set from
  which services it was given, so they cannot disagree, but a host that implements `DogwoodServices`
  directly could list a service and then return null for it — and would crash exactly as before.
  The interface says so on every accessor.
- **The verification is of the guard, not of the crash.** Kotlin/JavaScript tests cannot stand up a
  real Zipline bridge, so `ServicesTest` asserts the property that prevents the crash — that no
  accessor is called for a service `available()` does not name — with a fake that throws on absence
  the way the real boundary does. The crash itself was reproduced, and the fix verified, on device.

## 5. Updated Documents

- [Layer 5: The Native Host & Generated Binding Layer](../../specs/layer-5-host.md) — the host
  service surface section.
- [Layer 4: The Guest Runtime](../../specs/layer-4-sandbox.md) — how a guest must ask for a service.
- [Experience composition plan](../../plans/experience-composition.md)

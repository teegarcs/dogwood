# ADR-028: Guest-Initiated Navigation

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

A guest experience could render, fetch, log and record, but it could not ask to go anywhere. In a
product assembled from several experiences — the shape [ADR-027](ADR-027-the-host-shell-and-warm-experiences.md)
exists to make cheap — that makes every experience an island. A stay card cannot open a stay; a
promotion cannot open the thing it promotes; nothing that a product's own screens do routinely was
possible from inside the sandbox.

The obvious wrong answer is to let the guest name a destination in host terms — an activity, a
route object, a back-stack operation. That is chrome, and chrome belongs to the side that owns the
back stack, the transition, the hardware back button and the deep-link table. A guest that could
manipulate any of those would be a guest every host had to keep compatible with.

## 2. Decision

`DogwoodNavigation` joins the host service surface as an ordinary optional service, following
[ADR-013](ADR-013-host-services-and-entry-points.md)'s pattern — absence is normal, and a guest
running on a host that offers no navigation still runs.

```kotlin
interface DogwoodNavigation : ZiplineService {
  fun routes(): Set<String>
  fun navigate(route: String, params: JsonObject)
}
```

1. **The host interprets routes.** A route may become another Dogwood experience, a native screen,
   a browser, or nothing at all. The guest is not told which, and the guest is not told the
   outcome. Where a route leads is chrome.
2. **Routes are strings**, for the same reason entry points are: a deep link is a string the host
   already holds, and forcing destinations through a generated enumeration would mean a client
   build for every new one.
3. **Fire-and-forget, not suspending.** Navigation is initiated from an event handler, where a
   suspending call would mean a guest coroutine outliving the screen that started it. The answer a
   suspending call would return — "the host went somewhere" — is not one a guest should branch on.
4. **`routes()` is asked before anything is drawn.** What a guest legitimately needs is not the
   outcome but whether a control is worth rendering: a "See all reviews" button on a client with no
   reviews screen is worse than no button, because a dead control makes the user blame the product
   rather than the build. `HostServices.canNavigate(route)` answers it locally, with no boundary
   crossing.
5. **An empty `routes()` means "does not enumerate", never "handles nothing".** A host resolving
   routes from a deep-link table or remote configuration cannot list them. Reading an empty set as
   a refusal would hide every navigating control on every such host, so `canNavigate` is optimistic
   there and the host does the checking instead.
6. **An unknown route is skew, not a failure.** The host reports it and stays put, on the same rule
   as an unknown widget tag: a payload built against a newer client must degrade on an older one
   rather than break it. `SkewReport.unknownRoutes` carries it, and `CallbackNavigation` refuses
   locally before crossing whenever the host enumerates.

## 3. Rationale & Research

### The version gate, exercised for the first time

`DogwoodNavigation` is a new method on `DogwoodServices`, so the service surface moves from version
1 to 2. `dogwood-protocol/HostServices.kt` has always carried the warning that this is the one part
of the system where skew has no fallback:

> an unknown widget tag degrades to a placeholder, but calling a `ZiplineService` method an older
> host does not implement is an error at the boundary, not a fallback.

This is the first change to make that concrete, and it is a real hazard rather than a theoretical
one: payloads are delivered over the air and a newer payload routinely runs on an older client.
`HostServices.resolve` therefore checks `version >= NAVIGATION_MIN_VERSION` **before** calling
`services.navigation()`, rather than wrapping the call and catching. `NavigationTest` pins it with
an assertion that the accessor is *not called* on a version 1 host — not merely that it returned
null — because the failure being prevented is the call itself.

### Threading

A guest calls `navigate` on the Zipline thread; every navigation a host performs touches state
Compose reads. `CallbackNavigation` makes the hop to the user-interface scope itself, so that no
application embedding Dogwood has to know it was needed and forgetting it cannot become a race that
only appears under load.

### Verified on device

The slice's explore experience draws a "Browse all stays" control only because the host declares
`experience/feed`; tapping it sends `experience/feed {"from":"explore"}`, and the host swaps the
active experience. Composed with ADR-027, navigating to an experience that is already warm costs
the shell **0 ms, taken synchronously**, and 40 ms to draw. The diagnostics experience carries a
control that deliberately bypasses `canNavigate` and asks for a route this client does not have,
simulating a payload built against a richer one: the host logged
`unknown route 'experience/nowhere', staying put`, stayed where it was, and did not crash.

## 4. Unstated Assumptions

- **A route is a request, not a command.** A host may ignore one — a modal is open, the user is
  mid-checkout, the destination is behind a login — and no guest should be written as though
  navigation is guaranteed.
- **`routes()` is read once, at start.** A host whose route table changes while an experience is
  live will not see controls appear or disappear. Making routes live means making them a pushed
  value with dedupe rules, like `HostEnvironment`, which is a design rather than an addition.
- **Enumerating routes is a choice with a cost.** A host that enumerates gets local refusal and
  correct control-hiding; one that cannot must handle unknown destinations itself.

## 5. Consequence: launch parameters are start-time

`params` are the destination's *launch* parameters, and launch parameters are read when a session
starts. A host that keeps experiences warm will frequently route to a destination that is already
running — which is the entire point of ADR-027 — and that destination never restarts, so it never
reads them. This is a limit of what a route can carry, and it is stated on the interface rather than
discovered: a guest that needs to tell a *running* experience something needs a pushed value with
its own dedupe rules, which this is not.

Building the sample surfaced a related defect. `ShellEntry` held the launch parameters it was first
constructed with, forever. Navigating with new parameters therefore did nothing visible while the
experience stayed warm — correct, per the paragraph above — but if that experience was later evicted
and cold-started, it came back with parameters from whenever it had first been visited, several
navigations ago. The entry now takes the newest parameters on every activation, so any restart uses
the most recent ones.

## 6. Updated Documents

- [Layer 5: The Native Host & Generated Binding Layer](../../specs/layer-5-host.md) — the host
  service surface gains navigation; the experience shell section gains the routing path.
- [Layer 4: The Guest Runtime](../../specs/layer-4-sandbox.md) — the service-surface version gate,
  now that a service actually depends on it.
- [Experience composition plan](../../plans/experience-composition.md)

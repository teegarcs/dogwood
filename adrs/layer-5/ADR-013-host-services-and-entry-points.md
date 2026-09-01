# ADR-013: Host Services and Entry Points — Handed to the Guest, Optional by Default, and Policed at the Host

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

`roadmap.md` Phase 4 lists **host services, entry points, and host-registered components** as one
subsystem, and [ADR-005](ADR-005-corrected-coverage-and-bespoke-subsystem-list.md) describes it as
"three things every real deployment needs that Zipline makes *mechanically* easy and this
specification had not designed". This decision covers the first two. Host-registered components —
running the generator over a host application's own design-system modules — is deferred to its own
record, because it is generator work rather than protocol work.

Before this, both halves existed only as placeholders:

- **`launchParams` was a parameter nobody read.** `DogwoodGuestUi.start` took a `JsonElement` and
  `DogwoodGuest` ignored it entirely. A payload offered exactly one experience, hard-coded in
  `main()`. A host with a deep link had nothing to route on.
- **A guest could reach nothing.** No logger, so a guest's only diagnostic channel was
  `handleUncaughtException`, which requires it to be crashing. No clock it could ask about the time
  zone, which it genuinely cannot obtain because the pinned QuickJS ships no ECMA-402 `Intl`. No
  flags. No analytics. And no network, which meant the sample screen's data was a hard-coded list
  in the payload — a demonstration of server-driven user interface in which nothing was driven by a
  server.

## 2. Decision

### 2.1 Entry points

`DogwoodGuestUi.start` takes an `entryPoint: String`. `DogwoodGuest` is constructed from a map of
named entry points, each a `@Composable (JsonElement) -> Unit`. The launch payload is passed to the
chosen entry point *and* published as `LocalDogwoodLaunch`, so a screen deep in the tree does not
have to be threaded it by every composable above it.

**Named rather than positional**, so that adding an entry point cannot renumber an existing one and
so that a host holding a deep link can route on a string it already has.

**An unknown name is reported, with the names on offer.** A host and a payload ship separately and
can disagree about routing. The failure is routed to `DogwoodHost.handleUncaughtException` carrying
both what was asked for and what the payload actually has, and nothing is composed. A blank screen
would be the same outcome with none of the information.

**The launch payload is raw data, decoded by the guest.** The host cannot construct guest types —
it was built months before this payload and has never seen its classes — so the type lives on the
side that owns it. The sample decodes with `ignoreUnknownKeys = true`, which is the additive
evolution rule applied to launch parameters: a host that learns to send a new one must not break a
payload that predates it.

### 2.2 The service surface

One vendor, `DogwoodServices`, handed to `start` alongside `DogwoodHost`, with a nullable accessor
per service: `log`, `clock`, `analytics`, `featureFlags`, `network`. Three properties:

**Handed to the guest, not looked up by it.** Not `zipline.take("dogwood.log")`. A name lookup
makes "does this host offer analytics?" a runtime string question answered by an exception; a
nullable typed accessor makes it a value. A vendor rather than five more parameters on `start`,
so that adding a service leaves the entry-point signature alone.

**Every service is optional and its absence is normal.** A host that does not implement analytics
returns null and reports so through `available()`. Guest code degrades — records nothing, logs
nothing, falls back to `Date.now()` — rather than failing. The alternative, a host obliged to stub
every service Dogwood ever defines, makes adding a service a breaking change for every host.

**Resolved once, at start.** Every accessor call crosses the boundary and allocates a service proxy
on both sides; resolving per composition would leak a pair at the rate the screen recomposes. The
guest resolves all five in `DogwoodGuest.start` and publishes them as one *static* composition
local, static because the set cannot change while a composition is alive.

**Versioned through the dictionary channel.** `segmentVersions["dogwood.services"]` carries
`SERVICES_VERSION`. This matters more than a widget version and the asymmetry is the reason it is
recorded here: an unknown widget tag degrades to a placeholder, but **calling a `ZiplineService`
method an older host does not implement is an error at the boundary with no fallback path**. A
guest that wants a method added after revision *N* must check the version before calling it.

### 2.3 The network service is where the host is a policy point

`DogwoodNetwork.fetch` is `suspend`, because it must be: the guest is single-threaded, and a
blocking fetch would stop composition, the frame clock, and every pending event until the network
answered.

The reference implementation, `OkHttpNetwork`, **defaults to refusing every request**. This payload
was downloaded and can be replaced over the air without a store review; if it could reach an
arbitrary address through the application's network stack it would be an exfiltration channel with
the application's name on it. An embedder opts destinations in deliberately, and `allowHosts` is
the one-liner for the common case: exact host match, transport security required, and cleartext
opted into *per host* rather than by a global switch that gets turned on for a demo and shipped.

Three further limits, each closing a way a guest could hurt the host rather than itself:

- **A one-megabyte body cap**, checked against `Content-Length` and then again against what
  actually arrived, because a chunked response reports `-1`. Over the cap is a refusal, not a
  truncation: a silently truncated JavaScript Object Notation document is worse than none.
- **Input/output on `Dispatchers.IO`.** The call arrives on the Zipline thread, the only thread
  that may touch the guest.
- **Failures as values.** A refusal, a timeout and a connection error all return `HttpResponse`
  with `failure` set, so a guest handles them as one branch. `GuestServices.fetch` answers the same
  way when the host offers no network service at all, so "there is no network here" and "you may
  not go there" have one shape rather than two.

### 2.4 A frame loop that wakes for the guest's own state changes

This decision uncovered a gap in Layer 4 and closes it. **Until host services existed, every guest
state change began with a host call** — a frame, an event, a configuration push — so sending apply
notifications at the end of that call was sufficient. A suspending service call breaks that
assumption: the coroutine resumes long after the call that started it returned, writes state, and
there is nobody left to notice.

`DogwoodComposition` now registers a global snapshot write observer that requests a frame, coalesced
guest-side because the observer fires per write and a crossing per write would be a boundary call
for every field a guest touches. The observer is disposed with the composition; it is registered
globally and captures the host, so a guest replaced by a code update that left its observer behind
would keep asking a dead host for frames, once per generation, for the life of the QuickJS instance.

Writes made *during* composition go to the composition's own snapshot and never reach a global
observer, so this fires exactly for the case it exists for.

## 3. Rationale & Research

**Zipline supports both halves directly, and this is the "mechanically easy" ADR-005 refers to.**
Services are passed as parameters and returned from calls because Zipline's compiler plugin
generates adapters for `ZiplineService` types appearing in service signatures — the same mechanism
that already carries `DogwoodHost` into `start`. Suspending calls are a first-class Zipline feature:
[`ZiplineService`](https://github.com/cashapp/zipline/blob/trunk/zipline/src/commonMain/kotlin/app/cash/zipline/ZiplineService.kt)
and Zipline's own suspending-call tests are the precedent.

**Redwood's `Image(url: String)` is the precedent for keeping the payload out of the guest**
([`RedwoodUiBasic.kt`](https://github.com/cashapp/redwood/blob/trunk/redwood-ui-basic-schema/src/main/kotlin/app/cash/redwood/ui/basic/RedwoodUiBasic.kt)),
and the network service is the same idea one level down: the guest names what it wants and the host
decides whether and how to get it.

### Verified end to end

Run on the Pixel 9 Pro emulator (API 35), guest served over the network and signature-verified.
The suspending crossing was the piece most likely not to work, and it does:

```
08:20:51.133 I DogwoodSlice/explore: fetching http://10.0.2.2:8080/explore.json
08:20:52.082 I DogwoodSlice/explore: loaded 5 destinations and 6 stays
08:21:12.127 I DogwoodSlice: analytics: explore.filter {filter=1 room}
```

The explore screen renders five destinations and six stays fetched from an endpoint the host
allowed, with the address supplied in the launch parameters — `10.0.2.2` on an emulator and
`localhost` on a desktop are the same machine reached by different names, and only the host knows
which it is. Switching to the payload's second entry point renders the diagnostics screen, on which
every fact — the five service names, the surface revision, `America/New_York`, the flag, the launch
payload, three dictionary segments — is something the sandbox could not have worked out for itself.

**The 2.4 gap was found this way, not reasoned into.** On the first run the log showed the fetch
completing and the feed parsed while the screen sat on "Loading…" — the write had happened with
nobody left to notice it. Two tests now pin the fix: a write outside any host call asks for a frame,
and a burst of writes asks for exactly one.

Twenty-four tests were added across the two modules. Guest-side: the host chooses the experience,
launch parameters arrive, an unknown entry point names both what was asked for and what is on offer
and composes nothing, services resolve exactly once, a missing service is a null rather than a
crash, and a missing network answers like a refusal. Host-side: the default is to refuse every
request, a refusal is a value, a subdomain is a different host (a suffix match would allow
`api.example.com.attacker.test`), cleartext is per host, and closing the vendor does not close the
services it handed out.

## 4. Unstated Assumptions

- **Feature flags are a snapshot, not a feed.** A flag flipped while a screen is open does not
  reach it. Making them live means making them a pushed value with the dedupe rules of
  [ADR-012](ADR-012-host-environment-subsystem.md), which is a design rather than an addition.
- **The clock is about agreement, not availability.** QuickJS has `Date.now()`. What the guest
  cannot obtain is the time zone, and what a host may want is a clock it controls in tests. Guest
  code that only needs elapsed time should not reach for the service.
- **Assumes the host closes what it owns.** `DogwoodServiceHost.close` deliberately does *not*
  close the services it vended, because the guest outlives it. A host that wires a service holding
  a real resource is responsible for that resource's lifetime.
- **Assumes suspending guest calls resume correctly under `Dispatchers.Unconfined`.** This is now
  observed rather than assumed for the network path, but the guest's recomposer context is
  Unconfined for reasons unrelated to services, and a service that suspends in a more complicated
  way — many concurrent calls, cancellation mid-flight — has not been exercised.
- **Cancellation is not modelled.** A `LaunchedEffect` cancelled while a fetch is in flight leaves
  the host request running to completion. For an idempotent read that is waste, not a bug; it would
  become one for a non-idempotent call.
- **The body cap is a host-memory limit, not a guest-memory one.** The response crosses as a
  `String`, so a one-megabyte body is roughly two megabytes resident across both sides at the
  moment of crossing.

## 5. Updated Documents

- [`specs/layer-4-sandbox.md`](../../specs/layer-4-sandbox.md) — the `start` signature, the service
  boundary, and the frame-loop wake for guest-originated state changes.
- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — bespoke subsystem 9's first two thirds
  marked delivered, with the host-service diagram and its node definitions.
- [`roadmap.md`](../../roadmap.md) — Phase 4's host-services row.
- [`adrs/README.md`](../README.md) — index entry.

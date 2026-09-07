# ADR-055: The web guest gets its host's services

**Date:** 2026-09-07
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-048](ADR-048-the-real-guest-runs-on-the-web.md) put the real Kotlin/Compose guest in a Web
Worker, running **the same screens** as the mobile payload from `samples/slice-screens`. Every
summary since has said so, and every summary has been true.

It has also been misleading, and the sample said so in plain words the whole time:

```
this client offers no services at all
surface revision 0 (unreported)
host clock unavailable
time zone unavailable
```

The web guest was handed `services = WorkerServices` implementing exactly one service (a log to the
console), `launchParams = JsonNull`, and `segmentVersions = emptyMap()`. It ran the same screens as
the mobile payload, and it ran them **blind**: no clock, no feature flags, no analytics, no
navigation, no network, no launch parameters, and no idea which dictionary versions its client
implements — which is the map a guest branches on to decide what it may use.

`plans/production-readiness.md` §2.1 carried this as "the Worker service surface, launch parameters
and segment versions", one clause in a closed item. It is the largest remaining difference between
what a product can build on the web and what it can build on a phone.

## 2. Decision

**Split the service surface by *who can answer*, and cross the boundary once for the rest.**

A Worker boundary carries no object references ([ADR-032](ADR-032-the-web-profile.md)), so the
mobile arrangement — hand the guest a `DogwoodServiceHost` and let Zipline carry the objects — has
no analogue. Three groups, and the split is by capability rather than convenience:

| Service | Answered by | Why |
|---|---|---|
| `log` | the Worker | it has a `console` |
| `clock` | the Worker | it has `Date.now()` and `Intl`; the page's clock is the same clock |
| `network` | the Worker | it has `fetch` — see §4 |
| `featureFlags` | the host, once, at start | only the application knows them |
| `navigation` | the host | it owns the routing |
| `analytics` | the host | it owns the pipeline |

Three new envelope kinds carry the rest:

- **`start`** (host → guest) — a `WebStartPayload`: entry point, launch parameters, feature flags,
  the routes the host handles, and the dictionary versions it implements. **One message, before the
  first `configuration`**, which is what actually starts the composition; five accessors would be
  five round trips before the first frame.
- **`analytics`** and **`navigate`** (guest → host) — one-way, correlation `0`, because neither
  returns anything on any platform.

**The entry point becomes the host's choice.** The guest used to read it from its own Worker URL.
Now the page names it, exactly as `TabsActivity` and the iOS host do, and the guest's URL is the
fallback that keeps a newer guest working on a page that never sends `start`.

**The payload types live in `dogwood-wire`, not in `dogwood-web`.** The envelope's *kind* constants
are mirrored by hand in each half because the two do not link; the payloads are not, because a
hand-mirrored `data class` is a second place a shape is known and this repository has three ADRs
about what that costs. Both halves encode with the same `DogwoodJson`.

## 3. Rationale & Research

**It is verified by what the screen says, not by what the host logged.** The guest composes these
values into text, so the accessibility tree carries what the guest actually received. Before and
after, on the same Diagnostics screen in a real browser:

```
before: this client offers no services at all / surface revision 0 (unreported) / host clock unavailable
after:  analytics, clock, featureFlags, log, navigation, network
        surface revision 2 (2) / host clock 1788779171200 / time zone America/New_York
```

And the Explore screen, which needs a launch parameter *and* a network fetch to render anything at
all, now renders on the web: `Explore Japan`, `Stays in Tokyo`, `6 properties`. The city is a launch
parameter the page passed; the rows came from a fetch the guest made. Neither was possible before.

**A new conformance group, because the capability was never graded anywhere.** `J1`–`J4` are graded
on the web by `tools/conformance/web_services.py`, and `J5` — *host and guest read one declaration
of the start payload, not two* — is a shared test on every target. The mobile cells read `—`, which
is uncomfortable and correct: Android and iOS have had these services since Phase 4 and no drill
asserts them, so the only client where a machine checks is the one that had none of it this morning.
Borrowing a tick from the web would be the exact failure the catalogue exists to prevent.

**Where the sample's data comes from.** `samples/slice-guest` copies its `api/*.json` beside the
payload so the mobile hosts can reach them; the web page is served from a different directory and
needed its own copy. Without it the Explore fetch resolves to a 404 and the screen renders its empty
state — correct behaviour, and indistinguishable from a network service that does not work.

## 4. Unstated Assumptions

- **`network` on the web is the browser's guarantee, not Dogwood's, and it is weaker.** On Android
  and iOS `DogwoodNetwork` is implemented by the host and enforces an allow-list that **refuses
  everything by default**, because the payload is replaceable over the air without a store review.
  Here the guest calls `fetch` itself and what constrains it is the page's Content Security Policy.
  ADR-032 already records this asymmetry for the profile; this is where it becomes concrete. Routing
  `fetch` through the page would not fix it — the Worker would still have `fetch`, and a payload
  that wanted to bypass the host would simply not ask. A product that wants the mobile guarantee on
  the web sets `connect-src`.
- **`clock` answering locally is not permission to format locally.** `Intl` exists in a Worker and
  does not exist in QuickJS, which is why the mobile guest must ask its host for a time zone.
  Formatting stays host-resolved on every platform, because the result has to look the way the
  platform's own applications look and `Intl` in a Worker is not the platform's formatter.
- **Feature flags are a snapshot, on every platform.** A flag flipped while a screen is open does
  not reach that screen. Here that is doubly true: they cross once, in `start`.
- **The envelope revision did not change.** Adding kinds is additive, and a guest that meets an
  unknown kind reports it rather than dying — the behaviour the protocol file already promised.
  Bumping the revision would refuse an older guest outright for a change it can ignore.
- **Nothing verifies the mobile side of `J1`–`J4`.** See §3.

## 5. Updated Documents

- [`plans/conformance.md`](../../plans/conformance.md) — the new group **J**.
- [`plans/production-readiness.md`](../../plans/production-readiness.md) — §2.1's remaining clause,
  and the alignment table.
- [`tools/conformance/web_services.py`](../../tools/conformance/web_services.py),
  [`claims.tsv`](../../tools/conformance/claims.tsv), [`exempt.tsv`](../../tools/conformance/exempt.tsv),
  [`run-web.sh`](../../tools/conformance/run-web.sh) — new drill, wired in.
- [`engine/dogwood-wire/src/commonMain/kotlin/dev/dogwood/protocol/WorkerPayloads.kt`](../../engine/dogwood-wire/src/commonMain/kotlin/dev/dogwood/protocol/WorkerPayloads.kt)
  — new, and deliberately in the module both halves compile.
- [`engine/dogwood-web/`](../../engine/dogwood-web/) — the three kinds, the listener, and the
  experience's declaration.
- [`engine/samples/web-guest/`](../../engine/samples/web-guest/) and
  [`engine/samples/web-slice/`](../../engine/samples/web-slice/) — the guest's implementations and
  the page's declaration.
- [`docs/authoring.md`](../../docs/authoring.md) — the network rule now says which platform enforces
  what.

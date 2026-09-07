# Operating Dogwood

For whoever is on call. It covers what to do when a payload is bad, what the engine does on its own,
and what it deliberately does not do — because a runbook that describes a capability nobody built is
worse than no runbook.

Everything here is real today unless the section says otherwise, and the sections that say otherwise
say what is missing rather than implying it works.

---

## 1. What ships, and what does not

Two things reach a device, on completely different schedules, and almost every operational question
turns on which one you are looking at.

| | Ships through | Changes how often | Can you roll it back? |
|---|---|---|---|
| **The host** — bindings, the design system, the runtime | an app store | weeks to months | no faster than a store review |
| **The payload** — screens, logic, layout | your server | as often as you like | **yes, and this document is mostly about that** |

A payload is Kotlin compiled to JavaScript, signed, and executed in a sandbox. It can change what a
screen *is*. It cannot add a component the installed host does not bind — a widget tag no client
knows renders as an inert placeholder and is **reported**, which is the mechanism §4 is about.

---

## 2. A bad payload: what happens without you

Three protections run on the device with no operator involved. Knowing them tells you which pages to
skip when something is wrong.

**A payload that fails to download changes nothing.** The previous guest keeps running and the next
poll tries again. A network outage is not an outage of your application.

**A payload that crashes on launch stops being tried.** The host records an attempt *before* the
payload runs and persists it, so a crash cannot erase the evidence of itself. After two failed
starts a version is quarantined and the host names the last version known to have worked
([ADR-049](../adrs/layer-5/ADR-049-surviving-a-bad-publish.md)). **This is the one that matters
most**: without it, a payload that crashes on launch crashes on every relaunch, forever, on every
device that fetched it.

**A payload that will not stop is stopped.** Every guest runs under bounds
([ADR-060](../adrs/layer-5/ADR-060-bounds-on-a-runaway-payload.md)): allocation past 256 MiB is
refused, and any single uninterrupted run of guest execution past five seconds is interrupted —
with the stack source-mapped to the payload file that was stuck. The host process survives both.
These are tourniquets, not budgets: ordinary guest work yields many times a second, and nothing
that behaves is ever touched by them.

**A payload built against a newer dictionary degrades rather than breaking.** Unknown widgets become
placeholders, unknown icons become the fallback glyph, unknown colour tokens become unspecified, and
values Compose would throw on are clamped. The one exception is deliberate: a control that owns an
**affordance** — `enabled`, `checked`, `readOnly` — and carries a property this client cannot read is
**withheld entirely** rather than drawn, because a control that lies about what it will do is worse
than a gap ([ADR-031](../adrs/layer-5/ADR-031-safety-relevant-parameters.md)).

---

## 3. Stopping a release

**Set `dogwood.disabled` to `"true"` in the manifest metadata and republish.** Devices refuse that
release on their next manifest fetch.

```kotlin
zipline {
  metadata.set(mapOf("dogwood.disabled" to "true"))
}
```

Four things worth knowing before you use it:

- **It is signed.** The switch lives in the manifest's signed metadata, so it cannot be set by anyone
  who cannot sign for you. That is the point — an unsigned kill switch is a denial-of-service
  delivered through the channel that exists to secure your updates.
- **It does not fall back.** A refused release runs *nothing downloaded*; the host shows its own
  screen. That is deliberate: disabling a release may mean disabling the feature, and falling back
  to the previous payload would run the thing you just stopped.
- **It is as fresh as the manifest.** A device that cannot reach your server keeps running what it
  has. The switch is not instantaneous and cannot be.
- **A running guest keeps running.** Refusal applies to the *next* load. Users mid-session are not
  interrupted, which is usually what you want and is worth knowing when it is not.

**To un-stop it**, remove the metadata and republish. Devices pick the release back up on the next
fetch; nothing on the device needs clearing.

---

## 4. Knowing something is wrong before a user tells you

**Every degradation in §2 is silent by design.** A screen with a fallback icon and body text where a
heading should be looks fine. `SkewReport` is what makes it visible, and **Dogwood stores it; your
host reports it** ([ADR-048 seam](../adrs/layer-5/ADR-048-the-real-guest-runs-on-the-web.md)):

```kotlin
val drain = SkewDrain(experience.skew)
drain.drainTo { entries -> yourTelemetry.record(entries) }   // on the thread that composes
```

`drainTo` hands you what is **new** since the last call, so counts downstream are not inflated. The
kinds worth alerting on, in rough order of seriousness:

| Kind | What it means |
|---|---|
| `WITHHELD_WIDGET` | a control was **not drawn** because this client could not read something about it. Users are missing an action |
| `REJECTED_BATCH` | a whole update was refused as undecodable. The screen kept rendering the previous tree |
| `UNKNOWN_WIDGET` | a payload used a component this client does not have |
| `CLAMPED_VALUE` | a payload sent a value out of range; the screen survived because it was clamped |
| `REFUSED_IMAGE` | an image origin your policy does not allow |
| `UNKNOWN_ROUTE` | a payload asked to navigate somewhere this client does not handle. The host stayed where it was, and a control did nothing |

The first two are pages. The rest are usually a design-system update that reached payloads before it
reached devices, which is ordinary and worth a dashboard rather than a page.

**`UNKNOWN_ROUTE` needs one line from you on mobile and nothing on the web.** A navigation service is
built by your application before any experience exists, so the engine never sees the call and cannot
record it; your `onUnknownRoute` callback is the only place that can. The Android sample does it in
one line and is the reference. Without it a payload asking for a destination you removed produces a
button that does nothing, and no record anywhere that it happened.

---

## 4b. Reading a guest crash

A payload crash reaches your host through one channel — the `onGuestException` callback on the
experience — and what arrives is worth routing to your crash reporter: Zipline applies source maps
at build time, so the frames name **real Kotlin files from the payload**, with nothing to deploy
alongside it.

```
app.cash.zipline.ZiplineException: IllegalStateException: ...
    at os (dev/dogwood/slice/ExploreScreen.kt)
    at Ye (dev/dogwood/slice/ExploreScreen.kt)
```

Three things to know, each learned by crashing a guest on purpose
([ADR-059](../adrs/layer-5/ADR-059-a-guest-crash-a-host-can-read.md)):

- **Attribution is file-level.** Function names stay minified and line numbers do not survive the
  size-optimized build. One screen per file — the shape the authoring guide encourages — makes a
  file name enough to start.
- **Do not throw from the handler.** It runs inside a Zipline service dispatch, and a throw there is
  returned to the *guest* as the call's failure — your process never sees it, and the crash
  vanishes. The default prints; replace it with your pipeline, not with a rethrow.
- **The web is different.** Its Worker path carries an error *message*, not a stack; crash
  readability there is an open item on the audit's page.

## 5. Publishing

There is no publish pipeline in this repository, and that is a real gap rather than an omission from
this document. What exists is a Gradle task that serves a payload on `localhost:8080` for
development. A production deployment needs:

- **Build, sign and upload as one reviewable step.** The signing key must not be the one in this
  repository — those are throwaway development keys, committed on purpose and labelled as such.
- **Cache headers that match immutability.** Payload files are content-addressed and may be cached
  forever; **the manifest is not** and must not be. Getting that backwards gives you either stale
  clients or no caching at all.
- **`Content-Encoding: br` on the web bundle.** Serving gzip instead costs **27% and about five
  seconds** on a slow connection ([ADR-045](../adrs/layer-5/ADR-045-web-page-weight-where-the-levers-are.md)).
  Nothing on the device can detect this; it is silent and it is large.

---

## 6. What this does not do

Written down so nobody discovers it during an incident.

- **Nothing resumes a previous payload automatically.** The host *names* the last good version and
  refuses the bad one; running the old payload again means serving its manifest again, which is your
  server's job.
- **There is no staged rollout.** `InstallCohort` gives each installation a stable bucket 0–99 that
  your server could stage against. Deciding which cohorts get which manifest is a server's decision
  and no server here makes it.
- **Nothing reports refusals to you.** A fleet-wide quarantine is visible only if your host wires
  the refusal callback to telemetry, exactly as with `SkewReport`.
- **Performance budgets are ungraded.** `G1`–`G4` read `·` on every client because the gate device
  was never acquired ([Layer 4 ADR-008](../adrs/layer-4/ADR-008-gate-device-not-available.md)).
  Regressions on faster hardware still fail.
- **The web's network policy is the browser's.** A Content Security Policy set by the page, not the
  allow-list the mobile hosts enforce. Weaker, deliberately, and recorded as such in
  [ADR-032](../adrs/layer-5/ADR-032-the-web-profile.md).

---

## 7. A short incident checklist

1. **Is it the payload or the host?** A payload rolls back in minutes; a host does not. Check which
   changed.
2. **Stop the release** (§3) if the payload is at fault. This is reversible and cheap; prefer it to
   debugging live.
3. **Read the skew** (§4). `WITHHELD_WIDGET` and `REJECTED_BATCH` explain a class of symptom nothing
   else will.
4. **Check whether devices quarantined it themselves.** If they did, the release crashed on launch,
   and the last known good version is named in the host's report.
5. **Republish a fixed payload.** There is no store review in this path — that is the whole point of
   the architecture, and it is as true during an incident as during a feature launch.

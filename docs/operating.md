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

Four protections run on the device with no operator involved. Knowing them tells you which pages to
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

**A payload that declares a dictionary this client cannot render is refused before it starts.** The
manifest's **signed** metadata carries which dictionary segments the payload was built against, and
the client compares it before any entry point is called, any service is bound, or anything composes
([ADR-061](../adrs/layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md)). The refusal
reaches `onRefused` and says both numbers — *"dogwood.designsystem wants 15, this client implements
14"* — because "your client is behind" and "this release is quarantined" send somebody to look in
two different places, and only one of them is a publishing mistake. This is a *second* line, not a
new requirement: a payload that declares nothing still runs, which is every payload built before the
field existed, and the containment below is what protects those.

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
| `UNKNOWN_ENUM_VALUE` | a payload sent an enumeration entry this client's surface does not carry, as `Component.parameter=Name`. The parameter rendered as its default, which looks plausible and is not what the payload meant |
| `UNKNOWN_NAME` | a payload used a word from one of Compose's closed sets — an arrangement, a font weight, an overflow — that this client predates, as `kind:name`. Rendered as the default. Almost always a payload ahead of a device, never a typography drift |

The first two are pages. The rest are usually a design-system update that reached payloads before it
reached devices, which is ordinary and worth a dashboard rather than a page.

**`UNKNOWN_ROUTE` needs one line from you on mobile and nothing on the web.** A navigation service is
built by your application before any experience exists, so the engine never sees the call and cannot
record it; your `onUnknownRoute` callback is the only place that can. The Android sample does it in
one line and is the reference. Without it a payload asking for a destination you removed produces a
button that does nothing, and no record anywhere that it happened.

---

## 4b. Reading a guest crash

A payload crash reaches your host through **one channel**, and it is a constructor parameter rather
than a global so the compiler can tell you it exists:

```kotlin
DogwoodExperience(
  zipline, ziplineDispatcher, uiScope,
  onGuestException = { throwable -> yourCrashReporter.record(throwable) },
)
```

On the web the same channel is `WorkerBridgeListener.onGuestFailure(correlation, message, stack)`,
which defaults to dropping the stack and calling `onGuestError` so a host written before stacks
existed keeps working.

**Dogwood hands you the crash; your host sends it.** There is no built-in transport, which is the
same seam as everything else here.

### Saying where it came from

Three coordinates, and a crash report is hard to act on without all three.

**Which line.** On mobile and desktop, Zipline applies source maps at build time, so frames name
real Kotlin files from the payload with nothing to deploy alongside it:

```
app.cash.zipline.ZiplineException: IllegalStateException: ...
    at os (dev/dogwood/slice/ExploreScreen.kt)
    at Ye (dev/dogwood/slice/ExploreScreen.kt)
```

Attribution is **file-level**: function names stay minified and line numbers do not survive the
size-optimized build. One screen per file — the shape the authoring guide encourages — makes a file
name enough to start.

**On the web you get more, but later.** The Worker path used to carry a message and nothing else;
since [ADR-063](../adrs/layer-5/ADR-063-a-web-crash-carries-its-frames.md) it carries the stack as
well, and a real crash in a real browser delivers ten frames. They are minified — `bn.p8`, not a
function name — but their **line and column offsets are exact**, which is what a source map
resolves:

```
tools/symbolicate/resolve.py <build>/guest-kotlin.js.map < crash-stack.txt
```

Every frame resolves, the top one to the throwing call, with a file *and* a line. Symbolication is
an offline step against a build artefact on purpose: **the source map is deliberately not served**,
because a served map hands every reader your payload's Kotlin source, and nothing in the browser
needs it. Keep the map for each build you publish; a pipeline that discards build artefacts gets
frames it cannot resolve, which is still more than the one sentence it had before.

One caveat the tool will tell you about rather than hide: Kotlin/JavaScript sometimes emits a
mapping that is **not a position in the file it names** — a line past the end of it — for code it
synthesised. The resolver prints the specification's answer, marks it, and names the nearest real
mapping beside it, rather than inventing the plausible one. Drafted as upstream report 4.

**Which payload.** `SessionStatus` carries the release version and the key that verified it, and
both mobile samples log them on every swap. Without the version a stack is unattributable: payloads
ship faster than clients, so "which release" is the first question anyone will ask you.

**Which experience.** The entry point, from your shell's `onSwap` and `onFailure` callbacks.

### Two rules, both learned by crashing a guest on purpose

- **Do not throw from the handler.** It runs inside a Zipline service dispatch, and a throw there is
  returned to the *guest* as the call's failure — your process never sees it, and the crash
  vanishes. The default prints; replace it with your pipeline, not with a rethrow.
- **A crash after the screen mounts does not quarantine the release, and that is deliberate.** The
  release guard marks a version successful once a guest has started, produced a tree, and the host
  has mounted it. A payload that worked and then broke keeps running, because quarantining it would
  strand a fleet for a bug a user might never hit. Only failure *to start* burns an attempt — which
  is why the two crash fixtures in `samples/slice-screens` are different screens
  ([ADR-049](../adrs/layer-5/ADR-049-surviving-a-bad-publish.md),
  [ADR-059](../adrs/layer-5/ADR-059-a-guest-crash-a-host-can-read.md)).

## 5. Publishing

[`tools/reference-server/`](../tools/reference-server/) is a working implementation of this section:
one file, storing releases on disk, holding no opinion about your infrastructure. It is a
**reference to copy and diff against**, not a product to deploy — but it is executable, and
[`check.sh`](../tools/reference-server/check.sh) observes each behaviour below in a real response
rather than asserting it, ending with a real client loading and verifying a signed manifest
through it.

```
tools/reference-server/server.py publish --root /srv/dogwood --from build/zipline/ProductionWebpack --version 1.4.0
tools/reference-server/server.py rollout --root /srv/dogwood --version 1.4.0 --percent 10
tools/reference-server/server.py resume  --root /srv/dogwood --version 1.3.0
```

The four behaviours it exists to demonstrate, each easy to get wrong and expensive to get wrong
late:

- **Build, sign and upload as one reviewable step.** Signing stays in the build, where the key is;
  the server never holds one. The key must not be the one in this repository — those are throwaway
  development keys, committed on purpose and labelled as such.
- **Cache headers that match immutability.** Payload modules are content-addressed and served
  `immutable` for a year; **the manifest is `no-store`**. Backwards gives you either stale clients
  or no caching, and — worse — a cached manifest is a fleet you can neither update nor roll back,
  which is the failure that outlasts the outage.
- **`Content-Encoding: br` when the client offers it.** Serving gzip instead costs **27% and about
  five seconds** on a slow connection ([ADR-045](../adrs/layer-5/ADR-045-web-page-weight-where-the-levers-are.md)).
  Nothing on the device can detect it, so the reference server warns loudly at startup when the
  brotli module is missing rather than quietly serving gzip.
- **A publish does not go live.** It lands staged at 0%; `rollout` widens it by cohort against the
  stable bucket `InstallCohort` already gives every installation. Widening only ever *adds*
  devices, so nobody is moved back off a release they already have.

**Rolling back is `resume`, and it needs nothing from the client.** A device that quarantined a bad
release is refusing a *version*; a different version is not refused. That is why recovery is one
line of server state rather than a protocol.

---

## 6. What this does not do

Written down so nobody discovers it during an incident.

- **Nothing resumes a previous payload automatically.** The host *names* the last good version and
  refuses the bad one; running the old payload again means serving its manifest again — which is
  your server's job, and which `tools/reference-server`'s `resume` shows in full.
- **Staged rollout is a server decision, and the reference server makes it** against
  `InstallCohort`'s buckets. Your deployment still has to decide the policy; nothing here decides
  it for you.
- **Nothing reports refusals to you.** A fleet-wide quarantine is visible only if your host wires
  the refusal callback to telemetry, exactly as with `SkewReport`.
- **Performance budgets are ungraded.** `G1`–`G4` read `·` on every client because the gate device
  was never acquired ([Layer 4 ADR-008](../adrs/layer-4/ADR-008-gate-device-not-available.md)).
  Regressions on faster hardware still fail. **And the margin is thinner than the numbers look:**
  the same emulator at one core instead of four leaves steady-state recomposition unchanged and
  degrades every `p95` and `p99` two- to sixfold — enough that `G1` is met at four cores and missed
  at one, with nothing about the payload changed
  ([ADR-064](../adrs/layer-4/ADR-064-the-tail-budgets-headroom-was-parallelism.md)). Do not read the
  current headroom as slack on a slow device.
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
4. **Read the refusal, and read which kind it is.** `onRefused` reports three different situations
   and they need three different people. *"Quarantined after 2 failed starts"* means the release
   crashed on launch and devices stopped it themselves — the last known good version is named in the
   same report. *"The publisher disabled this release"* means somebody used the kill switch.
   *"…wants 15, this client implements 14"* means the payload needs a client that is not out there
   yet, which is a publishing-order mistake rather than a bad build, and is fixed by republishing
   the previous payload rather than by fixing this one.
5. **Republish a fixed payload.** There is no store review in this path — that is the whole point of
   the architecture, and it is as true during an incident as during a feature launch.

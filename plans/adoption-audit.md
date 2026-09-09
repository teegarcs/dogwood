# Adoption audit: what stops a production application from picking this up

**Date:** 2026-09-07. Audited at `main` = `fd09fff`, from the position of a product team — Android,
iOS and web engineers plus their release, security and quality people — trying to integrate Dogwood
into an **existing** application and ship it.

Every finding below was **verified against the repository**, not inferred from its documents; two
suspicions (right-to-left layout, licensing) were checked and dropped because the code already
handles them. Findings already recorded elsewhere are listed once, in Part C, so Parts A and B are
new information.

The one-paragraph verdict, as audited: **the engine is real and the evidence behind it is unusually
honest, but the adoption path has never been walked end to end.** Everything a *demo* needs exists
and is verified on three clients. Several things only a *production integration* needs have never
been exercised even once — and this project's own history says the first run of anything finds a
defect.

> **Status, end of 2026-09-07: Part A is closed** (A6's cross-version drill waits for a second
> engine version to exist) **and B2 with it** — each item by running the thing, each with an ADR
> (056–060). The history held: the first R8 build needed nine rounds of harness fixes, the first
> external payload compilation found a generator defect that had made a component unbindable for
> its whole life, the crash probe found guest crashes were *unobservable* rather than unreadable,
> and the runaway-loop negative control hung rather than failed. B1, B3 and B4 remain the first
> adopter's roadmap; Part C remains the owner's.

---

## Part A — Verified blockers. Each of these has never been exercised, and this repository's record is that the first run of anything finds a defect

### A1. ✅ Closed — the drills now run against the minified build ([ADR-056](../adrs/layer-5/ADR-056-the-engine-survives-code-shrinking.md))

The finding held right up to the word "rules": the first minified run rendered, round-tripped and
recorded skew with **zero Dogwood-specific keep rules**, because the reflective surfaces belong to
Zipline and kotlinx-serialization, which carry their own. Everything that *did* break — nine
failures, one per run — was the instrumentation harness meeting a shrunk app, each fix documented
at the rule that makes it. `testBuildType = "release"` now points the whole instrumented suite at
the shrunk APK permanently: `passed=19 failed=0` under R8, and continuous integration assembles the
shrunk build on every pull request. Original finding kept below for the record.

### A1 (original). The system has never been built with code shrinking enabled

No module declares `consumerProguardFiles`, no rules file exists anywhere, and no sample sets
`isMinifyEnabled`. **Every Android build this project has ever made is unminified.** Production
Android applications ship under R8, and this stack is exactly the shape R8 breaks: Zipline binds
services reflectively at the boundary, and `kotlinx.serialization` relies on generated serializers
that shrinkers strip when nothing keeps them. The plausible first symptom is not a crash — it is a
host that starts, verifies, and renders nothing, which this project has met three times under other
names.

*What closing it looks like:* build one sample with `isMinifyEnabled = true`, fix what breaks, ship
the resulting rules as `consumer-rules.pro` in `dogwood-host` so an adopter inherits them without
knowing they exist, and add the minified build to the conformance run so it cannot regress.

### A2. ✅ Closed — Umbra is a whole product, and its first payload build found a generator defect ([ADR-057](../adrs/layer-5/ADR-057-a-whole-product-outside-the-repository.md))

Umbra is three modules now — `:design`, `:guest`, `:app` — in one standalone build with no path
into `engine/`. The payload compiles against the published `dogwood-compose`, signs, serves, and
**renders** in Umbra's own host; the check's verdict is the render transcript, not the build, and
its first run failed. The first compilation of a guest outside this repository found that the
classifier rejected `onChange: (Int) -> Unit` as lazy-layout machinery — `UmbraStepper` had been
silently unbindable for as long as it existed, which is this finding's argument made by the build.
Original finding kept below for the record.

### A2 (original). No build outside this repository has ever produced a payload

`tools/standalone-check` proves the **host** half: Umbra applies the plugin by identifier, generates
bindings, and compiles against published artifacts. Its source tree contains `dev/umbra/design` and
nothing else — **no guest module, no payload, no signing, no serve**. The guest toolchain
(`dogwood-compose` from a repository + generated guest stubs + the Zipline Gradle plugin + the
signing configuration) has never been assembled anywhere except inside this repository, where a
path dependency can silently paper over a packaging hole. The claim "a product outside this
repository can use Dogwood" is half-proven, and the unproven half is the half a product ships.
The `outputModuleName` defect (a dot in the published group renaming the Kotlin/JS module and
breaking every browser test) was found on the *host* side of exactly this seam; the guest side has
had no equivalent shakeout.

*What closing it looks like:* give Umbra a `guest/` build that compiles one screen against
published artifacts, signs it with throwaway keys, and serves it to Umbra's host; extend
`tools/standalone-check/run.sh` to assert the payload loaded and rendered.

### A3. ✅ Closed — the guarded path is the default path ([ADR-058](../adrs/layer-5/ADR-058-protection-by-default.md))

`DogwoodShell` requires `releaseGuard` and `onRefused` now; `null` is accepted and is a written
decision. Removing the defaults turned the gap into two compile errors, which is the whole
argument. Every host in the repository rides the guarded path — the shell-less ones through
`loadGuarded`, calling `succeeded` on mount — and getting-started's outline opens with it. ~~**The web host remains unguarded**~~ — closed 2026-09-08 (Track E1): `WebDelivery` takes a
required `releaseGuard`, the sidecar carries a release identity and a `disabled` switch, the
verdict is taken and the attempt persisted **before the Worker is created**, and success is
reported only when a tree has actually been applied. Graded on a real browser as `H4`/`H5`:
`refused=ReleaseRefused workerCreated=false`. The switch is weaker than mobile's by record — this
sidecar is not signed, so it is only as trustworthy as its origin.

The **mobile** remainder of this finding is also closed, 2026-09-08 (S1,
[ADR-061](../adrs/layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md)). The asymmetry ran
the other way: the web refused a payload naming a dictionary it could not render, and the mobile
clients had only render-time containment, which degrades so gracefully that a user cannot tell it
from a bug. A payload now declares its dictionary in the manifest's **signed** metadata, derived
from the generator's own output rather than restated, and both mobile clients compare it before
`start`. Graded on a real emulator and a real simulator against a genuinely newer payload:

```
CONF B3 PASS -- [about] refused: the payload needs a dictionary this client does not have;
                dogwood.designsystem wants 15, this client implements 14 (last good: 1.0.0)
CONF B3-absent PASS -- no widget from the refused payload is on screen
```

Weaker than the web's by one step, and said so rather than blurred: Zipline exposes no
manifest-only fetch, so the mobile check runs after module evaluation and before `start` rather
than before anything executes at all. Containment stays for the payload that declares nothing.
Original finding kept below.

### A3 (original). Surviving a bad publish is opt-in, and four hosts of five do not opt in

`ReleaseGuard` — the crash-loop quarantine, the last-known-good record, the kill switch: capability
group H, and half the architecture's selling point per ADR-049 — is wired in exactly one place,
`SliceActivity` on Android. `TabsActivity` (the shell sample a product is told to copy), the iOS
host, the desktop host and the web page do not construct one, and neither `DogwoodDelivery` nor
`DogwoodShell` integrates it. `docs/getting-started.md` never mentions it. **An adopter who follows
the documentation ships with no bad-publish protection at all**, and discovers that during their
first bad publish — the one moment the mechanism exists for.

*What closing it looks like:* thread a `ReleaseGuard` parameter through `DogwoodDelivery` (or
`DogwoodShell`) so the protected path is the default path, wire it in the remaining samples, and
put it in getting-started §1.

### A4. ✅ Closed — and the probe found the crash was not merely unreadable but unobservable ([ADR-059](../adrs/layer-5/ADR-059-a-guest-crash-a-host-can-read.md))

Run before built, as the plan required, and the evidence went both ways. Worse than the finding: a
crash in a guest effect never reached the host at all (no exception handler on the composition's
scope), and the host's `{ throw it }` default boomeranged anything that did arrive back into the
sandbox — a Zipline service dispatch returns a handler's throw to the *guest*. Better than the
finding: Zipline applies source maps at build time, so once routed, frames name real Kotlin files
from the payload with nothing to deploy. Both fixed, pinned by `GuestCrashRoutingTest` with a
control, re-verified on the production pipeline. Attribution is file-level (functions minified,
no line numbers); the web Worker path carries a message rather than a stack and stays open here.
Original finding kept below.

### A4 (original). A production crash in a payload cannot be read

The payload ships as minified JavaScript, no build produces or preserves source maps, and no
mapping is published anywhere a crash reporter could use. `handleUncaughtException` hands the host
a `Throwable` whose stack frames are minified names. The operating guide tells a team how to *stop*
a bad release; nothing tells them how to find out **what the release did wrong**. For a system
whose premise is shipping code outside the store review cycle, un-triageable crashes are a
first-month incident, not an edge case.

*What closing it looks like:* emit source maps from the production payload build, keep them out of
the served artifact, and document the retrace step (or the deliberate decision not to have one) in
`docs/operating.md`.

### A5. ✅ Closed — bounds on by default, watched to hold ([ADR-060](../adrs/layer-5/ADR-060-bounds-on-a-runaway-payload.md))

`GuestLimits` (256 MiB, 5-second slice) applied by delivery on both load paths before any guest
code composes; `GuestLimits.none` is the written way out. Watched at the engine level with hostile
scripts and controls, and end-to-end on the production pipeline, where the interrupt fired
source-mapped to the stuck payload file and the host survived. The negative control could not go
red — it *hung*, which is the finding in its purest form. Recorded asymmetries: an interrupt inside
Zipline's own resumption reaches the thread's handler rather than `onGuestException`, and the web
profile has neither bound (the browser's process isolation is its containment). Original below.

### A5 (original). Nothing bounds a runaway payload

No memory limit, interrupt handler, or watchdog is configured on the QuickJS instance anywhere in
`dogwood-host`, and no drill exercises a payload that allocates without bound or never yields.
The containment story is thorough about *values* (clamps, A5), *names* (skew), *crashes*
(ReleaseGuard) and *time-per-frame* (the authoring check bans per-frame APIs by name) — and silent
about a plain `while (true)` or an unbounded list, either of which a payload can ship over the air
this afternoon. The authoring check cannot catch a loop; only a runtime bound can.

*What closing it looks like:* find out what Zipline exposes (memory limit, interrupt), configure
it in `DogwoodDelivery` with the refusal reported like any other, and add a hostile-payload drill —
this project's own rule is that the claim does not exist until something watched it hold.

### A6. ◐ Policy written; the cross-version drill remains

[`docs/getting-started.md`](../docs/getting-started.md) now carries the supported-versions table
(Umbra's root build is the same table as code) and the operational rule the coupling implies:
**hosts first, payloads after the fleet**, because a payload meets installed hosts and a
newer-payload-toolchain-on-older-host pairing is exercised by nothing. What stays open is exactly
that drill — a conformance row loading a payload built at engine N with a host at N−1 — which
needs two engine versions to exist and is deferred until there are two. Original finding below.

### A6 (original). There is no version-compatibility policy, and the coupling is triple

An adopter must reconcile **three** toolchains: their application's Kotlin/Compose, the engine's
(Kotlin 2.3.20, Compose Multiplatform 1.10.3, Zipline 1.27.0), and the payload's — which must also
agree with the *installed* host's Zipline across an over-the-air gap. Nothing documents what an
application on a different Compose version should expect, whether a payload built with a newer
Kotlin loads on an older installed host, or which of the three may move independently. The
dictionary has a versioning discipline that is genuinely excellent; the *toolchains* have none.
`DECISIONS-FOR-THE-OWNER.md` records that `0.1.0` makes no stability promise — this is the
concrete engineering underneath that promise when somebody wants to make it.

*What closing it looks like:* a supported-versions table in getting-started, and one conformance
row that loads a payload built at engine version N with a host at N−1, which is the over-the-air
gap every real deployment has.

---

## Part B — Friction a production team hits in the first month

### B1. ◐ Two of the named gaps closed, and the fifth holder shape proven

`Dialog` and `SheetArea` are in the catalogue (dictionary version 12), and the sheet is the one
that mattered structurally: it is the **fifth holder shape**, and adding it cost exactly what
[ADR-043](../adrs/layer-5/ADR-043-holders-are-declared-on-the-surface.md) promised — a table entry,
a guest holder, and a host mirror, with no protocol change. That claim had four data points and
now has five, which is the difference between a pattern and a coincidence.

`Dialog` is worth noting for the opposite reason: it needed **no** holder. A holder exists when the
host owns a value the guest must mirror; a dialog's openness is the guest's own state and the only
thing coming back is a dismissal, which is an ordinary event. Knowing when *not* to reach for the
machinery is part of the machinery being usable.

✅ Menu, pager and both pickers landed 2026-09-08 (C1–C3), with holder shapes six, seven and
eight — **eight of the roughly thirty** the coverage measurement predicts. The catalogue is
**20 components**, and a product design system is fifty to two hundred, so this finding stays
open with a smaller number rather than closing. Original finding below.

### B1 (original). The catalogue is a slice, and the missing components are the ones every product uses

The committed surface binds **14 components**. There is no dialog, no bottom sheet, no dropdown or
menu, no pager, no date or time picker — checked against the surface file, not the docs. The
readiness plan is honest that a product's first weeks are writing surfaces and mirrors, and the
holder machinery makes each addition cheap (a table entry plus a mirror). But `SheetState`,
`PagerState` and the picker states are named in the plan as "arrive with their widget" and none has
arrived: **the fourth holder shape is proven; the fifth through eighth are promises.** A team
evaluating this should budget the design-system build-out as the first milestone of adoption, not a
tail.

### B2. ✅ Closed — the loop existed and was undocumented

Both halves were already built: `serveDevelopmentWebpackZipline` under `--continuous` rebuilds the
unoptimized bundle on every save (verified the task exists by dry run), and every shell host polls
the manifest every five seconds and swaps the new guest in live, carrying `rememberSaveable` state
across — the production code-update machinery (`A7`) doubling as hot reload. And a screen test
needs no harness the engine does not already export: `DogwoodHost`, `DogwoodComposition` and
`decodePositional` are public, and `docs/authoring.md` §8 now carries the recipe — compose against
a fake host, pump a frame, decode what crossed with the real decoder. Original below.

### B2 (original). There is no payload development loop

The documented workflow is `serveProductionWebpackZipline` — a full production webpack build per
edit. No watch mode, no incremental serve, no way to preview a screen without a running host, and
no documented harness for a product author to *test* a screen (the engine's own guest tests exist,
but nothing in `docs/authoring.md` tells an author how to write one against their screens). The
inner loop is the thing a product team lives in; today its length is a production bundle.

### B3. ✅ Closed — two shells is the shape, and the costs are written down ([ADR-065](../adrs/layer-5/ADR-065-two-teams-need-two-shells-and-nothing-else.md))

No engine machinery, which was the plan's own rule: not until a need is demonstrated, and the
measurements did not demonstrate one. `samples/two-payloads` hosts two independently built, signed
and versioned payloads with everything that must be separate separate; `docs/multi-team.md` carries
the numbers and a diagram.

**The cost is disk, and it does not amortise:** 1.15 MB of cache for a payload that draws three
nodes, almost all of it Compose runtime and Kotlin standard library, because the two payloads are
separate Zipline containers sharing nothing. Time to first tree tracks payload size rather than
shell count (351 ms against 871 ms, started together, neither queued), and two live interpreters and
compositions cost about 14.8 MB of heap. A product that finds a runtime copy per payload
unacceptable should ship fewer payloads with more entry points — which is what a single
`manifestUrl` already gives.

**The merge workflow was run, not described.** Two branches each appending a component, both
claiming tag 24; the real conflict; the safe resolution (reset the lock to the *merge base*, never
hand-merge generated JavaScript Object Notation, then regenerate); and what the generator says when
you get it wrong — `dictionary lock violated; tags are permanent: FilterChip moved from tag 24 to
25`. It also surfaced a hazard the lock does **not** catch: the version number merges cleanly, so a
payload published from an unmerged surface branch declares a version that will mean something else
once it lands. Hence the rule, written rather than built because its enforcement point is a
deployment pipeline: **publish from the integration branch, never from a surface branch.**

Original finding below.

### B3 (original). One payload per shell

`DogwoodShell` takes a single `manifestUrl`; its "several experiences" are entry points **within
one payload**. Two teams shipping independently means two shells — separate caches, separate warm
pools, separate release guards — and the surface lock's append-only discipline has no documented
multi-team workflow (two branches appending to one surface produce a lock conflict whose safe
resolution is nowhere written down). Fine for one team; unexamined for an organization.

### B4. ✅ Closed — `samples/ios-embed` produces the XCFramework

A library module (not an application) assembling `DogwoodEmbed.xcframework` with device and both
simulator slices, exposing one Swift function that returns a `UIViewController`, with the release
guard on by default inside it. `tools/ios-embed-check/run.sh` assembles it and asserts on what an
Xcode project receives — both slices, and a header carrying the factory **under its documented
Swift signature** with `dogwood-host`'s types exported alongside, which is the failure an omitted
`export(...)` actually produces. Header inspection, not a compiled Swift app; the limit is stated
in the check. Original finding below.

### B4 (original). Embedding into an existing iOS application is described and not built

The iOS sample **is** the application: `UIApplicationMain` in Kotlin, no Xcode project, and no
framework or XCFramework target anywhere in the build. ADR-004 says a product "builds a framework
and embeds it" — nothing builds one, so the first iOS adopter does that packaging work themselves,
on a Kotlin/Native + Compose toolchain, before they can render a single screen inside their
existing app. (Android and web embed trivially — a composable and a page — this is iOS-specific.)

### B5. Evidence gaps the matrix already admits

Carried here so this audit is complete on its own page: `J2`/`J4` ungraded on iOS (that sample
wires no navigation service — a choice the surface allows, but it means launch parameters are
asserted by nothing on that client); ~~desktop has no skew drill~~; ~~`H2`'s device half cannot be
provoked~~; and ~~the mobile hosts have no pre-flight dictionary check~~.

Three of the four closed 2026-09-08/09. The desktop skew drill is `tools/skew-drill/run-desktop.sh`,
reading the render transcript because that client has no accessibility tree to walk from outside the
process — and it found that a withheld widget's binding *runs*, so the transcript had to learn to
say `withheld` before the claim could be graded honestly. `H2`'s device half is
`tools/reference-server/quarantine-drill.sh`: a payload that throws before the host can mount
anything, quarantined after two launches on a real emulator, recovered through `resume`. The mobile
pre-flight check is [ADR-061](../adrs/layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md),
graded as `B3` on Android and iOS.

`J2`/`J4` on iOS remains open, and is a property of that sample rather than of the engine.

---

## Part C — Already recorded, restated in one place because an adopter hits every one of them

These are decisions or known limits with an owner and a file; they are listed, not re-argued.

| Gap | Where it is recorded |
|---|---|
| Artifacts publish to `mavenLocal()` only — nobody else can resolve them | `DECISIONS-FOR-THE-OWNER.md` §5 |
| No production signing keys, no key ceremony, no payload hosting, no staged rollout server | `DECISIONS-FOR-THE-OWNER.md` §6 |
| Performance budgets `G1`–`G4` graded on no real device | `DECISIONS-FOR-THE-OWNER.md` §4, Layer 4 ADR-008 |
| Web first visit is 3.58 MB brotli, three quarters of it Skiko | `DECISIONS-FOR-THE-OWNER.md` §7, ADR-045 |
| Web network policy is the browser's Content Security Policy, not the default-deny allow-list | ADR-032, ADR-055 §4 |
| Web cannot announce a disabled control | `exempt.tsv`, upstream report 3 (drafted, unfiled) |
| Continuous integration cannot block a merge (branch protection returns 403) | `DECISIONS-FOR-THE-OWNER.md` §1 |
| No written Apple ruling on downloaded interpreted payloads | `DECISIONS-FOR-THE-OWNER.md` §2 |

---

## What is *not* a gap

Checked because an auditor should expect them to be, and they are handled: right-to-left layout
(`layoutDirectionRtl` crosses in the environment); licensing (Apache 2.0 with NOTICE); locale-honest
formatting and plurals (group C); state across process death and code updates on all clients
(E1, A7); skew containment run end to end on three real clients; signing with rotation (B1/B2);
and the documentation set, one quarter of which is generated so it cannot drift.

## Part D — The plan to close it

Adopted 2026-09-07, in the recommended order below. One constraint from the owner shapes A2: **a
fully outside-the-repository exercise is not available, so the proof is a complete standalone
sample that lives in this repository and is deliberately disconnected from the Dogwood source** —
the `samples-standalone` pattern, extended from "a host module" to "a whole product": its own
Gradle build, no `includeBuild`, no path into `engine/`, everything resolved from a repository. That
is weaker than a genuinely external build in exactly one way (it shares a checkout) and equal in
every way that has ever caught a defect here: publication metadata, module naming, plugin
resolution, and the generated-source seams.

| # | Item | Done means |
|---|---|---|
| 1 | ~~A1 — R8~~ ✅ | Done, and better than specified: the full instrumented suite runs against the minified build rather than a smoke check, and the verified finding is that an adopter needs **no** Dogwood-specific rules — so none ship, on evidence rather than neglect |
| 2 | ~~A2 — the standalone product~~ ✅ | Done as specified, and the first guest compilation outside the repository found a real generator defect (a `(Int) -> Unit` callback rejected as lazy-layout machinery) — the audit's reasoning, confirmed by the build |
| 3 | ~~A3 — protection by default~~ ✅ | Done: the parameters lost their defaults, every Zipline host rides the guarded path, and the worked example copies the protection with it. The web host is the recorded remainder |
| 4 | ~~A4 — readable crashes~~ ✅ | The claim went both ways: crashes were *unobservable* (no scope handler; a rethrowing default that returned the crash to the sandbox), and once routed, Zipline's built-in source maps give file-level Kotlin attribution with nothing to deploy. Web Worker path stays open |
| 5 | ~~A6 — the version policy~~ ◐ | The table and the hosts-first rule are written; the cross-version drill waits for a second engine version to exist |
| 6 | ~~A5 — the runaway payload~~ ✅ | Established from the artifact (`Zipline.quickJs` exposes both knobs), on by default, watched at two levels including a full-pipeline hostile drill whose negative control hung rather than failed |
| 7 | ~~B2 — the inner loop~~ ✅ | Both halves already existed and were undocumented: continuous dev-serve plus the shell's five-second poll is hot reload with state carried, and the screen-test recipe uses only public API and the real decoder |

B1 (catalogue), B3 (multi-payload) and B4 (iOS framework packaging) stay on this page as the first
adopter's roadmap rather than becoming engine work now; each is product-scale.

## Recommended order

By what unblocks adoption soonest per unit of work, and by this project's own heuristic that the
first run of anything finds the defect:

1. **A1** (one minified build + consumer rules) and **A2** (Umbra builds a payload) — both are
   "run the thing once"; both have the highest odds of hiding a real breakage.
2. **A3** — wiring and documentation, no new mechanism.
3. **A4** — source maps are a build flag plus a paragraph; the retrace step is the work.
4. **A6** — a policy page and one cross-version conformance row.
5. **A5** — needs upstream research before it needs code.
6. **B1–B4** — product-scale; the first adopter's roadmap rather than this repository's backlog.

# Adoption audit: what stops a production application from picking this up

**Date:** 2026-09-07. Audited at `main` = `fd09fff`, from the position of a product team — Android,
iOS and web engineers plus their release, security and quality people — trying to integrate Dogwood
into an **existing** application and ship it.

Every finding below was **verified against the repository**, not inferred from its documents; two
suspicions (right-to-left layout, licensing) were checked and dropped because the code already
handles them. Findings already recorded elsewhere are listed once, in Part C, so Parts A and B are
new information.

The one-paragraph verdict: **the engine is real and the evidence behind it is unusually honest, but
the adoption path has never been walked end to end.** Everything a *demo* needs exists and is
verified on three clients. Several things only a *production integration* needs have never been
exercised even once — and this project's own history says the first run of anything finds a defect.

---

## Part A — Verified blockers. Each of these has never been exercised, and this repository's record is that the first run of anything finds a defect

### A1. The system has never been built with code shrinking enabled

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

### A2. No build outside this repository has ever produced a payload

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

### A3. Surviving a bad publish is opt-in, and four hosts of five do not opt in

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

### A4. A production crash in a payload cannot be read

The payload ships as minified JavaScript, no build produces or preserves source maps, and no
mapping is published anywhere a crash reporter could use. `handleUncaughtException` hands the host
a `Throwable` whose stack frames are minified names. The operating guide tells a team how to *stop*
a bad release; nothing tells them how to find out **what the release did wrong**. For a system
whose premise is shipping code outside the store review cycle, un-triageable crashes are a
first-month incident, not an edge case.

*What closing it looks like:* emit source maps from the production payload build, keep them out of
the served artifact, and document the retrace step (or the deliberate decision not to have one) in
`docs/operating.md`.

### A5. Nothing bounds a runaway payload

No memory limit, interrupt handler, or watchdog is configured on the QuickJS instance anywhere in
`dogwood-host`, and no drill exercises a payload that allocates without bound or never yields.
The containment story is thorough about *values* (clamps, A5), *names* (skew), *crashes*
(ReleaseGuard) and *time-per-frame* (the authoring check bans per-frame APIs by name) — and silent
about a plain `while (true)` or an unbounded list, either of which a payload can ship over the air
this afternoon. The authoring check cannot catch a loop; only a runtime bound can.

*What closing it looks like:* find out what Zipline exposes (memory limit, interrupt), configure
it in `DogwoodDelivery` with the refusal reported like any other, and add a hostile-payload drill —
this project's own rule is that the claim does not exist until something watched it hold.

### A6. There is no version-compatibility policy, and the coupling is triple

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

### B1. The catalogue is a slice, and the missing components are the ones every product uses

The committed surface binds **14 components**. There is no dialog, no bottom sheet, no dropdown or
menu, no pager, no date or time picker — checked against the surface file, not the docs. The
readiness plan is honest that a product's first weeks are writing surfaces and mirrors, and the
holder machinery makes each addition cheap (a table entry plus a mirror). But `SheetState`,
`PagerState` and the picker states are named in the plan as "arrive with their widget" and none has
arrived: **the fourth holder shape is proven; the fifth through eighth are promises.** A team
evaluating this should budget the design-system build-out as the first milestone of adoption, not a
tail.

### B2. There is no payload development loop

The documented workflow is `serveProductionWebpackZipline` — a full production webpack build per
edit. No watch mode, no incremental serve, no way to preview a screen without a running host, and
no documented harness for a product author to *test* a screen (the engine's own guest tests exist,
but nothing in `docs/authoring.md` tells an author how to write one against their screens). The
inner loop is the thing a product team lives in; today its length is a production bundle.

### B3. One payload per shell

`DogwoodShell` takes a single `manifestUrl`; its "several experiences" are entry points **within
one payload**. Two teams shipping independently means two shells — separate caches, separate warm
pools, separate release guards — and the surface lock's append-only discipline has no documented
multi-team workflow (two branches appending to one surface produce a lock conflict whose safe
resolution is nowhere written down). Fine for one team; unexamined for an organization.

### B4. Embedding into an existing iOS application is described and not built

The iOS sample **is** the application: `UIApplicationMain` in Kotlin, no Xcode project, and no
framework or XCFramework target anywhere in the build. ADR-004 says a product "builds a framework
and embeds it" — nothing builds one, so the first iOS adopter does that packaging work themselves,
on a Kotlin/Native + Compose toolchain, before they can render a single screen inside their
existing app. (Android and web embed trivially — a composable and a page — this is iOS-specific.)

### B5. Evidence gaps the matrix already admits

Carried here so this audit is complete on its own page: `J2`/`J4` ungraded on iOS (that sample
wires no navigation service — a choice the surface allows, but it means launch parameters are
asserted by nothing on that client); desktop has no skew drill; `H2`'s device half cannot be
provoked; and the mobile hosts have no pre-flight dictionary check — render-time containment is
their only line, where the web refuses before a byte of guest code runs.

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

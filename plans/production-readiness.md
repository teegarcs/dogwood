# What remains before a product ships on this

Phase 7 closed the *engine*: three clients render the same tree from the same core, 600 tests pass,
and every conformance claim this machine can grade is met. That is not the same as a product being
able to ship on it, and this is the difference — read as a checklist rather than as a narrative.

Three questions are kept apart deliberately, because they have different owners and different
answers:

1. **Capability** — can a product *build* what it needs to build?
2. **Alignment** — does it behave the same on Android, iOS and web?
3. **Maintenance** — can a team operate it on an ordinary Tuesday, and recover on a bad one?

Everything blocked on a person rather than on work is in
[`DECISIONS-FOR-THE-OWNER.md`](../DECISIONS-FOR-THE-OWNER.md) and is not repeated here.

> **Superseded in part, 2026-09-07.** This plan closed, and then an adoption audit —
> [`plans/adoption-audit.md`](adoption-audit.md) — asked a harder question than the plan did: not
> "does the engine work" but "can a production application pick this up". It found six verified
> blockers this plan never had a row for, of which the pattern is one this project knows well:
> **capabilities that exist and have never been exercised once** (no build has ever run under R8,
> no payload has ever been built outside this repository, four hosts of five ship without the
> bad-publish guard). The audit is the successor to this plan's Part 5.

**Where this stands, 2026-09-07.** Every item in Part 5's ordered list is closed or has moved to the
owner. A product can declare its own components, consume the whole thing from outside this
repository, run the real guest on all three shipping targets from one set of screens, survive a bad
publish without a server, report what it could not understand, and read four manuals — one of which
is generated so it cannot go stale. What is left below is not a queue of gaps: §2.2 to §2.4 are
**product-scale** work that arrives with a product's own components, and the alignment table's
remaining rows say what each claim rests on rather than what is missing. The honest summary is that
the engineering plan is finished and the two things still between this and a first ship —
[signing keys and payload hosting](../DECISIONS-FOR-THE-OWNER.md) — are decisions rather than code.

---

## Part 1 — The gap that blocked everything else ✅

### A product can now register its own components

**Closed** ([ADR-046](../adrs/layer-5/ADR-046-a-product-registers-its-own-segment.md)). A product
writes a surface, one `*Impl` per component, and a build file naming a segment identifier; the
generator emits both sides and a host calls `DogwoodRegistry.register(…)` once, at application
start. `samples/product-design-system` is the worked example — Acme's three components in
`dev.acme.*`, rendering and handling events on a device.

Adding the second caller is what found the three places that had quietly assumed one: the guest
runtime's recording seam was `internal`, generated code assumed it shared a package with the
runtime, and a segment's Kotlin name and its wire name were the same field — which put the design
system in the version map twice the moment a binding named itself.

✅ **And the packaging is done too**
([ADR-047](../adrs/layer-5/ADR-047-the-generator-ships-as-a-plugin.md)). The generator ships as the
Gradle plugin `dev.dogwood.codegen`; the runtime publishes under `dev.dogwood`. What a product
writes is a `dogwood { segment(…) }` block with two real decisions in it, not fourteen command-line
arguments — one of which had to be *omitted* or the engine's version vector was silently overwritten.

`samples-standalone/umbra` is the proof, and it is a separate Gradle build: no `includeBuild`, no
project dependency, no path into `engine/`. It resolves the plugin by identifier, the generator as a
dependency and the runtime as artifacts. `tools/standalone-check/run.sh` publishes and builds it, and
asserts what a green build does not imply — that bindings were generated, that they **compiled**, and
that a lock was written beside the surface.

Publishing found two defects nothing else could have. Adding a publishing `group` renamed the
Kotlin/JavaScript module — `internal` declarations are mangled against that name — and killed every
browser test in `dogwood-compose` with `then_babg2s_k$ is not a function`. And a sources jar
*packages* a generated directory rather than compiling it, so `dependsOn` on the compilations was not
enough; the source directory is now wired through its producing task.

### The original entry, kept because it is what the plan said

**This was the gap.** Everything else on this page is smaller than it.

`specs/layer-5-host.md` names it as item (c) of bespoke subsystem 9 and says why in one sentence:

> Without (c) a guest can emit only raw Material 3, which no product team ships.

The generator today runs over **one** surface file — `engine/surface/DesignSystemSurface.kt`, thirteen
components — and emits one dictionary segment. A product has its own design system: its own button,
its own card, its own video player, its own map, its own chart. It cannot add them. Redwood's entire
model was app-defined schemas, and deleting the schema must not delete the escape hatch.

What it needs, and none of it is speculative — the machinery all exists and is pointed at the wrong
number of inputs:

- **Multiple surfaces, multiple segments.** `buildDictionary` already takes a segment name and
  identifier; `Segments` already reserves the space; the lock already tracks tags per segment. What
  does not exist is a build in which a *consumer* project declares its own surface and gets its own
  segment.
- **Per-segment versioning and skew.** A client advertises a version per segment and a guest branches
  on it (`LocalSegmentVersions`). A product segment needs the same, which mostly means the lock file
  and the version bump becoming per-project artefacts rather than repository ones.
- **A published generator.** `dogwood-codegen` is an internal Gradle project. A product consumes it
  as a plugin, which means publishing coordinates, a plugin marker, and a supported way to point it
  at a source directory.

**Until this exists, this project can demonstrate a slice and cannot host a product.** It is the
first thing to build, and it is the thing every other item below is smaller than.

---

## Part 2 — Capability gaps, in the order they will be hit

### 2.1 The real guest runs on the web ✅

The web slice's guest is **a hundred lines of hand-written JavaScript**, and its own header says so:

> It is NOT what a real guest is: a real guest is Kotlin compiled to JavaScript, running a Compose
> composition, with `dogwood-compose`'s applier producing the batch.

**Closed** ([ADR-048](../adrs/layer-5/ADR-048-the-real-guest-runs-on-the-web.md)). The screens moved
to `samples/slice-screens`, and both entry points depend on them: `slice-guest` binds them to a
Zipline service, `samples/web-guest` drives them from a Worker. The screens name no transport. In a
real browser the guest composed the **whole About screen — 93 nodes**, the host rendered 27 of them,
a tap produced a recomposition on a host frame, and `rememberSaveable` round-tripped.

The answering batch was `[[1,19,11,true],[1,19,12,1]]`: a live-state holder, declared on a surface,
generated by `@Holder`, crossing from a real Compose composition in a Web Worker. The hand-written
guest stays, because it is the only evidence the protocol is writable by something that is not this
codebase.

The three unknowns this was expected to surface all resolved, and none where it looked. The
Kotlin/JavaScript output is **201 KB brotli** — 5.6% of a page that is already 3.58 MB, and off the
first frame's critical path. `dogwood-compose`'s applier behaves identically without a Zipline event
loop, because it never depended on one: `Dispatchers.Unconfined` and a `BroadcastFrameClock` are
transport-free. The service bridge is the one that did **not** resolve — see below.

~~**What is still thin is the service surface.**~~ ✅ **Closed on 2026-09-07**
([ADR-055](../adrs/layer-5/ADR-055-the-web-guest-gets-its-hosts-services.md)). `WorkerServices`
answered `log` and declared everything else absent, so the sample's own Diagnostics screen read
`this client offers no services at all`, `surface revision 0 (unreported)` and `host clock
unavailable` — it ran the same screens as the mobile payload and ran them **blind**.

All of it crosses now, split by *who can answer*: the Worker answers `log`, `clock` and `network`
itself, and a single `start` message carries the entry point, launch parameters, feature flags,
routes and segment versions before the first composition, with `analytics` and `navigate` going back
one-way. The same screen now reads `analytics, clock, featureFlags, log, navigation, network`,
`surface revision 2 (2)` and a real clock — and the Explore screen, which needs a launch parameter
*and* a network fetch to render anything, renders on the web for the first time.

**The remaining honest asymmetry is `network`**, and it is the browser's rather than Dogwood's: the
guest calls `fetch` inside the page's origin, constrained by the page's Content Security Policy
rather than by a host allow-list that refuses everything by default. ADR-032 recorded that for the
profile; ADR-055 §4 makes it concrete. ~~**A code update has still never been exercised on web.**~~ ✅ It has now, and it is graded:
`DogwoodWebExperience.update` snapshots the running guest, closes its bridge, clears the tree and
attaches a new Worker with the snapshot riding the start message — because a new Worker is a new
module with a new composition and nothing survives implicitly. Claim `A7` on the web moves off the
default tab, publishes, and asserts the screen comes back where the user left it, with a control
proving the tab had moved and the whole check watched to fail with the restore removed.

### 2.2 Live-state holders ◐

Three shapes are proven end to end — a list, a focus request, a scroll position — and the mechanical
half of adding one now costs a table entry plus a host mirror
([ADR-043](../adrs/layer-5/ADR-043-holders-are-declared-on-the-surface.md),
[ADR-044](../adrs/layer-5/ADR-044-scroll-position-is-a-declared-quantum.md)). Roughly 27 types
remain, and **most belong to widgets no dictionary here carries**, so they arrive with their widget
rather than as a queue.

✅ **The fourth shape is proven** ([ADR-051](../adrs/layer-5/ADR-051-a-holder-that-answers.md)):
`SnackbarHostState`, a request that **answers**. The three before it run one way at a time — a
target goes down, or a report comes up, and the two are independent. This is the first thing a guest
asks for and waits on, and what comes back decides what it does next.

It needs no new protocol: the request is properties, the reply is an event carrying **the sequence
it answers**, which is what stops two requests in flight being confused. Verified on three targets
and on a device, where the user tapping *Undo* resumed the guest into the branch that undoes.

The ones a product will hit early are now all one of four known shapes, and none is more than a
mirror: `PagerState`, `SheetState` / `DrawerState`, and the picker states (`DatePickerState`,
`TimePickerState`).

### 2.3 The design system is a slice, not a system

Thirteen components. A product design system is fifty to two hundred. This is not a defect — it is
what §1 exists to fix — but it changes the shape of what a product does first: the first week is
writing surfaces and mirrors, not writing screens.

### 2.4 Coverage the measurement already predicts

67.6% of the uppercase Compose widget surface is generable, and
`specs/layer-5-host.md` is explicit that this **overstates parameter coverage**: many of those
components carry an optional live-state, callback-object or asset parameter that guest code cannot
pass until the corresponding subsystem exists. The three classes that remain unbuilt are
callback-objects (`VisualTransformation`, `InputTransformation`, `PopupPositionProvider` — invoked
per keystroke, per layout or per draw), asset types the guest cannot name, and the ~27 holders above.

---

## Part 3 — Alignment: what is actually shared, and what only looks it

**What is genuinely shared.** Since [ADR-041](../adrs/layer-5/ADR-041-one-host-core-split-at-the-zipline-seam.md)
the tree, the applier, the bindings, modifiers, expressions, theme, plurals, the state store, the
skew report and the warm pool are **one implementation** compiled for every client. That is the
strongest alignment guarantee available: not "three implementations that agree" but one.

**What is not shared, and must not be**: the transport (Zipline/QuickJS on mobile, a Worker on web),
storage, the network stack, and the accessibility surface. Those are platform-shaped and
[ADR-040](../adrs/layer-5/ADR-040-conformance-is-a-catalogue-not-a-suite.md) is the answer to them —
a shared catalogue and grammar, per-client instruments.

**Where alignment is claimed on thinner evidence than it looks:**

| Claim | What is actually asserted | Gap |
|---|---|---|
| `D8`, `D9`, `D10` (holders) | a composition on **three** targets | ✅ Closed. Running them on iOS and in a browser found a hostile-value clamp that fires on the Java Virtual Machine and not on the web — see `conformance.md` Part 7 |
| `A2`–`A4` (skew containment) | ✅ three real clients, three instruments, one procedure | Closed 2026-09-06. `run-ios.sh` and `run-web.sh` found one host-integration defect each ([ADR-052](../adrs/layer-5/ADR-052-the-skew-drill-on-every-client.md)). The web drill runs in continuous integration; desktop still has none |
| `D1`–`D5`, `D7` (accessibility) | three real clients, three instruments | Sound. What no instrument covers is whether the *speech is good*, reading order as experienced, and typing and selection |
| `F1`–`F4` (network policy) | mobile enforce; web is the browser's Content Security Policy | Recorded as **weaker on web, not equal** — correct, and a product should know it |
| `G1`–`G4` (performance) | nothing | No host that exists can grade them; see the decisions file |

**The cheapest alignment win available** is running the existing host tests on the other Kotlin
targets rather than only on the Java Virtual Machine. It is a build-configuration change, not new
tests, and it converts three claims from "shared code, asserted once" to "asserted where it runs".

---

## Part 4 — Maintenance: the ordinary Tuesday, and the bad one

This is the least-built area, and the gap is not subtle: **the machinery to publish a payload
exists; the machinery to operate one does not.**

### 4.1 What exists

- **Signing and rotation.** Ed25519, with a rotation key, verified before execution
  ([`B1`, `B2`](conformance.md)). The keys in the repository are throwaway development keys,
  committed on purpose and labelled as such.
- **Skew containment.** A client meeting a newer dictionary degrades per documented rules and
  **records what it did not understand** in a `SkewReport`, per experience.
- **Refusal and survival.** An undecodable batch is rejected whole, reported, and survived; the tree
  on screen keeps rendering, and the guest is asked to resynchronise
  ([Layer 4 ADR-012](../adrs/layer-4/ADR-012-resynchronisation-after-a-rejected-batch.md)).
- **State across updates.** A code update while a screen is live is the normal case and is tested as
  one.

### 4.2 What does not exist

- ✅ **The `SkewReport` can now leave the process.** `DogwoodSkewReporter` is a one-method interface a
  product implements with whatever telemetry it already has; `SkewDrain` hands it what is **new**
  since the last call, because the sets accumulate for the life of an experience and a reporter that
  sent the whole report every time would send the same entries forever. Dogwood stores, the host
  reports — no transport, batching policy or sampling rate is Dogwood's business. Verified end to
  end by inducing real skew with `tools/skew-drill` and reading it out of logcat.
  **What remains is per-product**: a host has to call `drain()`, on the thread that composes. The
  Android sample is the reference implementation.
- ✅ **A bad publish is survivable**
  ([ADR-049](../adrs/layer-5/ADR-049-surviving-a-bad-publish.md)). An attempt is recorded and
  **persisted before the payload runs**, which is what makes a crash-on-launch loop terminate rather
  than repeat forever; a version that fails twice is quarantined and the last known good one is
  named; and a publisher can stop a release from the manifest's signed metadata. Verified on a
  device: the kill switch refuses while the running guest keeps rendering, a cold launch under it
  shows the host's own screen and the reason, and removing it restores the payload. Capability group
  **H** in `conformance.md`.
- **What is still missing is the server half.** Nothing *resumes* a previous payload — that needs a
  manifest that still serves it. There is no staged rollout: `InstallCohort` gives a device a stable
  bucket a server could stage against, and there is no server. And nothing reports a refusal to a
  publisher, so a fleet-wide quarantine is visible only if the host wires it to telemetry.
- **No key ceremony, and no payload hosting story.** Both are the owner's rather than the
  engineering plan's, and both now live in
  [`DECISIONS-FOR-THE-OWNER.md` §6](../DECISIONS-FOR-THE-OWNER.md) so they stop being restated
  here: production keys need generating, holding, rotating and revoking by somebody, and the samples
  serve from a Gradle task on `localhost:8080`. [`docs/operating.md`](../docs/operating.md) §5 is
  what the person picking that up reads.
- ✅ **The authoring check exists** ([ADR-050](../adrs/layer-5/ADR-050-the-authoring-check.md)).
  `dev.dogwood.guest` rejects per-frame animation APIs and resource loaders at build time, joins
  `check`, and names the replacement for each. Best-effort by construction, as Layer 1 always said:
  it catches a directly-named API and not one assembled at runtime. Capability group **I**.

---

---

## Part 4b — Documentation, demos and the server, which this plan did not have a place for

The three questions that produced this section — *are the demos finished, is there documentation,
how does this work on a server* — had no home in Parts 1–4, and the answers are worse than the
engineering gaps because they are the parts a new team meets first.

### The demos are one application on four hosts ✅

`slice-android`, `slice-ios`, `slice-desktop` and now the web all run the **same screens**, from
`samples/slice-screens`. The hand-written JavaScript guest is still there and still served, because
it proves something the Kotlin one cannot: that the protocol is an interface rather than an artefact
of having Kotlin on both ends. The page picks between them with a sidecar.

**No sample has a README.** `engine/README.md` carries the run commands, which is enough for
somebody already in the repository and not enough for anybody else.

No sample has a README. `engine/README.md` carries the run commands for all of them, which is
enough for somebody already in the repository and not enough for anybody else.

### The documentation is a pitch, not a manual

`developer-experience.md` explains what this is like to use and is written for someone deciding
whether to adopt. Two statements in it were false until 2026-09-06 — it declared the system unbuilt,
and it described a build-time dictionary check that does not exist — which is what a document nobody
re-reads does over seven phases.

What does not exist at all:

| Document | For whom | Why it is missing today |
|---|---|---|
| ~~**Getting started**~~ ✅ | somebody adding Dogwood to an existing application | [`docs/getting-started.md`](../docs/getting-started.md). Consumable since item 1: the plugin publishes and `samples-standalone/umbra` builds against it with no path into this repository |
| ~~**Authoring guide**~~ ✅ | somebody writing screens | [`docs/authoring.md`](../docs/authoring.md). Each rule with the reason it exists, because a rule whose reason is missing gets worked around |
| ~~**Operations guide**~~ ✅ | whoever is on call | [`docs/operating.md`](../docs/operating.md). Written around what *is* built — the three protections that need no operator, the kill switch, the skew report — and §6 of it lists what does not exist, because a runbook describing a capability nobody built is worse than none |
| ~~**API reference**~~ ✅ | everyone | [`docs/api/`](../docs/api/), emitted by the generator from the same parse as the bindings. A product asks for its own with one line — `referenceFile.set(...)` — and `tools/standalone-check` asserts one is produced outside this repository |

`docs/checks.md` is the one operational document that exists, and it covers checks rather than
operation.

### There is no server

This is the largest single omission on the page, and it is invisible from inside the repository
because a Gradle task fills the gap. A payload today is served by
`:samples:slice-guest:serveProductionWebpackZipline` on `localhost:8080`.

A product needs, and none of it exists:

- **A publish pipeline** — build, sign, upload, and make a manifest live, as one reviewable step
  rather than a developer's Gradle invocation.
- **Manifest hosting with the right cache semantics** — the payload files are content-addressed and
  immutable, the *manifest* is not, and getting that backwards means either stale clients or no
  caching at all.
- **`Content-Encoding: br`** on the web bundle, whose absence silently costs 27%
  ([ADR-045](../adrs/layer-5/ADR-045-web-page-weight-where-the-levers-are.md)).
- **Rollout and rollback** — §4.2.
- **A signing key that is not in the repository.** The keys in the samples are throwaway development
  keys, committed on purpose and labelled as such.

**The mechanism was always designed to be somebody's server, not to be one.** That is a defensible
architecture and an indefensible omission from a production plan, which is why it is written down
here rather than left as an assumption.

---

## Part 5 — The order to do it in

Sequenced by what unblocks the most, not by size.

| # | Item | Why here |
|---|---|---|
| 1 | ~~Publish the generator~~ ✅ done | `dev.dogwood.codegen` at `0.1.0`, proved by a build with no path into this repository |
| 2 | ~~The real guest on web~~ ✅ done | Closed, and so is the clause it left behind: the Worker service surface, launch parameters and segment versions all cross now ([ADR-055](../adrs/layer-5/ADR-055-the-web-guest-gets-its-hosts-services.md)) |
| 3 | ~~Ship the `SkewReport`~~ ✅ done | The seam exists and is verified against real skew on a device; wiring it to a product's telemetry is per-product |
| 4 | ~~Rollout, rollback, kill switch~~ ✅ the device half | A bad publish is survivable without a server. Resuming a previous payload and staging a release still need one |
| 5 | ~~The authoring checker~~ ✅ done | Rejects per-frame animation APIs and resource loaders, with the replacement named |
| 6 | ~~Host tests on the other targets~~ ✅ done | The shared core is asserted on the Java Virtual Machine, an iOS simulator and a real browser. It found a clamp that fires on one and not the others |
| 7 | ~~The holders a product hits early~~ ✅ the shapes | All four shapes are proven; the remaining holders are a table entry plus a mirror each, and arrive with their widget |
| 8 | ~~Skew drill on iOS and web~~ ✅ done | `run-ios.sh` and `run-web.sh`, same five steps, read off each platform's accessibility tree. It found one host-integration defect per client ([ADR-052](../adrs/layer-5/ADR-052-the-skew-drill-on-every-client.md)) |
| 9 | ~~Key ceremony and payload hosting~~ → the owner | Not engineering work: the mechanism is built and verified. Moved to [`DECISIONS-FOR-THE-OWNER.md` §6](../DECISIONS-FOR-THE-OWNER.md) |
| 10 | ~~The four documents~~ ✅ done | Getting started, authoring, operating, and a **generated** component reference. The first three are in `docs/`; the fourth is emitted by the generator, because a hand-written page about the components is a fifth thing that can disagree with the surface |

**Both of the items that changed what the project *is* are closed.** A product can declare its own
components, a build outside this repository can consume the whole thing, and the real guest runs on
all three shipping targets from one set of screens. What is left below is what makes it *operable* —
and the least-built of that is still maintenance rather than capability. Everything from 3 down makes it
operable; those two make it usable.

**And one thing is not on the list because it is not sequenced — it is continuous.** Every document
here goes stale silently. `developer-experience.md` spent seven phases telling readers the system did
not exist, and nothing caught it, because no check reads prose. The cheapest guard is the habit this
repository already has elsewhere: when a record's claim stops being true, the record says so in the
place it said the opposite.

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

**What remains is packaging, not design.** The generator is still an internal Gradle project, so
Acme consumes it as `project(":dogwood-codegen")` and a real product cannot. Publishing it —
coordinates, a plugin marker, a supported way to point it at a source directory — is item 1 below
and it is the last thing standing between this and somebody outside the repository using it.

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

### 2.1 The real guest has never run on the web ◐

The web slice's guest is **a hundred lines of hand-written JavaScript**, and its own header says so:

> It is NOT what a real guest is: a real guest is Kotlin compiled to JavaScript, running a Compose
> composition, with `dogwood-compose`'s applier producing the batch.

That was the right thing to build — a hand-written guest proves the protocol is an interface rather
than an artefact of having Kotlin on both ends. But it means **no Kotlin/Compose guest has ever run
in a Worker**. The mobile guest is Kotlin/JS already, and ADR-032 says the web guest is "ordinary
JavaScript in a Web Worker", so the two should meet — nobody has made them.

Unknowns this will surface, none of them obviously fatal and none of them measured: the Kotlin/JS
output's size inside a Worker, whether the Zipline-shaped service bridge translates to `postMessage`
cleanly, and whether `dogwood-compose`'s applier behaves the same without a Zipline event loop
underneath it.

**This is the largest hole in the alignment story**, because every web conformance claim today is
graded against either shared host code or a guest that a product would never write.

### 2.2 Live-state holders ◐

Three shapes are proven end to end — a list, a focus request, a scroll position — and the mechanical
half of adding one now costs a table entry plus a host mirror
([ADR-043](../adrs/layer-5/ADR-043-holders-are-declared-on-the-surface.md),
[ADR-044](../adrs/layer-5/ADR-044-scroll-position-is-a-declared-quantum.md)). Roughly 27 types
remain, and **most belong to widgets no dictionary here carries**, so they arrive with their widget
rather than as a queue.

The ones a product will hit early, and none is more than a mirror: `PagerState`, `SheetState` /
`DrawerState`, `SnackbarHostState`, and the picker states (`DatePickerState`, `TimePickerState`).
`SnackbarHostState` is the one shape not yet proven — a *host-invoked, one-shot action with a
result* — so it is worth doing before it is needed rather than during.

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
| `D8`, `D9`, `D10` (holders) | one Java Virtual Machine composition | `FocusManager.clearFocus()` and `Modifier.verticalScroll` are one call and three implementations. Running `runComposeUiTest` on the other targets is the instrument and is not wired up |
| `A2`–`A4` (skew containment) | Android only, via `tools/skew-drill` | The two-build procedure — a skewed payload served to an installed binary — does not exist for iOS or web. The shape is portable |
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
- **No rollout, no rollback.** A payload is published and clients fetch it. There is no staged
  rollout, no canary, no kill switch, and no "go back to the previous manifest" that is not "publish
  the old bytes again by hand". For a delivery mechanism whose selling point is *shipping without a
  store review*, the ability to un-ship is the other half and it is missing.
- **No crash story for the guest.** What a host does when a guest throws — retry, fall back to the
  previous generation, fall back to a native screen — is not designed. On mobile the payload is
  replaceable over the air, so a bad publish is recoverable in principle; nothing implements the
  recovery.
- **No key ceremony.** Production signing keys need generating, storing, rotating and revoking by
  somebody. The mechanism supports it; there is no procedure.
- **No payload hosting story.** The samples serve from a Gradle task on `localhost:8080`. A real
  deployment needs a content delivery network, cache headers that match the manifest's immutability,
  and — per [ADR-045](../adrs/layer-5/ADR-045-web-page-weight-where-the-levers-are.md) —
  `Content-Encoding: br`, whose absence silently costs 27%.
- **No authoring checker.** [Layer 1](../specs/layer-1-authoring.md) requires a checker that
  **rejects** guest code using per-frame animation APIs, "because the failure mode of not rejecting
  them is silent". A guest author can today write `animateFloatAsState` and get code that ticks the
  boundary every frame.

---

---

## Part 4b — Documentation, demos and the server, which this plan did not have a place for

The three questions that produced this section — *are the demos finished, is there documentation,
how does this work on a server* — had no home in Parts 1–4, and the answers are worse than the
engineering gaps because they are the parts a new team meets first.

### The demos are four hosts, not one application on three platforms

`slice-android`, `slice-ios` and `slice-desktop` run the **same** Kotlin guest. `web-slice` does
not: its guest is a hundred lines of hand-written JavaScript, deliberately, so the protocol is
proven to be an interface rather than an artefact of Kotlin on both ends. The consequence is that
**there is no "one screen, three platforms" demonstration**, which is the demonstration the
architecture's central claim deserves. Closing §2.1 closes this too.

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
| **Getting started** | somebody adding Dogwood to an existing application | There is no supported way to consume it — the generator is an internal Gradle project (§1) |
| **Authoring guide** | somebody writing screens | The rules are spread across `developer-experience.md` §5, Layer 1, and half a dozen ADRs |
| **Operations guide** | whoever is on call | Nothing exists. See §4.2 — most of what it would document is not built |
| **API reference** | everyone | The guest surface is generated, so this is generated too, and nothing generates it |

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
| 1 | **Publish the generator** (§1) | Registration works; consuming it from outside this repository does not. It is the last step of §1 and it is packaging |
| 2 | **The real guest on web** (§2.1) | The largest alignment hole; also the last unproven assumption in the web profile |
| 3 | ~~Ship the `SkewReport`~~ ✅ done | The seam exists and is verified against real skew on a device; wiring it to a product's telemetry is per-product |
| 4 | **Rollout, rollback, kill switch** (§4.2) | The other half of shipping without a store review. A bad publish currently has no defined recovery |
| 5 | **The authoring checker** (§4.2) | Cheap, and it prevents the one class of guest code this architecture cannot absorb |
| 6 | **Host tests on the other targets** (§3) | A build-configuration change that upgrades three claims from inference to assertion |
| 7 | **The holders a product hits early** (§2.2) | `SnackbarHostState` first, because it is the one unproven shape |
| 8 | **Skew drill on iOS and web** (§3) | The shape is portable from Android; it closes the last per-client evidence gap |
| 9 | **Key ceremony and payload hosting** (§4.2, §4b) | Needed before a first ship, not before a first product build |
| 10 | **The four documents** (§4b) | Getting-started and the authoring guide are worth writing the day §1 lands, because that is when somebody outside this repository first tries to use it |

**Item 2 is now the one that changes what the project *is*.** Item 1 was, and is closed but for
its packaging. Everything from 3 down makes it
operable; those two make it usable.

**And one thing is not on the list because it is not sequenced — it is continuous.** Every document
here goes stale silently. `developer-experience.md` spent seven phases telling readers the system did
not exist, and nothing caught it, because no check reads prose. The cheapest guard is the habit this
repository already has elsewhere: when a record's claim stops being true, the record says so in the
place it said the opposite.

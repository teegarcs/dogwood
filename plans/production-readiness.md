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

## Part 1 — The one gap that blocks everything else

### A product cannot register its own components

**This is the gap.** Everything else on this page is smaller than it.

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

- **Nothing ships the `SkewReport` anywhere.** It is collected per experience and a host "can ship it
  as telemetry" — no host does. This is the feedback loop that tells a team a design-system update
  reached payloads before it reached devices, and it is currently a data structure nobody reads.
  **This is the highest-value maintenance item**, because every degradation rule in the architecture
  is silent by design and this is the only thing that makes them visible.
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

## Part 5 — The order to do it in

Sequenced by what unblocks the most, not by size.

| # | Item | Why here |
|---|---|---|
| 1 | **Component registration** (§1) | Nothing a product does is possible without it, and everything below is smaller |
| 2 | **The real guest on web** (§2.1) | The largest alignment hole; also the last unproven assumption in the web profile |
| 3 | **Ship the `SkewReport`** (§4.2) | Every degradation rule is silent by design; this is the only thing that makes them visible, and it is small |
| 4 | **Rollout, rollback, kill switch** (§4.2) | The other half of shipping without a store review. A bad publish currently has no defined recovery |
| 5 | **The authoring checker** (§4.2) | Cheap, and it prevents the one class of guest code this architecture cannot absorb |
| 6 | **Host tests on the other targets** (§3) | A build-configuration change that upgrades three claims from inference to assertion |
| 7 | **The holders a product hits early** (§2.2) | `SnackbarHostState` first, because it is the one unproven shape |
| 8 | **Skew drill on iOS and web** (§3) | The shape is portable from Android; it closes the last per-client evidence gap |
| 9 | **Key ceremony and payload hosting** (§4.2) | Needed before a first ship, not before a first product build |

**Items 1 and 2 are the two that change what the project *is*.** Everything from 3 down makes it
operable; those two make it usable.

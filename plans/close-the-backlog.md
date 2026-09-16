# Closing the backlog: everything still deferred, planned to done

Drafted 2026-09-16. Status: **plan, not started.** Every open engineering item this repository
records anywhere — the generator's four remaining milestones, the proof plan's leftovers, the
consolidated deferred table, the open decisions' engineering halves, the conformance plan's carried
forward items — gathered into one plan with a done-means for each, in the order they should be
run. Nothing is implemented here; every number in §0 was read off the repository at `10fcbd2`.

The house rule holds: **done means run.** A milestone whose deliverable is a document is done when
the document's claims have been executed; one whose deliverable is code is done when the gate that
would catch its absence has been watched to fail.

---

## 0. The inventory, and where each item came from

| # | Item | Recorded in | Group below |
|---|---|---|---|
| 1 | iOS `M4`/`M5` not established: the Material drill stalls before the dialogs section | `plans/material3-proof.md` §1.6 | 1 |
| 2 | `B6` on Android and iOS: a host without the tier refuses a payload that declares it | `plans/material3-proof.md` §2.3 | 1 |
| 3 | `G6`: the guest script's weight is unbudgeted | `plans/material3-proof.md` §5 | 1 |
| 4 | Umbra's README does not point at the Material catalogue; `developer-experience.md` §4 still says preview is planned | `plans/material3-proof.md` §6; generator v2 M7 | 1 |
| 5 | **M4** — the mapping table's first growth; the foundation, layout and ui tiers | `plans/generator-v2.md` | 2 |
| 6 | **M5** — holders for the library's state types (11 types found, ~20 components behind them) | `plans/generator-v2.md`; roadmap Phase 7 | 3 |
| 7 | **M6** — `@Preview`: a payload rendered on the JVM against real Compose, no protocol | `plans/generator-v2.md`; Layer 1 Milestone 3; `developer-experience.md` §4 | 4 |
| 8 | **M7** — manuals rewritten around the tier; the guest check learns the controlled-text-field names and inspects the classpath | `plans/generator-v2.md`; ADR-050 §4 | 5 |
| 9 | The Maven Central transport: coordinates decided, `signing` wired, no Portal upload | `OPEN-DECISIONS.md` §5 | 6 |
| 10 | Key rotation: roll-forward described, procedure never written, never drilled | `OPEN-DECISIONS.md` §6 | 6 |
| 11 | Build-sign-upload as one reviewable step, with the cache and encoding rules checked | `OPEN-DECISIONS.md` §6; `docs/operating.md` §5 | 6 |
| 12 | Staged rollout by cohort: `InstallCohort` built, no server selects by it | ADR-049; generator v2 §4 | 6 |
| 13 | Tier C does not gate in continuous integration | `plans/conformance.md` Part 7; `docs/checks.md` | 7 |
| 14 | Cross-version drill for **engine** skew (payload at N, host at N−1), and Layer 2's per-version build matrix | audit A6; roadmap Phase 3 item 5; Layer 2 Milestone 4 | 7 |
| 15 | Five upstream findings drafted; the web dialog finding needs a real pointer to settle | `tools/upstream-reports/README.md` | 8 |
| 16 | Apple Guideline 4.7 inquiry; gate device; signing keys and hosting; Central account and GPG key; the Pixel pause run; the iOS organisation's written yes | `OPEN-DECISIONS.md` §2, §4, §5, §6; roadmap Phase 0 | 8 |

Items 1–14 are engineering and are planned. Item 15 is drafted and stays unfiled on the owner's
standing instruction, with one engineering follow-up. Item 16 is the owner's and is listed so the
list is complete, with the one part of each that engineering can prepare.

**What the coverage report says today**, because Groups 2 and 3 are sized from it:

| Module | Composables | Bound | Generable, excluded | Bespoke | Unreachable | Deprecated |
|---|---:|---:|---:|---:|---:|---:|
| `material3` | 186 | 79 | 22 | 38 | 6 | 41 |
| `foundation` | 47 | 2 | 17 | 12 | 4 | 12 |
| `foundation-layout` | 12 | 8 | 0 | 0 | 0 | 4 |
| `ui` | 15 | 4 | 0 | 8 | 1 | 2 |

Of the 39 *generable, excluded*, **26 are excluded for one reason**: a default expression names a
symbol the library keeps `internal` (`Default` ×17, `MaterialTheme` ×7, `ElevationTokens` ×2), so
the host binding cannot quote it. Of the 58 *bespoke*, **20 are behind eleven live-state types**
(`CarouselState` ×3, `DrawerState`, `PagerState`, `TimePickerState`, `ToggleableState` ×2 each,
`SliderState`, `RangeSliderState`, `SnackbarHostState`, `SearchBarState`,
`SwipeToDismissBoxState`, `SubcomposeLayoutState`), 11 are controlled text input that `TextInput`
already covers, and the rest are assets, in-frame lambdas and types with nowhere to go.

---

## Group 1 — Finish what the proof plan left open (first, and small)

### 1.1 iOS `M4` and `M5`

The drill grades five claims and then stops making progress before the dialogs section,
reproducibly, and not at a point its own deadline reaches. Two hypotheses, checked in this order:

- `collectAccessibilityElements` caps its walk at **250 nodes**. The Selection section plus the
  picker plus the header is plausibly past that, so `Open alert` is never *collected*, and `reach`
  spends its whole budget scrolling for an element the walk would never return. Test: print the
  count the walk hit the cap at; raise the cap; watch.
- `accessibilityScroll` is offered to every element and taken by the first that accepts it. On the
  Selection section that is a slider, which accepts and moves nothing. Test: offer the scroll to
  the scroll view by class first.

Done means: iOS `M4` and `M5` graded, the switch's `M3-announced` skip kept, **and** a per-claim
time budget that prints a `RESULT` line whatever happens — the deadline added on 2026-09-16 did not
reach the place the drill stalled, which is the sentence that should have been impossible.

### 1.2 `B6` on Android and iOS

The web grades it; the mobile pre-flight path is different code (`checkDeclaredDictionary` in
`dogwood-host` rather than `WebDelivery`), so it is a different claim on a different client.
`slice-android` and `slice-ios` put their `Material3Binding` registration behind a Gradle property
`dogwoodMaterial3` (default `true`); `run-preflight.sh` and `run-preflight-ios.sh` gain one case
that builds the client with it `false`, serves the ordinary declared payload, and asserts refusal
before `start` naming `androidx.material3`, with the ordinary build as the control.

### 1.3 `G6` — the guest script has a budget

`from_web_weight.py` gains a sibling that measures `guest-kotlin.js` brotli. The budget goes in
`budgets.tsv` under the same attribution rule as `G5`, set with the same headroom, and the first
number is measured rather than guessed. The catalogue added guest code that nothing bounded.

### 1.4 Two documents the proof plan promised

`samples-standalone/umbra/REFERENCE.md` points at the Material tab as the worked example.
`developer-experience.md` §4 stops saying preview is planned only once Group 4 lands; until then it
gains one sentence pointing at Group 4's plan. §1's vocabulary table already says 79.

**Effort:** two days, of which the iOS drill is most.

---

## Group 2 — M4: the mapping table's first growth, and three more tiers

### 2.1 Omit, don't quote

The 26 internal-default exclusions share a cause the generator can remove without touching the
mapping table. A host binding quotes a library default *because* the guest might not send the
parameter — but for a **host-default-only** parameter the guest can *never* send it, and a Kotlin
call that simply omits the argument gets the library's default, internal or not. Quoting is only
needed for *settable* parameters, whose absence is the sentinel.

So: the emitter omits host-default-only arguments instead of quoting them, by name. The exclusion
survives only where the internal default is on a settable parameter, which the report will count.
Expected outcome, to be measured rather than promised: most of the 26 return, `BasicText` and
`NavigationBar` among them.

### 2.2 The table grows, by measured demand

In the order the report's "cannot cross the boundary" reasons rank them:

| Type | Crossing | Unlocks |
|---|---|---|
| `ClosedFloatingPointRange<Float>` | two floats | `RangeSlider` (three overloads) |
| `GridCells` / `StaggeredGridCells` | `Fixed(n)` or `Adaptive(dp)` as a kind and a number | the lazy grids' *signature* (their content stays unreachable; the parameter stops being the blocker for a future lazy-slot shape) |
| `BorderStroke` | width and colour | `OutlinedCard`'s border, chips' borders — today host-default-only |
| per-corner `Shape` | four radii | every `shape` parameter, today a single token |
| `*Colors` (`ButtonColors`, `CardColors`, …) | **named tokens only** — the colour scheme roles, never literals | the tier's most-asked-for parameter, in the form that keeps a payload themable by the host |

Each row lands with a factory on the guest side, a reader on the host side, a `ClassifierTest` case
and a render test; the coverage report's parameter columns are the measure.

### 2.3 The foundation, layout and ui tiers

Segments 254, 253 and 252 per ADR-072 D-B, generated by the same task shape as Material 3, in **one
new module `dogwood-foundation`** rather than three — they share a classpath and a page-weight
decision, and a host that wants any of them wants the layout one. The primitive tier stays, as
D-I says; the manuals point new code at the generated `Column` once it is on a device.

Registered by every sample; the web measurement repeated under ADR-066's rule and the `G5`
attribution written before the budget moves.

### 2.4 The catalogue grows with it

A section per new family in `MaterialScreen.kt` (renamed to the generated-tiers catalogue), the
coverage test's floor raised to what was measured, the `M` claims re-run on three clients.

**Done means:** the report's bound count moved and says by how much; `MaterialScreenCoverageTest`'s
floor moved with it; `G5` re-attributed; ADR-072 amended with M4's numbers.
**Effort:** five to seven days. **Gate:** the coverage report's delta, which is committed.

---

## Group 3 — M5: holders for the library's state

ADR-043 gives a holder a shape, a guest object and a host mirror, and the design system's holders
arrived that way with a `@Holder` annotation. A library cannot be annotated, so the tier gets a
**library shape table keyed by type name**, the same way its affordance rule is keyed by parameter
name. Eleven types, in the order of what they unlock and what already exists:

| Type | Shape | Note |
|---|---|---|
| `PagerState` | the existing pager shape (six) | `PagerMirror` already exists for the primitive pager; the library's `HorizontalPager` binds to it |
| `SnackbarHostState` | the existing snackbar shape | `SnackbarMirror` exists; `SnackbarHost` binds to `SnackbarArea`'s holder |
| `DrawerState` | open/closed target, `byUser` report | two drawers |
| `SliderState`, `RangeSliderState` | value(s), `onValueChangeFinished` as event | the state-holder overloads of the sliders |
| `TimePickerState`, `DatePickerState` | chosen value as ISO-8601 text, per the picker precedent | the picker dialogs' content |
| `ToggleableState` | an enum, not a holder — it is a value | tri-state `Checkbox`; one line |
| `SearchBarState` | expanded/collapsed target and report | `SearchBar` |
| `CarouselState` | current item, like the pager | three carousels |
| `SwipeToDismissBoxState` | dismiss direction as an event | one component |
| `TooltipState` | shown target | the tooltip family |
| `SubcomposeLayoutState` | **not bound** | a layout primitive with no product meaning across a boundary; recorded, not attempted |

Each holder ships with its component: a render test that drives it through the screen and reads
the report back, a catalogue entry with a witness, and the report row moving from bespoke to bound.

**Done means:** the bespoke count down by the number of components behind the holders that
landed, each one graded on a device. **Effort:** six to eight days.

---

## Group 4 — M6: a payload screen in a preview, with no protocol between it and Compose

`developer-experience.md` §4 says what this is and says it is not built. The design is fixed by
Layer 1: the authoring module compiles twice from one source set, and the JVM path links a
`dogwood-compose-preview` that delegates to real Compose instead of recording.

For the generated tiers this is nearly free, because the guest stub and the library function have
the *same signature by construction*: the generator emits, from the same parse, a JVM package with
the same names whose bodies call `androidx.compose.material3` directly. For the primitive tier and
the design system it is a hand-written delegate per component, which is the work.

Two steps, and the first is the one that matters:

1. **A desktop preview window.** `./gradlew :samples:slice-screens:preview -Pscreen=material`
   opens the catalogue in a Compose Desktop window from the JVM compilation — no Zipline, no
   wire, no host tree. Done means the window opens and a screenshot of it is in the manual beside
   the same screen rendered through the protocol, with the differences named (there will be some;
   that is the point of the paragraph in §4 about what a preview cannot show).
2. **The Android Studio pane.** Layer 1 already records that a `jvm` target alone does not drive
   it; an Android library variant of the preview module does. Second, because the desktop window
   proves the delegation and the pane is packaging.

**Done means:** step 1 on the catalogue and on the About screen; `developer-experience.md` §4
rewritten from "planned" to what it shows and what it cannot. **Effort:** five days.

---

## Group 5 — M7: the manuals, and the two things the guest check does not do

- `docs/getting-started.md` §2–3 and `developer-experience.md` §1 written around the generated
  tiers as the default vocabulary, with the design-system path as the way to add what a library
  does not have — the inversion generator v2 made and the manuals have not yet followed.
- The guest check names the controlled-text-field overloads (`TextField`, `OutlinedTextField`,
  `BasicTextField`) and says to use `TextInput` — a better error than "unresolved reference" for
  the one family a Compose developer will reach for first.
- The guest check inspects the module's resolved classpath for the artifacts Layer 1 forbids
  (`animation-core` and its kin), which is the half ADR-050 §4 deferred. A Gradle task reading the
  configuration, not a source scan; one banned artifact is added to a fixture module and watched to
  fail.

**Effort:** two days.

---

## Group 6 — Delivery and operations: the engineering halves of §5 and §6

The owner owns the account, the keys and the hosting. Everything a mechanism can do before those
exist is here, so that when they exist the remaining step is entering a credential.

### 6.1 The Central Portal transport

`com.vanniktech.maven.publish` configured for the Central Portal on every published module,
credentials from `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD`, signing from the existing
`DOGWOOD_GPG_*` environment. Done means `publishToMavenCentral` exists and, with no credentials,
the dry-run task assembles exactly the bundle the Portal validates — checked against the Portal's
published requirements (POM completeness, sources, javadoc, checksums, signatures) by a script
that opens the bundle, since the Portal cannot be asked without an account.

### 6.2 The rotation runbook, and its drill

`docs/keys.md`: generating a key somewhere it can be held, naming the owner, the roll-forward
rotation (publish dual-signed manifests until every client trusts the new key, *then* drop the
old entry — the second step is the one to finish), and the revocation answer. Then the drill that
makes it a procedure rather than prose: `tools/reference-server/rotation-drill.sh` publishes
dual-signed, moves a client's trust to the new key, drops the old signature, and shows the client
still updating and a client holding only the old key stopping at exactly that moment. `B2` grades
half of this today; the drill grades the half nobody has run.

### 6.3 Publish as one reviewable step

`.github/workflows/publish-payload.yml`, manual dispatch: build the payload, stamp the version from
the input, sign with `DOGWOOD_SIGNING_KEY` / `DOGWOOD_ROTATION_KEY` from secrets (falling back to
the throwaway keys **only** when an input says this is a dry run, and saying so in the job
summary), run the reference-server check against the result so the cache split and
`Content-Encoding: br` are asserted on the artifact that would ship, and upload the signed bundle
as a workflow artifact. Where it is served from stays the owner's; the workflow ends at a bundle
somebody can put anywhere.

### 6.4 Staged rollout, on the reference server

`InstallCohort` gives every installation a stable bucket 0–99 and nothing consumes it — the
client computes it and sends nothing, which is the whole of "the client's half". Two small pieces:
the delivery path sends the bucket on the manifest request (a query parameter, so a static server
can ignore it and a cohort-aware one can route on it), and the reference server gains a
`cohorts.json` mapping bucket ranges to manifest variants and serves the matching one. A drill
publishes a bad release to buckets 0–9 only and shows the other ninety installations untouched.
Small, and it turns "a server's decision" into a demonstrated one.

**Effort:** four days across the four.

---

## Group 7 — Verification: the two gaps that widen every other claim

### 7.1 Tier C in continuous integration

The repository is public, so GitHub's macOS runners are free, and both device drills can run
there: a macOS job boots a simulator and runs the iOS accessibility, Material, skew and pre-flight
drills plus the web drills in headless Chrome; an ubuntu job runs the Android drills on an emulator
(`reactivecircus/android-emulator-runner`, with the KVM the runners now have). Nightly and on
demand rather than per pull request, because it is forty minutes of machine time and Tier S
already blocks merges. Done means the matrix in `plans/conformance.md` Part 3 is regenerated by
the workflow, the `result-*.conf` files arrive as artifacts, and the README carries a third badge.
`docs/checks.md` "Enforcement — the honest state" is rewritten to the new state.

### 7.2 Engine skew, and the per-version build matrix

Audit `A6` is blocked on two engine versions existing. They exist the moment one is tagged: the
drill checks out `v0.1.0` in a worktree, builds the **host** there, builds the **payload** at
`HEAD`, and serves one to the other — graded as `K3`/`K4` beside `K1`/`K2`, on the desktop first
because it needs no device. The tag is the first Maven Central publish, or an annotated tag made
for this on the day the drill lands, whichever comes first.

Layer 2's Milestone 4 — one artifact per supported client dictionary — is the payload half of the
same drill: the generator run with an **older lock** (`--lock` pointing at the lock as of the
tag) emits the stubs that dictionary had, and a payload built against them is the per-version
artifact. Done means the drill builds two payloads from one source tree, keyed by their declared
vectors, and each is accepted by the host it was built for and refused pre-flight by the other.

**Effort:** four days.

---

## Group 8 — What engineering can prepare, and what only the owner can do

**Item 15, the upstream drafts.** Five stay unfiled by instruction. One engineering follow-up: the
web dialog finding says a synthesised pointer cannot operate a Compose dialog, and cannot say
whether a real one can. Group 7.1's macOS job can run Chrome **headed** on the runner's display,
and one run there with a real `xdotool`-style pointer either confirms the finding or narrows it to
headless mode. Either answer improves the draft.

**Item 16, the owner's, with what can be readied:**

| Decision | What engineering prepares | What only the owner can do |
|---|---|---|
| Apple Guideline 4.7 | `docs/apple-4.7-inquiry.md`: the Developer Technical Support inquiry text, describing the mechanism exactly and citing the conditions | file it; put the answer in ADR-003 |
| The gate device | nothing further; the harness runs on any device and writes its file | acquire one and run it, or keep the risk |
| The Pixel pause outlier | nothing further; `--es experiment pauses` exists | one run on that Pixel |
| Central account and GPG key | Group 6.1 | create them; enter two secrets |
| Production keys and hosting | Group 6.2 and 6.3 | generate, hold, choose where to serve |
| The iOS organisation's written yes | nothing | a conversation |

**Design positions, deliberately not planned:** origin isolation on the web (ADR-032, a recorded
position); Android rendering in instrumented rather than unit tests (Part 7, a settled reason);
per-component binding `D1` (resolved by measurement — the tier is registered whole).

---

## 9. Order, gates and estimate

**Order.** Group 1 first, because it closes a plan already merged and the iOS drill is a defect.
Then Group 7.1, because every device claim that follows should be graded by a machine rather than
by whoever has a simulator booted. Then Groups 2 and 3 together, which are the bulk and are the
same kind of work. Group 6 alongside them, because it does not touch the generator. Group 4, then
5, then 7.2 last — it wants a tag, and the tag wants Group 6.1.

| Group | Effort | Gate watched to fail by |
|---|---|---|
| 1 | 2 days | the iOS drill's per-claim budget, by removing a witness |
| 7.1 | 2 days | a deliberately failing claim on the nightly run |
| 2 | 5–7 days | the coverage report's committed delta; the coverage test's raised floor |
| 3 | 6–8 days | each holder's render test with its mirror stubbed out |
| 6 | 4 days | the rotation drill with the second step skipped; the publish workflow in dry run without the flag |
| 4 | 5 days | a preview of a screen with a deliberately wrong stub |
| 5 | 2 days | a fixture module with a banned artifact on its classpath |
| 7.2 | 4 days | the older payload served to the newer host, refused |

About six working weeks of engineering. Every group ends with its ADR amended or written, the
manuals it touched updated, and the conformance matrix regenerated — the three things the last two
plans found were easier to promise than to remember.

**What this plan does not do.** It does not decide the owner's items, and it does not file
anything upstream. It does not attempt `SubcomposeLayoutState`, lazy content slots, or assets
across the boundary; those are the bindability rule's actual boundary, and the coverage report will
keep saying so.

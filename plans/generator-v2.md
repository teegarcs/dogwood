# Generator v2: the Compose surface, generated

Adopted 2026-09-15. This is the plan for the thing the roadmap has called "generator v2" since
Phase 3 and the thing every coverage figure in this repository has been quoting as a ceiling
rather than a floor: **a generator that reads the Compose Multiplatform sources the host is
compiled against and emits guest stubs, host bindings and a dictionary segment for every widget the
bindability rule accepts.** With it, a payload calls `Button`, `Switch`, `Card`, `Scaffold` and
`TopAppBar` the way it calls `Column` today, and the "two-thirds of Compose" sentence becomes
something measured on the pinned version rather than a projection from an androidx commit.

It follows the house rule: **done means run**, every number is measured on the version the host
resolves, and each first run's defect is recorded where it was found.

---

## 0. What is known before a line is written

Everything below was established on 2026-09-15 against the artifacts `engine/` actually resolves,
not against a checkout of androidx.

**The versions.** `compose.material3` in Compose Multiplatform 1.10.3 resolves to
`org.jetbrains.compose.material3:material3:1.9.0` — Material 3 is versioned independently of the
rest since 1.10 — while `foundation`, `foundation-layout`, `ui`, `ui-text`, `ui-graphics` and
`ui-unit` resolve at 1.10.3. Every one publishes a `-sources.jar` on Maven Central, organised by
source set (`commonMain/androidx/compose/…`). The common source set is the surface every host
shares, and it is the only one v2 reads.

**The denominator, at those versions.** Public, uppercase, `@Composable` functions in `commonMain`:

| Module | Composables | Distinct names |
|---|---:|---:|
| material3 1.9.0 | 171 | 106 |
| foundation 1.10.3 | 46 | 19 |
| foundation-layout 1.10.3 | 12 | 9 |
| ui 1.10.3 | 12 | 9 |
| **total** | **241** | **143** |

(A quick regex count; M1 replaces it with the parser's own.) The metalava measurement in
[ADR-005](../adrs/layer-5/ADR-005-corrected-coverage-and-bespoke-subsystem-list.md) counted 445
across ten androidx modules including `material` (Material 2) and `animation`, neither of which the
host links. 241 is the honest denominator for a host built on these artifacts.

**The defaults problem is smaller than the roadmap feared, for one reason.** The host binding for a
generated component calls the *real* library function, in host code, with the real library on the
classpath. So a default expression like `colors: ButtonColors = ButtonDefaults.buttonColors()` does
not need to be *understood* by the generator — it needs to be *copied* into the binding as the
argument to pass when the guest sent nothing. `Button`'s nine optional parameters default to
`Modifier`, `true`, `ButtonDefaults.shape`, `ButtonDefaults.buttonColors()`,
`ButtonDefaults.buttonElevation()`, `null`, `ButtonDefaults.ContentPadding` and `null`: every one is
public API the host can evaluate. Reading source rather than a signature dump
([ADR-002](../adrs/layer-5/ADR-002-standalone-codegen-tool-not-ksp.md)) was the right call for
exactly this reason, and the sources jars make the source the pinned artifact's own.

**Type resolution is mostly imports.** The roadmap said v2 "needs resolved types rather than
declaration text". For the *host* binding it needs only that the names in a signature resolve in
the generated file, which they do if the generated file carries the same imports as the library
file it was generated from. For the *guest* stub the types are Dogwood's own by a mapping table,
and a type the table does not know is a parameter the guest cannot set rather than a component
the guest cannot call. A full analysis-API resolution is not required for M1–M4 and is listed as a
risk, not a prerequisite.

---

## 1. The decisions, taken here and recorded in ADR-072

**D-A. The source of truth is the sources jar of the artifact the host resolves.** A Gradle
configuration in `dogwood-codegen` resolves `-sources.jar` for the pinned modules; the generator
reads `commonMain/` out of them. A library upgrade is a version bump in `libs.versions.toml`, a
regeneration, and whatever the lock then refuses. Nothing is vendored and nothing is a checkout.

**D-B. Generated tiers take segment identifiers from the top of the range.** Products allocate
upward from 2 (`FIRST_PRODUCT_SEGMENT`); Dogwood's generated tiers allocate downward from 255:

| Segment | Wire name | Identifier |
|---|---|---:|
| Material 3 | `androidx.material3` | 255 |
| Foundation | `androidx.foundation` | 254 |
| Foundation layout | `androidx.foundation.layout` | 253 |
| UI | `androidx.ui` | 252 |

`LAST_PRODUCT_SEGMENT = 200` is declared and the registry refuses a product above it, so the two
allocation directions cannot meet by accident. Segment 0 (the hand-written primitive tier) and 1
(Dogwood's design system) are unchanged; a payload may keep using them.

**D-C. A tier's version is the library's, encoded.** `androidx.material3` at 1.9.0 is version
`10900`; at 1.9.1, `10901`. A payload declares it in its signed manifest exactly as it declares any
segment ([ADR-061](../adrs/layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md)), and a
host behind it refuses before `start`. The lock is committed per tier beside the generator, and the
lock rules are the ordinary ones: a component the new library version removed keeps its tag
retired, and the host renders it as an unknown widget with a report, which is the containment rule
already graded as `A2`.

**D-D. Absence is the sentinel, and the host evaluates the library's own default.** Every optional
parameter is `= null` on the guest stub. When the property is absent, the host binding passes the
default expression copied verbatim from the library source. A parameter whose *type* the mapping
table cannot cross but which has a default is **omitted from the guest stub** and passed its
default always: the component stays callable and the reference says the parameter is not settable
from a payload. A parameter whose type cannot cross and which has *no* default makes the component
unbindable, and it is excluded with the reason recorded — the same refusal ADR-068 made loud for
product surfaces, applied at scale and reported rather than failing, because a library is not a
surface an author can edit.

**D-E. The type mapping table is the whole of v2's guest-side design**, and it is small on day one
by intent:

| Library type | Guest type | Wire | Host reads |
|---|---|---|---|
| `String`, `Boolean`, `Int`, `Long`, `Float`, `Double` | the same | primitive | the same |
| `Dp` | `Dp` (value class over `Float`, `Int.dp`/`Float.dp`) | number | `.dp` |
| `TextUnit` | `TextUnit` (`sp`, `em`) | `[value, unit]` | `.sp`/`.em` |
| `Color` | the existing recipe | recipe | `resolveColor` |
| `Shape` | the existing recipe, plus per-corner rounded and cut-corner factories | recipe | evaluator |
| `PaddingValues` | `PaddingValues(start, top, end, bottom)` | `[s,t,e,b]` | `PaddingValues(…)` |
| `Arrangement.Horizontal` / `.Vertical` / `.HorizontalOrVertical` | the existing `Arrangement` | string | the existing resolver |
| `Alignment`, `.Horizontal`, `.Vertical` | the existing enumerations | ordinal | the existing resolvers |
| `FontWeight`, `TextAlign`, `TextOverflow`, `TextDecoration` | the existing tokens | string | the existing resolvers |
| `Modifier` | the existing chain | chain | `composeModifier` |
| `@Composable () -> Unit` and `@Composable Scope.() -> Unit` | slot | children tag | `RenderChildren` |
| `() -> Unit`, `(Boolean) -> Unit`, `(Int) -> Unit`, `(Float) -> Unit`, `(String) -> Unit` | event | event tag | `events.send` |
| `MutableInteractionSource?`, `*Colors`, `*Elevation`, `BorderStroke?`, `WindowInsets`, `TextStyle`, `ScrollBehavior?` | **not settable**: omitted from the stub, host default always | — | the default expression |
| `Painter`, `ImageVector`, `ImageBitmap` (required) | unbindable | — | — |
| `*State` holders (required) | unbindable until a `@Holder` shape exists | — | — |
| `LazyListScope.() -> Unit`, `DrawScope.() -> Unit`, in-frame scopes | unbindable, structurally | — | — |

M4 grows the table by measured demand — `BorderStroke`, `ButtonColors` and `TextStyle` are the
three most likely, each a recipe factory — and every row added is a row in the reference.

**D-F. Overloads are disambiguated by parameter names, not by position.** 106 names cover 171
material3 composables. The guest keeps Kotlin overloads; the dictionary needs one entry per
signature. An entry is named `Button` for the first declaration in a file and `Button~<hash>` for
each further one, where the hash is over the *parameter names* in order — stable across a library
version that reorders declarations, and different for a version that adds a parameter, which is a
retype the lock already catches.

**D-G. The primitive tier stays, and the two coexist.** Segment 0's `Column`, `Row`, `Box`, `Text`
are hand-written with Dogwood's own arrangement, alignment and text-override vocabulary, and
`androidx.foundation.layout` will generate a second `Column` with the library's exact signature.
Both are callable. A payload picks by import. The hand-written tier is not retired, because every
payload in the field uses it and because it carries things the generated one will not (per-side
padding as an animated target, `Row(onClick)`). When the generated tier is proven, the manuals
point new code at it and the primitive tier becomes what it was always meant to be: the floor.

**D-H. Page weight is measured before it is decided.** A generated material3 binding references
all 171 composables, which defeats dead-code elimination for the whole library on the web. M3
measures three builds per [ADR-066](../adrs/layer-5/ADR-066-the-pickers-cost-half-a-second.md)'s
attribution rule — without the tier, with the tier registered, with the tier linked but
unregistered — and puts the numbers in front of the owner with the three options: raise `G5` with
attribution; let the web profile register the tier optionally (a profile asymmetry the owner
declined for the design-system catalogue, and a different question at this size); or take up `D1`,
per-component binding. **No default is chosen here.**

**D-I. Preview is generated from the same parse.** The Layer 1 Milestone 3 design — compile the
payload a second time for the JVM against real Compose — becomes tractable once the generated
stubs mirror real signatures: a preview emitter forwards each stub to the library function, with a
conversion layer for Dogwood's own value types. M6, after the tier itself is proven on a device.

---

## 2. Milestones, each with a gate that is a run

| # | Milestone | Gate |
|---|---|---|
| **M0** ✅ | This plan; ADR-072; the consolidated backlog below | committed 2026-09-15 |
| **M1** ✅ | **Sources in, surface out.** A `composeSources` configuration resolves the pinned sources jars. The parser reads a library tree (many files, `commonMain` only, `expect`/`actual` filtered, `internal` and `private` excluded, deprecated recorded). The v2 classifier applies D-E and produces `tools/generator-v2/coverage.md`: per module, per component, bound / not-settable parameters / unbindable with reason | the report is regenerated by one Gradle task; its totals are the numbers every document quotes from now on |
| **M2** ✅ | **Material 3, generated and rendering** (2026-09-15: 80 of 186 bound; four render tests on three targets; the About screen's block graded by the Android accessibility drill at 19 of 19).** A new engine module `dogwood-material3` holds the generated host bindings for segment 255 and compiles on every host target; the guest stubs compile into `dogwood-compose`; the segment registers as a built-in on mobile and desktop; a payload composing `Card { Switch(); Checkbox(); Button { Text() } }` renders on the desktop host and the About screen carries one such block for the device drills. Excluded components are listed with reasons. | the standalone check, the skew drills and tier S pass with the tier present; the About screen renders the block on Android and iOS |
| **M3** ◐ | **The page-weight decision, measured** (2026-09-15: without 3,770,785; linked-unregistered 3,772,894; registered 3,929,685 — 29,685 over the ceiling). **The owner decides**; the three options are in `OPEN-DECISIONS.md` §7 and the web sample leaves the registration parked with the numbers beside it | `budgets.tsv` and ADR-066's rule honoured, whatever is decided |
| **M4** | **Foundation, layout and UI tiers**, and the mapping table's first growth: `PaddingValues`, `BorderStroke`, `ButtonColors`, per-corner shapes | coverage report shows the delta; render tests per added factory |
| **M5** | **Holders for the library's state types.** `SliderState`, `TooltipState`, `DrawerState`, `SearchBarState` — each a `@Holder` shape and a mirror, arriving with its widget | the shape table grows; the component moves from unbindable to bound in the report |
| **M6** | **Preview.** A JVM/Android preview emitter from the same parse | a payload screen renders in a preview pane with no protocol between it and real Compose |
| **M7** | **Manuals and the authoring check.** `docs/api/androidx.material3.md` generated; getting-started and developer-experience rewritten around the tier; the guest check learns the controlled-text-field overloads by name | link check, render-shape, tier S |

**What "fully built" means, so it can be checked rather than declared:** M1 through M5 done and
gated, the coverage report reproducible, and the vocabulary table in `developer-experience.md` §1
replaced by the report's own totals. M6 and M7 are what makes it *pleasant*; M1 to M5 are what
makes the sentence true.

---

## 3. Risks, named so nobody rediscovers them

- **A default expression that references something not public.** The binding will not compile,
  and the generator cannot know without resolution. M1 emits; the module compile is the check; a
  committed `exclusions.txt` beside the tier names the component and the reason, and the report
  counts it. This is the one place "generate, compile, refuse" replaces "refuse at parse".
- **`inline` composables with reified or non-composable lambda parameters.** `Column` is `inline`
  and takes a `@Composable ColumnScope.() -> Unit`; calling it from a binding is ordinary. An
  inline function with a non-composable lambda that the binding cannot supply is unbindable and
  reported.
- **Overloads that differ only in a type the table erases** — two `Slider`s, one `Float` and one
  `SliderState` — map to one guest signature. D-F keeps their dictionary entries distinct; the
  guest stub for the erased one is not emitted, and the report says which won.
- **Experimental API annotations.** Much of material3 is `@ExperimentalMaterial3Api`. The
  generated bindings opt in file-wide; the reference marks each such component so an adopter knows
  which ones the library may change under them.
- **Page weight.** D-H. Not a risk to the engineering, a decision the engineering will put a
  number on.
- **Memory on the build machine.** A tier module of 171 composables on five targets is a large
  compile; the standalone check and the gate already run with two workers, and M2 will be measured
  for what it adds.

---

## 4. Everything else that was deferred, in one table

Gathered 2026-09-15 from the backlog, the audit, the roadmap's Phase 7 carry-over, the open
decisions and every ADR's unstated-assumptions section. Disposition is what happens to it now.

| Item | Recorded in | Disposition |
|---|---|---|
| Generator v2 | roadmap Phase 3; ADR-069 | **This plan.** |
| `@Preview` / JVM compilation of a payload | Layer 1 Milestone 3; developer-experience §4 | **M6 of this plan** — tractable only once stubs mirror real signatures |
| Per-component binding (`D1`) | engineering backlog; ADR-066 | **M3 of this plan** — the measurement that decides it is the same measurement |
| Remaining live-state holders (~27) | roadmap Phase 7; ADR-043 | **M5**, arriving with their widgets |
| `SkewKind` for unknown arrangement / text names (ride `unknownTextStyles` with a prefix) | ADR-069 §4 | **Now** — a distinct kind, `UNKNOWN_NAME`, one line plus the drift test |
| `clickable` with a semantics role; `border` with a shape; animated per-side padding | ADR-069 §4 | **Now** — one tag each |
| Snackbar `dismiss()` | ADR-051 §4 | **Now** — a target property on the shape |
| Web guest script integrity: hash in the signed sidecar, fetched bytes verified, Worker from a blob | ADR-032; ADR-062; `docs/security.md` §4 | **Now** — the one open item in the threat model that is engineering rather than a design position |
| Maven Central publishing: `signing` plugin wired, credentials from the environment | OPEN-DECISIONS §5 | **Now**, the engineering half; the account and the GPG key stay the owner's |
| Layer 1 spec's bindability rule does not mention enumerations; ADR-032 does not say the Worker transport is a library | noted 2026-09-14 | **Now** — two paragraphs |
| iOS sample wires no navigation service, so `J2`/`J4` are ungraded there | audit B5 | **Now** — the sample gains the service; two cells turn green |
| Guest check does not inspect the classpath | ADR-050 §4 | Later; a dependency-graph walk, and the direct-name check catches what actually happens |
| Cross-version drill: payload at engine N on host N−1 | audit A6 | **Blocked until two engine versions exist.** The first published version creates the second |
| Staged rollout by cohort | ADR-049 | A server's decision; `InstallCohort` is built; nothing here to do until there is a server |
| Origin isolation on the web | ADR-032; security §4 | A design position, recorded; not planned |
| Apple Guideline 4.7 ruling; Phase 0 gate device; upstream reports; payload hosting; signing keys | OPEN-DECISIONS §2, 3, 4, 6 | **Owner's.** Not engineering |
| Web first-visit weight | OPEN-DECISIONS §7 | **Reopens at M3** with a larger number |

---

## 5. How the work is run

Three tracks in parallel, on one branch, with file ownership so they cannot collide:

- **Track V2** — M1 then M2: `engine/dogwood-codegen` (a `v2` package), a new
  `engine/dogwood-material3` module, generated stubs into `dogwood-compose`, the coverage report.
- **Track SB** — the "Now" rows of §4: `dogwood-host`, `dogwood-compose`, `dogwood-web`, the
  iOS sample, the publishing configuration, two documents.
- **Track D** — ADR-072, the manuals, and the integration: reading both tracks' reports, running
  the gate, and writing what happened rather than what was planned.

Every track's first run finds a defect; the plan expects it and the ADR records it.

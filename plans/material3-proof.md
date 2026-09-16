# Material 3, proven: the tier on every screen, declared by every payload, upgradable by anyone

Drafted 2026-09-16. Status: **plan, not started.** Nothing below has been implemented; every
"will" is a commitment and every number in §0 is a measurement taken before writing.

[`plans/generator-v2.md`](generator-v2.md) delivered a generated Material 3 tier — 80 of the
library's 186 composables bound, compiling on every host, registered by every sample — and then a
review of the merged result asked three plain questions and got three honest answers:

| Question | Answer on 2026-09-16 |
|---|---|
| Do the sample applications use much of what was generated? | **Six components** on one screen block. 74 bound components have compiled and never been seen on a screen. |
| Is upgrading Material and regenerating documented? | **One paragraph in a plan** (D-A). The version is pinned in three places by hand, and no check says they agree. |
| Is backwards compatibility documented? | **Designed, not documented.** The mechanism is in the lock and the pre-flight check; nothing states what a reader on call, or a reader upgrading, can rely on. |

This plan closes all three, in four items, and the first one is deliberately the largest: a
tier that has only ever rendered a Switch and a Button is a tier whose other 74 bindings are a
hypothesis. The house rule applies throughout: **done means run**, on a device, with the verdict
recorded where a check can re-read it.

---

## 0. What is known before a line is written

Everything here was read off the repository at commit `8b232e3`, not remembered.

**The tier.** Segment 255, version 10900, 80 bound Material 3 composables in 73 distinct names
(`tools/generator-v2/coverage.md`). Seven names carry two bound overloads each: `BottomAppBar`,
`Card`, `ElevatedCard`, `OutlinedCard`, `ExtendedFloatingActionButton`, `Slider`, `Tab`. The two
`Slider` stubs have **identical parameter names and types in a different order**
(`steps, onValueChangeFinished` against `onValueChangeFinished, steps`), so a call that supplies
only the two required parameters is ambiguous to the Kotlin compiler. That is a generator defect
that no test caught because no payload has called `Slider`. It is the first thing §1 will hit, and
it is recorded here so that finding it counts as expected rather than surprising.

**What the samples use.** One block on `AboutScreen.kt` in `slice-screens`, the guest module every
client renders: `Card`, `Switch`, `Checkbox`, `Button`, `LinearProgressIndicator`, Material 3's
`Text`. The Android accessibility drill graded that block on a device. No other guest code in the
repository imports `dev.dogwood.compose.material3`, including Umbra.

**What the samples declare.** `slice-guest` writes its declared segments into the signed Zipline
manifest by reading the generator's own outputs — `DogwoodSegments.kt` for the built-ins and
Acme's dictionary JSON — and *does not read* `generated/dogwood-material3/dictionary/androidx.material3.json`,
which exists. The web sample's committed sidecars name `androidx.layout` and, in one of them, a
`dogwood.designsystem` version that the sidecar's own comment admits is stale. Consequence: a host
without the tier does not refuse these payloads before `start`; it renders the About block as
placeholders with a skew report. That path works and is graded (`A2`), but it is not the path
[ADR-072](../adrs/layer-5/ADR-072-the-compose-surface-is-generated-from-the-artifact-it-binds.md)
describes for a library tier.

**Where the web sample stands.** `web-slice` links `dogwood-material3` and leaves the
registration commented out, with the three page-weight numbers beside it, pending the owner's
decision in `OPEN-DECISIONS.md` §7: registered, the page is 29,685 bytes over the `G5` ceiling.
`G5` measures the WebAssembly modules, `app.js` and `index.html`; **it does not measure
`guest-kotlin.js`**, so a larger guest screen moves no budget.

**Where the version lives.** Four places, three of them hand-typed:

| Place | Says | Kept in step by |
|---|---|---|
| `engine/dogwood-codegen/build.gradle.kts`, the sources coordinates | `org.jetbrains.compose.material3:material3:1.9.0` | a person |
| the same file, `generateMaterial3`'s `--version` argument | `10900` | a person |
| `dogwood-codegen/.../v2/Main.kt`, `PINNED_VERSIONS` (for the coverage report's header) | `1.9.0` | a person |
| `engine/dogwood-material3/androidx.material3.lock.json` | `10900` | the generator, from the argument above |

And a fifth that none of them checks against: **what the host actually compiles**. The host gets
Material 3 through the Compose Multiplatform plugin's `compose.material3` alias, which the plugin
maps to a Material 3 version of its choosing (1.10.3 → 1.9.0). A Compose Multiplatform bump that
moves that mapping to 1.9.1 would leave the generator reading 1.9.0 sources for a host linking
1.9.1, and nothing would say so.

**How each client is observed today.** Android: an instrumented test walks the accessibility tree
through `UiAutomation`, finds controls by their spoken label, performs actions and re-reads.
iOS: a drill inside the application walks `accessibilityElements` with VoiceOver on. Web: a
Python harness reads `Accessibility.getFullAXTree` over the DevTools protocol and synthesises
clicks. Desktop: a `RenderTranscript`, one line per composed node with its box. All four print the
`CONF` grammar that `tools/conformance/aggregate.py` reads; claims live in
[`plans/conformance.md`](conformance.md) by family and in `tools/conformance/claims.tsv` by test.
The Android drill scrolls to find things and has been fooled twice by a screen that buried them.

**What the tier's own tests cover.** Four render tests in `dogwood-material3`'s shared
`renderTest` source set (JVM, iOS simulator, WebAssembly): a Switch event with its argument, a
Button on defaults, a Card's slot, an affordance withheld. Three guest tests in `dogwood-compose`.

**Compatibility, as built.** Tags are permanent; the lock refuses a retype and refuses reuse of a
retired tag (`Lock.kt`); a removed component keeps its tag and goes into `exclusions.txt`; a
payload that declares a segment the host lacks, or a version the host is behind, is refused
before `start` (`checkDeclaredDictionary`, on every client); a payload that declares nothing
degrades through placeholders and reports. The host evaluates the library's default for any
parameter the payload did not set — which means **an older host renders an older default**, and
nothing in the documentation says that.

---

## 1. The Material screen: every bound family on every client, with witnesses

### 1.1 What it is

A fourth tab in `slice-screens` — the guest module all four clients render — called **Material**,
built entirely from the generated tier and organised as a **catalogue with sections**. The section
picker is itself a row of `FilterChip`s, so navigating the catalogue is the first proof. One
section is composed at a time, which keeps every screen short enough that the Android drill's
scroll never has to search for anything, the lesson `AboutScreen.kt` records twice.

Every interactive component gets a **witness**: a `Text` immediately beside it whose content is a
function of the component's state, with a stable, unique, greppable label. `Button` increments
`m3.button=3`; `Switch` writes `m3.switch=on`; `Slider` writes `m3.slider=0.60`; `PrimaryTabRow`
writes `m3.tab=2`; an `AlertDialog`'s confirm button writes `m3.dialog=confirmed`. The witness is
what every drill asserts on, because it is the same observation on four clients that otherwise
have four different instruments: *the control was found by its label, activated through the
platform's accessibility layer, and the screen changed accordingly.* That is AGENTS.md §1.5's rule
— prefer the consequence to the property — applied as a screen design.

### 1.2 Coverage, by section

The target is **every bound family, and at least 60 of the 80 bound composables** composed on the
screen. Not all 80: `PermanentNavigationDrawer` and `ModalWideNavigationRail` take the whole
viewport and would make the section they sit in un-drillable, and two of the seven overload pairs
differ only in a slot. The table is the commitment; the count is checked by a test (§1.4).

| Section (chip label) | Composables | Witness |
|---|---|---|
| **Buttons** | `Button`, `ElevatedButton`, `FilledTonalButton`, `OutlinedButton`, `TextButton`; `FilledIconButton`, `FilledTonalIconButton`, `OutlinedIconButton`; `FilledIconToggleButton`, `FilledTonalIconToggleButton`, `OutlinedIconToggleButton`; `FloatingActionButton`, `SmallFloatingActionButton`, `LargeFloatingActionButton`, both `ExtendedFloatingActionButton` overloads; one of each with `enabled = false` | per-button press counts; toggle states |
| **Selection** | `Checkbox`, `Switch` (with `thumbContent`), `RadioButton` ×3 in a group, `Slider` (both overloads, after the fix in §1.5), `SingleChoiceSegmentedButtonRow`, `MultiChoiceSegmentedButtonRow` (rows only — `SegmentedButton` itself is unbound, so each row holds `OutlinedButton`s; the reference will say so) | checked/selected/value |
| **Chips** | `AssistChip`, `ElevatedAssistChip`, `FilterChip`, `ElevatedFilterChip`, `InputChip` (with avatar slot), `SuggestionChip`, `ElevatedSuggestionChip` | selected set, press counts |
| **Cards and lists** | `Card`, `ElevatedCard`, `OutlinedCard` — both overloads each (static and clickable); `ListItem` with every slot filled; `HorizontalDivider`, `VerticalDivider`; `BadgedBox` + `Badge`; `Label` | clickable-card counts |
| **Progress and text** | `LinearProgressIndicator`, `CircularProgressIndicator` (default and with `gapSize`); Material 3 `Text` with `fontWeight`, `maxLines`, `overflow`, `textDecoration`, `textAlign` set | none (static); rendering asserted by presence |
| **App bars** | `TopAppBar`, `CenterAlignedTopAppBar`, `MediumTopAppBar`, `LargeTopAppBar` (each with `navigationIcon` and `actions`); both `BottomAppBar` overloads | action press counts |
| **Navigation** | `ShortNavigationBar` + `ShortNavigationBarItem` ×3; `NavigationRail` + `NavigationRailItem` ×3 (with `header`); `WideNavigationRail` + `WideNavigationRailItem` ×2 | selected index |
| **Tabs** | `PrimaryTabRow`, `SecondaryTabRow`, `PrimaryScrollableTabRow`, `SecondaryScrollableTabRow`; `Tab` (both overloads), `LeadingIconTab` | selected index per row |
| **Dialogs** | `AlertDialog` (icon, title, text, confirm, dismiss), `BasicAlertDialog`, `DatePickerDialog`, `TimePickerDialog` — each opened by a `Button` | opened/confirmed/dismissed |
| **Sheets, drawers, menus** | `ModalBottomSheet`; `ModalNavigationDrawer` + `ModalDrawerSheet`; `DismissibleNavigationDrawer` + `DismissibleDrawerSheet`; `PermanentDrawerSheet` (the sheet alone, inside a bounded `Box`); `ExposedDropdownMenuBox` + `DropdownMenuItem` ×3; `Snackbar` (the composable, laid out statically with `action` and `dismissAction`); `VerticalDragHandle` | open/closed, chosen item |

Icons inside icon buttons, chips, app bars and navigation items come from the **primitive tier's**
`Icon`, because Material 3's `Icon` is asset-backed and unbound. That mixing — a Material 3
component whose slot holds a segment-0 component — is itself a claim worth having on a device, and
§1.6 grades it.

Anything in the table that turns out not to render, or not to be operable through the
accessibility layer on some client, is **not worked around on the screen**. It goes into the
plan's §5 with the client, the observation and the binding, and the generator or the binding is
fixed. A catalogue that quietly drops what does not work is the coverage report's projection all
over again.

### 1.3 The tests, in three layers

**Layer A — the bindings, in the tier's shared render tests** (`dogwood-material3/src/renderTest`,
run on JVM, iOS simulator and WebAssembly in tier S). Today: four tests. Target: **one test per
family in §1.2**, each of the same shape as the existing four — a wire tree built by hand, the
host composes it, `runComposeUiTest` finds by test tag and asserts. Each test pins the piece of
generated glue that family depends on:

- every event-carrying family sends its event on the derived tag with its argument
  (`onClick`, `onCheckedChange(Boolean)`, `onValueChange(Float)`, `onClick` per item);
- every slot-carrying family composes its slots in the right place (`navigationIcon` before
  `title` before `actions`; a `ListItem`'s five slots; a dialog's `confirmButton`);
- every family with a host-default-only parameter renders with nothing optional set;
- every family with an affordance parameter (`enabled`, `checked`, `selected`) is withheld on
  unreadable skew;
- the dialog, sheet and drawer families **open and dismiss** — `onDismissRequest` reaches the sink
  when the scrim is clicked, which is a property of the generated binding's lambda plumbing and is
  currently untested.

These are expression-bodied (`tools/render-shape/check.py` enforces the shape) and are the cheapest
evidence in the plan: they run on every pull request in about a minute.

**Layer B — the payload, on the JVM, driving the real screen.** A new test source set in
`slice-desktop` that serves the built `slice-guest` payload from a local HTTP server on an
ephemeral port (the desktop host loads a manifest URL through a `ZiplineCache`, so a cold cache
directory per test run is part of the setup — the trap `tools/conformance/cross-version.sh`
records), composes the desktop host inside `runComposeUiTest`, and **operates the Material tab** as a user would: click the
"Buttons" chip, find `Button` by its text, click it, assert the witness reads `m3.button=1`; open
the dialog, confirm it, assert; drag the slider, assert the value changed. One test per section,
asserting every witness in it.

This is the layer that answers "what renders" for the whole stack — guest code, generated stub,
wire, generated binding, real library — on one machine, in tier S. It is also the layer with the
most feasibility risk (§5), so it is spiked first: one test, one button, watched to fail with the
witness assertion inverted, before the rest are written.

**Layer C — the devices, through the accessibility layer.** A new conformance family **M** in
`plans/conformance.md`, graded by all four existing drills on the Material tab:

| ID | Claim | Instrument |
|---|---|---|
| M1 | Every section of the catalogue renders: each section's witness set is present after its chip is activated | labels present (Android, iOS, web); transcript nodes (desktop) |
| M2 | Every button family is operable through the accessibility layer: activate by label, witness increments | perform click / `accessibilityActivate` / synthesised click |
| M3 | Every selection family reports state and changes it: checkbox, switch, radio, toggle buttons, chips, segmented rows, tabs, navigation items | state read from the node (`isChecked`, `accessibilityValue`, AX `checked`) **and** the witness |
| M4 | A dialog opens, is announced, confirms and dismisses; focus returns to the screen | the dialog's title is the next focused/announced element; witness |
| M5 | A sheet, a drawer and a menu open and choose | witness |
| M6 | A Material 3 component whose slot holds a primitive-tier `Icon` announces the icon's `contentDescription` | spoken label of the icon button includes the description |
| M7 | The `Slider` changes value through the accessibility layer (`ACTION_SET_PROGRESS`, `accessibilityIncrement`, AX `valuenow`) | node value and witness |

M1 is graded per section, so its detail line names any section that did not render on that
client. The claims are added to `claims.tsv` with Layer A's test classes as the shared-code
evidence and the drills as the device evidence, and the matrix in `plans/conformance.md` Part 3
gets the family with every cell empty until a drill fills it — the rule that makes an empty cell
mean something.

**Layer B and Layer C overlap on purpose.** B proves the stack composes and reacts; C proves a
person using assistive technology can do the same thing. The last time those were assumed to be
the same question, thirteen anonymous controls were reported.

### 1.4 A test that keeps §1.2 honest

`MaterialScreenCoverageTest` in `slice-screens`'s JS tests: composes the Material tab section by
section into the recording applier (the same instrument `RoleBorderPaddingDismissTest` uses),
collects the distinct segment-255 widget tags that were emitted, maps them back through the
tier's dictionary, and asserts **at least 60 distinct bound composables and every family** were
composed. The number and the family list are in the test, and the test's failure message prints
which bound composables the screen does not use. When M4 of generator v2 binds more, this test
says what the screen owes.

### 1.5 What this is expected to find, and where it is fixed

- **`Slider` is uncallable** (§0). The generator's overload rule (ADR-072: `Name~<hash of
  parameter names>`) treats two declarations with the same names in a different order as distinct,
  and the guest stubs collide. Fix in `Classifier.kt`: when two overloads' *sets* of guest-visible
  parameter names are equal, the second is "generable, excluded — its guest signature is another's
  after erasure", which is the bucket the plan already has for erased duplicates. The coverage
  report's Material 3 bound count drops by one, honestly. A `ClassifierTest` pins it.
- **Dialogs and the primitive `Icon` on iOS.** VoiceOver's treatment of a Compose dialog's scrim
  and of an icon-only button is the least-known cell in the matrix. Expected outcome is that it
  works; if not, the finding is a binding or an upstream note, and either is recorded.
- **Drawers.** `ModalNavigationDrawer` takes the viewport; the section composes it inside a
  bounded `Box` of fixed height, which is a legitimate use and the only drillable one.
- **The Android drill's reach.** Sections are short by construction; if any section's witness set
  is still past the first scroll on the emulator, the section is split, not the drill loosened.

### 1.6 Done means

1. `MaterialScreenCoverageTest` passes at ≥ 60 composables, all families.
2. Layer A: ≥ 20 render tests green on JVM, iOS simulator and WebAssembly; render-shape check
   passes.
3. Layer B: every section's test green on the JVM in tier S; the spike's inverted assertion was
   watched to fail.
4. Layer C: M1–M7 graded PASS on Android (emulator), iOS (simulator, VoiceOver on), web (headless
   Chrome) and desktop (transcript, M1–M3 only — the transcript cannot operate a control, and the
   matrix says so). Result files committed as `result-<client>-<date>.conf`.
5. The About screen's existing block stays, unchanged, so the existing drills' claims keep their
   evidence.
6. `developer-experience.md` §1 and `docs/authoring.md` §10 point at the Material tab as the
   worked example.

---

## 2. Every sample payload declares the tier, and a host without it says no before `start`

### 2.1 Mobile

`slice-guest/build.gradle.kts` already refuses to hand-type a segment version and reads the
generator's outputs. It gains a fourth entry, read from
`generated/dogwood-material3/dictionary/androidx.material3.json` exactly as Acme's is read from
its dictionary — `wireName` and `version` by the same two regular expressions — with a task
dependency on `:dogwood-codegen:generateMaterial3` so the file exists when the manifest is
assembled. `second-guest` and `two-payloads` do the same only if they use the tier (today they do
not; the rule is *declare what you compose*, not *declare everything the host has*).

### 2.2 Web

The web sidecars are committed fixtures, and one of them already names a stale design-system
version because a committed number goes stale silently. The fix is the one `guestScriptSha256`
already uses: **a `null` is a request to be stamped.** `signWebSidecars` fills a
`"segmentVersions": null` from the generated vector — `DogwoodSegments.kt` for the built-ins,
the tier's dictionary JSON for `androidx.material3` — before it signs, and leaves any manifest
that spells its versions out (the too-new fixture, the skewed one the drill writes) alone. The
main manifest and the Kotlin-guest manifest move to `null`. The stale `9` disappears with them.

### 2.3 The refusal, demonstrated on every client

A declared segment is only worth declaring if a host that lacks it refuses. Three demonstrations,
one per client family, each with its control:

- **Web.** A fixture `dogwood-manifest-needs-material3.json` (stamped, signed) loaded by a page
  built **without** the tier registered. Claim `B6`: *a payload that declares a generated tier the
  host lacks is refused before a Worker exists, naming the segment.* Control: the same fixture on a
  page with the tier registered renders the Material tab's first witness. The two pages are the
  same `web-slice` build with a `?tier=none` query parameter that skips the registration — the
  pattern `?trust=none` already uses for the unsigned-posture control.
- **Android and iOS.** `run-preflight.sh` and `run-preflight-ios.sh` gain one case: the client
  built with `-PdogwoodMaterial3=false` (the sample's registration behind a Gradle property,
  defaulting to true) meets the ordinary declared payload and refuses it before `start`, naming
  `androidx.material3`. Graded as `B6` on those clients through the drills' existing `CONF` path.

### 2.4 The dependency this creates, stated plainly

Once the web payload declares `androidx.material3`, **the web sample as it stands today refuses
it** — the tier is linked but not registered, pending the `OPEN-DECISIONS.md` §7 decision. So §2
cannot land on the web until that decision is taken, and the plan's recommendation is recorded
here rather than implied: **take option (a), raise `G5` by the measured 158,900 bytes with the
attribution line ADR-066 requires, and register the tier.** The reason is this plan's premise —
the tier is only proven if the web renders it — and the cost is about a second on Fast 3G for a
page that already ships two WebAssembly modules. If the owner takes option (b) instead, the web
sample needs a second guest payload that does not compose the Material tab, and §1's web column
becomes "not offered", which the matrix can say. Either way the decision is taken before §2's web
half, and §2's mobile half does not wait for it.

### 2.5 Done means

1. `slice-guest`'s manifest names `androidx.material3:10900` (read, not typed), and a test in the
   Android instrumented suite reads the loaded manifest's declared segments and asserts the tier
   is among them.
2. The web sidecars carry stamped `segmentVersions`; `signWebSidecars` refuses a manifest with
   neither `null` nor an explicit map, as it refuses one without a digest field.
3. `B6` graded PASS with its control on web, Android and iOS; the web conformance drill and both
   pre-flight drills updated; `plans/conformance.md` family B gains the row; `docs/security.md`
   §4 and `docs/operating.md` §2 mention that a generated tier is refused pre-flight like any
   segment.
4. ADR-061 and ADR-062 gain a note each: a tier declares like a product; a sidecar's versions are
   stamped like its digest.

---

## 3. One version, derived from what the host resolves, and a check that the lock agrees

### 3.1 The decision

**The generator does not pin the Material 3 version; it reads the one the host resolved.** That
is what ADR-072's first sentence already claims ("the exact Compose Multiplatform artifacts the
host resolves"), and §0 shows the implementation pins a coordinate by hand instead. The fix makes
the claim true:

- `fetchComposeSources` resolves each module's version from **`dogwood-host`'s compile
  classpath** (`configurations.getByName("jvmCompileClasspath").resolvedConfiguration`, filtered
  to the four module coordinates) and fetches *that* sources jar. The hand-typed `1.9.0` is
  deleted. A Compose Multiplatform bump moves Material 3 to whatever the plugin maps it to, and the
  generator follows without anyone remembering.
- The task writes `compose-sources/versions.json` — `{"material3": "1.9.0", ...}` — beside the
  extracted sources. `generateMaterial3` reads the tier's version from it and encodes it
  (`1.9.0` → `10900`; `1.9.1` → `10901`; a pre-release suffix is refused with the reason that a
  payload cannot declare a version that will be republished) and passes it as `--version`. The
  `10900` literal is deleted. The coverage report reads its header from the same file and
  `PINNED_VERSIONS` in `Main.kt` is deleted.
- **The lock's `version` is checked, not just written.** `checkAgainstLock` today writes the
  version the generator was given. It will refuse when the lock's version is *ahead* of the
  resolved one ("the lock says 10901, the host resolves 1.9.0: the host was downgraded, and a
  payload in the field may declare 10901") and *accept and record* when the resolved one is ahead,
  printing the old and new versions on the same line the "lock updated, added [...]" message uses.
  A downgrade is a decision, so it is a refusal with the flag to make it: `--accept-downgrade`,
  mirroring `--allow-unbindable`.
- A `CodegenVersionTest` asserts the encoding, the pre-release refusal, and the downgrade
  refusal. A Gradle verification task `checkGeneratedTierVersions` (wired into `check`, so tier S
  runs it) asserts that every committed lock's version equals the encoding of the resolved version
  — the cross-check that would have caught a Compose Multiplatform bump moving the mapping. It is
  watched to fail once by editing the lock's version by hand before it is trusted.

### 3.2 Why derive rather than pin and check

Pinning and checking would keep a number a person has to move in step with a number the plugin
moves. Deriving removes the number. The one argument for a pin — reproducibility — is already
answered by the lock: the lock is the record of what was generated, and a regeneration that
disagrees with it is refused or reported. This is a small amendment to ADR-072's D-A and gets its
own record, **ADR-073**, listing `plans/generator-v2.md`, `docs/upgrading-compose.md` (§4) and
the two build files as updated documents.

### 3.3 Done means

1. `grep -rn "1\.9\.0\|10900" engine/dogwood-codegen engine/dogwood-material3` finds only the lock
   and comments that describe history.
2. `checkGeneratedTierVersions` is in `check`, was watched to fail on a hand-edited lock, and
   passes.
3. The generator refuses a lock version ahead of the resolved artifact and accepts one behind it,
   with a test for each.
4. Full engine build and tier S green; the coverage report's header still says 1.9.0, now read
   rather than typed.

---

## 4. `docs/upgrading-compose.md`: the procedure, the outcomes, and the compatibility matrix

### 4.1 The document

A manual for two readers: the engineer bumping Compose Multiplatform, and the engineer on call
who needs to know what an old payload does on a new host. Sections, in order:

1. **What moves when you bump.** Compose Multiplatform's version in `libs.versions.toml` moves
   the renderer and, through the plugin's mapping, Material 3. The generator follows (§3). The
   tier's version follows the library's. A table of what changes and what does not.
2. **The procedure**, as commands with expected output: bump; `./gradlew :dogwood-codegen:generateMaterial3`
   and read the lock line (`unchanged` / `updated, added [...]` / `violated` and what each means);
   `generateComposeCoverage` and read the coverage diff; build the tier and read `exclusions.txt`
   for anything the host compiler newly refused; run Layer A; run the Material tab drills on one
   device; commit the lock, the exclusions, the coverage report and the reference together, in one
   change, with the ADR-072 amendment line.
3. **The four lock outcomes**, each with what to do: *added* (accept; new components are new
   tags); *removed* (accept; the tag is retired in the lock and the component listed in
   `exclusions.txt`, and payloads in the field that use it render a placeholder and report — link
   to `docs/operating.md` §4); *retyped* (refused; a parameter changed type or a required
   parameter was added; the choices are to exclude the component under its old tag and let the
   generator bind the new signature under a new name, or to hold the upgrade — never to edit the
   lock); *downgraded* (refused; §3).
4. **The compatibility matrix**, the section the review asked for:

   | Payload built against | Host has | What happens | Graded by |
   |---|---|---|---|
   | tier at 10900 | tier at 10900 | renders | M1–M7 |
   | tier at 10900 | tier at 10901 (host upgraded) | renders; every tag the payload uses still exists; **defaults are the host's library's**, which may differ from the ones the author saw | K1, K2; §4.2 |
   | tier at 10901 | tier at 10900 (host behind) | refused before `start` if declared (`B3`, `B6`); placeholders and a report for the new components if not declared (`A2`) | B3, B6, A2 |
   | uses a component 10901 removed | tier at 10901 | that component is a placeholder with a report; the rest of the screen renders | A2 |
   | tier declared | no tier | refused before `start`, naming the segment | B6 |
   | tier not declared | no tier | every tier component is a placeholder; the screen's structure holds | A2 |

5. **Host-evaluated defaults, and what that means for authors.** The single non-obvious rule in
   the design: a parameter the payload does not set is the *host's* library's default, evaluated
   on the device, and a fleet mid-upgrade renders two defaults for one payload. Authors who need
   one look set the parameter. Operators who see a visual difference between two devices on the
   same payload check the host version first. This is stated once, here, and linked from
   `docs/authoring.md` §10 and `docs/operating.md` §4.
6. **Fleet order.** The existing rule in `docs/getting-started.md` ("hosts first, payloads after
   the fleet") applied to the tier: a payload may declare a tier version only once the fleet's
   hosts have it, and the manifest declaration is what enforces it on the day someone forgets.
7. **What is not covered**, honestly: a library that changes a default's *behaviour* without
   changing its signature is invisible to the lock; a component whose accessibility semantics
   change upstream is caught only by the Material tab drills.

### 4.2 A measured example, not a hypothetical one

The document's procedure section ends with a **real diff**: the generator run against the next
published Material 3 sources (whatever `org.jetbrains.compose.material3:material3` has published
above 1.9.0 on the day this is written, fetched with the same task pointed at that coordinate by
a `-PdogwoodComposeSourcesOverride=` property that exists for exactly this) — the lock's verdict,
the coverage delta, and the exclusions delta, pasted as they came out. Nothing is committed from
that run; the point is that the procedure has been walked once by the person who wrote it, and
the reader sees what the output looks like before they need it. If the next version retypes
something, the example shows a refusal, which is the more useful example.

### 4.3 Where it is linked from

- `docs/README.md`, the front door: the *Maintaining the engine* row gains it, and a new row
  *Upgrading Compose or Material* points at it alone.
- `docs/getting-started.md` "Supported versions": one paragraph pointing at it for the tier.
- `docs/operating.md` §4: the defaults rule, one sentence, with the link.
- `docs/authoring.md` §10: the defaults rule for authors.
- `docs/checks.md`: the `checkGeneratedTierVersions` row.
- `plans/generator-v2.md` §4: this plan, and D-A's amendment.
- Every link checked by `tools/link-check/check.py`, which runs on every pull request.

### 4.4 Done means

The document exists with all seven sections; the example in §4.2 is pasted from a real run and
says which version it was run against; every link resolves; the front door names it; the
compatibility matrix's "graded by" column names only claims that exist and have a result file.

---

## 5. Order, gates, and what could go wrong

**Order.** §3 first — it is small, it changes the generator's arguments that §1's fix also
touches, and §4's procedure describes it. Then §1, because it produces the findings §4's example
and matrix need to be honest about. §2's mobile half beside §1; §2's web half after the owner's
§7 decision. §4 last, written from what §1–§3 measured, not before.

**Gates, each watched to fail before it is believed.**

| Gate | Watched to fail by |
|---|---|
| `MaterialScreenCoverageTest` | lowering the threshold to 61 with 60 composed |
| Layer B spike | inverting the first witness assertion |
| `B6` | loading the needs-material3 fixture on the registered page and seeing it *render* (the control) |
| `checkGeneratedTierVersions` | hand-editing the lock's version |
| the downgrade refusal | pointing the sources override at an older version |

**What the first runs found, recorded rather than worked around.** None of it is about a generated
binding: each entry is Compose Multiplatform's accessibility bridge on one client, and each has a
skip carrying its observation rather than a red cell that can never go green — the precedent `D7`
set.

| Client | Observation | Where it is recorded |
|---|---|---|
| web | `Checkbox`, `Switch` and `RadioButton` are published as unnamed `button` nodes with no state | `M3-announced` skip; the catalogue now names each control with a `contentDescription`, which is the fix for a real user as well as for the drill |
| web | No accessibility node at all is published for a `Slider` | `M7` skip |
| web | While a Compose dialog is open, the client answers no input from outside the process: not a click on the accessibility node, not a mouse event at its own box, not Escape | `M4-operable` skip; each modal claim gets a page of its own |
| iOS | `WideNavigationRail` and `ModalWideNavigationRail` crash the application during measurement (`maxWidth must be >= than minWidth`), while the same composition lays out on Android and the web | excluded from the catalogue, which says so where they were |
| iOS | A `Switch` announces its label and its button trait but publishes no value, so VoiceOver is not told whether it is on | `M3-announced` skip |

The bindings are not in doubt in any of these: `Material3FamiliesTest` toggles the switch, moves the
slider and confirms the dialog on the Java Virtual Machine, WebAssembly and the iOS simulator. What
a platform hands an assistive technology is a different question from whether the event crossed the
boundary, and keeping the two apart is why both are graded.

**Three defects in this work's own making, found by running it.** The `Slider` overload ambiguity
(§0, fixed in the classifier). The section picker, which was a clipped `Row`, then a lazy
`HorizontalList` whose off-screen chips do not exist for a drill to find, and is now three wrapped
rows. And the Android drill scrolling whichever container the tree published first, which moved the
chip row instead of the page and reported six controls as missing that were one swipe away.

**Known risks, with the fallback.**

- *Layer B feasibility.* **Resolved, and differently from either option.** Running Zipline inside
  `runComposeUiTest` was not attempted: the guest is Kotlin/JavaScript, so a Java Virtual Machine
  test cannot compose it directly, and standing up QuickJS plus a served payload inside a unit test
  buys a slow, fragile version of what the device drills already do. What was built instead splits
  the path at the wire. `slice-screens` composes every section on Node — the real guest module, the
  real generated stubs, the real Compose runtime — and writes the change batches it sent;
  `MaterialReplayTest` in `slice-desktop` replays those exact bytes through `HostTree` and the
  generated bindings and reads the screen. Everything between a payload and a pixel is covered with
  no device, which is what lets it run on every pull request. What it does not cover is
  interaction, because there is no guest at the other end to receive an event: Layer A covers that
  per binding and the device drills cover it end to end.
- *iOS dialogs and VoiceOver.* If a dialog's title is not the next announced element, M4 on iOS
  is recorded as a finding with the observation, not loosened.
- *CI time.* Tier S is 20 minutes today; Layer A adds about a minute and Layer B an estimated
  three (one payload build, already made; one JVM composition per section). If Layer B pushes the
  job past 30 minutes it moves to its own job beside the web skew drill.
- *The page-weight decision.* Stated in §2.4; the plan does not decide it and does not wait on it
  for anything but §2's web half.
- *Guest script weight is unbudgeted.* `guest-kotlin.js` will grow with the Material tab and no
  claim measures it. That is a gap the review noticed and this plan records rather than fixes; a
  `G6` for the guest script is a one-line proposal for `plans/conformance.md` §G, taken up when
  the number is worth arguing about.

**Estimated effort**, for the owner's planning rather than as a promise: §3 one day; §1 five to
seven days, of which the device drills on three clients are the majority; §2 two days plus the
decision; §4 one day after §1 has run. The findings in §1.5 are already budgeted; anything past
them extends §1.

---

## 6. What this plan does not do

- It does not bind more of Material 3. That is generator v2's M4 and needs the mapping table to
  grow; this plan proves what is bound.
- It does not touch Umbra. The standalone sample stays the minimal integration; a note in its
  README points at the Material tab for the tier.
- It does not restructure the sample's tab bar. The Material tab is a fourth `when` branch in
  `AppShell.kt`; the shell's own tab bar was deliberately guest-composed and stays so.
- It does not resolve `OPEN-DECISIONS.md` §7. It recommends, in §2.4, and stops.

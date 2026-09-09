# Decisions and actions that are not engineering's to take

Everything in this file is **blocked on a person**, not on work. Each item says what it is, what it
costs to leave open, and what "done" looks like — so it can be picked up cold, and so nothing here
needs re-explaining in conversation.

Engineering does not act on these. Where a record says "not filed", "not acquired" or "unproven",
that is the honest tense and it stays that way until somebody here changes it.

---

## 1. Continuous integration is not an enforced merge gate

**Status:** open. **Cost of leaving it:** everything is green by discipline rather than by
enforcement.

`.github/workflows/conformance.yml` runs on every push and pull request and has been green on every
merge. What it cannot do is *block* a merge: setting branch protection on `main` returns **403**,
because the repository is private on a plan that does not include protected branches.

So the check exists, runs, reports, and can be ignored. Nothing prevents a red pull request being
merged — the only thing stopping it is that nobody has.

**What done looks like — either one:**

- Make the repository public. Branch protection is free on public repositories.
- Upgrade the plan to one that includes protected branches on private repositories.

Then, in **Settings → Branches → Add rule** on `main`, require the status check named
**`Tier S — shared-code claims and budgets`**. That is one setting; `docs/checks.md` records what
the check covers and what it deliberately does not.

---

## 2. Apple has given no written ruling on downloaded interpreted payloads

**Status:** open, and it is the single largest risk to the iOS client.
**Cost of leaving it:** the iOS host is built, passes 39 conformance claims, and has never been
submitted to review.

The architecture downloads a signed payload and executes it in a bundled QuickJS. Whether that is
permitted is governed by **Guideline 4.7** (mini apps, mini games and plug-ins, conditions
4.7.1–4.7.5), *not* by the Guideline 2.5.2 JavaScriptCore exception this project originally cited —
that sentence no longer appears in the guidelines. The re-anchoring is recorded in
[Layer 4 ADR-003](adrs/layer-4/ADR-003-treehouse-precedent-and-evidence-refresh.md).

**What is known:** Cash App shipped Zipline-delivered JavaScript to a bundled QuickJS, which is
strong precedent. **What is not known:** anything in writing. Apple's guidelines are interpreted by
review, not by text, and no ruling exists.

**What done looks like:** a Developer Technical Support inquiry that references Guideline 4.7
explicitly and describes the actual mechanism — first-party signed payloads, no third-party code,
no marketplace — and a written answer. The answer goes into ADR-003, and
[Layer 5 ADR-004](adrs/layer-5/ADR-004-compose-multiplatform-sole-host-target.md) stops carrying the
risk as unproven.

**A related, separate item:** ADR-004 asks more of an iOS organisation than Redwood did, and
Redwood's own reported iOS-adoption reluctance was never characterised beyond the maintainer's
"the decision wasn't technical". An iOS organisation's **written yes** to owning a Compose
Multiplatform host is its own prerequisite, and it is not an engineering artefact.

---

## 3. Two upstream defect reports are drafted and not filed

**Status:** open by your explicit decision. **Cost of leaving it:** the workarounds stay
undocumented upstream, so the next person to hit them pays the same debugging cost.

The full text of each is ready to paste in
[`tools/upstream-reports/README.md`](tools/upstream-reports/README.md). Filing publishes this
project's name, a reproduction, and an implicit claim about a vendor's product — which is an
outward-facing action and belongs to a person.

| # | Defect | Where it would go |
|---|---|---|
| 1 | `wasm-opt` GUFA miscompiles `String.toCharArray()` to an array of zeros — silent wrong answer in a release build, context-sensitive, blast radius unknown | Binaryen (WebAssembly/binaryen) and/or JetBrains YouTrack, Kotlin/Wasm |
| 2 | Incremental Kotlin/Wasm klib compilation crashes on every rebuild after a source-set edit; the documented flag does not clear it | JetBrains YouTrack, Kotlin/Wasm |

**It is two, not three.** `roadmap.md` and `plans/platform-review.md` both said three; the folder
holds two, and always has. Corrected in both, and noted here because a count that drifts is how a
list stops being trusted.

**What done looks like:** the reports filed, and their URLs pasted into the ADRs that currently say
"drafted, not filed" — `ADR-032` and `ADR-033`. Until then those records keep the honest tense.

---

## 4. The Phase 0 gate device was not acquired

**Status:** closed as a decision, open as a standing risk. Recorded in
[Layer 4 ADR-008](adrs/layer-4/ADR-008-gate-device-not-available.md).

Phase 0's performance gate is defined on a low-end 2022-tier Android device — a Galaxy A14 or
nearest. None was acquired and none will be. Every measurement to date comes from an Apple silicon
development machine, an Android emulator and a Pixel — **all faster than the gate device, so all of
them are lower bounds**.

The consequence is visible in the conformance matrix: budgets `G1`–`G4` read `·` on every client,
because no host that exists can grade them. The grading is wired and asymmetric — the first such
device to run it closes or reopens the gate with no further work, and a regression on faster
hardware still fails today.

**What done looks like:** one such device, and one run of the Phase 0 harness on it. Nothing else
changes.

**A second thing that device would settle:** the 22.1 ms pause outlier from Phase 0 is still
unattributed on the machine that produced it. `PauseWatcher` can now answer the question from the
public API ([Layer 4 ADR-013](adrs/layer-4/ADR-013-attributing-a-pause-without-patching-quickjs.md)),
and one run of `--es experiment pauses` on that Pixel closes it.

---

## 5. Where the published artifacts actually go

**Status:** open, and it is now the only thing between this and a product depending on Dogwood.
**Cost of leaving it:** a product can consume Dogwood only from a developer's own machine.

`dogwood-wire`, `dogwood-protocol`, `dogwood-compose`, `dogwood-host` and the Gradle plugin
`dev.dogwood.codegen` publish under **`dev.dogwood`** at **`0.1.0`**, and
`samples-standalone/umbra` proves the whole path works — a separate Gradle build with no route into
this repository, resolving the plugin by identifier and the runtime as artifacts
([ADR-047](adrs/layer-5/ADR-047-the-generator-ships-as-a-plugin.md)).

It resolves them from **`mavenLocal()`**, which is a developer's own machine and nobody else's.

**What done looks like:** a repository these are deployed to, an account that owns them, and
credentials a build can use. That is one of:

- Maven Central, under a group somebody has verified ownership of. `dev.dogwood` is not registered
  to anyone; a real one would be a domain you control.
- A private repository — an internal Artifactory, Nexus, or GitHub Packages — if this is not meant
  to be public.

Then `samples-standalone/umbra/settings.gradle.kts` points at it instead of `mavenLocal()`, and the
plugin gets a marker on the Gradle Plugin Portal if it is meant to be applied by identifier without
a `pluginManagement` block.

**A related decision that comes with it:** `0.1.0` is a number, not a stability promise. Nothing is
API-frozen. Whether the first published version carries a compatibility commitment is a decision to
take *before* anyone depends on it rather than after.

---

## 6. The signing keys, and where payloads are served from

**Status:** open, and it is the last thing between this and a first ship.
**Cost of leaving it:** payloads can be signed and served on a developer's machine and nowhere else.

Two halves of one decision, and neither is engineering work — the mechanism is built and verified.

**The keys.** Payloads are signed with Ed25519 and verified before a byte executes, with a second
**rotation** key so a key can be replaced without stranding installed clients ([`B1`, `B2`](plans/conformance.md)).
The keys in this repository are **throwaway development keys, committed on purpose** so the samples
build for anyone who clones it, and labelled as such where they sit. A real one is passed in:

```
-PdogwoodSigningKey=<hex> -PdogwoodRotationKey=<hex>
```

**What done looks like:** production keys generated somewhere they can be held, a named owner, a
written rotation procedure, and a revocation answer. Rotation is roll-forward: publish manifests
carrying both signatures until every client trusts the new key, *then* delete the old entry — a
client holding only the old key stops accepting updates at that moment, which makes the second step
something to finish rather than to start. Nobody has written that procedure down.

**Where payloads are served from.** Today: `./gradlew :samples:slice-guest:serveProductionWebpackZipline`
on `localhost:8080`. A production deployment needs three things and two of them are silent when
wrong:

- **Build, sign and upload as one reviewable step**, rather than a developer's Gradle invocation.
- **Cache headers that match immutability.** Payload files are content-addressed and may be cached
  forever; **the manifest is not** and must not be. Backwards gives you either stale clients or no
  caching at all, and neither announces itself.
- **`Content-Encoding: br` on the web bundle.** Serving gzip instead costs **27% and about five
  seconds** on a slow connection ([ADR-045](adrs/layer-5/ADR-045-web-page-weight-where-the-levers-are.md)).
  Nothing on the device can detect it.

Two capabilities wait on the server rather than on code: **resuming a previous payload** needs a
manifest that still serves it, and **staged rollout** needs somebody to decide which cohorts get
which manifest — `InstallCohort` already gives each installation a stable bucket 0–99 to stage
against. [`docs/operating.md`](docs/operating.md) §5 and §6 are written for whoever picks this up.

---

## 7. The web profile's first-visit trade is a product judgement

**Status:** open, and it is the one item here that changes what gets built.

The web page is **3,766,502 bytes brotli**, and roughly seven tenths of it is Skiko — a prebuilt
binary this project cannot shrink, configure or defer. Every engineering lever has now been measured
([Layer 5 ADR-045](adrs/layer-5/ADR-045-web-page-weight-where-the-levers-are.md)), and the profile
this produces is clear:

**It grows with the catalogue, and that is now a decision rather than a drift.** It was 3,576,606
bytes when ADR-045 measured it; the design system has gained six components since, of which two
Material 3 pickers cost 96,924 bytes between them. The decision taken
([ADR-066](adrs/layer-5/ADR-066-the-pickers-cost-half-a-second.md)) is that components cost every
client globally — which is what mobile already does, where they occupy install size whether an
application composes them or not — so the ceiling moves with the catalogue and every raise must
arrive with an attribution. The timings below are ADR-045's and are left as measured; the trade they
describe is unchanged in shape and slightly worse in degree.

| Connection | First frame |
|---|---|
| unthrottled | 0.14 s |
| 5G | 0.45 s |
| 4G | 3.5 s |
| Fast 3G | 19.2 s |

(Those are current, after preloading the WebAssembly chunks — the last free improvement available,
worth 13% on 5G. Serving gzip rather than brotli would add 4.9 s to the last row.)

Returning visitors pay none of it — the bytes are hash-named and cached.

**The question is what the web surface is for.** A returning-user application surface — signed in,
opened repeatedly, deep in a funnel — fits this profile well. A first-impression surface — a landing
page, anything search-driven, anything where a slow first visit *is* the product — does not.

**What done looks like:** an answer, because the answer decides whether a Document Object Model
renderer gets built. That is the only lever large enough to matter, and it is declined today on the
grounds that it is a second design-system implementation for the web — which is real engineering
cost, not a preference. If the product needs the first visit, that cost becomes worth paying and the
decision reopens.

# Open decisions

**This is the project's list of what is not decided.** Every item is blocked on a person rather
than on work: a purchase, an account, a ruling from somebody else, or a judgement call that code
cannot make. Each says what it is, what it costs to leave open, and what "done" would look like.

**If you are evaluating Dogwood, read this before the README's status section.** It is the
unflattering half, kept current on purpose, and it is where the difference between "built" and "in
production" is written down. Where a record says "not filed", "not acquired" or "unproven", that is
the tense it stays in until somebody changes it — nothing here is softened because the repository
is public.

Dogwood is maintained by one person, so "the owner" below means whoever holds the accounts and the
keys. Several items are shaped by that arrangement and would read differently on a team.

---

## 1. ✅ Closed — continuous integration is an enforced merge gate

**Status:** closed, 2026-09-14, by making the repository public.

For the life of this project the check ran, reported, and could be ignored: setting branch
protection on `main` returned **403**, because the repository was private on a plan without
protected branches. Going public made protection free, and it is now on. A pull request cannot be
merged into `main` until both jobs pass:

| Required check | What it grades |
|---|---|
| `Tier S — shared-code claims and budgets` | every target compiles, every shared test passes, the shared-code claims are graded against tests that actually ran, the web slice is inside its byte budget, render tests are shaped to report on Kotlin/WebAssembly, every link resolves |
| `Skew containment — the web client` | a client at dictionary version N meeting a payload at N+1, in a real browser |

**`enforce_admins` is deliberately off**, which is the one loose thread. The checks are required
for every pull request, including from forks; the repository owner can still push directly to
`main` and bypass them. That is the right setting for a solo maintainer with an emergency to fix,
and it is a discipline rather than a wall. Tighten it with:

```
gh api -X PUT repos/teegarcs/dogwood/branches/main/protection/enforce_admins
```

**The status is visible rather than assumed:** the badge at the top of
[`README.md`](README.md) is the live tier-S result on `main`. Tier C has no badge on purpose —
see `docs/checks.md` for why, and the dated `result-<client>-*.conf` files for what it last found.

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

**Status:** half closed. The **coordinates are decided** — `io.github.teegarcs`, taken 2026-09-14
by [ADR-071](adrs/layer-5/ADR-071-the-coordinates-are-a-namespace-somebody-owns.md) — and the
**deployment is still open**: nothing is published anywhere but a developer's own machine.
**Cost of leaving the rest:** a reader who follows `docs/getting-started.md` cannot resolve a
single artifact without cloning this repository first.

`dogwood-wire`, `dogwood-protocol`, `dogwood-compose`, `dogwood-host`, `dogwood-web` (since
2026-09-13 — it had no publishing coordinates at all before, and this sentence listed the others
without anyone reading the omission; [ADR-070](adrs/layer-5/ADR-070-every-shipping-platform-is-consumable.md))
and the Gradle plugin `io.github.teegarcs.dogwood.codegen` publish under **`io.github.teegarcs`** at **`0.1.0`**, and
`samples-standalone/umbra` proves the whole path works on every shipping platform — a separate
Gradle build with no route into this repository, resolving the plugin by identifier and the runtime
as artifacts ([ADR-047](adrs/layer-5/ADR-047-the-generator-ships-as-a-plugin.md)), compiling its
design system for Android, iOS and the web, resolving the web host, building a Worker payload and
linking an iOS framework.

It resolves them from **`mavenLocal()`**, which is a developer's own machine and nobody else's.

**What is decided.** The group is `io.github.teegarcs`, because Maven Central grants that
namespace on GitHub identity alone. The alternative was keeping `dev.dogwood` and buying and
verifying `dogwood.dev`, which is a recurring cost and a second thing to renew for a project that
is not yet published. Taken while nobody depended on the old coordinates, which is the only time it
is free.

**The engineering half is done, and since 2026-09-16 that includes the transport.** Every
publishing module signs its artifacts when `DOGWOOD_GPG_KEY` and `DOGWOOD_GPG_PASSPHRASE` are in
the environment and does not require signing when they are not — verified both ways:
`publishToMavenLocal` with nothing set succeeds unsigned, and with a throwaway key it wrote 198
`.asc` signatures beside the artifacts. Every POM carries the name, description, URL, Apache-2.0
licence, developer and source-control block Central requires.

**`com.vanniktech.maven.publish.base` is now applied**, and the `.base` variant is the whole trick:
the full plugin takes over publication creation for Kotlin Multiplatform modules, which would
replace the hand-configured publications [ADR-047](adrs/layer-5/ADR-047-the-generator-ships-as-a-plugin.md)
and [ADR-071](adrs/layer-5/ADR-071-the-coordinates-are-a-namespace-somebody-owns.md) pin the module
names of. The base plugin adds only the credentials, the bundle and the upload; `publishToMavenCentral`
exists, and signing stays where it was.

**And the bundle is checked without an account.** `publishToMavenCentral` refuses to run without
credentials, by design, which would have left "is the bundle actually complete?" answerable only by
whoever eventually has the account. So every publication is also written to a plain file repository
and archived:

```
cd engine && ./gradlew assembleMavenCentralBundle     # no credentials, no network
python3 tools/reference-server/portal-bundle-check.py
```

Six requirements graded on the zip a person would upload — POM completeness, a sources jar and a
javadoc jar per jar-packaged coordinate, a PGP signature beside every deployable file, md5 and sha1
recomputed, and nothing in the archive that does not belong. **36 coordinates, 1990 files, all
green** — after the check found a real defect on its first run: `dogwood-codegen` published no
sources jar, which Central refuses a deployment for. Watched to fail: rebuilt with no
`DOGWOOD_GPG_KEY`, `M4` goes red on all 199 deployable files; one `.sha1` overwritten with zeroes,
`M5` names it. The script says on every run what it cannot do, which is ask the Portal.

**What is left, and it is account work rather than a decision:**

- A Sonatype Central account for `io.github.teegarcs`, verified by the namespace-ownership check.
- A GPG signing key for the artifacts, which Central requires and which is **not** the Ed25519
  payload signing key of §6. Two different keys for two different jobs — [`docs/keys.md`](docs/keys.md)
  §1 is the table. There is no `gpg` on the build machine today; the throwaway key used to verify
  signing was generated with BouncyCastle.
- The two credentials, as **Gradle properties**. `MAVEN_CENTRAL_USERNAME` cannot be read by the
  build itself: the plugin reads `providers.gradleProperty`, and Gradle computes its property set
  before any build script runs — measured, not assumed, by setting
  `org.gradle.project.mavenCentralUsername` from the root build and watching the task still report
  it missing. Use `ORG_GRADLE_PROJECT_mavenCentralUsername` / `...Password` in the environment, or
  `-PmavenCentralUsername`; the build fails with that instruction if only the plain environment
  variable is set.
- Then `samples-standalone/umbra/settings.gradle.kts` points at Central instead of `mavenLocal()`,
  and the plugin gets a marker on the Gradle Plugin Portal if it is to be applied by identifier
  without a `pluginManagement` block.

**Still open, and it is the owner's:** nothing has been uploaded, because nothing can be until the
account exists. A green bundle check means complete by Sonatype's published rules, not accepted by
Central.

**Until that is done, `docs/getting-started.md` says so in its first paragraph** rather than letting
a reader discover it at the first failed resolution.

**A related decision that comes with it:** `0.1.0` is a number, not a stability promise. Nothing is
API-frozen. Whether the first published version carries a compatibility commitment is a decision to
take *before* anyone depends on it rather than after.

---

## 6. The signing keys, and where payloads are served from

**Status:** open on the two things only the owner can do — **a production key somewhere it can be
held, and a place to serve from**. Everything a mechanism can do before those exist was built on
2026-09-16 and is listed below.
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
something to finish rather than to start.

**The procedure is now written, and run.** [`docs/keys.md`](docs/keys.md) is the runbook:
generating a key, what the ownership answer has to satisfy, the three-step rotation with a sequence
diagram, and the revocation answer — which is that there is no revocation list and never will be,
because a remotely mutable trust anchor is not a trust anchor, so "revoke" decomposes into three
different actions and the useful thing is knowing which one you need.

`tools/reference-server/rotation-drill.sh` runs the whole rotation rather than describing it: two
real payloads, published through the reference server, met by a client holding only the old key and
a client holding only the new one. Seven claims, and the pair that matters is `R1` and `R5` — the
same client under the same procedure, one release apart, updating and then stopping **at exactly
the publish that drops its signature**. `R3` asserts the thing the runbook rests on: dropping a
signature is a publish, not a rebuild, because Zipline signs the manifest with its `unsigned`
object excluded. Watched to fail with `--dual-at-step-3`, which starts step 3 without finishing it:
`R5` goes red and the other six stay green.

The two clients cannot be the samples — every host here compiles in both development keys, so a
rotation drilled against one would pass every step without testing the step that matters.
`tools/reference-server/rotation-client/` is the delivery path's decision with the key set as an
argument, built on Zipline's own `ManifestVerifier`.

`tools/reference-server/new-payload-key.sh --prove` generates a pair **and proves it**: it builds a
payload signed by the private half and points a client holding only the derived public half at it,
because a transposed pair produces artifacts that look correct and a fleet that silently refuses
every update.

**Still the owner's, and neither is engineering:** where a production key lives, and who is named
as its owner. One piece of key hygiene is worth doing before either: ship hosts that trust *two*
keys from the start, with the second held somewhere the first is not. That collapses step 2 of an
emergency rotation — the weeks-long one — to zero. The samples sit mid-rotation deliberately for
that reason.

**Where payloads are served from.** Today: `./gradlew :samples:slice-guest:serveProductionWebpackZipline`
on `localhost:8080`. A production deployment needs three things and two of them are silent when
wrong:

- **Build, sign and upload as one reviewable step**, rather than a developer's Gradle invocation.
  **Built:** [`.github/workflows/publish-payload.yml`](.github/workflows/publish-payload.yml),
  manual dispatch with a version and a dry-run input. It stamps the version as a build input, signs
  from `DOGWOOD_SIGNING_KEY` / `DOGWOOD_ROTATION_KEY`, runs the checks below on the bytes that would
  ship, and uploads the signed bundle. The committed throwaway keys are a fallback **only** on a dry
  run and the job summary leads with it; a real run without the secret fails.
- **Cache headers that match immutability.** Payload files are content-addressed and may be cached
  forever; **the manifest is not** and must not be. Backwards gives you either stale clients or no
  caching at all, and neither announces itself.
- **`Content-Encoding: br` on the web bundle.** Serving gzip instead costs **27% and about five
  seconds** on a slow connection ([ADR-045](adrs/layer-5/ADR-045-web-page-weight-where-the-levers-are.md)).
  Nothing on the device can detect it.

The last two are graded on the artifact rather than described: `tools/reference-server/publish-check.sh`
serves the payload a build just produced and asserts the stamped version, both signatures accepted
by a client holding one key at a time, `Cache-Control: no-store` on the manifest, `immutable` on the
modules, `Content-Encoding: br`, and every module fetched with the digest the signed manifest names.
Watched to fail on a wrong version, a key that signed nothing, and an interpreter without the brotli
module — which is the failure the `br` claim exists for, and the reason a missing module is a red
claim here rather than a shrug.

**Staged rollout is no longer waiting on code.** `InstallCohort` gave every installation a stable
bucket 0–99 and nothing consumed it; the delivery path now sends it as `?cohort=N` on every manifest
request (a query parameter, so a static server ignores it and nothing has to change before this
ships), and the reference server routes on it through `cohorts.json`.
`tools/reference-server/cohort-drill.sh` publishes a bad release to buckets 0–9 and sweeps all one
hundred: ten get it, **ninety are untouched**. The ninety-row half is the one a server ignoring the
parameter could not satisfy. Watched to fail by widening the pin to 0–99.

**Resuming a previous payload** still needs a manifest that still serves it, which is `resume` and
is already drilled. [`docs/operating.md`](docs/operating.md) §5 and §6 are written for whoever picks
this up.

**Found while drilling, and it is a publishing rule rather than a server one:** module addresses
must be unique per release. The manifest names each module by whatever address the build wrote, and
this project's Zipline configuration writes the same name in every release — so two releases live at
once, which is what a canary *is*, publish different bytes at one address. A module request carries
nothing that says which release it wants. The signature catches it (the client refuses the load on
the digest the signed manifest names rather than rendering the wrong screen), but the outcome is an
outage. Put a content hash in the module file name, or serve each release from its own path prefix.
The reference server now warns at `publish` when it is about to create that situation.

**Still the owner's:** where payloads are served from. The workflow ends at a signed, checked bundle
that somebody can put anywhere.

---

## 7. The web profile's first-visit trade is a product judgement

**Status:** ✅ **closed, 2026-09-16, by taking option 1.** The tier is registered on the web, `G5`
rose from 3,900,000 to 4,060,000 with the three-build attribution written into
`tools/conformance/budgets.tsv` as ADR-066's rule requires, and the page measured 3,929,095 bytes
brotli after. The reason recorded at the time: the alternative makes the web a second-class profile
for the one capability the tier exists to provide — a payload could call `Button` everywhere except
in a browser — and a profile difference that large is worse than about a second of first load on
Fast 3G.

Two things followed from closing it, and both are now true rather than planned. The guest payload
has a budget of its own (`G6`, 252,000 bytes), because `G5` measures the page and never measured
the script the page fetches, and the Material catalogue grew that script by a quarter of a
megabyte under nothing. And the further tiers — `androidx.foundation`, `androidx.foundation.layout`
and `androidx.ui` — were measured against the same rule when they landed rather than assumed to
fit.

**The measurement that closed it**, kept because the next raise has to argue against it. The
generated Material 3 tier ([ADR-072](adrs/layer-5/ADR-072-the-compose-surface-is-generated-from-the-artifact-it-binds.md))
was the first thing that did not fit under the old ceiling:
([ADR-072](adrs/layer-5/ADR-072-the-compose-surface-is-generated-from-the-artifact-it-binds.md))
is the first thing that does not fit under the ceiling:

| Web slice build | Bytes brotli | Against the 3,900,000 ceiling |
|---|---:|---|
| without the tier | 3,770,785 | 129,215 under |
| tier linked, not registered | 3,772,894 | 127,106 under — dead-code elimination drops what nothing references |
| **tier registered** | **3,929,685** | **29,685 over** |

Registering the tier on the web costs 159 KB, about a second on Fast 3G. Three ways were open, and
the first was taken:

1. **Raise the ceiling to 3,950,000 with this attribution**, under ADR-066's rule that components
   cost every client globally and a raise arrives with its three builds. Consistent with the
   decision taken for the pickers; the web pays what mobile pays.
2. **Let the web profile leave the tier out.** The line is one registration in the page, and the
   measurement shows leaving it out costs nothing. A payload using Material 3 would then render
   placeholders on the web and be refused pre-flight if it declared the segment — a profile
   asymmetry, which is what ADR-066 declined for the catalogue, at a different size.
3. **Per-component binding** — the backlog's `D1`, whose unverified premise this measurement
   verified. Its cost is written there: a third skew state and a finer pre-flight declaration.

**Taken: option 1.** The web sample registers the tier like every other client, and the pending
asymmetry is gone. `engine/samples/web-slice/src/wasmJsMain/kotlin/dev/dogwood/slice/web/Main.kt`
carries the three numbers beside the registration, and `?tier=none` on that page is what claim `B6`
uses to grade a client that does *not* have the tier — the case that used to be the sample's
ordinary state is now a deliberate control.

The rest of this section is the earlier measurement, unchanged.

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

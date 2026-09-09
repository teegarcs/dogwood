# Every check, what it proves, and where it runs

One page, because the question "is this covered?" should have an answer you can read rather than
one you have to reconstruct from four directories. The claims themselves are in
[`plans/conformance.md`](../plans/conformance.md); this is the inventory of the machinery that
grades them.

## The two tiers, and why the split is real

| Tier | Grades | Runs | Needs |
|---|---|---|---|
| **S** | claims about code every client compiles, plus page weight | **every pull request**, `.github/workflows/conformance.yml` | a Java Development Kit and a browser |
| **C** | accessibility, network policy, skew containment, timing budgets | **locally and before a release**, `tools/conformance/run-all.sh` | a booted iOS simulator with VoiceOver, an attached Android device, Chrome, and the guest served on `:8080` |

**The Android drills run against the minified release build** (`testBuildType = "release"`), so every Android cell is graded against what a user would install — R8 on, shrinking and optimization included. See ADR-056 for why the sample's keep rules exist and why an adopter needs none of them.

Tier C is not in continuous integration because a hosted runner has none of what it needs. Wiring
it in anyway would produce a green tick that means less than it appears to, which is the failure the
conformance plan exists to avoid.

**One tier-C run is in continuous integration, and it is an exception on the evidence rather than on
the rule.** `tools/skew-drill/run-web.sh` puts a real web client in front of a real payload built
against a newer dictionary, and needs no device to do it: the host is a WebAssembly module in a
directory and the guest is a script beside it. It is its own job in the workflow so that it does not
compete with the tier-S build for one two-processor runner, and it fails loudly if the runner has no
Chrome rather than reporting a drill that could not run as one that passed.

## Tier S — on every pull request

| Check | What it proves | Fails when |
|---|---|---|
| `./gradlew build` | every target compiles and every shared test passes, on Java Virtual Machine, Android, three iOS targets, JavaScript and WebAssembly | a compile error or a failing test anywhere |
| `from_tests.py --have jvm,js,wasm` | the shared-code conformance claims — groups A, B, C, E, F, and the shared halves of D — are backed by tests that **actually ran** | a claim's evidence failed, **or did not run at all**: deleting a test must not silently drop a claim to green |
| `from_web_weight.py` | the **shipped web slice** is within its byte budget (3.70 MB brotli against 3.57 MB today) | the page grows past the ceiling, or cannot be measured at all |
| `render-shape/check.py` | every shared render test **returns** its `runComposeUiTest` result, which is the only thing that makes it compose on Kotlin/WebAssembly | a render test has a block body, or does anything after the render block — either way it is green on the web while composing nothing |
| `link-check/check.py` | every relative link in the repository's markdown resolves | a document sends a reader to a file that has moved or never existed. It checks links rather than prose, because that is the part of a document a machine can see is wrong |

The run summary prints the graded claims into the pull request, so a reviewer sees them without
opening a log.

**`--have` names the toolchains the runner can execute.** A Linux runner has no Kotlin/Native, so
the two rows whose evidence is an iOS-target test (`E4`, `F1`) are reported as *not gradable here*
rather than as failures; they are graded on a Mac by `run-all.sh`. The distinction is load-bearing:
"this environment cannot run that" and "somebody deleted that test" must never look the same, and
the fourth column of `claims.tsv` is what keeps them apart.

### Three faults this workflow shipped with, and what they cost

Recorded because each is a category rather than a one-off.

- **`python3 grade.py | tee out.conf` reports `tee`'s exit code.** GitHub's default shell is
  `bash -e`, without `pipefail`, so the first *green* run of this workflow was green while two
  claims were red. Every pipeline here now runs under `bash -euo pipefail`. This is the third time
  a shell pipeline has reported a product state incorrectly in this repository — after `grep -q`
  under `pipefail` in the skew drill, and `--console-pty` carriage returns in the iOS drill.
- **`brotli` is not installed on an Ubuntu runner**, so the page-weight budget reported `SKIP` and
  the workflow went green having graded nothing. It is installed now, and
  `from_web_weight.py` reports a `FAIL` rather than a `SKIP` when it cannot measure — an
  environment asked to grade a budget and unable to is broken, and saying so is the only way that
  gets fixed rather than tolerated.
- **It measured the wrong artifact.** `from_web_weight.py` called `web-weight/measure.sh`, which
  measures the two *spike* modules ADR-030 built to establish what Compose costs — artifacts no
  product change can affect. It therefore reported a steady 2.92 MB through a change that grew the
  real page by 463 kilobytes, and that steadiness was written into an ADR as evidence the change
  was free. A proxy that cannot move is worse than no measurement, because it reads as reassurance.
- **A four-gigabyte Gradle daemon plus parallel execution kills a two-processor, seven-gigabyte
  runner** with `exit code 143`, nineteen minutes in. CI-sized limits go in `GRADLE_USER_HOME`,
  which takes precedence over the project file and leaves a development machine alone.

## Tier C — locally, `tools/conformance/run-all.sh`

Each drill refuses rather than fails when its prerequisite is missing, so a partial run reports what
it could not do instead of reporting green.

| Drill | Claims | What it actually does |
|---|---|---|
| [`run-android.sh`](../tools/conformance/run-android.sh) | `D1`–`D5`, `D7`, `F1`, `F2`, `F4` | instrumented `UiAutomation` — the same accessibility service TalkBack uses — plus real network requests at a witness server |
| [`a11y-drill/run.sh`](../tools/a11y-drill/README.md) | `D1`–`D5`, `D7`, `F1`, `F2`, `F4` | in-application walk of `UIAccessibility` with VoiceOver enabled, plus the iOS network drill |
| [`run-web.sh`](../tools/conformance/run-web.sh) | `D1`–`D5`, `D7`, `J1`–`J4`, `A7`, `H4`, `H5`, `B1`, `B2`, `A4` | headless Chrome: `Accessibility.getFullAXTree`, plus the host services, a real code update, the kill switch, and the sidecar's detached Ed25519 signature checked against fixtures the build itself signed ([ADR-062](../adrs/layer-3/ADR-062-a-signed-web-sidecar.md)), and a guest that crashes on purpose so the frames can be read ([ADR-063](../adrs/layer-5/ADR-063-a-web-crash-carries-its-frames.md)) |
| [`skew-drill/run.sh`](../tools/skew-drill/README.md), `run-ios.sh` | `A2`–`A4` | two builds: a client at version N meeting a payload at N+1 that **declares nothing** — the render-time containment rules |
| [`skew-drill/run-preflight.sh`](../tools/skew-drill/README.md), `run-preflight-ios.sh` | `B3` | the same two builds, with the payload **declaring** N+1: the client refuses before `start` ([ADR-061](../adrs/layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md)) |
| [`cross-version.sh`](../tools/conformance/fixtures/README.md) | `K1`, `K2` | a **frozen, signed** payload from an earlier toolchain served to a desktop host built from current sources |
| [`cross-version-mobile.sh`](../tools/conformance/fixtures/README.md) | `K1`, `K2` | the same fixture served to an installed Android or iOS build, pointed at it by `--es manifest` / `--dogwood-manifest` |
| [`symbolicate/resolve.py`](../tools/symbolicate/resolve.py) | — | resolves a minified guest stack against the build's source map; run by hand on a crash report, not part of a gate |
| [`from_phase0.py`](../tools/conformance/from_phase0.py) | `G1`–`G4` | grades the Phase 0 timings against `budgets.tsv` |
| [`aggregate.py`](../tools/conformance/aggregate.py) | — | generates the matrix from every run and exits non-zero on a red cell |

## Freezing a payload — a rule, not a run

**Every dictionary-version bump freezes that day's signed payload into
[`tools/conformance/fixtures/`](../tools/conformance/fixtures/README.md).**

The cross-version claims (`K1`, `K2`) are the only ones that test the pairing every real deployment
actually has — a payload built earlier than the host running it — and they can only test as far back
as the oldest fixture. A rebuild cannot stand in: rebuilding produces today's toolchain, which is
the pairing already covered by everything else here.

So the window has to be widened deliberately, and the trigger is mechanical on purpose. "Freeze one
when something significant changes" is a rule nobody executes; "freeze one whenever the surface
version moves" is one the same commit already has to think about, because the lock file is in it.

Add, never replace: each fixture widens the window, and replacing one narrows it back to a point.
The drills serve the newest by default, so this costs nothing per run.

## The iOS embed check — can an existing Xcode project link this?

```
tools/ios-embed-check/run.sh
```

Assembles `DogwoodEmbed.xcframework` and asserts both slices exist and the header carries the
factory under its documented Swift signature with host types exported. Not in continuous
integration: three Kotlin/Native links, minutes each, on hardware a Linux runner does not have.

## The cross-version drill — does today's host run an older payload?

```
tools/conformance/cross-version.sh
```

Serves a **committed, signed payload fixture** built by an earlier toolchain to a host built from
current sources, and requires that it loads, verifies, and renders (`K1`, `K2`). Part of
`run-all.sh`. The pairing every deployment has and a rebuild cannot test.

## The reference-server check — does the operational back half behave?

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
tools/reference-server/check.sh
```

Observes, in real responses: a publish lands staged rather than live; the manifest is `no-store`
and modules are `immutable`; brotli is used when offered; a cohort inside a rollout gets the staged
release **and one outside it does not**; `resume` puts the earlier release back. The last leg runs
the desktop host against it with a cold cache and asserts the client loaded and *verified* a signed
manifest and fetched a module — because everything before it is the server agreeing with itself.
Not in continuous integration: it starts servers and a windowed client.

## The standalone check — can anyone outside this repository use it?

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
tools/standalone-check/run.sh
```

Publishes Dogwood to the local repository and runs `samples-standalone/umbra` — a **whole product**
in a separate Gradle build: no `includeBuild`, no project dependency, no path into `engine/`. Since
the adoption audit's A2 ([ADR-057](../adrs/layer-5/ADR-057-a-whole-product-outside-the-repository.md))
its verdict is a **render, not a build**: Umbra's `:design` generates its segment, `:guest` compiles
one screen against the published `dogwood-compose` and signs it, and `:app` fetches, verifies and
renders it over the guarded delivery path — the check reads the render transcript for the payload's
marker string, the product's own generated bindings, and a non-zero measured box. Its first run
failed on the marker, which is how it earned belief.

Not in continuous integration, for two reasons now: it publishes into the developer's own local
repository (a check that mutates a shared location on a build agent fails somebody else's build),
and the render half opens a real window. Run it before a release, and after anything that touches
publishing, the generator, or the delivery path.

**It asserts four things a green build does not imply**, because a product's own implementations
compile whether or not the generated bindings exist:

| Assertion | What its absence would mean |
|---|---|
| host bindings were generated | the plugin registered nothing |
| guest stubs were generated | the guest half never ran |
| the binding **compiled** | generated code does not build against the published runtime |
| a lock was written beside the surface | a product's tags are not being held permanent |

## One check that is not automated, and costs the most

**Serve the web page with `Content-Encoding: br`.**

Every page-weight figure in this project — the `G5` budget, ADR-030's table, ADR-038's first-frame
measurements — is brotli at quality 11. `tools/web-ttff/run.sh` refuses to measure at all unless the
server is actually sending it, so no recorded number was ever taken against uncompressed files.

A **deployment** has no such check, and the failure is silent: the page works, and is 27% heavier
than every number in these records.

| Encoding | Total | Fast 3G first frame |
|---|---:|---:|
| brotli −q 11 | 3,576,606 | 18.45 s |
| gzip −9 | 4,563,713 | 23.38 s |
| penalty | **+987,107 (27.6%)** | **+4.94 s** |

That is more than three times the entire design system, and more than any page-weight lever
available to this project could deliver ([ADR-045](../adrs/layer-5/ADR-045-web-page-weight-where-the-levers-are.md)).
It costs one server setting.

## Checks that are not conformance claims

Worth listing so nobody assumes the matrix covers them.

| Check | What it is |
|---|---|
| [`leak-soak/run.sh`](../tools/leak-soak/README.md) | 50 runs of the Kotlin/Native leak suite. Measures whether the leak *evidence* is stable — a precondition for trusting it, not a promise about the product |
| [`web-ttff/run.sh`](../tools/web-ttff/README.md) | time to first frame under throttling. Recorded, not gated: it is a product constraint to know, not a threshold to hold |
| [`web-slice/run.sh`](../engine/samples/web-slice/run.sh) | the web slice renders real pixels in a real browser, and the refusal path refuses before a Worker exists |
| `dogwood-codegen` dictionary lock | fails the build if a widget tag moves or a property tag is renumbered. Enforced inside `./gradlew build`, so tier S already covers it |

## Enforcement — the honest state

The workflow **runs** on every pull request. It cannot yet **block** a merge: branch protection and
auto-merge both return `403 Upgrade to GitHub Pro or make this repository public` on this
repository's plan. Two ways to close that, both decisions for the repository owner rather than
defaults to assume:

- make the repository public, or
- upgrade the plan,

after which requiring the `Tier S — shared-code claims and budgets` check on `main` is one setting.

Until then the rule is procedural and written here so it is not merely remembered: **wait for the
tier-S check to pass before merging a pull request, and run `tools/conformance/run-all.sh` before a
release.** A check that runs and nobody waits for is the same thing as no check — which this project
has now demonstrated six times in other forms.

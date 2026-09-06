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

Tier C is not in continuous integration because a hosted runner has none of what it needs. Wiring
it in anyway would produce a green tick that means less than it appears to, which is the failure the
conformance plan exists to avoid.

## Tier S — on every pull request

| Check | What it proves | Fails when |
|---|---|---|
| `./gradlew build` | every target compiles and every shared test passes, on Java Virtual Machine, Android, three iOS targets, JavaScript and WebAssembly | a compile error or a failing test anywhere |
| `from_tests.py --have jvm,js,wasm` | the shared-code conformance claims — groups A, B, C, E, F — are backed by tests that **actually ran** | a claim's evidence failed, **or did not run at all**: deleting a test must not silently drop a claim to green |
| `from_web_weight.py` | the **shipped web slice** is within its byte budget (3.70 MB brotli against 3.57 MB today) | the page grows past the ceiling, or cannot be measured at all |

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
| [`run-web.sh`](../tools/conformance/run-web.sh) | `D1`–`D5` | headless Chrome, `Accessibility.getFullAXTree` |
| [`skew-drill/run.sh`](../tools/skew-drill/README.md) | `A2`–`A4` | two builds: a client at version N meeting a payload at N+1 |
| [`from_phase0.py`](../tools/conformance/from_phase0.py) | `G1`–`G4` | grades the Phase 0 timings against `budgets.tsv` |
| [`aggregate.py`](../tools/conformance/aggregate.py) | — | generates the matrix from every run and exits non-zero on a red cell |

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

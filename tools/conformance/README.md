# Conformance: one capability list, four clients

The catalogue, the tiers and the rollout are in [`plans/conformance.md`](../../plans/conformance.md);
the decision behind them is [ADR-040](../../adrs/layer-5/ADR-040-conformance-is-a-catalogue-not-a-suite.md).
This directory is the machinery.

```
tools/conformance/run-android.sh     # instrumented UiAutomation drill
tools/conformance/run-web.sh         # headless Chrome over the DevTools Protocol
tools/a11y-drill/run.sh              # iOS, in-application, with VoiceOver enabled
python3 tools/conformance/aggregate.py tools/conformance/build/*.conf
```

## The grammar

Every client, in every language, emits the same lines:

```
CONF <id> PASS|FAIL|SKIP <detail>
CONF RESULT client=<android|ios|desktop|web> passed=<n> failed=<n> skipped=<n>
```

Text rather than a shared library, deliberately: the four clients are Kotlin/JVM, Kotlin/Native,
Kotlin/Wasm and Python-driving-a-browser, and a library would have to exist in all four — which is
the same constraint that makes the test *bodies* unshareable. A text format has no such problem.

The four verdicts are not interchangeable:

| | Meaning |
|---|---|
| `PASS` | The claim holds on this client. |
| `FAIL` | The claim does not hold. Turns the gate red. |
| `SKIP` | The claim does not apply here, with a reason — e.g. the screen carries no disabled control. |

**There is no verdict for "fails, but not our fault."** One was added and removed inside a day. The
web drill reported that a control could not be reached by keyboard, which looked upstream, so it got
an escape hatch. The failure was not real: the check had read a `focusable` flag instead of pressing
Tab, and Compose routes keyboard focus through the canvas rather than per-element `tabindex`. The
escape hatch legitimised a wrong conclusion within an hour of existing.

## Three clients, three instruments, one set of claims

That the machinery differs is the point rather than a compromise:

| Client | Instrument | Why not the others' |
|---|---|---|
| **iOS** | In-application walk of `UIAccessibility`, VoiceOver enabled first | Nothing can read the tree from outside the process, and Compose builds no tree at all until an assistive technology is running |
| **Android** | Instrumented test over `UiAutomation` | `UiAutomation` *is* an accessibility service, so it sees what TalkBack sees, out of process — a more faithful reader than anything in-app |
| **Web** | Headless Chrome, `Accessibility.getFullAXTree` | Compose draws to a canvas and publishes a parallel DOM **inside a shadow root**; selectors find nothing, node handles from the accessibility tree find everything |

## The matrix is generated

`aggregate.py` reads the `CONF` lines and writes Part 3 of the plan. It is never hand-maintained,
and it proved why on its first run: the committed Android result was from a negative-control run,
so the generated table showed a red claim for a client that passes. A hand-written table would have
said whatever it last said.

## Gating

Two environments, because they can honestly grade different things.

| | What it grades | Where |
|---|---|---|
| **Tier S** | the build, shared-code claims, the page-weight budget | `.github/workflows/conformance.yml`, every push and pull request |
| **Tier C** | accessibility, network policy, skew containment, performance budgets | `tools/conformance/run-all.sh`, locally and before a release |

Tier C cannot run on a hosted runner: it needs a booted iOS simulator with VoiceOver enabled, an
attached Android device, a browser with a graphics stack, and the guest being served. Wiring it into
continuous integration anyway would produce a green tick that means less than it appears to — which
is the failure this whole plan exists to avoid — so the workflow file names the boundary rather than
hiding it.

## Performance budgets, and why most of them read `·`

`budgets.tsv` holds the thresholds the roadmap sets. `from_phase0.py` grades `G1`–`G4` from the
Phase 0 results and `from_web_weight.py` grades `G5`.

The gate-validity rule is **asymmetric on purpose**. The Phase 0 gate is defined on a low-end
2022-tier Android device that this project decided not to acquire
([Layer 4 ADR-008](../../adrs/layer-4/ADR-008-gate-device-not-available.md)), and **no host here
qualifies** — not the development machine, not the simulator, and not the Pixel 10 Pro either,
which is a flagship where the gate names an entry tier. So:

- **within budget on such a host is a `SKIP`, never a `PASS`** — a comfortable number from fast
  hardware says nothing about slow hardware, and letting it read green would quietly discharge a
  risk the project chose to carry;
- **over budget is a `FAIL`** — favourable hardware exceeding a budget is damning precisely
  because the hardware was favourable.

That asymmetry is what keeps these numbers doing the one thing they can honestly do today, which is
catch regressions. Where two hosts speak for one client the slower is preferred: a phone over an
emulator running on the development machine's own processor.

`G5`'s budget is a **ceiling above today's figure** (3.30 MB against 2.92 MB), not today's figure.
Pinning it exactly would fail on the next legitimate component and teach everyone to raise the
number, which is how a budget stops meaning anything.

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

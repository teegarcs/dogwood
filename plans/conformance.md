# The conformance plan: one capability list, four clients

> **Status: the rollout is complete, 2026-09-05.** All nine steps are done, across pull requests
> #21–#35. Every claim group has real-client evidence on the platforms it applies to; the matrix is
> generated from real runs and gates in two places. What is carried rather than closed is listed at
> the end of Part 6.

## Part 0 — Why this exists

Every drill in `tools/` was built to answer a problem that had just bitten us, on the one client
where it bit. That produced good instruments and a lopsided map:

| Drill | Android | iOS | Desktop | Web |
|---|---|---|---|---|
| Accessibility | — | ✅ | — | — |
| Skew containment | ✅ | — | — | — |
| Hostile property values | ✅ | ✅ | — | — |
| Leak soak | — | ✅ | — | — |
| Phase 0 performance | ✅ | ✅ | ✅ | — |
| Page weight, first frame | n/a | n/a | n/a | ✅ |

Nothing there is wrong. What is missing is the thing that makes it a *system*: a statement of what
this architecture promises, written once, against which every client is graded — so that building
the fifth client is a matter of working down a list rather than rediscovering, one incident at a
time, what the fourth one already knew.

The failure this prevents is specific and this project has already met it four times. Evidence
machinery gets built, and nothing consumes it: the dead `skew` variable, the tautological `--gufa`
gate, an accessibility probe with no caller, an unsoaked leak suite. A per-client drill with no
catalogue above it is the same shape one level up — an instrument that answers a question nobody
asked twice.

### Three decisions taken before writing this, so the empty cells mean something

1. **Desktop is a development loop, not a shipping target** (roadmap Phase 1 step 2, reaffirmed
   2026-09-04). It is graded on **correctness only** — protocol, skew, hostile values, host
   resolution. Accessibility, performance budgets and platform-integration claims are marked *out
   of scope* for desktop, with that reason, rather than left as gaps somebody will feel guilty
   about. If desktop is ever promoted, the exempt cells are exactly the work.
2. **Design-system parity on web is committed.** The web host binds five layout widgets and one
   segment today. The design-system rows are therefore recorded as **gaps with owners**, not as
   "not applicable" — the matrix is supposed to apply pressure there.
3. **Conformance gates.** A red drill blocks the merge. This follows the house rule that a gate
   which cannot fail is not a gate, and it is the answer this project's own history argues for: a
   report nobody blocks on is a report nobody reads. The cost is flake tolerance, and Part 4 says
   what is done about it.

## Part 1 — The architecture

The instinct is to write the tests once and run them everywhere. **That is not available here, and
pretending otherwise is how this goes wrong.** The thing under test on the accessibility rows is
UIKit's `UIAccessibility` on iOS, Android's `AccessibilityNodeInfo`, and the DOM's accessibility
tree on web. There is no shared surface. The same is true of storage, of the network stack, and of
the garbage collector.

So the sharing happens one level up. Three things are shared; the test bodies are not.

**1. The claim.** Every promise this architecture makes is a numbered entry in Part 2, phrased as
something observable from outside. `D4` means the same thing on every client, and is the unit the
matrix is scored in.

**2. The report grammar.** Every drill, on every client, in every language, emits the same lines:

```
CONF <id> PASS|FAIL|SKIP <detail>
CONF RESULT client=<android|ios|desktop|web> passed=<n> failed=<n> skipped=<n>
```

That is the whole contract, and it is deliberately a text format rather than a library, because the
four clients are Kotlin/JVM, Kotlin/Native, Kotlin/Wasm and a browser — and a shared *library* would
have to exist in all four, which is precisely the constraint that made the test bodies unshareable
in the first place. A text grammar has no such problem. One aggregator reads all four runs and
writes the matrix.

A `SKIP` must carry a reason and is not a pass. Skips are counted in the matrix, so "we never ran
it" cannot masquerade as "it works".

**There is deliberately no verdict for "fails, but not our fault."** One was added and removed
inside a day, and the reason it was removed is the argument against it. The web drill reported that
a control could not be reached by keyboard; that looked like an upstream limitation, so it got an
escape hatch that recorded the failure without blocking. The failure was not real — the check had
read a `focusable` flag off the accessibility node instead of pressing Tab, and Compose routes
keyboard focus through the canvas rather than through per-element `tabindex`. **The escape hatch
legitimised a wrong conclusion within an hour of existing**, which is exactly what such a mechanism
is for and exactly why this catalogue does not have one. A claim passes, does not apply, or fails.

**3. The tier.** Each claim is assigned the cheapest instrument that can honestly settle it:

| Tier | Instrument | Covers | Runs |
|---|---|---|---|
| **S — shared** | `commonTest` in `dogwood-wire` / `dogwood-host` | Claims about code every client actually shares | Every build, gates the merge |
| **C — client drill** | A launch flag on the sample, printing the grammar above | Claims about platform machinery: accessibility trees, network stacks, storage, collectors | Every build on the two shipping clients; see Part 4 |
| **N — not applicable** | — | Must carry a written reason and a date | — |

**Tier S is the goal for every claim it can honestly hold**, and the discipline is that a claim
lands in tier C only when the platform is genuinely the thing under test. `A1` (a batch applies
whole or not at all) is tier S because `BatchValidation.kt` is one implementation all four clients
share — proving it once proves it everywhere. `D4` (activation drives the guest) is tier C because
the accessibility layer that delivers the activation is different code on every client.

Getting that boundary wrong in the permissive direction is the expensive mistake: a claim marked S
that actually depends on platform behaviour is a claim nobody is testing on three of four clients,
and it will read as green.

## Part 2 — The capability catalogue

Rows are the architecture's own promises, taken from the specifications rather than invented here.
"Today" is the honest state as of 2026-09-04.

### A. Protocol and containment

| ID | Claim | Tier | Today |
|---|---|---|---|
| A1 | A batch applies whole or not at all; a rejected one leaves the previous tree drawing | S | ✅ `TransactionalApplyTest`, `WebTreeTransactionTest` |
| A2 | An unknown widget tag becomes a placeholder and later indices in the batch stay correct | S + C | S ✅; C Android only |
| A3 | An unknown property on a widget with no affordance is ignored and the widget renders | S + C | S ✅; C Android only |
| A4 | An unknown property on a widget that owns an affordance withholds the widget | S + C | S ✅; C Android only |
| A5 | Out-of-range numeric values are clamped and reported, never thrown | S + C | S ✅; C Android + iOS |
| A6 | An undecodable batch is rejected whole and reported | S | ✅ |
| A7 | Node identity survives a code update, so `remember` is preserved | S | ✅ |

### B. Delivery and trust

| ID | Claim | Tier | Today |
|---|---|---|---|
| B1 | A payload signed by an untrusted key is refused before it runs | S + C | S ✅; C — |
| B2 | Key rotation: a manifest carrying both signatures is accepted by clients holding either | S | ✅ |
| B3 | A payload naming a dictionary version this client lacks is refused **before any guest code runs** | S + C | S ✅; C web only |
| B4 | Delivery failure leaves the last known-good payload serving | S | ✅ |

### C. Host resolution

| ID | Claim | Tier | Today |
|---|---|---|---|
| C1 | A theme swap repaints every reader with zero guest traffic | S + C | S ✅; C — |
| C2 | An unknown colour token, text style or icon degrades and is reported | S | ✅ |
| C3 | Formatting uses the device's locale; the number crosses, not the rendered string | S | ✅ |
| C4 | Plural category comes from host rules, and a language without rules says so | S | ✅ |
| C5 | A recipe re-resolves when the environment changes, with no wire traffic | S + C | S ✅; C — |

### D. Interaction and accessibility

| ID | Claim | Tier | Today |
|---|---|---|---|
| D1 | Guest-composed text reaches the platform's accessibility layer | C | ✅ iOS, Android |
| D2 | Every exposed element announces something — no anonymous elements | C | ✅ iOS, Android |
| D3 | A guest-composed control is exposed **as a control**, not as text | C | ✅ iOS, Android |
| D4 | Activating through the accessibility layer drives the guest and changes the tree | C | ✅ iOS, Android, web — including by keyboard on web |
| D5 | The screen scrolls through the accessibility layer | C | ✅ iOS, Android |
| D6 | Text input is host-authoritative: mask, limit and counter apply where the typing is | S + C | S ✅; C — |
| D7 | A disabled control is announced as disabled | C | ✅ Android; iOS reports none on screen |
| D8 | A guest can move focus, and a code update neither loses it nor takes the keyboard back | S | ✅ both halves — guest and host binding |
| D9 | A guest can drive and observe a scroll position on a declared quantum, and it survives a code update | S | ✅ both halves — guest and host binding |

### E. Lifecycle and resources

| ID | Claim | Tier | Today |
|---|---|---|---|
| E1 | State survives process death and is restored | S + C | S ✅; C Android + iOS |
| E2 | An evicted experience cold-starts and restores rather than starting over | C | Android + iOS |
| E3 | A code update leaves no live reference to the previous guest generation | S + C | S ✅ (JVM); C iOS soak |
| E4 | No cross-language reference cycles | C | iOS only — genuinely iOS-shaped, see Part 5 |

### F. Network and security

| ID | Claim | Tier | Today |
|---|---|---|---|
| F1 | Data requests are default-deny; a host must be named to be reachable | S + C | ✅ S; C Android, iOS |
| F2 | Every redirect hop is re-checked against the allow rule | S + C | ✅ S; C Android, iOS |
| F3 | Images are policed on their own allow rule, not the data one | S + C | S ✅; C Android |
| F4 | Cleartext is opted into per host and per scheme | S + C | ✅ C Android, iOS — including the `file://` case that shipped |

### G. Performance

| ID | Claim | Budget | Tier | Today |
|---|---|---|---|---|
| G1 | Guest recomposition p95 | 8 ms | C | Android, iOS, desktop |
| G2 | Batch crossing, per-frame | 4 ms | C | Android, iOS, desktop |
| G3 | Collection pause p99 | 16.7 ms | C | Android, iOS, desktop |
| G4 | Cold start to first composition | 500 ms | C | Android, iOS, desktop |
| G5 | Time to first frame, throttled | recorded, not gated | C | web only |

## Part 3 — The matrix

**Generated by `tools/conformance/aggregate.py`.** Do not edit by hand: a matrix that drifts from
reality is worse than none, because it is a document asserting that something is tested.

```
tools/conformance/run-all.sh
```

Last generated 2026-09-06, with the performance budgets graded:

| Claim | android | desktop | ios | web |
|---|---|---|---|---|
| A1 | ✅ | ✅ | ✅ | ✅ |
| A2 | ✅ | ✅ | ✅ | ✅ |
| A3 | ✅ | ✅ | ✅ | ✅ |
| A4 | ✅ | ✅ | ✅ | ✅ |
| A5 | ✅ | ✅ | ✅ | ✅ |
| A6 | ✅ | ✅ | ✅ | ✅ |
| A7 | ✅ | ✅ | ✅ | ✅ |
| B1 | ✅ | ✅ | ✅ | ✅ |
| B2 | ✅ | ✅ | ✅ | ✅ |
| C1 | ✅ | ✅ | ✅ | ✅ |
| C2 | ✅ | ✅ | ✅ | ✅ |
| C3 | ✅ | ✅ | ✅ | ✅ |
| C4 | ✅ | ✅ | ✅ | ✅ |
| C5 | ✅ | ✅ | ✅ | ✅ |
| D6 | ✅ | ✅ | ✅ | ✅ |
| D8 | ✅ | ✅ | ✅ | ✅ |
| D9 | ✅ | ✅ | ✅ | ✅ |
| E1 | ✅ | ✅ | ✅ | ✅ |
| E2 | ✅ | ✅ | ✅ | ✅ |
| E3 | ✅ | ✅ | ✅ | ✅ |
| F1 | ✅ | ✅ | ✅ | ✅ |
| F2 | ✅ | ✅ | ✅ | ✅ |
| F3 | ✅ | ✅ | ✅ | ✅ |
| F4 | ✅ | ✅ | ✅ | ✅ |
| D1 | ✅ | n/a | ✅ | ✅ |
| D2 | ✅ | n/a | ✅ | ✅ |
| D3 | ✅ | n/a | ✅ | ✅ |
| D5 | ✅ | n/a | ✅ | ✅ |
| D4 | ✅ | n/a | ✅ | ✅ |
| D7 | ✅ | n/a | ✅ | · |
| G1 | · | n/a | · | — |
| G2 | · | n/a | · | — |
| G3 | · | n/a | · | — |
| G4 | · | n/a | · | — |
| E4 | n/a | n/a | ✅ | n/a |
| G5 | — | n/a | — | ✅ |

✅ met · · nothing here to judge · n/a exempt, see `exempt.tsv` · ❌ failed · — gap

- `desktop` is not graded on D: desktop is a development loop, not a shipping target
- `desktop` is not graded on G: desktop is a development loop, not a shipping target
- `desktop` is not graded on E4: one collector, so no cross-language cycles are possible
- `android` is not graded on E4: one collector, so no cross-language cycles are possible
- `web` is not graded on E4: one heap, so no cross-language cycles are possible
- `web` is not graded on F: the web profile's network policy is the browser's Content Security Policy, enforced by the browser rather than by Dogwood; ADR-032 records that this is weaker than the mobile guarantee rather than equal to it

- **android**: pass 37, skip 4
- **desktop**: pass 24
- **ios**: pass 38, skip 4
- **web**: pass 31, skip 1

**Which clients a test covers is stated per claim, never inferred.** A first version of the mapping
had a `shared` scope meaning "code every client compiles", and it was wrong within minutes:
`dogwood-web` depends on `dogwood-wire` alone and **not** on `dogwood-host`, so twenty-one
`dev.dogwood.host.*` tests filled green cells for a client that does not compile the code under
test. The web host has its own tree, its own bindings and its own decoder. That is the one way this
table can lie, the plan predicted it in Part 1, and it happened on the first attempt — which is the
argument for `claims.tsv` naming clients explicitly rather than a scope keyword doing it silently.

**The three worst gaps, and they remain the plan's priorities:**

1. ~~Web is graded on 8 claims of 28.~~ ✅ **Closed by
   [ADR-041](../adrs/layer-5/ADR-041-one-host-core-split-at-the-zipline-seam.md): web is graded on
   29 of 30.** The three kinds of gap resolved as the analysis predicted — the earnable claims were
   inherited rather than earned one at a time, because `dogwood-web` now renders through
   `dogwood-host`'s core instead of a copy of it; the exempt ones are in `exempt.tsv` with ADR-032
   as the reason; and the ones called "blocked on parity" were exactly the ones the split
   unblocked. `WebTree` and `WebBindings` are deleted — 627 lines of second implementation.
2. ~~Accessibility exists on one client of three that need it.~~ ✅ Closed: all three shipping
   clients now assert `D1`–`D5`, and `D7` on the two that have a disabled control on screen.
3. **Nothing outside a unit test has ever checked network policy on a real client.** `F1`, `F2` and
   `F4` are tier S only everywhere. The one policy defect this project shipped — `file://` served
   from the app container — was found by reading code, not by a test.
4. **Skew containment ran once, on Android, in a way that is documented as deliberately
   uncommitted.** The version-8 surface it needs is re-created by hand each time, which means in
   practice it runs approximately never.

## Part 4 — How it runs, and what stops it rotting

**Gating.** Tier S runs in `./gradlew build` and blocks the merge today. Tier C blocks the merge on
Android and iOS once each client's drill exists; web joins when its drill exists.

**Flake is the real cost of gating**, so three rules, each of which this project already learned the
hard way:

- **Every drill carries a negative control**: the gate is watched to fail with the fix reverted.
  A drill without a demonstrated failure is not admitted to the gate.
- **A red claim is validated before it is believed** (`AGENTS.md` §1.5). Two accessibility claims
  failed against a *property* — a node's own text, a node's `focusable` flag — where the real
  screen reader aggregates a subtree and the real browser routes focus through the canvas. Neither
  was a real defect.
- **A drill that cannot run REFUSES rather than fails.** The accessibility drill refuses when no
  assistive technology is running, because twenty failures from one cause reads as a broken screen
  rather than an unconfigured machine. Refusal is a distinct exit code and does not report as a
  test failure.
- **A new drill soaks before it gates**, and the number is chosen for what it is measuring rather
  than copied. The leak suite ran fifty times because its risk was a *garbage collector* behaving
  differently run to run — genuinely random, so only repetition characterises it. The device drills'
  risk is different: every failure seen while building them had a deterministic cause with a name
  (stray Chrome helpers starving SwiftShader, the Kotlin/Wasm klib checker, a shell pipeline
  reporting `SIGPIPE` as a product failure), and repetition does not characterise a cause that is
  already understood. **Ten consecutive full-suite runs, 2026-09-05, all green**
  (`tools/conformance/soak-2026-09-05.log`) — roughly an hour of real device work. If a flake with
  no identified cause ever appears, that is when fifty becomes the right number again.

**The matrix is generated, not maintained.** `tools/conformance/aggregate.py` reads the `CONF` lines
from every client's run and writes Part 3. A hand-maintained matrix drifts from reality, and a
matrix that drifts is worse than none — it is a document that says the thing is tested.

**The new-client checklist.** When a fifth client is proposed, Part 2 *is* the specification of what
it must do, and the matrix row it starts with is empty. That is the deliverable of this plan: the
question "what does it take to add a client?" has a written answer that is not "find out".

## Part 5 — Deliberately not covered, with reasons

Recorded so that an absence is a decision somebody can point at.

- **Whether the speech is good.** Nothing automated hears anything. `D1`–`D5` prove the surface is
  correct and operable; whether "Expand" is the right word, and whether the reading order makes
  sense, is a human judgement and stays one.
- **Typing and selection as experienced.** `D6` proves the host is authoritative for the text; the
  input method editor, the keyboard appearing and selection handles are driven by touch and
  hardware input that `simctl` and `adb` cannot faithfully supply.
- **`D7`, disabled controls, is unclaimed because the sample has no disabled control on screen.**
  The check is written and reports zero. Adding one to the sample is a small task and is in Part 6;
  until then the row is honest about being untested rather than counted as passing.
- **`E4`, cross-language cycles, is iOS-shaped and stays there.** It exists because Kotlin/Native's
  tracing collector and Objective-C's reference counting cannot see each other's graphs. Android and
  desktop have one collector; web has one heap. Marking this `n/a` elsewhere is a statement about
  the platforms, not an exemption.
- **`D8`'s tier-C half has no drill.** The shared tests prove what crosses the boundary — a request
  is a direction and a count, a holder that was not passed sends nothing, and a replacement guest
  neither reissues the last request nor loses the ability to make a new one. What no assertion here
  covers is whether the **keyboard actually moves**, which is the same limitation `D6` records: the
  input method is driven by hardware input that `adb` and `simctl` cannot faithfully supply. It was
  verified by hand on an Android emulator, reading `dumpsys input_method` rather than a screenshot
  ([ADR-043](../adrs/layer-5/ADR-043-holders-are-declared-on-the-surface.md)), and a hand-run is
  evidence for a record and not for a matrix cell. The cell stays blank.
- **`D8` and `D9` are tier S on both halves**, which is a correction rather than an achievement.
  They were first written as "S for what crosses, C by hand for what the host does", citing ADR-014's
  claim that exercising a host binding in a composition needs "a Compose UI test harness this project
  does not have". That claim was false when ADR-014 made it and had been repeated twice since:
  `dogwood-host`'s `jvmTest` source set carries `compose.uiTest`, and a dozen tests already used
  `runComposeUiTest`. `FocusMirrorTest` and `ScrollMirrorTest` assert the host halves in a real
  composition, and each was watched to fail with the line it covers removed. What *remains* outside
  any assertion is the same thing `D6` records — the keyboard itself, driven by hardware input that
  `adb` and `simctl` cannot supply.
- **Web's network policy is the browser's**, per ADR-032. The claim holds; the instrument is a
  policy header rather than a drill, and the guarantee is weaker than the mobile one rather than
  equal to it.

## Part 6 — Rollout

Each step is a branch and a pull request, and each ends with the matrix regenerated.

**Accessibility comes first, ahead of the machinery.** The first draft of this plan put the grammar
and the aggregator first, on the argument that a catalogue not generated from real runs decays into
a wish list. That argument is still true, but it bought no coverage for two steps, and the gap that
prompted the whole plan is accessibility on three clients that do not have it. So the order is
inverted, and the cost of inverting it is paid rather than ignored: **the Android drill emits the
`CONF` grammar from its first commit**, so step 3 is writing the aggregator against output that
already exists rather than retrofitting three drills that grew their own formats. Defining a text
format is cheap; discovering it was wrong across four implementations is not.

1. ✅ **Accessibility on Android — done.** `UiAutomation` is itself an accessibility service, so
   the drill is an instrumented test reading the tree out of process, the way TalkBack does — the
   opposite of iOS, where no such access exists. 9 claims, gated, negative-controlled. A disabled
   control was added to the sample so `D7` is claimable rather than permanently unknown. **It found
   a cross-client inconsistency introduced by the iOS fix**: Android's Material 3 merges a text
   field's label where iOS's does not, so naming the field in semantics made Android announce it
   twice — "Card number Card number 0/16". Fixed by drawing the label with
   `clearAndSetSemantics`, and re-verified green on both clients. That is the first finding this
   plan's premise predicted: a defect visible only when two clients are graded on one claim.
2. ✅ **Accessibility on web — done.** Compose draws to a canvas, which has no intrinsic
   accessibility, so the first question was whether a tree exists at all. It does: Compose
   Multiplatform publishes a live DOM of roles and names, **inside a shadow root** — which is why
   the drill reaches it through `Accessibility.getFullAXTree` and node handles rather than through
   selectors, and why the first draft found nothing. `D4` passes: a synthesised click on the node
   the accessibility tree named drives the guest and the counter moves. `D4-keyboard` is a
   **known gap** — the elements carry no `tabindex`, so keyboard navigation cannot reach them.
3. ✅ **The grammar and the aggregator — done.** `tools/conformance/`, generating Part 3 from three
   real runs. It earned its keep immediately by catching a **stale committed result**: the saved
   Android file was from the negative-control run, and the generated table showed `D7` red for a
   client that passes. A hand-written matrix would have said whatever it last said.
4. ✅ **Retrofit the drills to the grammar.** Done for the test suites, which was the large half:
   `from_tests.py` maps test classes onto claims and the matrix now covers groups A, B, C, E and F
   as well as D. Still outstanding: the leak soak, Phase 0 and page-weight harnesses, which produce
   numbers rather than verdicts and need a budget attached to each before they can emit `PASS`
   (folded into step 8 below, since a budget is what turns a measurement into a gate).
5. ✅ **Network policy drills on Android and iOS — done.** Both hosts now issue real requests at a
   witness server that records what it received, so a refusal is proved by the **absence of a
   request** rather than by the client reporting one — a client can report a refusal and still have
   opened the connection. The existing tests could not make that claim and said so: the Java Virtual
   Machine one passes a client that fails if called, and the iOS one's header admits it "does not
   test that NSURLSession" behaves.

   It found a cross-client divergence on its first run. A blocked redirect was an explicit refusal
   on Android and a bare `302` with no `failure` on iOS — which a guest checking `failure != null`
   would read as a successful request. The iOS behaviour was deliberate and documented ("the 3xx
   itself is a perfectly ordinary response for a guest to see"), and the reasoning did not survive
   the question: this host follows redirects on the guest's behalf, so a 3xx never reaches a guest
   by any other route, and its only meaning is "policy stopped a hop". iOS now refuses the way
   Android does. Negative control: disabling the allow check turns `F1` and `F4` red *with the
   witness recording the request that got through*.
6. ✅ **Web correctness evidence — done, and not the way this step expected.** It planned to write
   web-specific tests against `WebTree` and the web bindings. The parity build-out deleted both, so
   the claims are inherited from the shared suite instead: one implementation, one set of tests,
   graded on four clients. The step that would have written a second test suite was made
   unnecessary by not having a second implementation.
7. ✅ **Skew containment is re-runnable.** `tools/skew-drill/run.sh` does the whole two-build
   procedure -- install the client at version N, patch the surface and bump to N+1, rebuild only
   the payload, read the rendered tree -- and restores the surface on every exit path including a
   failure, because a permanently skewed surface is a permanently failing lock. Claims `A2`–`A4`
   with a control and a report check; negative control turns `A4` red while `A3` stays green, which
   is the useful part: the two rules are independent and the drill tells them apart.

   Two things the automation had to learn, both recorded in the drill's README. Property tags are
   append-only and the lock enforced it against the drill's own first patch. And `grep -q` inside
   `set -o pipefail` reports a success as a failure -- it exits on the first match, `SIGPIPE`s its
   feeder, and `pipefail` calls that a failed pipeline; the arrival check said "not yet" against a
   file that plainly contained the marker, twice, before the pipe was removed altogether.

   **Still open: `A2`–`A4` end-to-end on iOS and web.** Both now render through the same core, so
   the shared tests cover the rules; what is missing is the two-build procedure on those clients,
   which needs a skewed payload served to an already-installed binary.
8. ✅ **Budgets for the numeric harnesses.** `budgets.tsv` is where the roadmap's thresholds stop
   being prose: `from_phase0.py` reads the Phase 0 results and `from_web_weight.py` the page
   weight, and both emit verdicts. **The gate-validity rule is asymmetric, deliberately.** No host
   here is gate-valid — not the development machine, not the simulator, and not the Pixel 10 Pro
   either, which is a flagship where the Phase 0 gate names an entry tier (Layer 4 ADR-008). So a
   number *within* budget on such a host is a `SKIP`, never a `PASS`, because a comfortable result
   from fast hardware says nothing about slow hardware; a number *over* budget is a `FAIL`, because
   favourable hardware exceeding a budget is damning precisely for being favourable. That keeps
   these numbers doing the one thing they can honestly do today, which is catch regressions.
   Where two hosts speak for one client the slower is preferred — a phone over an emulator running
   on the development machine's own processor.

   The leak soak is **not** a claim and is not given one. It measures whether the leak *evidence*
   is stable, which is a precondition for gating rather than a promise about the product; it
   belongs to step 8.
9. ✅ **Gating, split by what each environment can honestly grade.**

 **Tier S gates in continuous integration** (`.github/workflows/conformance.yml`): the build, the
   shared-code claims via `from_tests.py`, and the page-weight budget — the one performance number
   that is a property of the build rather than of the machine. It runs on every push and pull
   request, and a claim whose evidence did not run fails it rather than reading as absent.

   **Tier C gates locally and before a release**, through `tools/conformance/run-all.sh`, which
   exits non-zero on any red cell. It cannot run on a hosted runner: it needs a booted iOS
   simulator with VoiceOver enabled, an attached Android device, a browser with a graphics stack,
   and the guest being served. Wiring it into continuous integration anyway would produce a green
   tick that means less than it appears to, which is the failure this plan exists to avoid — so the
   workflow file says where the boundary is instead of hiding it.

   **The workflow's first run failed, and the lesson is the one this plan keeps relearning.** It
   was written and merged without ever being run — asserted to pass rather than watched to. What
   it caught was a genuine defect: `LeakDetectorTest.aDetachedNodeIsNotReportedAsALeak` set a
   400 ms threshold on a garbage-collection negative control, which is fine on a fast development
   machine and expires before the collector next runs on a slower one. `dogwoodLeakDetector`'s own
   default is ten seconds and its documentation says why — "a reference merely waiting for the next
   collection is not a leak" — and `BrowserLeakWatcher`, written the same day, carries the identical
   contract. This test predated it and had the defect latent, waiting for a slower machine.

   Note the asymmetry that made it survive so long: the *positive* control in the same class uses a
   200 ms threshold and is safe, because slower hardware only makes a retained reference more
   likely to be reported. Only the negative control is exposed to the timing, which is the general
   rule — a test that asserts something was collected is a test about a collector.

*(Lifecycle and host-resolution drills on the shipping clients — previously step 7 — are folded
into step 6 for web and deferred for mobile: `C1`, `C5`, `E1`–`E3` already carry shared-test
evidence on Android and iOS, so a device drill there verifies wiring rather than behaviour, and
ranks below everything above.)*

---

## Part 7 — Carried forward, not closed

Five things, each recorded where somebody will meet it rather than left to be rediscovered.

- **`A2`–`A4` end to end on iOS and web.** Both clients render through the same host core now, so
  the shared tests cover the containment *rules*; what is missing is the two-build procedure — a
  skewed payload served to an already-installed binary — on those two platforms. Android has it
  (`tools/skew-drill`), and the shape is portable.
- **The performance budgets cannot be closed by any host that exists.** `G1`–`G4` read `SKIP`
  everywhere, correctly: the Phase 0 gate names a low-end 2022-tier Android device that this
  project decided not to acquire ([Layer 4 ADR-008](../adrs/layer-4/ADR-008-gate-device-not-available.md)).
  The grading is wired and asymmetric, so the first such device to run it closes or reopens the
  gate without further work, and a regression on faster hardware still fails today.
- **`D8` and `D9` are asserted on one Java Virtual Machine, and claimed for four clients.** Both
  halves are shared code — the guest holder and the host mirror alike, since the mirror lives in
  `dogwood-host`'s transport-free core — so the claim is sound in the way every other tier-S row is.
  What is untried is whether *taking the keyboard away* and *scrolling* mean the same thing on each
  platform: `FocusManager.clearFocus()` and `Modifier.verticalScroll` are one call each and three
  implementations, and the redirect-policy row is the standing reminder that a shared rule can refuse
  on one client and quietly not on another. Running `runComposeUiTest` on the other targets is the
  instrument, and it is not wired up.
- **`LazyListMirror` has no host test.** `FocusMirror` and `ScrollMirror` do; the holder that
  established the pattern is still demonstrated on a device rather than asserted, which is now an
  omission rather than a limit — [ADR-014](../adrs/layer-5/ADR-014-live-state-holders.md) has had its
  claim to the contrary withdrawn.
- **Tier C does not gate in continuous integration**, and cannot: it needs a booted simulator with
  VoiceOver, an attached device, a browser with a graphics stack and the guest being served. It
  gates locally and before a release. If this project ever acquires a device lab, the command to
  point at it is `tools/conformance/run-all.sh` and nothing else changes.

## Part 8 — What the rollout actually found

Worth separating from the machinery, because the machinery is only justified by this.

**Four defects, none of which any existing test could have caught:**

- A text input reached VoiceOver with **no name at all**, on a screen that had authored the label,
  crossed it over the wire, resolved it host-side and drawn it.
- The same fix then made Android **announce the label twice** — Material 3 merges a text field's
  label where iOS's does not. Visible only when two clients were graded on one claim.
- A blocked redirect was an explicit refusal on Android and a **bare `302` with no `failure`** on
  iOS, so a guest checking `failure != null` would read a blocked request as a successful one.
- `HostTree.clear()` was missing from the mobile hosts entirely — they build a fresh tree per
  experience and never met the bug the web host had already fixed.

**A false alarm the rule caught, and the drill now catches by itself.** `D7` went red on iOS with
`1: [<no label> traits=0x101]` — a disabled control announcing nothing, which would be a real
accessibility defect. It was not one. iOS publishes only what is on screen, and the drill's scroll
loop stops at the *first* frame where its target is reachable, so whatever comes next sits at the
viewport edge with its traits published and its name clipped. Adding an unrelated section to the
sample moved where the loop stopped, and a button that had not changed went anonymous. The finding
was a hypothesis about a control and was really an observation about a viewport. The drill now does
what `AGENTS.md` §1.5 asks of a person — brings the element further into view and looks again before
reporting — and the failure message names the element by its neighbours rather than printing `[]`,
which is what the first version did because its helper filtered empty labels out of a claim about
missing labels.

**One defect in the machinery itself, found by running it.** `tools/skew-drill/run.sh` patches the
surface and restores it on every exit path, which is right; it restored it with `git checkout --`,
which is a different operation wearing the same clothes. That restores the *committed* content, so
it silently discarded whatever uncommitted work was in those four files — and it did, to the surface
change that added `@Holder`, mid-review, with no message and no failure. The drill cannot tell its
own patch from a developer's. It now saves the files it is about to touch and copies those back.
The symptom is worth remembering: the next build failed in a file the drill never mentions, on a
parameter that had existed minutes earlier.

**And a recurring shape, now met a sixth time**: evidence that produced no evidence. The `shared`
scope that filled twenty-one green cells for a client that does not compile the code under test.
A `D4-reverse` check that passed *because* its subject had failed. A page-weight harness whose
number nothing compared to a threshold. Each was found by asking a question the machinery was
supposed to answer and looking at what came back.

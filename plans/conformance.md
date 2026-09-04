# The conformance plan: one capability list, four clients

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
| F1 | Data requests are default-deny; a host must be named to be reachable | S + C | S ✅; C — |
| F2 | Every redirect hop is re-checked against the allow rule | S + C | S ✅; C — |
| F3 | Images are policed on their own allow rule, not the data one | S + C | S ✅; C Android |
| F4 | Cleartext is opted into per host and per scheme | S + C | S ✅; C — |

### G. Performance

| ID | Claim | Budget | Tier | Today |
|---|---|---|---|---|
| G1 | Guest recomposition p95 | 8 ms | C | Android, iOS, desktop |
| G2 | Batch crossing, per-frame | 4 ms | C | Android, iOS, desktop |
| G3 | Collection pause p99 | 16.7 ms | C | Android, iOS, desktop |
| G4 | Cold start to first composition | 500 ms | C | Android, iOS, desktop |
| G5 | Time to first frame, throttled | recorded, not gated | C | web only |

## Part 3 — The matrix

**Generated by `tools/conformance/aggregate.py` from the runs in `tools/conformance/*.conf`.** Do
not edit by hand: a matrix that drifts from reality is worse than none, because it is a document
asserting that something is tested. Regenerate with:

```
tools/conformance/run-android.sh && tools/conformance/run-web.sh && tools/a11y-drill/run.sh
python3 tools/conformance/aggregate.py tools/conformance/build/*.conf
```

Last generated 2026-09-04, from the accessibility group:

| Claim | android | ios | web |
|---|---|---|---|
| D1 | ✅ | ✅ | ✅ |
| D2 | ✅ | ✅ | ✅ |
| D3 | ✅ | ✅ | ✅ |
| D5 | ✅ | ✅ | ✅ |
| D4 | ✅ | ✅ | ✅ |
| D7 | ✅ | ✅ | · |

✅ met · · not applicable here · ❌ failed · — not run

- **android**: pass 9
- **ios**: pass 11
- **web**: pass 6, skip 1

Desktop does not appear because it is a development loop, not a shipping target, and the
accessibility claims are out of scope for it (decision 1, Part 0). It will appear when the
correctness groups are retrofitted to the grammar.

**The rest of the catalogue is not in this table yet**, because only the accessibility drills emit
the grammar so far. Groups A, B, C, E, F and G are covered by the drills and unit tests listed in
Part 2 and are retrofitted in step 4 of the rollout. Until then, Part 2's "Today" column is the
hand-written record and this table is the generated one — the difference between them is exactly
the retrofit backlog.

**The three worst gaps, and they remain the plan's priorities:**

1. ~~Accessibility exists on one client of three that need it.~~ ✅ Closed: all three shipping
   clients now assert `D1`–`D5`, and `D7` on the two that have a disabled control on screen.
2. **Nothing outside a unit test has ever checked network policy on a real client.** `F1`, `F2` and
   `F4` are tier S only everywhere. The one policy defect this project shipped — `file://` served
   from the app container — was found by reading code, not by a test.
3. **Skew containment ran once, on Android, in a way that is documented as deliberately
   uncommitted.** The version-8 surface it needs is re-created by hand each time, which means in
   practice it runs approximately never.

## Part 4 — How it runs, and what stops it rotting

**Gating.** Tier S runs in `./gradlew build` and blocks the merge today. Tier C blocks the merge on
Android and iOS once each client's drill exists; web joins when its drill exists.

**Flake is the real cost of gating**, so three rules, each of which this project already learned the
hard way:

- **Every drill carries a negative control.** The accessibility drill's gate was checked by
  reintroducing the defect and confirming `exit=1`. A drill without a demonstrated failure is not
  admitted to the gate.
- **A drill that cannot run REFUSES rather than fails.** The accessibility drill refuses when no
  assistive technology is running, because twenty failures from one cause reads as a broken screen
  rather than an unconfigured machine. Refusal is a distinct exit code and does not report as a
  test failure.
- **A new drill soaks before it gates.** Fifty consecutive runs, as the leak suite did. Until then
  it reports, so its flake rate is known before anybody's merge depends on it.

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
4. **Retrofit the remaining drills** — skew, leak soak, Phase 0, page weight — to emit `CONF` lines.
   No new coverage; this is what makes the matrix cover the whole catalogue rather than one row of
   it.
5. **Network policy drills on Android and iOS** (`F1`, `F2`, `F4`). The highest-value gap after
   accessibility: the one policy defect this project shipped was in exactly this area, and a unit
   test did not catch it.
6. **Skew containment on iOS and web** (`A2`–`A4`), and make the Android drill re-runnable rather
   than hand-rebuilt.
7. **Lifecycle and host resolution drills** (`C1`, `C5`, `E1`–`E3`) on the two shipping clients.
8. **Gate the tiers**, in the order the drills soak clean.

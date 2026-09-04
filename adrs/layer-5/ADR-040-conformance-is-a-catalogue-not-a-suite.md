# ADR-040: Conformance Is a Catalogue and a Grammar, Not a Shared Test Suite

**Date:** 2026-09-04
**Status:** Accepted

## 1. Context & Problem Statement

Dogwood serves four clients — Android, iOS, desktop and web — from one guest payload. Verification
grew the other way round. Every drill in `tools/` was built to answer a problem that had just
happened, on the client where it happened: skew containment on Android, accessibility on iOS, the
leak soak on iOS, page weight on web. Each is a good instrument. Together they are a lopsided map,
and the lopsidedness is invisible unless somebody lays them side by side.

The concrete symptom that prompted this: the accessibility drill added in
[ADR-039](ADR-039-accessibility-is-asserted-not-inspected.md) asserts six real things about
VoiceOver, and **nothing equivalent exists on Android or web**. Not because those clients are known
to be worse, but because nobody had been bitten there yet.

The failure mode is building the same experience four times and discovering, four separate times,
what the first client already knew.

## 2. Decision

**Share the capability catalogue and the report grammar. Do not try to share the test bodies.**

The instinct is a cross-platform test suite written once. That is not available and pretending
otherwise is how this goes wrong: the thing under test on the accessibility rows is UIKit's
`UIAccessibility`, Android's `AccessibilityNodeInfo`, and the DOM accessibility tree. There is no
shared surface, and the same holds for storage, the network stack and the collector.

So three things are shared, one level up from the tests, and the test bodies are not:

**1. The catalogue.** Every promise the architecture makes is a numbered claim in
[`plans/conformance.md`](../../plans/conformance.md), phrased as something observable from outside.
`D4` — "activating through the accessibility layer drives the guest" — means the same thing on every
client and is the unit the matrix is scored in.

**2. The report grammar**, deliberately a text format rather than a library:

```
CONF <id> PASS|FAIL|SKIP <detail>
CONF RESULT client=<android|ios|desktop|web> passed=<n> failed=<n> skipped=<n>
```

A shared *library* would have to exist in Kotlin/JVM, Kotlin/Native, Kotlin/Wasm and a browser —
which is the same constraint that made the test bodies unshareable. A text grammar has no such
problem, and one aggregator reads all four. A `SKIP` carries a reason and is counted separately,
so "never ran" cannot present as "works".

**3. The tier**, assigning each claim the cheapest instrument that can honestly settle it: **S** for
claims about code every client shares, provable once in `commonTest`; **C** for claims where the
platform genuinely *is* the thing under test; **N** for not applicable, with a written reason and a
date.

The boundary between S and C is the load-bearing judgement. `A1` (a batch applies whole or not at
all) is S because `BatchValidation.kt` is one implementation all four clients share. `D4` is C
because the layer delivering the activation is different code on every client. **Getting this wrong
in the permissive direction is the expensive error**: a claim marked S that actually depends on
platform behaviour is untested on three clients and reads as green.

Three scoping decisions were taken with the project owner rather than assumed:

- **Desktop is a development loop, not a shipping target**, so it is graded on correctness only.
  Accessibility, performance and platform-integration rows are `n/a` *with that reason*, so the
  exempt cells are exactly the work if it is ever promoted.
- **Design-system parity on web is committed**, so the web host's five-widget surface produces
  **gaps with owners** rather than `n/a`. The matrix is meant to apply pressure there.
- **Conformance gates**: a red drill blocks the merge.

## 3. Rationale & Research

**Why gate rather than report.** This project has now found four cases of evidence machinery that
produced no evidence — the dead `skew` variable, the tautological `--gufa` check, an accessibility
probe with no caller, an unsoaked leak suite. In each the instrument was right and nothing consumed
it. A conformance matrix nobody blocks on would be the fifth, one level up. The house rule already
says a gate that cannot fail is not a gate; this extends it to say a result that blocks nothing is
not a result.

**Why the matrix is generated rather than maintained.** A hand-written matrix drifts, and a drifted
matrix is worse than none, because it is a document asserting that something is tested. The
aggregator reads the `CONF` lines from real runs and writes the table.

**What gating costs, and the three rules that pay for it.** Device and browser drills are slower and
less stable than unit tests, so: every drill carries a demonstrated negative control before it is
admitted to the gate; a drill that cannot run **refuses** with a distinct exit code rather than
failing, because twenty failures from one cause reads as a broken product rather than an
unconfigured machine; and a new drill **soaks fifty runs** before it gates, so its flake rate is
known before anybody's merge depends on it. All three are practices this project already arrived at
individually — from ADR-039's negative control, from the accessibility drill's refusal path, and
from the leak soak.

**Why steps 1 and 2 of the rollout buy no new coverage and are still first.** They build the grammar
and retrofit the four existing drills to it. A catalogue not generated from real runs becomes a wish
list within a month, and the plan's whole premise is that a document nobody regenerates is the
failure mode rather than the fix.

## 4. Unstated Assumptions

- **The catalogue is assumed to be complete enough to be worth grading against.** It is drawn from
  the specifications rather than invented, but a promise the specifications never wrote down is
  also absent here, and the matrix would show green over it. The catalogue is a living document;
  the honest claim is that it covers what is written down, not everything that is true.
- **Claim identity across clients is assumed to be meaningful.** `D4` on Android
  (`performAction(ACTION_CLICK)` through `AccessibilityNodeInfo`) and on iOS
  (`accessibilityActivate`) are different calls, and the plan asserts they are the *same claim*.
  That holds while both are "the call the screen reader makes to operate this control". If a
  platform's assistive technology worked another way, the claim would need splitting rather than
  forcing.
- **Tier S coverage is assumed to transfer.** Proving `A1` once covers four clients only because
  the code really is shared. A platform-specific override of shared behaviour would silently break
  that inference, which is an argument for keeping host divergence in `expect`/`actual` where it is
  visible rather than in per-client branches.
- **A generated matrix is only as current as its last run.** Cells carry the date of the run that
  produced them; a stale cell is a stale cell, not a passing one.

## 5. Updated Documents

- [`plans/conformance.md`](../../plans/conformance.md) — the catalogue, the matrix, and the rollout
  (new).
- [ADR-039: Accessibility Is Asserted, Not Inspected](ADR-039-accessibility-is-asserted-not-inspected.md)
  — its iOS drill becomes claims `D1`–`D5`, to be implemented on Android and web.
- [ADR-032: The Web Profile](ADR-032-the-web-profile.md) — its narrower surface is recorded as
  committed gaps rather than permanent exclusions.
- [ADR-033: The iOS Host Profile](ADR-033-the-ios-host-profile.md)
- [Layer 5: Host](../../specs/layer-5-host.md)

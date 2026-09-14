# The framework grade: rubric v1

**What this is, and what it is not.** A repeatable instrument for grading **Dogwood** against the
leading Server-Driven User Interface (SDUI) approaches, run by a **fresh agent with no stake in
this codebase** and no session context. It exists to tell this project where it stands and what to
fix next; the competitor columns are the yardstick, not the subject. **It is not a published
comparison and should not be read as one** — every competitor is graded in one pass against
Dogwood's own priorities, by a rubric written here, and none of their maintainers was consulted.
Where a competitor's row is unflattering the reasoning and sources are in the run report, and a
correction is welcome as an issue. The first run (2026-09-07) is the baseline in [`scores.md`](scores.md); the point
of writing the rubric down is that the second run is comparable to the first.

**The anti-gaming rule, stated up front.** The score improves by closing gaps, never by editing
this rubric. The rubric is versioned; a run records which version graded it, and a rubric change
resets comparability (the ledger says so at the row). Weights and anchors below are frozen as v1.

## Procedure

1. Spin one **fresh** general-purpose agent (no conversation context, no fork) with the verbatim
   prompt in [`prompt.md`](prompt.md).
2. The agent must **verify claims in-repo** (count components, count tests, read the conformance
   matrix and the adoption audit, check git history) rather than take documents at face value, and
   must **re-research each competitor's current state** on every run — the field moves (Redwood was
   discontinued between this project's start and its first grading).
3. The agent returns the full report card; the operator records the row in `scores.md` and files
   the full report under `results/YYYY-MM-DD.md`, both committed.

## Dimensions, weights, and grade anchors

Weights are a principal engineer's, betting a product. Letter grades map to numbers for the
weighted total: A+ 4.3 · A 4.0 · A− 3.7 · B+ 3.3 · B 3.0 · B− 2.7 · C+ 2.3 · C 2.0 · C− 1.7 ·
D+ 1.3 · D 1.0 · D− 0.7 · F 0.

### 1. Ease of adoption — weight 10%
Getting it into an **existing** application: packaging, docs, samples, platform integration cost.

- **A** — resolvable published artifacts, per-platform integration guides, a runnable end-to-end
  worked example, every shipping platform embeddable into an existing app without framework-side work.
- **B** — all of the above with one platform's embedding rough or one manual step undocumented.
- **C** — good documentation over a partially manual path.
- **D** — documentation and reality diverge: artifacts unresolvable outside the repository, or a
  shipping platform with no embedding artifact at all.
- **F** — cannot be adopted as published.

### 2. Authoring ergonomics — weight 10%
Writing screens: language, tooling, inner loop, testability.

- **A** — a mainstream language with real IDE support, a sub-minute live-reload loop, screen tests
  against public API, previews; migrating existing native screens in is near-mechanical.
- **B** — the above with one hole (e.g., migration is a port; previews cannot show degraded modes).
- **C** — a workable DSL or format with tooling.
- **D** — a bespoke format without loops/expressions or without tooling.
- **F** — no viable authoring path.

### 3. Flexibility & expressiveness — weight 15%
Can the server change **logic and structure**, or only data? Component extensibility, interaction,
animation and state depth. Grade the floor a product ships on today, not only the model's ceiling.

- **A** — server-delivered logic, state and structure; products extend the component set without
  engine changes; a component catalogue a real product could launch on.
- **B** — rich data-driven expressiveness (expressions, triggers, patches) or a full-logic model
  with a thin catalogue.
- **C** — data-only templates over a fixed registry.
- **D** — templates without conditionals.
- **F** — static layouts only.

### 4. Production readiness — weight 25%
Delivery security (signing, trust, rotation), un-ship (rollback, kill switch, crash-loop
quarantine), observability (crash triage, skew/telemetry), resource containment, minified-build
evidence, versioning policy, CI enforcement — **and whether the path has been walked**: graded on
evidence, with real-fleet deployment as the ceiling-setter. Machinery that exists but has never
run in production caps at C+.

- **A** — the full checklist, evidenced in production at scale.
- **B** — full checklist evidenced end-to-end outside production, plus real hosting/keys/rollout.
- **C** — strong machinery with the operational back half (hosting, keys, rollout server) missing.
- **D** — signing or rollback absent.
- **F** — unsigned remote code or no recovery story.

### 5. Performance & footprint — weight 10%
Runtime overhead, payload size, startup, web page weight — **on hardware representative of the
product's fleet**. Numbers taken only on fast hardware cap at C+ regardless of how good they are.

### 6. Platform coverage & consistency — weight 5%
How many shipping platforms, and how *evidenced* the parity is (a generated conformance matrix
outranks a claim; per-cell honest gaps outrank a uniform green).

### 7. Maturity, ecosystem & bus factor — weight 20%
Age, adopters, contributors, backing, survival odds over three years. This dimension is graded
**brutally and cannot be argued up by artifact quality** — it moves only with time, adopters, and
maintainers. A single-contributor unpublished 0.x project is an F here whatever else is true.

### 8. Maintainability of the system itself — weight 5%
Could a new team take it over safely: architecture docs and diagrams, decision records,
verification culture, checks inventory, generated-vs-hand documentation, enforced gates.

## Comparison set (v1)

Grade every dimension for: **Dogwood**, generic in-house JSON SDUI (as a category),
**Zipline + Redwood/Treehouse** as published, **DivKit**, **Beagle**, **Flutter RFW**, and the
**WebView** baseline; Meta Bloks / Airbnb / Lyft class systems in prose as the closed-source
proof-at-scale. Additions to the set are allowed (note them); removals are not.

## Output contract

The report must contain: the summary table (frameworks × dimensions, letter grades), per-dimension
analysis with the evidence cited (file paths for Dogwood; sources for competitors), the weighted
overall per framework, a "choose X if…" guide, Dogwood's top advantages and risks, and the
"what you'd have to believe" section against (a) JSON SDUI and (b) the Redwood lineage.

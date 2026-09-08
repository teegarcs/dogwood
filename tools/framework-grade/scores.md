# The ledger

One row per run. The full report is filed under `results/` and linked; the row is for the trend.
Numeric overall per the rubric's mapping, weighted by rubric weights. A rubric version change
breaks comparability and must be flagged in the row.

| Date | Commit | Rubric | 1 Adopt | 2 Author | 3 Flex | 4 Prod | 5 Perf | 6 Platf | 7 Mature | 8 Maint | **Overall** | Report |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-09-07 | `39fb9c2` | v1 | D+ | A− | A− | C+ | C+ | B | F | A− | **2.20 / 4.3 (C+)** | [baseline](results/2026-09-07-baseline.md) |
| 2026-09-08 | `fb057d5` | v1 | D+ | A− | B+ | C+ | C+ | B+ | F | A− | **2.15 / 4.3 (C+)** | [run 2](results/2026-09-08-run2.md) |

## Reading run 2 (2.20 → 2.15)

The number fell 0.05 while the repository improved, and both halves of that sentence are correct:
run 2 verified every Track E closure against code and found **no gaming** — its words: "a repo that
improves while its score corrects downward is the opposite of a gamed instrument." The drop is one
re-anchor: flexibility A− → B+, because the baseline graded the model's *ceiling* and the rubric
orders grading the shipping *floor*, whose B anchor is verbatim "a full-logic model with a thin
catalogue" — and 16 components is thin. Coverage rose B → B+ on the same evidence standard.

Run 2 also found one discipline slip and it was fixed the same day, its way: the committed
conformance matrix had gone stale against prose totals, with the fresh evidence uncommitted — the
repo's own rule broken at its own finish line. The matrix in `plans/conformance.md` Part 3 is
regenerated at `fb057d5` and the raw runs are committed beside the tools.

The unchanged grades confirm the plan's arithmetic: production readiness and performance sit **at
their rubric caps**, movable only by the owner unlocks (real hosting/keys; representative
hardware), and maturity's F — 20% of the total — moves only with a public home, adopters, a second
maintainer, and time.

## Reading the baseline

The weighted 2.20 decomposes into two stories. The **artifact** dimensions (authoring 3.7,
flexibility 3.7, maintainability 3.7) average an A−; the **exposure** dimensions (adoption 1.3,
production readiness 2.3, performance 2.3, maturity 0.0) are what an adopter actually buys, and
maturity's 20% weight at zero costs 0.80 of overall on its own — more than any other single lever.
The improvement plan ([`plans/score-improvement.md`](../../plans/score-improvement.md)) is ordered
by which of these engineering can actually move; maturity mostly cannot be engineered, and the
plan says so rather than pretending.

Cheapest one-step raises, per the baseline grader: adoption — resolvable artifacts plus an iOS
embedding artifact; production — the operational back half (hosting, keys, a rollout server) or
failing that a reference implementation of it; performance — any budget graded on representative
low-end hardware; flexibility — a launchable catalogue.

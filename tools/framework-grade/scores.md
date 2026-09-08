# The ledger

One row per run. The full report is filed under `results/` and linked; the row is for the trend.
Numeric overall per the rubric's mapping, weighted by rubric weights. A rubric version change
breaks comparability and must be flagged in the row.

| Date | Commit | Rubric | 1 Adopt | 2 Author | 3 Flex | 4 Prod | 5 Perf | 6 Platf | 7 Mature | 8 Maint | **Overall** | Report |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-09-07 | `39fb9c2` | v1 | D+ | A− | A− | C+ | C+ | B | F | A− | **2.20 / 4.3 (C+)** | [baseline](results/2026-09-07-baseline.md) |

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

# ADR-005: Phase 0 Harness Resolutions — Reference Screen Size, Event Handlers, and Stack Size

**Date:** 2026-08-31
**Status:** Accepted

## 1. Context & Problem Statement

Building the Phase 0 measurement harness (`tools/phase0/`) turned the harness appendix in
[roadmap.md](../../roadmap.md) from prose into code, and code cannot leave a contradiction
unresolved. Four items had to be settled before a single number could be produced, and all
four change what the recorded numbers mean, so none of them may be settled silently.

1. **The reference screen's size contradicts its own construction.** The appendix specifies
   "a product-detail-like screen of **~160 nodes**" and then specifies the construction: a
   header block, "a plain `Column` of **50 rows** (each row: `Row(image-box, Column(Text,
   Text), Text)`)", and a footer. That row is six nodes — `Row`, `Box`, `Column`, and three
   `Text` — so fifty rows alone are three hundred nodes. The stated construction produces
   roughly 320 nodes, twice the stated size. Experiment 0.3's gate leg, "the 150-node
   **batch crossing** is ≤ 4 ms end to end", is written against the smaller figure, so the
   two readings do not merely differ in wording; they differ in what the gate measures.

2. **The state-holder count admits no chip selection.** The appendix enumerates exactly
   twenty-four `mutableStateOf` holders — "row selection ×20, quantity, promo visibility,
   total, loading flag" — and separately requires "a selectable chip row of 8". A chip
   selection holder would be a twenty-fifth.

3. **"One event handler per row" has nowhere to attach.** The row is `Row(image-box,
   Column(Text, Text), Text)`. Compose's `Row` takes no `onClick`; the idiomatic attachment
   is `Modifier.clickable`, whose argument is a lambda. Modifiers with lambda arguments need
   the modifier subsystem and the deferred-expression grammar, both of which are Phase 2
   deliverables that do not exist during Phase 0.

4. **Layer 4's stack-size figure describes QuickJS, not Zipline.**
   [Layer 4](../../specs/layer-4-sandbox.md) states "Measured from `QuickJs.kt`:
   `gcThreshold = 256 KiB`, `memoryLimit = -1` (unbounded), `maxStackSize = 512 KiB`," and
   then recommends Redwood's 8 MB. The first two are the values a Dogwood host would
   actually inherit. The third is not: `Zipline.create` overwrites it before any guest code
   runs.

## 2. Decision

**2.1 The reference screen is parameterised by row count, and Phase 0 measures two points.**

- The **primary** point is **23 rows**, which yields **exactly 160 widget nodes** and is the
  screen the appendix's "~160 nodes" and the gate's "150-node batch" describe. All gate legs
  are evaluated against this point.
- The **secondary** point is **50 rows** — the appendix's literal construction — which
  yields **322 widget nodes**. It is measured and reported alongside, because a second point
  is what turns one measurement into a scaling curve, and because the discrepancy should
  cost the project a data point rather than an argument.

The arithmetic is committed in the reference screen file so it cannot drift:

| Region | Widget nodes |
| --- | ---: |
| Root `Column` | 1 |
| Header: `Box` + `Text` + `Text` + `Row` + `Text` + `Text` | 6 |
| List `Column` | 1 |
| Each row: `Row` + `Box` + `Column` + `Text` + `Text` + `Text` | 6 × rows |
| Footer: `Column` + `Row` + three buttons + `Row` + eight badges | 14 |
| **Total** | **22 + 6 × rows** |

Children-slot markers are counted separately and produce no `Create` change, so the
"node count" the gate speaks of is unambiguously the widget-node count.

**2.2 Chip selection derives from `quantity`.** The chip row renders eight badges, the
selected one being the badge whose index equals `quantity`. The holder count stays at exactly
twenty-four, and the chip row still exercises eight sibling nodes whose properties change
together — which is the measurement value the appendix wanted from it.

**2.3 The row's event handler is an explicit `onClick` parameter on the Phase 0 `Row` stub,**
documented in the stub as a stand-in for `Modifier.clickable`. It registers an entry in the
lambda slot table under `EventTag(1)` exactly as a real event parameter would, so the slot
table is exercised at twenty-three and fifty entries and the depth-first purge has something
to purge. The three footer handlers are three `PrimaryButton`s; the chips carry no handlers.
When the modifier subsystem lands in Phase 2, the reference screen moves to
`Modifier.clickable` and the numbers are re-taken, not translated.

**2.4 Layer 4's memory paragraph is corrected** to say that `QuickJs.create()` sets
`maxStackSize = 512 KiB` and that **`Zipline.create()` then raises it to 6 MiB**, which is
therefore the figure a Dogwood host inherits. Redwood's 8 MB remains the recommendation; the
delta to argue about is 6 MB to 8 MB, not 512 KiB to 8 MB.

## 3. Rationale & Research

**On the row count.** Both readings of the appendix cannot be satisfied, and the tie-break is
the gate: the gate leg names a node count ("the 150-node batch crossing"), not a row count,
and a gate is the one thing in Phase 0 that must not be reinterpreted after the fact. 23 rows
is the row count for which the appendix's node figure and its gate figure agree. Measuring 50
rows as well costs one extra loop and removes the temptation to argue the discrepancy later.

**On `Modifier.clickable`.** That `Modifier.Element` implementations are `internal` and that
Dogwood must therefore define its own serializable modifier type is
[Layer 5](../../specs/layer-5-host.md)'s finding and the premise of Phase 2 in
[roadmap.md](../../roadmap.md), which explicitly scopes Phase 2 to "modifiers whose arguments
are primitives and value classes" and defers lambda- and expression-argument modifiers to
Phase 3. Using a direct parameter in Phase 0 is therefore not a shortcut around a solved
problem; it avoids inventing a grammar whose ADR is scheduled for Phase 2.

**On the stack size.** Read from Zipline 1.27.0's own source:
[`QuickJs.kt`](https://github.com/cashapp/zipline/blob/1.27.0/zipline/src/jniMain/kotlin/app/cash/zipline/QuickJs.kt)
sets `memoryLimit = -1`, `gcThreshold = 256 KiB`, `maxStackSize = 512 KiB` in `create()`, and
[`Zipline.kt`](https://github.com/cashapp/zipline/blob/1.27.0/zipline/src/hostMain/kotlin/app/cash/zipline/Zipline.kt)
`Zipline.create` then executes `quickJs.maxStackSize = 6 * 1024 * 1024L` with the comment
"Expect callers to use 8 MiB stack sizes for their calling threads." The harness's host
threads are created with an eight-megabyte stack for exactly that reason.

## 4. Unstated Assumptions

- **Assumes the gate's "150-node batch" means the reference screen's initial batch.** At 23
  rows that batch is 160 `Create` changes and 572 changes in total; the harness reports both
  numbers so the leg can be re-read against either if this assumption is wrong.
- **Assumes an `onClick` parameter and a `Modifier.clickable` chain cost the same to record.**
  Both write one lambda-slot entry. This stops being true once modifier chains are diffed
  per element rather than replaced whole, which is a v1 question.
- **Assumes 6 MiB is enough for composition depth at 50 rows.** It was, in every run of this
  harness. Deeper trees than the reference screen are untested at that setting.

## 5. Updated Documents

- [roadmap.md](../../roadmap.md) — Phase 0 harness appendix: reference-screen row count,
  node arithmetic, chip-selection derivation, and the row event handler
- [specs/layer-4-sandbox.md](../../specs/layer-4-sandbox.md) — Guest Memory Management:
  the effective `maxStackSize` a Dogwood host inherits from `Zipline.create`
- [tools/phase0/README.md](../../tools/phase0/README.md) — the harness these resolutions
  are implemented in

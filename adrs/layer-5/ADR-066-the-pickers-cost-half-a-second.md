# ADR-066: The pickers cost half a second, and the budget is not being raised to hide it

**Date:** 2026-09-09
**Status:** Proposed — the decision in §2 is the owner's, and the measurement is why

## 1. Context & Problem Statement

Conformance claim `G5` bounds the shipped web slice at 3,700,000 bytes brotli-compressed. After the
catalogue work (C1–C3, twenty components with eight holder shapes) the whole-matrix run reports:

```
CONF G5 FAIL -- 3,766,502 bytes brotli of 3,700,000
FAIL -- see the matrix above; a red cell blocks the merge
```

66,502 bytes over. `budgets.tsv` anticipated this in its own comment:

> 3.70 MB against a measured 3.57 MB: a ceiling with headroom rather than today's figure. Pinning it
> exactly fails on the next legitimate component and teaches everyone to raise the number, which is
> how a budget stops meaning anything. The headroom is deliberately tight — about 130 kilobytes —
> because on Fast 3G every 100 kilobytes is roughly half a second of waiting (ADR-038).

So the gate fired exactly as designed, on exactly the situation it was written for. The question is
what to do, and the wrong answer is named in the comment.

## 2. Decision

**The budget is not being raised in this change.** The measurement below is committed, `G5` stands
red and attributed, and the choice between the options in §3 is the owner's, because it is a product
trade — first-load latency for every web user against catalogue completeness on that client — rather
than an engineering one.

## 3. Rationale & Research

**The growth was attributed rather than assumed.** Three builds of `samples/web-slice`, same
toolchain, brotli level 11, counting what `from_web_weight.py` counts:

| | brotli total | delta |
|---|---|---|
| before C1 (`ea5bcf9`) | 3,643,599 | — |
| after C1 + C2 — dialog, sheet area, menu, menu item, pager (`37e79a5`) | 3,669,578 | +25,979 for **five** components |
| after C3 — date picker, time picker (`48c1004`) | 3,766,502 | **+96,924 for two** |

Skiko is byte-identical across all three (2,596,146). Every byte of the growth is the application's
own WebAssembly module.

**The two Material3 pickers cost four times the other five components combined**, and roughly half a
second of Fast 3G waiting, for two components most screens never show. That is the finding, and it
is what makes this a decision rather than an arithmetic problem: the catalogue grew by six
components and one pair of them is 79% of the cost.

The options, with what each actually costs:

1. **Raise `G5` to 3,850,000.** Honest about what shipped, and pays half a second on every web first
   load, forever, for two components. It is also the move the budget's own comment warns teaches
   everyone to raise the number — the second time is easier than the first.
2. **Let a client bind a subset of a segment.** Today a host registers a whole segment or none of
   it, so the web client links every design-system implementation whether or not it composes one.
   Per-component linking would let the web profile drop the pickers and keep everything else. This
   is real engine work with real consequences for skew reporting — a widget a client *could* have
   rendered and chose not to link is a third state alongside "unknown tag" and "withheld" — and it
   deserves its own plan and its own ADR.
3. **Accept a red `G5` on the web and say so.** The claim stays failing, the number stays
   attributed, and the matrix keeps telling the truth. The cost is that a red cell blocks the merge
   gate, so this option means changing what the gate does about `G5` specifically — which is
   option 1 wearing a different hat unless it is time-boxed.

**What is not an option:** measuring something else. Changing the sample so the number passes is the
one move that would make the budget stop meaning anything permanently rather than temporarily.

## 4. Unstated Assumptions

- **That the pickers' cost is Material3's rather than the generator's.** Inferred from the ratio —
  five simple components at ~5 KB each, two picker components at ~48 KB each — and from Material3's
  date and time pickers being large composables with their own layouts, formatters and state
  machines. Not confirmed by a build with only the picker *implementations* stubbed, which would be
  the next measurement if option 2 were taken up.
- **That 100 KB is half a second on Fast 3G.** ADR-038's figure, carried forward rather than
  re-measured here.
- **That the web client wants the pickers at all.** A date picker on a web page is a plausible thing
  to want. This ADR does not assume it is not; it puts the price beside the question.

## 5. Updated Documents

- [`tools/conformance/budgets.tsv`](../../tools/conformance/budgets.tsv) — unchanged, deliberately
- [Conformance plan](../../plans/conformance.md) — `G5` red, with the attribution
- [Engineering backlog](../../plans/engineering-backlog.md) — the open decision recorded

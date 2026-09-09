# ADR-066: The pickers cost half a second, and the budget is not being raised to hide it

**Date:** 2026-09-09
**Status:** Accepted

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

**Components cost every client globally, and the ceiling moves with the catalogue. `G5` is raised
from 3,700,000 to 3,900,000 bytes, and per-component binding is deferred.**

Taken by the owner, 2026-09-09, on the measurement in §3. The reasoning is alignment: a design
system's components already cost every *mobile* client space in the installed binary whether that
application composes them or not, and making the web profile the one place where a component is
conditional would mean two different answers to "what does adding a component cost?" — one per
platform, diverging as the catalogue grows. One answer is worth more than the bytes.

Three things follow, and the second is what stops this becoming the failure `budgets.tsv` warned
about:

1. **The ceiling is 3,900,000** — today's 3,766,502 plus about 133 kilobytes, which is the same
   tightness the old ceiling had against its own measurement. It is a ceiling with headroom, not
   today's figure.
2. **A raise to `G5` must arrive with an attribution, in the commit that makes it** — three builds:
   before, after, and the split across whatever was added. That is what turned "the bundle grew"
   into "two pickers cost 96,924 bytes and the other five components cost 25,979 between them",
   which is a fact somebody can act on and a sentence somebody can object to. Recorded in
   `budgets.tsv` and in `docs/checks.md`.
3. **Per-component binding is deferred, not dropped**, as `D1` in the engineering backlog, with what
   it would cost written down so the next reader does not rediscover it.

**What was accepted, stated plainly rather than buried:** the two profiles are aligned in *policy*
and not in *who pays*. On mobile the bytes are install size — downloaded once, from a store,
usually on Wi-Fi. On the web they are first-load latency, paid by every new visitor on whatever
network they have, every time the catalogue grows. The decision is that the alignment is worth
that, and this paragraph exists so nobody has to infer it later.

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

The options, with what each actually costs. **Option 1 was taken**, with the attribution rule
attached to stop the objection to it from being true silently:

1. **Raise `G5`.** Honest about what shipped, and pays half a second on every web first load,
   forever, for two components. It is also the move the budget's own comment warns teaches everyone
   to raise the number — the second time is easier than the first, which is why the raise now costs
   an attribution.
2. **Let a client bind a subset of a segment.** Today a host registers a whole segment or none of
   it, so the web client links every design-system implementation whether or not it composes one.
   Per-component linking would let the web profile drop the pickers and keep everything else. This
   is real engine work with real consequences for skew reporting — a widget a client *could* have
   rendered and chose not to link is a third state alongside "unknown tag" and "withheld", and it
   breaks the pre-flight check as written, which compares one version number per segment. **Deferred
   as `D1`** in the engineering backlog, with the design problem and the unverified premise recorded
   there.
3. **Accept a red `G5` on the web and say so.** The claim stays failing, the number stays
   attributed, and the matrix keeps telling the truth. The cost is that a red cell blocks the merge
   gate, so this option means changing what the gate does about `G5` specifically — which is
   option 1 wearing a different hat unless it is time-boxed. Not taken: a gate with a permanent
   exception is a gate that has been turned off with extra steps.

**What is not an option:** measuring something else. Changing the sample so the number passes is the
one move that would make the budget stop meaning anything permanently rather than temporarily.

## 4. Unstated Assumptions

- **That dead-code elimination would actually drop an unreferenced component.** The premise the
  deferred option rests on, and it is unverified. Plausible, but this repository has been bitten
  once by a WebAssembly optimizer pass doing something other than the obvious (upstream report 1).
  Stubbing the two picker implementations and rebuilding would settle it in about ten minutes, and
  that is the first step if `D1` is ever picked up rather than something to assume on the way in.
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

- [`tools/conformance/budgets.tsv`](../../tools/conformance/budgets.tsv) — `G5` raised to
  3,900,000, with the attribution rule beside it
- [Conformance plan](../../plans/conformance.md) — `G5` green, with the attribution kept
- [Engineering backlog](../../plans/engineering-backlog.md) — per-component binding deferred as `D1`
- [Checks](../../docs/checks.md) — the attribution rule, beside the fixture-freezing rule

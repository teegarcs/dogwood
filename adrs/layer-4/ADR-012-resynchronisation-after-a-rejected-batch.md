# ADR-012: Resynchronisation After a Rejected Batch

**Date:** 2026-09-06
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-011](ADR-011-a-batch-applies-whole-or-not-at-all.md) made batch application all-or-nothing: a
batch that refers to a node nobody created, or removes past the end of a slot, is rejected whole and
the tree already on screen keeps drawing. It also recorded, in its own unstated assumptions, exactly
what that does not do:

> **A rejected batch leaves the guest ahead of the host, permanently.** Containing the damage is not
> the same as repairing it: the host's tree is now older than the guest believes. Nothing here
> resynchronises them — the honest answer is a resynchronisation protocol (a full re-send on
> request), and it is not built.

The consequence is worse than one lost frame. The guest sends **diffs**. After a rejection every
later batch is expressed against a tree that no longer exists on the other side, so the screen is
frozen at the last good state and each new change either fails to apply or applies to the wrong
node. Containment bounded the damage; nothing ended it.

## 2. Decision

**On rejecting a batch, the host clears its tree and asks the guest to send the whole thing again.**

`DogwoodGuestUi` gains `resynchronise()`. The guest answers it by taking its own state snapshot,
disposing its composition, and building a new one from that snapshot — which emits the entire tree
as creations rather than as a diff, because a fresh composition has nothing to diff against.

Three things make it safe:

**The host clears first, on the user-interface thread, before the request crosses.** What comes back
is a complete tree, not a patch. Applying it onto the old one would put a second copy of every node
under the root — the screen twice. `ResynchronisationTest.aResentTreeOntoAnUnclearedOneDuplicatesTheScreen`
asserts precisely that failure, so if the clear ever becomes redundant somebody finds out from a
test rather than from a screenshot.

**Identifier and sequence counters carry across the rebuild.** `ChangeRecorder` takes a starting
identifier and sequence. Identifiers, because the host has cleared its tree but an event composed
against the old one may already be in flight, and restarting at 1 would let it land on whichever new
node inherited its number. Sequences, because `Event.q` is how a guest drops events composed against
a stale batch — a sequence that went backwards would make every later event look stale for the life
of the experience, a screen that renders and ignores every tap.

**One attempt.** If the re-send is itself rejected, the fault is in the protocol rather than in the
divergence, and asking again would produce an endless cycle of full re-sends — the most expensive
thing this boundary can do — while the screen stays broken either way. The guard clears when a batch
applies, so one rejection cannot silence the repair for the life of the experience.

## 3. Rationale & Research

**Why rebuild the composition rather than replay retained state.** The guest's `WidgetNode` keeps
structure — identifier, tag, slots, children — and *not* property values or modifier chains; those
are emitted and forgotten. A full re-send from retained state would therefore mean holding every
property on every node, forever, on every screen, to serve a failure that should never happen.
Rebuilding pays nothing in steady state and pays a full recomposition at the moment of repair, which
is the right way round.

**Why this reuses the code-update shape.** Snapshot, dispose, rebuild from the snapshot is exactly
what a code update already does ([Layer 5 ADR-027](../layer-5/ADR-027-the-host-shell-and-warm-experiences.md)),
and what a user typed survives it for the same reason. A protocol failure the user did not cause
should not cost them a half-filled form.

**Verified on a device, because the unit tests cannot show the loop.** The guest was temporarily
patched to send a batch referencing a node nobody created — the same technique the skew and
hostile-value drills use, and reverted the same way. On the Android emulator:

```
SkewReport(rejectedBatches=[batch 3: change 0 sets a property on node 4242,
  which does not exist, resynchronising: batch 3 was rejected])
```

**28 text nodes on screen afterwards** — the screen came back. With `guest?.resynchronise()`
commented out and everything else identical, the same run leaves **8**: the host shell's tab bar and
status line, with the guest's entire screen gone. That difference is the repair, and it is the
reason this is a decision record rather than a comment.

## 4. Unstated Assumptions

- **The screen blanks for one round trip.** The host clears before the re-send arrives, so there is
  a visible gap. That is honest rather than unfortunate: the host genuinely does not know what
  should be on screen. Holding the stale tree until the replacement landed would look better and
  show a user a screen the guest had already stopped believing in.
- **Non-saveable guest state is lost**, exactly as it is on a code update. Anything a screen wants
  to survive must go through `rememberSaveable`, which is the rule everywhere else too.
- **The guest is assumed able to recompose.** If the composition itself is what is broken, the
  rebuild reproduces the broken tree and the second rejection stops the loop — the screen stays
  blank and the report says so. That is a worse outcome than a frozen screen and a better one than
  an infinite re-send; it is the case the single-attempt guard exists for.
- **Resynchronisation is not a code update**, so the payload is unchanged and the dictionary
  version with it. A divergence caused by skew rather than by a protocol defect would reproduce
  itself, and be reported twice rather than repaired.

## 5. Updated Documents

- [ADR-011: A Batch Applies Whole, Or Not At All](ADR-011-a-batch-applies-whole-or-not-at-all.md) —
  its recorded gap is closed here.
- [Layer 4: Sandbox](../../specs/layer-4-sandbox.md)
- [Roadmap](../../roadmap.md) — removed from Phase 7's deferred list.
- `engine/dogwood-protocol/src/commonMain/kotlin/dev/dogwood/protocol/Services.kt`
- `engine/dogwood-compose/src/jsMain/kotlin/dev/dogwood/compose/{Runtime,Nodes}.kt`
- `engine/dogwood-host/src/ziplineMain/kotlin/dev/dogwood/host/Experience.kt`

# ADR-011: A Batch Applies Whole, Or Not At All

**Date:** 2026-09-03
**Status:** Accepted

## 1. Context & Problem Statement

Decoding a batch has always been all-or-nothing. A batch whose *grammar* this client does not share
throws `ProtocolMismatch`, the host records it in `SkewReport.rejectedBatches`, and the tree already
on screen keeps drawing — the same shape the delivery layer uses for a manifest that fails
verification, and for the same reason.

Applying was not. The changes in a batch are applied strictly in order, so the eleventh of them
could fail after the first ten had already landed:

- `node(id)` calls `error(…)` when a change refers to an identifier nobody created.
- `slot.removeAt(x)` and `slot[x + offset]` throw when a removal runs off the end.
- `slot.add(x, child)` and `slot.addAll(destination, moved)` throw when an index is out of range.

What remained on screen after such a failure was not the previous tree and not the new one. It was
a **tree the guest never composed** — the prefix of a batch, applied to a tree the guest believes it
has finished editing. And because the guest keeps sending *diffs*, every later batch is expressed
against the tree it thinks the host has. The desynchronisation does not heal; it compounds, silently,
and the next visible symptom is arbitrarily far from the cause.

That is a strictly worse outcome than the failure that produced it. A rejected batch costs one
frame. A half-applied one costs the correspondence between the two trees, which is the thing the
whole protocol is built on.

On the mobile host the exception also escaped into `uiScope.launch`, where nothing caught it; on the
web host a `catch (Throwable)` recorded a message but could do nothing about the state already
written.

## 2. Decision

**Validate the whole batch first, against a shadow, and apply only if the shadow survives.**

`BatchValidation.kt` lives in `dogwood-wire` — transport-free, shared by every host — and exposes
two things:

```kotlin
interface TreeShape {
  fun exists(id: Int): Boolean
  fun slotTags(id: Int): Set<Int>
  fun childIds(id: Int, slot: Int): List<Int>
}

fun ChangeBatch.rejection(shape: TreeShape): String?   // null when it will apply cleanly
```

The rule is shared; the storage is not. The mobile host, its plain-tree comparison applier, and the
web host each implement `TreeShape` over their own node type in about six lines, and all three call
`rejection` at the top of `apply`, throwing `ProtocolMismatch` when it returns a message. Both call
sites report that into the channel they already had for a rejected *decode*, because a batch that
cannot be applied is skew of the same kind and is survived the same way.

The shadow is copy-on-write: nothing is copied out of the real tree until a change refers to it, so
a batch that touches one slot materialises one list rather than walking a tree. It models three
things — which identifiers exist, which have been detached, and the contents of each touched slot —
and it repeats the appliers' index arithmetic exactly, including the move destination adjustment
(`if (f > t) t else t - n`), because getting that wrong in either place is an out-of-bounds insert.

**Removal is modelled depth-first**, which is the subtle half. A batch that removes a subtree and
then refers to a node *inside* it is precisely the batch that fails halfway through, and a validator
tracking only slot *sizes* would wave it through. `TransactionalApplyTest.aReferenceIntoASubtreeThisBatchRemovedIsRejected`
pins that case.

**One rule is stricter than the applier**: creating an identifier that is already live is rejected.
The applier does not throw on it — it silently overwrites `byId`, leaving the first node parented on
screen and unreachable by identifier, which is worse than a rejection. Identifiers are not reused
*while live*; a batch that removes a node and then creates one with the same identifier is legal and
must stay legal, because that is what list re-keying sends. `anIdentifierMayBeReusedAfterItsNodeIsRemoved`
pins that it does.

## 3. Rationale & Research

Three options were on the table, from the platform review.

**Snapshot rollback** — wrap `tree.apply` in `Snapshot.takeMutableSnapshot()` and `dispose()` on
failure. Rejected as **incorrect here, not merely expensive**: only part of the tree is snapshot
state. `byId` is a plain `HashMap`, the per-node `slots` map is a plain `HashMap`, and
`appliedSequence` is a plain `Int`. A snapshot rollback would restore the state lists and maps and
leave all three of those carrying the failed batch's edits — a rollback that half-rolls-back is not
better than a half-apply, it is harder to reason about.

**An undo journal** — record an inverse for every change and replay it backwards on failure.
Correct in principle, and rejected for two reasons. It duplicates the applier's semantics in a
second place that only ever runs on the failure path, which is the code least likely to be exercised
and most likely to rot. And it mutates first: the leak detector would have already watched the nodes
a `ChildRemove` detached, so an undone removal would report live nodes as leaked.

**Validate-then-apply** — chosen. It never touches the real tree on a bad batch, so there is no undo
to get wrong, no leak-watcher false positive, and nothing Compose reads is written. The one thing it
costs is a second pass, which is the thing that had to be measured.

### The measured cost

`TransactionalApplyTest.validationCostsFarLessThanDecoding`, on a 600-change batch (a 200-row list,
built from nothing), on a JVM host, two runs:

| | run 1 | run 2 |
|---|---|---|
| decode | 257 µs | 181 µs |
| **validate** | **59 µs** | **68 µs** |
| validate + apply | 137 µs | 95 µs |

Validation costs roughly **0.06 ms**, about a quarter to a third of decoding the same batch, against
a Phase 0 whole-screen decode of 0.17 ms and a 16.7 ms frame. It is free in any sense a frame budget
cares about, and the test asserts a loose ceiling relative to decode — rather than a fixed number a
loaded continuous-integration machine would fail on — while printing the measurement so a regression
is visible even when it passes.

### Verified on hardware

The risk this change carries is not that it rejects too little but that it rejects **too much**: a
false rejection blanks a screen that would have rendered. Real composition sends removals, moves and
re-keyings constantly, so the check was run against real payloads on every host:

- **Android**, on the emulator: every tab twice around, plus flings up and down a lazy list to force
  recycling. No crash, no rejection, 37 rendered text nodes.
- **iOS**, on the simulator, via the `--dogwood-drill` harness: all four experiences activated, two
  evictions, a memory trim and a state write. No rejection line at all.
- **Web**, in headless Chrome: 2 batches applied, 7 nodes rendered, event round-trip and correlated
  snapshot answered.

## 4. Unstated Assumptions

- **The shadow's arithmetic must stay in step with the appliers'.** It is a second implementation of
  the same index rules, which is the classic way two things drift. The mitigation is that a drift in
  the *permissive* direction reintroduces exactly the bug this ADR fixes, and a drift in the
  *strict* direction blanks a screen in the first minute of use — neither is silent. Nine tests on
  the mobile applier and four on the web one pin the arithmetic; `aBatchThatRemovesAndRebuildsStillApplies`
  caught a wrong expectation in the test itself rather than in the code, which is the seam behaving
  as intended.
- **Validation assumes the tree it validates against is the one the batch will apply to.** True
  because both run on the user-interface thread inside one `apply`, with no suspension between them.
  If applying ever became asynchronous this guarantee would need re-establishing.
- **A rejected batch leaves the guest ahead of the host, permanently.** Containing the damage is not
  the same as repairing it: the host's tree is now older than the guest believes. Nothing here
  resynchronises them — the honest answer is a resynchronisation protocol (a full re-send on
  request), and it is not built. What this ADR buys is that the divergence is *recorded and bounded*
  rather than silent and compounding.
- **`appliedSequence` is not advanced by a rejected batch**, so outbound events keep reporting the
  last sequence the host actually applied. That is what lets the guest drop events it should treat
  as stale, and it is asserted.

## 5. Updated Documents

- [Layer 4: Sandbox](../../specs/layer-4-sandbox.md)
- `engine/dogwood-wire/src/commonMain/kotlin/dev/dogwood/protocol/BatchValidation.kt` (new)
- `engine/dogwood-host/src/commonMain/kotlin/dev/dogwood/host/HostTree.kt`
- `engine/dogwood-host/src/commonMain/kotlin/dev/dogwood/host/PlainTree.kt`
- `engine/dogwood-host/src/commonMain/kotlin/dev/dogwood/host/Experience.kt`
- `engine/dogwood-web/src/wasmJsMain/kotlin/dev/dogwood/web/WebTree.kt`
- `engine/dogwood-web/src/wasmJsMain/kotlin/dev/dogwood/web/DogwoodWebExperience.kt`
- `engine/dogwood-host/src/jvmTest/kotlin/dev/dogwood/host/TransactionalApplyTest.kt` (new)
- `engine/dogwood-web/src/wasmJsTest/kotlin/dev/dogwood/web/WebTreeTransactionTest.kt` (new)

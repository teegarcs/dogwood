# ADR-015: Node Identity and Reuse — Already Built, Now Actually Tested

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

`roadmap.md` Phase 4 lists **node reuse** — "key stability across list mutation" — as one of its
nine subsystems, and `specs/layer-5-host.md` states the requirement unusually firmly: wrapping
every child in `key(node.id)` is "a **generator requirement with a test**, not a note."

The mechanism was in place before this record. `RenderChildren` wraps every child in
`key(child.id.value)`, the lazy containers pass `key = { it.id.value }` to `items`, and guest
identifiers are allocated monotonically. What did **not** exist was the test — and specifically the
half that matters. The guest-side gate test proves the right *change* crosses: a reorder produces a
`ChildMove` rather than a rewrite. It says nothing about the thing the requirement is actually
about, which is that the host's composition keeps the state attached to a node when that node
moves. Host-side scroll position, focus, animation state and the input method connection all live
in `remember`s inside those composition groups.

So this subsystem was in the awkward position of being *believed* rather than *known*, and the
belief had no way to be checked, because the project had no way to run a host composition outside a
device.

## 2. Decision

**Add a host-side Compose test harness, and use it to test the claim rather than restate it.**

`dogwood-host`'s JVM test source set now depends on `compose.uiTest` and
`compose.desktop.currentOs`, so `runComposeUiTest { setContent { … } }` runs a real composition in
the ordinary unit-test suite. Desktop Compose Multiplatform runs on the Java Virtual Machine, which
is where these tests already were; nothing new is installed and no device is involved.

`DogwoodSurface` was split so this is possible without a Zipline instance. `DogwoodTree(tree,
events, modifier)` renders a `HostTree` and knows nothing about how it got there; `DogwoodSurface`
is now a two-line wrapper that supplies an experience's tree and event sink. That split is worth
having beyond tests — a preview, and the Web profile where the guest loads into the browser's own
engine rather than through `ZiplineLoader`, both want a renderable tree without a QuickJS instance.

Four tests now hold the subsystem up:

- **Scroll position survives a reorder of a node's siblings.** A list scrolled to item 30 keeps its
  position when a sibling moves in front of it. This is the user-visible consequence.
- **A keyed child keeps its composition group when it moves**, with two children of the *same*
  widget tag so nothing but identity can explain the result.
- **A negative control**: the same tree rendered without keys, where the two children trade groups.
  Without it the first two tests would pass whether or not `key` was doing anything.
- **Identifiers are never reused**, guest side, across a full remove-then-create cycle.

`ChangeRecorder.reset()` is **deleted**. It was unused and it set the identifier counter back to 1,
which would have reused identifiers inside a live composition. The invariant is easier to keep when
the only way to get a fresh counter is a fresh recorder.

Since the harness existed, one more long-standing gap closed with it:
[ADR-009](ADR-009-modifier-subsystem.md) recorded that modifier *order* was asserted only as
protocol — that a chain crosses in order — and that its effect on layout was not asserted because
the project could not measure one. It can now: `padding(8).size(48)` and `size(48).padding(8)`
measure 48 and 32 respectively at the test density, which is the claim that the chain is replayed
exactly rather than sorted, merged, or deduplicated somewhere between the two sides.

## 3. Rationale & Research

**Why this needed a test at all, given the code was already right.** Two of the three findings
below were invisible to reading.

**Finding one: the first version of the test asserted nothing.** `ChildMove(from = 0, to = 1,
count = 1)` on a two-element list is a **no-op**. Both sides implement Compose's
`AbstractApplier.move` convention, in which `to` is an index in the list's *pre-removal* space, so
removing at 0 and inserting at 1 puts the element back where it started. Every assertion passed,
because nothing had moved. The test only became a test once the move was `from = 1, to = 0`.

**Finding two — the one worth the whole exercise: `key` can be silently defeated, and the code
still renders correctly.** An earlier version of the negative control was written as one renderer
with a flag:

```kotlin
for (child in children) {
  if (keyed) key(child.id.value) { Tracked(child) } else Tracked(child)
}
```

The keyed case behaved exactly like the unkeyed one. `key` relocates a *movable group* among its
**immediate siblings**; a per-item conditional puts each movable group alone inside its own replace
group, where there is nothing for it to be matched against. Nothing warns, nothing looks wrong, and
the screen renders correctly — the only symptom is state quietly lost on reorder, which is
precisely the bug the requirement exists to prevent.

This is a live hazard for the generator, which emits exactly this loop. **A `key` wrapped in a
per-child conditional is indistinguishable from no key at all**, so a generated
`if (something) key(id) { … }` would revert the subsystem without failing anything. The requirement
in the specification is therefore sharpened: the key must be the outermost thing inside the loop
body, not nested inside a condition.

**Finding three: a headless composition has no global snapshot manager.** In the running host,
`HostTree.apply` is called from the user-interface dispatcher, where the platform sends snapshot
apply notifications for it. A test composition has no such manager, so a mutation lands in the
global snapshot and the composition never hears about it — which, again, looks exactly like the
test passing. Tests call `Snapshot.sendApplyNotifications()` explicitly, and the helper says why.

**Why key stability rather than pooling.** Redwood pools host widgets because platform views are
expensive to allocate. Dogwood's host nodes are composables whose state is positional, so the
failure mode is lost state rather than lost allocations, and the fix is identity rather than
recycling. This was already the specification's position; the tests are what make it checkable.

**Why identifiers must be monotonic.** Not for rendering — for events. An identifier reused inside
a live composition lets an event aimed at a node that is gone land on whichever node inherited the
number. That is a *wrong* tap rather than a dropped one, and the stale-event machinery in
[Layer 4 ADR-004](../layer-4/ADR-004-change-event-protocol-v0.md) cannot detect it, because the
event is well-formed.

## 4. Unstated Assumptions

- **The harness runs Skia off-screen, not the platform's own renderer.** It is the right tool for
  identity, layout and semantics, and the wrong one for anything about platform look and feel.
  Pixel identity against hand-written Compose remains unasserted, as ADR-009 says.
- **Assumes `key` is emitted at the top of the loop body.** Enforced by the tests above only for
  the hand-written `RenderChildren`; a future generator change could reintroduce the conditional
  hazard without failing them. The durable fix is a generator test, which belongs with the
  generator.
- **The negative control asserts a specific wrong behaviour.** If a future Compose release changed
  how unkeyed siblings are matched, that test would fail without anything being broken. It is
  documenting a property of Compose, deliberately, because the positive test is only meaningful
  relative to it.
- **Identifier monotonicity is per composition, not per payload.** A replacement guest starts a new
  recorder at 1, which is correct because the host tree is rebuilt with it.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — the identity-and-reuse section gains the
  conditional-`key` hazard and the state of the tests.
- [`roadmap.md`](../../roadmap.md) — Phase 4's node-reuse row.
- [`adrs/README.md`](../README.md) — index entry.

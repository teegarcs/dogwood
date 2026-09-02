# ADR-023: Enter and Exit — A Container and a Handshake, Not Applier Retention

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-020](ADR-020-animation.md) and [ADR-022](ADR-022-animated-colour-and-repetition.md) animate the
*arguments* of what a guest already emits. Neither can animate a node **appearing or disappearing**,
which is most of what "animation" means to a product: a row sliding away as it is dismissed, a
banner expanding in, a card fading out of a list.

Entering is easy. Exiting is the problem, and it is a protocol problem rather than a rendering one:
something has to keep the node alive after the guest has removed it.

## 2. Decision

### 2.1 The fork, decided: not the applier

The obvious implementation is for the applier to retain removed nodes until their exit finishes.
**Rejected**, and the reason is not aesthetic. Indices inside a `ChangeBatch` assume removal is
immediate: a `ChildRemove` at index 3 is followed by adds and moves whose indices are relative to
the list *after* that removal. A retained node makes every subsequent index in the batch address
the wrong slot. That is a correctness failure — the wrong widget in the wrong place — not a
cosmetic one, and it would be intermittent, because it only manifests when a batch removes and then
touches the same slot.

### 2.2 Instead: the guest keeps the node and declares visibility

`Presence(visible, enter, exit, onExited) { … }` is a registered component. The guest keeps its
child composed and flips `visible`; the host animates; `onExited` fires when the exit has finished
and the content is gone. **A node animating away is a node the guest still owns.**

The list-removal pattern that falls out of it is two sets, and the pair is the point:

```kotlin
var dismissed by remember { mutableStateOf(emptySet<String>()) }   // what the user asked to remove
var departed  by remember { mutableStateOf(emptySet<String>()) }   // what has finished leaving

for (stay in stays.filter { it.name !in departed }) {
  Presence(
    visible = stay.name !in dismissed,
    exit = "fade+shrinkVertically",
    onExited = { departed = departed + stay.name },
  ) { StayCard(stay) }
}
```

Collapsing the two sets removes the node mid-exit, which is the bug this design exists to make
impossible to write by accident.

### 2.3 "Exited" means *was visible and now is not*

Narrower than "is not visible", and the difference is not academic. A `Presence` that starts hidden
satisfies the looser condition on its very first frame, so a guest using the event to know when
removal is safe would tear down content that had never been shown. The host tracks whether the node
has ever been current, and resets after reporting so a node shown and hidden again reports again.

**An interrupted exit reports nothing.** Becoming visible again mid-exit is not an exit, for the
same reason a retargeted animation reports no completion: "finished" has to mean one thing. A guest
that treated an interruption as an exit would remove a node the user can still see.

### 2.4 Transitions are named, and combine with `+`

`"fade+shrinkVertically"`. Named rather than a structured specification because the set is small,
the host owns what each name looks like, and an unknown one can then **degrade to a fade and be
recorded as skew** — the same three-part rule every other named thing in this project follows.

## 3. Rationale & Research

**Why `MutableTransitionState` rather than the boolean overload of `AnimatedVisibility`.** It is the
form that exposes `isIdle` and `currentState`, which is what makes the completion observable at all.
It is remembered per node, so it survives a code update exactly as a scroll position does — a
payload republished mid-exit does not restart it.

**Why `onExited` is an optional callback**, and what that cost. Declaring it `(() -> Unit)? = null`
made the generator **reject the entire component**: `(() -> Unit)?` is one lambda type, not a
parenthesised something else, and reading its return type naively yields `Unit)?`, which matches
nothing — so it was classified as a host-invoked lambda, with a rejection message about blocking the
guest mid-frame that had nothing to do with the real problem. The parser now normalizes the wrapper,
carefully enough not to eat a real parameter list (`(Boolean) -> Unit` opens with a parenthesis
too), and the emitter **clears** the slot when a handler is withdrawn rather than leaving the old
one registered. Both are latent defects that any surface author would have hit the first time they
declared an optional callback.

### Verified

Fourteen tests added. Host, on a controlled clock: content is present when the guest says so; an
exit reports exactly once **and not at thirty milliseconds in**; an interrupted exit reports nothing
and the content stays; content hidden from the start neither appears nor reports; an unknown
transition name still exits and lands in the `SkewReport`; combined parts are accepted. Guest: an
absent callback registers nothing, and a withdrawn one stops receiving events rather than firing
into the composition it was born in. Generator: an optional callback is an event rather than a
rejection, its arguments survive, a required callback's parameter list is unaffected, and an
optional *host-invoked* lambda is still rejected — nullability must not become an escape hatch.

On the emulator, dismissing a stay animates it away and the list goes from "6 properties" to "5
properties" only after the exit completes.

## 4. Unstated Assumptions

- **No shared-element transitions.** They need cross-node choreography the protocol cannot express —
  two nodes in different subtrees agreeing about one moving thing. Not a gap this design narrows.
- **`Presence` costs a node.** Every wrapped child gains one, which is one `Create` and one
  `ChildAdd` per item. Negligible for a list of stays; worth knowing before wrapping ten thousand.
- **The exit's duration is the host's.** A guest cannot ask how long it will take, and should not
  time anything against it — `onExited` is the only correct signal.
- **A `Presence` removed by the guest while exiting disappears instantly**, because the node is
  gone. That is the guest opting out of the handshake, and it behaves exactly as it did before this
  existed.
- **Assumes the transition names stay small.** A structured specification — durations, easings,
  offsets per part — is a grammar, and would deserve its own record.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — the animation subsystem's entry.
- [`roadmap.md`](../../roadmap.md) — Phase 4's animation row.
- [`plans/text-and-animation.md`](../../plans/text-and-animation.md) — A2 marked done.

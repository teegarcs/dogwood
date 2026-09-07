# ADR-051: A Holder That Answers

**Date:** 2026-09-06
**Status:** Accepted

## 1. Context & Problem Statement

Three holder shapes were built and proven. All three run one way at a time.

- `LazyListState` and `ScrollState` send **targets** down and take **reports** up, and the two are
  independent — a report is not the answer to a target, it is an observation the guest did not ask
  for ([ADR-014](ADR-014-live-state-holders.md), [ADR-044](ADR-044-scroll-position-is-a-declared-quantum.md)).
- `FocusRequester` sends targets and takes nothing back at all, deliberately
  ([ADR-043](ADR-043-holders-are-declared-on-the-surface.md)).

`plans/production-readiness.md` named `SnackbarHostState` as the one shape not yet proven and said
it was worth doing before it was needed rather than during. It is the first thing a guest asks for
**and waits on**: `showSnackbar` suspends, and what it returns decides what happens next. A user who
tapped *Undo* gets their row back; a user who let it time out does not. A guest that ignored the
answer has written a notification.

That is a correlated request and reply, and no existing holder needed one.

## 2. Decision

**The request goes down as properties; the reply comes up as an event carrying the sequence it is
answering.**

No new protocol. The request is message, action label and a sequence — ordinary properties, exactly
as a scroll target is. What makes the reply an *answer* rather than another report is the sequence:
**the number that went down is the number that comes back**, so two requests in flight cannot be
confused.

**The suspension is guest-side and costs the boundary nothing.** A `CompletableDeferred` per
request, resumed when the matching reply arrives. Nothing is polled and no frame is requested.

Four consequences that are decisions:

- **A reply for a sequence the holder is no longer waiting on is dropped.** Not defensive tidiness:
  a snackbar dismissed by the host arrives *after* the guest has replaced it, and resuming the new
  request with the old one's answer would undo the wrong row.
- **A superseded request is answered as dismissed rather than left suspended.** Its snackbar is
  about to be replaced on screen, so "dismissed" is what actually happened to it. Leaving the
  coroutine suspended would leak a caller that never resumes.
- **One at a time.** Compose's own `SnackbarHostState` queues; a guest that could enqueue without
  limit across a boundary could fill a host's queue from a server. The newest message is the true
  one.
- **The saver carries the count and not the message or the pending request.** A snackbar that
  reappeared after a code update would be telling a user about something that finished before the
  update — and a suspended `showSnackbar` cannot survive one anyway, because the coroutine it
  suspended does not.

**`SnackbarDuration` is bounded on purpose.** Indefinite would be a guest holding the screen hostage
from a server; a snackbar the user never dismisses answers itself.

## 3. Rationale & Research

**Why the reply is an event and not a new `Change` subtype.** Events already carry positional
arguments, arrive in order, and have a tag space. A correlated reply is an event with a correlation
argument, which is the same trick the Worker bridge uses on the web and the same reasoning ADR-014
gave for putting targets on properties: the channel that exists already has the ordering and the
encoding, and a second one would need both again.

**Why the tests use `rememberCoroutineScope`.** `showSnackbar` suspends, and where a real guest
calls it from is inside its own composition. A test that launched from a hook on the composition
would be exercising a caller nobody writes.

### Verified on three targets and on a device

`SnackbarMirrorTest` runs in `renderTest`, so the host half is asserted on the Java Virtual Machine,
an iOS simulator and a real browser — including that tapping the action answers with
`actionPerformed` and that the answer carries the sequence that was asked rather than the current
one. Seven guest tests pin the correlation, including the two cases the sequence exists for: a stale
answer must not resume the wrong request, and a superseded request must be answered rather than left
suspended.

On a Pixel emulator, against the live delivery path: **"Row deleted / Undo"** appears, the guest is
suspended, and tapping *Undo* resumes it into the branch that undoes — `undone 1`.

**The first three attempts at that tap reported a dismissal, not an action**, and the cause is worth
recording because it would read as a protocol bug: the taps were landing during the snackbar's enter
animation. Waiting for it to settle is what made the difference. Nothing about the correlation was
wrong; the finger was early.

## 4. Unstated Assumptions

- **The guest cannot cancel a snackbar it asked for.** It can supersede one by asking again, which
  answers the first as dismissed. An explicit `dismiss()` would be another target property and is
  not built.
- **A suspended `showSnackbar` does not survive a code update.** The coroutine does not, so the
  caller is gone; the holder's saved count means the *next* request is still a change. Nothing
  re-shows a snackbar that was on screen when a payload was replaced, which is right.
- **`SnackbarArea` is a `Box`, not a `Scaffold`.** A scaffold owns an app bar, a floating action
  button and its own insets, none of which a guest asked for. What a snackbar needs is somewhere to
  be drawn on top of.
- **The host's queue is not modelled.** The guest sees one request at a time and the host's
  `SnackbarHostState` handles the rest; a guest cannot observe a queue depth and should not.

## 5. Updated Documents

- [`plans/conformance.md`](../../plans/conformance.md) — claim `D11`.
- [`plans/production-readiness.md`](../../plans/production-readiness.md) — §2.2 and the order.
- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — the fourth shape.
- [`adrs/README.md`](../README.md) — index entry.

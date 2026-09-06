# ADR-014: Live-State Holders — Targets Down, Reports Up, and the Host Is Authoritative

**Date:** 2026-09-01
**Status:** Accepted — **extended by [ADR-043](ADR-043-holders-are-declared-on-the-surface.md)**,
which keeps this shape unchanged and moves the *wire form* of a holder from hand-written on both
sides to generated from a surface declaration. What that record changes is the cost this one
accepted in §4: "assumes thirty holders can share this shape".

## 1. Context & Problem Statement

`roadmap.md` Phase 4 lists **live-state holders** as its own subsystem and says to start with
`LazyListState`. [ADR-005](ADR-005-corrected-coverage-and-bespoke-subsystem-list.md) enumerates
roughly thirty holder types in the widget surface plus seventy-six `remember*` factories that
construct them, so whatever shape is chosen here is paid for about thirty times over.

`LazyListState` is the right one to start with because it states the problem plainly. **Scroll
offset changes every frame**, and Layer 4's standing invariant is that no per-frame state lives in
the guest. A holder the guest owned would tick the boundary sixty times a second for as long as a
finger is moving, which is precisely the design the whole architecture exists to avoid.

Nothing existed before this. `LazyColumn` and `LazyRow` were bound without a `state` parameter at
all, so a guest could neither read where a list was nor ask it to move — and a code update
published while somebody was halfway down a page put them back at the top, on an architecture whose
central claim is that a code update while a screen is live is the *normal* case.

## 2. Decision

**The host owns the real holder. The guest gets a mirror, and the mirror is deliberately
asymmetric.**

**Reads are reports, and they are stale by design.** The host sends the visible range when it
changes **by an item**, never by a pixel: `snapshotFlow { first to last to scrolling }` with
`distinctUntilChanged`. A sixty-frame fling across three items is three crossings, not a hundred
and eighty. A guest that renders "showing 3 to 8" is correct; a guest that tries to drive a
parallax effect from this is asking for per-frame state and will not get it. Saying which of those
the mirror supports is more useful than the mirror itself.

**Writes are declared targets, not commands.** `scrollToItem(index)` records where the guest wants
to be and bumps a sequence number. The pair crosses as **ordinary properties on the list widget**,
so it rides the existing change channel, arrives in order with everything else in the same
composition pass, and **needs no protocol change at all** — no new `Change` subtype, no addition to
the positional codec. This is the same shape the roadmap prescribes for animation ("declare a
target, the host runs it") applied one subsystem early.

**The conflict rule falls out of the channel rather than being enforced on top of it.** The host is
authoritative for where the list actually is, and the newest guest target wins. A stale target
cannot arrive, because a property carries only its latest value: a guest that asks for item 40 and
then item 0 in the same composition pass sends *one* property set, for 0.

**The sequence number is a counter, not a flag.** Asking twice for the same index is two requests —
a user who taps "back to top", scrolls away, and taps it again expects to go back. With a flag the
second tap would change no property and cross nothing.

**A target is held until the list can satisfy it.** A target naming an item that does not exist yet
waits for the item count to grow rather than clamping to the end. It is abandoned when a newer
target arrives or the node goes away; a target for an item that never appears simply never fires,
which is the right outcome for a position into content that turned out not to exist. §3 records why
this is not a refinement but the difference between the feature working and not.

**Nothing is reported unless the guest said it was watching.** The host cannot see guest closures,
so presence is a boolean property. Without it, every list on every screen would pay for a viewport
observer nobody reads.

**Position survives a code update, through the ordinary saveable mechanism.** The holder's `Saver`
stores the first visible index and restores by *reissuing it as a target*, so restoring needs no
separate path at either end — the constructor's non-zero-index case is the same case as any other
declared target.

## 3. Rationale & Research

**Why properties rather than a new `Change` subtype.** A command channel would need its own place
in the batch, its own positional encoding, and its own ordering rules against the composition
changes it interleaves with. Properties already have all three. The cost is that a "command" is
really a level-triggered target, which turns out to be what a declarative guest wants anyway: the
guest describes where it should be, and a host that was mid-animation, mid-fling, or not yet laid
out reconciles that against reality.

**Why the host holds the real `LazyListState`.** It is the object Compose's own `LazyColumn` takes,
so nothing is reimplemented, and it is where the layout information lives. Redwood makes the same
split for `TextFieldState` — its host binding holds authority and discards stale guest updates —
and this is that pattern with the conflict rule made structural instead of procedural.

**Why `distinctUntilChanged` over an index triple is the right throttle.** roadmap.md asks for
"throttled viewport callbacks" without saying what the throttle is. Time-based throttling picks an
interval that is wrong at both ends: too slow to keep a header honest, too fast when nothing moves.
Item-granular reporting is the natural quantum — it is the smallest change a guest can act on —
and it produces no traffic at all for a list that is not moving between items.

### Verified end to end, and one finding that only a device produced

Run on the Pixel 9 Pro emulator (API 35) against the live delivery path.

| Observation | Result |
|---|---|
| Scroll two pages | header updates to "showing 1–8" from the host's viewport report |
| Tap "Back to top" | the host animates the real list to the top; the target crossed as two properties |
| Scroll, publish v1.0.2 while scrolled | `load #2 … restored 3 saved state keys`, and **the list comes back mid-list**, at Asakusa through Yanaka Ryokan |

**The third row failed on the first attempt, and the failure is the interesting part.** The position
was saved and restored — the banner said three keys — and the list still came back at the top. The
reason is specific to this architecture: a replacement guest re-runs its `LaunchedEffect`, so its
content is being *fetched* while it declares its restored target. At that moment the list holds a
header and a loading row, `scrollToItem(7)` clamped to the end, and the position was silently lost.
Holding a target until the list is long enough is what makes restore actually work, and no amount of
reasoning about the protocol would have surfaced it.

Nine guest-side tests pin the parts a device cannot show cheaply: that a list without a holder
reports it is not watching, that a target crosses as an index and a sequence, that asking twice for
the same place is two requests, that two targets in one pass cross as one property set with both
counted, that a report reaches the holder, that an unchanged report costs no traffic, that the
visible range reads `-1` rather than `0` before the first report (so a guest can tell "not told yet"
from "the first item is visible"), and that the position survives a replacement guest **as a
reissued target** rather than as a number nobody acts on.

Writing those tests surfaced a constraint worth recording on its own: `rememberSaveable` keys on
`currentCompositeKeyHash`, which is the *path* through the composition rather than the local call
site. The same holder remembered through two different paths is two different keys and nothing is
restored. That is the concrete form of "the new code may have a different composition shape" — a
refactor that moves a call site loses its state.

## 4. Unstated Assumptions

- **The host-side behaviour is verified on a device, not unit-tested.** Exercising a real
  `LazyListState` inside a composition needs a Compose UI test harness this project does not have —
  the same limitation [ADR-009](ADR-009-modifier-subsystem.md) records for pixel identity. The
  protocol half is tested; the binding half is demonstrated.
- **A held target waits indefinitely.** Bounded in practice by cancellation — a newer target or the
  node's removal ends it — but a guest that declares a target into content that never loads leaves
  a suspended effect until the node goes away.
- **Completion is not reported.** `animateScrollToItem` returns nothing to the guest. A guest that
  wants to know when a scroll finished cannot, and would need a completion event of the kind the
  animation subsystem will have to design anyway.
- **Reports arrive with boundary latency.** `isScrollInProgress` is useful for suspending expensive
  work while a finger is down and useless for anything frame-accurate.
- **One holder per list, and the mirror does not model item offsets.** `firstVisibleItemScrollOffset`
  is deliberately absent: it is a per-frame quantity, and exposing it would invite exactly the
  guest code this design exists to prevent.
- **Assumes thirty holders can share this shape.** `FocusRequester` is next and is plausibly the
  same (a target with no report). `TextFieldState` is explicitly *not* — Layer 5 subsystem 3 keeps
  its own version-vector design, because text has a conflict rule that a level-triggered target
  cannot express.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — bespoke subsystem 4 marked started, with
  the holder pattern and its diagram.
- [`specs/layer-4-sandbox.md`](../../specs/layer-4-sandbox.md) — the no-per-frame-state invariant
  gains the holder pattern as its constructive half.
- [`roadmap.md`](../../roadmap.md) — Phase 4's live-state-holder row.
- [`adrs/README.md`](../README.md) — index entry.

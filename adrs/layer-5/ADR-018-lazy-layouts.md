# ADR-018: Lazy Layouts — Windowing Declared, Not Negotiated

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

`roadmap.md` Phase 4: "**Lazy layouts.** Guest-side windowing, placeholder pool, throttled viewport
callbacks. Redwood needed ten modules for this alone — budget accordingly."
[ADR-008](ADR-008-design-system-audit-backpack.md) called the carousel "the load-bearing finding"
of the design-system audit, because lazy layouts are the one thing that **cannot** be absorbed by
registering a component the way images and animation could.

The lists built so far are lazy on the host and not lazy at all **on the boundary**. `VerticalList`
composes every child the guest gives it, so a ten-thousand-row feed crosses ten thousand nodes —
and each row in the sample is fifteen. The host's `LazyColumn` then renders about ten of them. The
laziness stops exactly where it matters, and every number in
[Layer 4 ADR-007](../layer-4/ADR-007-v1-wire-format-positional-json.md) says why that is fatal: the
initial batch is the expensive crossing, and it would scale with the feed.

## 2. Decision

**The guest composes a window; the host renders the true length.**

Two more properties on the lazy containers — the real item count, and the index the window starts
at — plus a second children slot holding one placeholder node. The host's `LazyColumn` is built
with `items(count = itemCount)` and, for each index, renders the guest's node if the window covers
it and the placeholder template if it does not.

The window itself comes from the viewport report that [ADR-014](ADR-014-live-state-holders.md)
already delivers, plus an overscan.

Four things follow, and each is a decision rather than a detail.

**Scrolling costs a diff, not a rewrite.** Each item is `key`ed by its **index** in the guest, so
sliding the window by one produces one removal and one insertion. Without that key, sliding by one
would rewrite every node in the window — making the traffic proportional to the window on every
frame of a fling rather than to the movement. This is measured, not assumed: the test asserts
exactly one `ChildRemove` and one `ChildAdd`.

**Identity is the index, not the node — a deliberate departure from
[ADR-015](ADR-015-node-identity-and-reuse.md).** In the host's lazy list, item five hundred is item
five hundred whichever guest node currently represents it. Keying on the node identifier would
destroy and rebuild every visible row each time the window slid, which is the opposite of what
ADR-015 is for. The rule generalises rather than breaks: *identity should be whatever is stable
about the thing*, and in a windowed list that is the position.

**There is no placeholder pool, and there does not need to be one.** The guest composes the
template **once**; the host repeats it. Redwood needed an explicit pool because it was creating
real platform widgets; these are composables, and Compose's own lazy item recycling is the pool.
The roadmap's phrasing anticipated a subsystem that this design does not require.

**Overscan is the guest's decision, not the protocol's.** A viewport report is a round trip, so a
window exactly the size of the screen shows placeholders on every flick. Overscan is how much
latency a guest chooses to hide, and a dense feed and a full-screen carousel want different
answers.

**Windowing is opt-in.** `VerticalList` without an item count behaves exactly as before, keyed by
node identifier. A six-item list should not pay for a windowing protocol, and more importantly a
short list must not get *worse* — the seed window is sixteen, so a five-item list still crosses
whole on the first batch.

## 3. Rationale & Research

**Why the host must know the true length.** A list that only knew about its window would have a
scrollbar the length of a screen and no index to fling to. That is the failure mode of naive
windowing and it is immediately visible to a user. Sending the count is one integer and it buys the
scroll extent, the scrollbar, and `animateScrollToItem(9_000)` into content the host has never laid
out — which the sample does.

**Why the window is seeded rather than left empty.** The host cannot report a viewport until it has
laid something out, and it cannot lay anything out until the guest has sent something. An empty
initial window is a deadlock, not an empty screen. `windowFor` therefore never returns an empty
range for a non-empty list, and the test says so.

**Why declared rather than negotiated.** A windowing *protocol* — the host asking for a range, the
guest replying — would add a request/response round trip on top of the change channel, with its own
ordering questions against the composition changes it interleaves with. Instead the guest declares
what it composed and how long the list really is, both as ordinary properties, and the host
reconciles. Same shape as the scroll target in ADR-014 and the formatting recipes in
[ADR-017](ADR-017-resources-and-assets.md): the guest describes, the host resolves.

### Verified end to end

On the Pixel 9 Pro emulator, through a third entry point built for this — the sample's other lists
are six items long, which is exactly the size at which the difference between "lazy on the host"
and "lazy on the boundary" cannot be seen.

| Observation | Result |
|---|---|
| A 10,000-row feed | renders; guest logs `viewport 0..7 of 10000` |
| "Jump to 9,000" | `viewport 8999..9006`, then settles at `9000..9007`; rows `#9000 Kyoto` onward, with host-formatted prices |
| A hard fling | window tracks continuously — `9015..9023`, `9019..9027`, `9023..9031` — with no placeholder visible at that velocity |

Guest tests pin the cost claim directly, which is the only way a cost claim is worth anything: ten
thousand rows produce **fewer than sixty node creations**, the window follows the viewport report,
sliding by one row costs exactly one removal and one insertion, the placeholder crosses exactly
once, and a five-item list still crosses whole.

## 4. Unstated Assumptions

- **A fast enough fling will show placeholders.** The window is refreshed by a round trip, so
  scroll velocity beyond what overscan covers outruns it. The emulator's hardest fling did not, at
  overscan six; a slower device or a longer list will. That is the trade windowing makes, and the
  knob is exposed rather than hidden.
- **Rows are assumed to be about one height.** The placeholder is a single template, so a list of
  wildly varying row heights will have a scroll extent that jumps as real rows replace estimates.
  Compose's lazy lists handle that gracefully; the scrollbar still lies briefly.
- **One placeholder per list, not per item type.** A feed with headers, cards and adverts gets one
  shape for all three. A per-type placeholder needs a keyed slot, which the children protocol
  supports but the surface does not yet express.
- **`items(count = itemCount)` means the host composes an item slot for every visible index**,
  which is what makes ten thousand cheap — but a guest that declared ten million would still be
  asking the host to hold a ten-million-item lazy list. There is no cap; there probably should be.
- **Nested lazy containers are still forbidden**, exactly as before: a lazy list inside a lazy list
  has infinite height in the scroll direction and crashes. Nothing here changes that.
- **The sample's ten thousand rows live in the guest's heap**, which is the point — the *list* is
  guest data and only the window crosses. A design that sent the list would have moved the problem
  rather than solved it.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — bespoke subsystem 2 marked delivered,
  with the windowing contract.
- [`specs/layer-4-sandbox.md`](../../specs/layer-4-sandbox.md) — the boundary-cost section records
  that list traffic is now proportional to the viewport rather than the feed.
- [`roadmap.md`](../../roadmap.md) — Phase 4's lazy-layout row.
- [`adrs/README.md`](../README.md) — index entry.

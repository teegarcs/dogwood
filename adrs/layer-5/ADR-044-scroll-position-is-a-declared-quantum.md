# ADR-044: A Scroll Position Is Reported on a Quantum the Guest Declares, and the Ends Are Exact

**Date:** 2026-09-06
**Status:** Accepted

## 1. Context & Problem Statement

A guest could not scroll anything that was not a list. `Column` fills and clips, so a screen taller
than the viewport lost its bottom, and the only scrolling container in the dictionary was
`VerticalList` — which wants *items*, and is the wrong shape for a form, an article, a settings page
or a terms-of-service panel. That is a capability gap rather than a refinement: the content is one
composition, and a list is a machine for not composing all of it.

Adding the container is easy. The holder is not, and it is the first live-state holder whose shape
`LazyListState` genuinely does not fit.

[ADR-014](ADR-014-live-state-holders.md) settled the reporting quantum for a list by observing that
a list has one already: **the item.** `distinctUntilChanged` over the visible-index triple turns a
sixty-frame fling across three items into three crossings, and the record says why that is the right
throttle — an item boundary is "the smallest change a guest can act on".

A scrolling container has no items. Its offset is a length, it changes every frame, and it is
exactly the per-frame quantity Layer 4 forbids the guest to hold. There is no boundary in it to
report on. Time-based throttling is the answer ADR-014 already rejected for the list and it is worse
here — an interval is wrong at both ends, too slow to keep a header honest and too fast when nothing
moves — and reporting every pixel is the design the whole architecture exists to avoid.

## 2. Decision

**The guest declares the quantum, and it crosses as an ordinary property.**

`rememberScrollState(reportEveryDp = …)` says how far the container must move before the host says
anything. It rides the same channel as every other holder property, it is visible in the payload,
and it makes the trade legible at the call site: a guest that wants a coarse "have we scrolled at
all" pays almost nothing, and one that wants a smoother read pays in crossings and can see what it
is paying. **No value of it yields per-frame state**, because the host reports on a threshold rather
than on a frame — which is the property that makes this safe to expose at all.

It bounds traffic rather than merely throttling it. A full scroll of a container `n` density-
independent pixels long costs at most `n / reportEveryDp` crossings, however fast the finger moves.

**Both ends are exact, whatever the quantum is.** The host reports the true value at zero and at the
maximum, and the quantised value between them. This is not a refinement:

> A quantised offset equals the maximum only when the scroll happens to land on a multiple of the
> quantum. "Am I at the bottom?" — which is the question a paginating guest actually asks — would
> therefore be answerable only by accident.

The sample makes the case concrete. The container measures **992 dp** against a declared quantum of
40; 992 is not a multiple of 40, so a purely quantised report says 960, and a guest that scrolled all
the way to the end would never learn that it had.

**Everything else is ADR-014's shape unchanged.** Targets go down as ordinary properties, the newest
wins because a property carries only its latest value, and the sequence is a counter so that asking
twice for the same place is two requests.

**The end is asked for as an intent, not a number.** `scrollToEnd()` declares a sentinel and the host
resolves it against the layout it actually has. A guest cannot compute the end — the maximum is host
layout, and the guest's copy of it is as stale as its last report — so sending a number would scroll
to where the end *was*. For the case this exists for, content that grows while you watch it, that is
precisely the wrong place. The host also **waits for a layout** before resolving it, which is
ADR-014's held-target rule reappearing for the same reason: a target declared in a replacement
guest's first batch arrives while the content is still being fetched.

**The saver carries the quantum as well as the position.** It is a constructor argument rather than
mutable state, so a saver that dropped it would hand the replacement the default — and the container
would quietly start reporting at a granularity the guest never asked for, with nothing looking wrong.

### A report is edge-triggered, and a replacement guest needs a level

This is the defect the device produced, and it is general to the holder pattern rather than specific
to scrolling.

A host mirror reports when its value **changes**; that is the whole throttle. A code update leaves
that value exactly where it was while handing the guest a brand-new holder that knows nothing. The
host therefore has nothing new to say, and the new guest never learns what it is looking at.

Observed, on an emulator, against the live delivery path: a container scrolled to 800 dp came back
from a code update reading **`offset 800dp of -1dp`**. The offset was right because it was *saved*;
the maximum was absent because it was only ever *reported*. A mirror that is half right is worse than
one that is empty, because the half that is right makes the other half look like a number rather
than an absence.

`LocalGuestGeneration` is the fix: an identity that changes exactly when the guest is replaced, and
never otherwise, provided by `DogwoodTree` from the same key the expression cache already uses. A
reporting effect keyed on it restarts once per code update, and `snapshotFlow` emits on collection —
so the restart *is* the level the new guest needs.

**`LazyListMirror` is keyed on it too, and it was not broken.** That was checked rather than assumed:
the Explore screen's header still read "showing 1–9" across a publish. It survives because clearing
and rebuilding the tree churns its visible range, which happens to make the flow emit again. That is
a coincidence of the update path, not a property of the design, and one fewer intermediate state
would take it away.

## 3. Rationale & Research

**Why density-independent pixels rather than raw pixels.** Every other length in this protocol is
declared in them — `padding(dp)`, `height(dp)`, the modifier chain — and a guest that received raw
pixels would be holding a number whose meaning changes with the device. The host converts at the
boundary with `LocalDensity`, and the density is read once per composition rather than inside the
effects, so a rotation does not restart a target effect keyed on a sequence.

**Why the container is in the generated design-system segment.** The layout tier is hand-written
because the generator does not model *lazy* layouts ([ADR-011](ADR-011-generator-emits-the-bridge.md)).
A non-lazy scrolling box is not a lazy layout, so the rule does not exclude it — and generating it
is what proves the reporting half of
[ADR-043](ADR-043-holders-are-declared-on-the-surface.md)'s mechanism against a real host rather than
only against the generator's own tests. It is the first generated holder that reports.

**Why the orientation belongs to the widget and not the holder.** `ScrollMirror.modifierFor(horizontal)`
takes it as an argument. A guest's `ScrollState` is a *position*; whether that position runs down the
screen or across it is a property of the container it was handed to, and a guest that moved a holder
between the two should not find its meaning changed underneath it.

**Why the scroll modifier goes on before the guest's own chain.** A guest that asked for padding
expects the padded edge to stay put while the content moves within it. Applying the guest's chain
first would scroll the padding away with everything else.

**Why five properties are passed to the mirror by name.** The generator emits named arguments for a
holder's properties. `ScrollState` has five, three of which are `Int`, and a positional call is one
transposition away from a container that scrolls to its own sequence number — which compiles, runs,
and says nothing.

### Verified on a device, because the host half is where the quantising happens

Pixel emulator, API 35, against the live delivery path.

| Observation | Result |
|---|---|
| Container composed | `offset 0dp of 992dp · at top` — a real host measurement reaches the guest |
| Tap "End" | scrolls to Clause 24 and reports `offset 992dp of 992dp · at end` |
| Drag to the middle | `offset 800dp of 992dp`, no edge claimed — 800 is a multiple of the declared 40 |
| Publish while scrolled, **before** the generation fix | `offset 800dp of -1dp` — the defect |
| Publish while scrolled, **after** it | `offset 80dp of 992dp`, position preserved |

Thirteen guest tests pin what a device cannot show cheaply: that the quantum reaches the host rather
than being assumed by both ends, that the end is asked for as an intent, that both edges are
recognised from what the host reports, that neither edge is claimed before the first report, and
that the position **and the quantum** survive a replacement guest.

## 4. Unstated Assumptions

- ~~The quantising is verified on a device, not unit-tested.~~ **Withdrawn, and it was wrong when
  written.** It repeated ADR-014's statement that exercising this needs "a Compose UI test harness
  this project does not have"; `dogwood-host`'s `jvmTest` source set has had `compose.uiTest` and
  `runComposeUiTest` since before either record, and a dozen tests already used it. `ScrollMirrorTest`
  asserts the quantising, both exact ends, the resolved end sentinel and the re-report, in a real
  composition with real layout, on every build — and each was watched to fail with the corresponding
  line removed. **The device run was not wasted and it was not sufficient**: it found what a
  screenshot can find, and a screenshot is a person looking at pixels once rather than a gate.
- **A test found what the device could not.** The first report carried
  `maxOffsetDp = 2147483647` — `ScrollState.maxValue` reads `Int.MAX_VALUE` before the first
  measure, which is Compose's "not laid out yet" and not a very tall container. Converting it
  produces a number with no meaning that a guest cannot distinguish from a real one. On a device the
  first report always happened to arrive after layout, so the window in which this is wrong never
  opened. Nothing is now reported until there is a measurement to report, and `maxOffsetDp` has
  three distinct meanings: `-1` never told, `0` measured with nothing to scroll, larger a real
  maximum.
- **A container with no holder still scrolls and reports nothing.** That is deliberate — the
  modifier is attached regardless — but it means a guest that forgot the holder sees a working
  container and silence, rather than an error.
- **The reported offset can be up to one quantum behind the truth in the middle.** A guest that
  treats it as the container's true position will be wrong by that much. `isNearEnd` is wrong in the
  safe direction — it becomes true up to one quantum early, which is the right way to be wrong when
  the consequence is starting a fetch.
- **Nested scrolling is not modelled.** A `ScrollArea` inside a `VerticalList` will fight for the
  gesture exactly as the equivalent Compose code would, and nothing here mediates it.
- **The container is not lazy, and nothing warns.** A guest that puts a thousand rows in one will
  compose a thousand rows. The surface documentation says so; the protocol does not enforce it.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — the live-state holder section, the third
  shape, and `LocalGuestGeneration`.
- [`roadmap.md`](../../roadmap.md) — Phase 7's deferred live-state-holder item.
- [`plans/conformance.md`](../../plans/conformance.md) — claim `D9`.
- [`adrs/README.md`](../README.md) — index entry.

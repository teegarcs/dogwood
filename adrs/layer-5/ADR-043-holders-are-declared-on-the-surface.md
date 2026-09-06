# ADR-043: Live-State Holders Are Declared on the Surface, and the Generator Plumbs Them

**Date:** 2026-09-06
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-014](ADR-014-live-state-holders.md) built the first live-state holder, `LazyListState`, and
established the shape every other one was meant to follow: **the host owns the real object, the
guest holds a mirror, targets go down, reports come up, and the host is authoritative.** It also
stated the bill it was leaving unpaid. [ADR-005](ADR-005-corrected-coverage-and-bespoke-subsystem-list.md)
enumerates roughly thirty holder types across the widget surface plus seventy-six `remember*`
factories that construct them, and `specs/layer-5-host.md` says the consequence plainly: "this
subsystem's per-holder cost recurs far more often than the five examples suggest."

Two things were being paid per holder, and only one of them is real work.

The real work is the **decision**: what does a target mean for this holder, what may be reported,
what happens when a target cannot yet be satisfied. That is different for a list, a focus request,
a bottom sheet and a text field, and ADR-014's own §4 says so — `TextFieldState` explicitly cannot
use the level-triggered pattern, because a stale scroll target is a wish to override and a stale
text value would delete a character the user just typed.

The work that is **not** real is the wire form. `LazyListState` crosses as an index property, a
sequence property, an "is anybody watching" property and a report event; the guest stub reads the
holder's fields in the composable body, writes four properties, and installs a report lambda. Every
line of that is mechanical, and every line of it was hand-written on both sides of the boundary,
twice — once in the guest stub and once in the host binding. Doing that thirty more times is thirty
opportunities to write `set(...)` inside `update` instead of reading the field in the composable
body, which is invisible when you get it wrong: the target simply never crosses.

Worse, the generator did not merely fail to help. It **refused**: `Parser.kt` classified any
parameter whose type contained a known holder name as `UNSUPPORTED`, with the reason "live-state
holder". That is the correct default — the guest cannot hold a live object — but it left no way for
a surface to say "this one is mirrored, and here is the mirror". Every holder was therefore
condemned to be a hand-written widget outside the generated design system, which is exactly what
`VerticalList` and `HorizontalList` are and exactly why they need reserved dictionary tags.

## 2. Decision

**A holder is declared on the surface with `@Holder`, its shape is registered in the generator, and
everything between the holder and the wire is emitted.**

**`@Holder` is an assertion the generator checks, not a marking it takes on trust.** The annotation
says "this parameter is a mirrored holder"; `SurfaceParser.DEFAULT_HOLDER_SHAPES` says which types
that is true of. A `@Holder` on a type with no registered shape **fails the build with the type's
name in the message**. It is not plumbed by inference, because a guessed shape emits properties
nothing on the host reads — and that failure is invisible: the widget renders perfectly and the
holder is silently inert. An *unmarked* live-state parameter is still rejected exactly as before.

**A shape declares the wire form, not the behaviour.** `HolderShape` names the properties the
holder expands into — a suffix, a serializable type, the guest field to read, and the value the host
assumes when the property is absent — and optionally one report event and the holder method it
feeds. It also names a **host-side mirror function, written by hand**. This is the same line
[ADR-011](ADR-011-generator-emits-the-bridge.md) already draws between the bridge and the widget:
what a holder *does* on the host — move a list, take the keyboard, open a sheet — is the part that
requires taste, and it is not the part that grows without bound.

**Holder properties are numbered in declaration order with everything else**, so appending a holder
to a surface appends tags rather than renumbering the properties around it. Report events share one
numbering with declared callbacks, because they share one channel: a report is the host speaking
through an `EventTag`, and it is not a different kind of thing merely because the surface did not
spell it out as a lambda. A report's argument list is given a synthesised signature so the
dictionary lock's type check covers it exactly as it covers a declared callback.

**Absence is the sentinel, here as everywhere else, and here it is load-bearing rather than
symmetric.** A holder parameter is always optional, and when no holder is passed the stub sends
*nothing at all*. Sending the properties unconditionally would put new tags on every one of these
widgets; a client one dictionary version behind meets property tags it has never seen on a widget
that owns an affordance, and [ADR-031](ADR-031-safety-relevant-parameters.md) defines that as
withhold-the-widget. Every text field on that client would go blank because a **newer** guest
declined to ask for focus. The generated stub is `set(focusRequested) { if (it != null) … }` for
that reason.

**`FocusRequester` is the first holder built this way, and it is a target with nothing reported
back.** A guest can ask for the keyboard and ask to give it up. It cannot ask whether a field is
focused, and that is a decision rather than an omission: focus moves with every tap and every
keyboard dismissal, so a guest branching on it would hold precisely the per-frame state Layer 4
forbids. A guest that needs to know a field was left has an ordinary event for it.

Two details of the focus mirror are decisions in their own right:

- **Giving focus up is a request, not the absence of one.** The direction crosses as its own
  property, so `requested = false` means "take the keyboard away". A host that read it as "nothing
  was asked" would leave the keyboard up.
- **The saver carries the count and deliberately not the direction.** Restoring the direction would
  reissue the last request after a code update, so a screen the user had moved on from would take
  the keyboard back seconds later for no reason they could see. Carrying the count forward is what
  makes the *next* real request still a change — a restored requester that started at zero would
  send a sequence the host had already acted on, and the request would be lost.

## 3. Rationale & Research

**Why an annotation rather than inferring from the type.** The generator already knows the fifty-odd
holder type names — `Parser.kt` and `tools/measure-compose-surface.py` both carry the list. It could
have plumbed any parameter whose type appeared there. That would be the optimistic default the
corrected coverage measurement was burned by twice
([ADR-005](ADR-005-corrected-coverage-and-bespoke-subsystem-list.md) records the fail-closed triage
rule that exists to prevent a third), and the failure mode here is the quiet kind: emitted
properties, a rendered widget, an inert holder. `@Range` took the same route for the same reason
([ADR-042](ADR-042-ranges-are-declared-on-the-surface.md)) — the bound is declared, not guessed from
a parameter's name.

**Why the shape table is injectable.** `SurfaceParser` takes its shapes as a constructor argument,
defaulting to the shipping table. That is what lets the generator's own tests exercise a
*reporting* shape without adding one to the shipping table — `FocusRequester` deliberately reports
nothing, so the shipping table cannot cover that half. Registering a reporting shape for the sake of
a test would be worse than not testing it: the entry names a host mirror, and one written only to
satisfy a test is dead code with a maintenance cost.

**Why the mirror's modifier is always attached.** `FocusMirror` builds a `FocusRequester` and
attaches it whether or not anybody ever asks. A requester created only once a request had been made
would be attached in the same frame the request is acted on, and `FocusRequester.requestFocus()`
throws when its node is not yet attached. One always-attached modifier node per text field is a
cheaper answer than a race.

**Why a refused request is dropped and reported rather than thrown.** Compose signals an unattached
or unfocusable target by throwing, and the request came from a payload delivered over the air
without a store review. An exception inside composition takes the screen down on **every** client
that receives the payload, at the same moment — the failure
[ADR-035](ADR-035-hostile-property-values.md) exists to prevent. The request is dropped and lands in
`SkewReport.rejectedFocusRequests`, because the visible symptom otherwise is a keyboard that did not
open and no record of why.

**Why focus is a second holder on the text field rather than part of `TextFieldState`.** They have
different conflict rules. Text is version-vectored — the host counts edits and discards a guest value
stamped older than its own count ([ADR-019](ADR-019-text-input.md)) — and focus is level-triggered.
Folding one into the other would put a level-triggered target inside a version-vector holder, and
would mean a guest that is behind on the text cannot focus the field. Two holders on one widget is
the first case of it, and the generator numbers them independently.

### Verified on a device, which is where the host half of a holder can be verified at all

Run on a Pixel emulator, API 35, against the live delivery path, reading `dumpsys input_method`
rather than believing a screenshot.

**This paragraph originally said `FocusMirror` could not be unit-tested here, citing ADR-014's claim
that it would need "a Compose UI test harness this project does not have". That was false when it
was written** — `dogwood-host`'s `jvmTest` source set has carried `compose.uiTest` and used
`runComposeUiTest` for a dozen tests since before ADR-014. The claim was inherited rather than
checked, which is precisely the failure `AGENTS.md` §1.5 is about, pointed the other way: a
*capability* asserted absent without looking. `FocusMirrorTest` now asserts the host half in a real
composition, and each assertion was watched to fail with the mirror's action removed. The device run
below stands as what it always was — evidence a screenshot can give, which is not a gate.

| Observation | Result |
|---|---|
| Tap "Focus card number" | field outlined and captioned, caret in it, `mInputShown=true` |
| Tap "Dismiss keyboard" | field released, `mInputShown=false` |
| Focus, dismiss, focus, dismiss, focus again | six requests, six actions — the counter, not a flag |
| Publish v1.0.2 while the field is focused | `load #2 · restored 3 keys`, and the field **stays** focused |
| Dismiss, then publish v1.0.3 | `mInputShown=false` — the replacement guest does **not** take the keyboard back |
| Ask again after that update | `mInputShown=true` — the restored requester still works |

The last two rows are the pair that matters, and they are the reason the saver carries a count and
not a direction. Either one alone is satisfiable by a mistake: a requester that saved nothing passes
the fifth row and fails the sixth, and one that saved the direction passes the sixth and fails the
fifth.

Eighteen tests pin the parts a device cannot show cheaply — nine in the generator, over what it
emits on both sides and what it refuses; nine on the guest, over what crosses, what does not cross
when no holder was passed, and what survives a replacement guest.

## 4. Unstated Assumptions

- **One shape is proven and one is only tested.** `FocusRequester` proves the target-only shape end
  to end. The *reporting* half of the mechanism is exercised only by the generator's own tests
  against an injected shape; the first real reporting holder generated this way will be its first
  run against a host.
- **`LazyListState` was not migrated.** It rides on `VerticalList` and `HorizontalList`, which are
  hand-written outside the generated segment because the generator does not model lazy layouts
  ([ADR-011](ADR-011-generator-emits-the-bridge.md)). Migrating it would need that first, and the
  mechanism was derived *from* it, so it would be a rewrite with nothing to learn.
- **The host mirror is still hand-written per holder, by design.** Roughly thirty holder types
  remain; this makes each one a table entry plus a mirror rather than a table entry plus a mirror
  plus two hand-written wire forms. It removes the mechanical half of the cost, not the half that
  requires a decision.
- **A holder whose shape needs more than properties and one event does not fit.** Anything wanting
  its own `Change` subtype, its own ordering rule, or a two-way handshake is outside this and should
  say so rather than being bent into it. `TextFieldState` is the standing example of a holder that
  needs a different protocol, and it keeps one.
- **A dropped focus request is reported, and nothing reads the report yet.** `SkewReport` is
  telemetry a host collects; a host that ships none will see a keyboard that did not open and have
  no more information than before.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — bespoke subsystem 4, the holder shape
  table and what is now generated.
- [`roadmap.md`](../../roadmap.md) — Phase 7's deferred live-state-holder item.
- [`plans/conformance.md`](../../plans/conformance.md) — claim `D8`.
- [`adrs/README.md`](../README.md) — index entry.

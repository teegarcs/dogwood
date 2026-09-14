# ADR-069: The primitive tier is the lever

**Date:** 2026-09-13
**Status:** Accepted

## 1. Context & Problem Statement

The owner's goal for this architecture is stated in one sentence: *support pretty much anything
Compose offers without new app releases.* A production review measured what a payload could
actually call and found the gap between that sentence and the code was structural, not
incremental.

Every widget crosses the wire as an integer tag from a declared surface, and a binding is native
code that ships through a store ([ADR-046](ADR-046-a-product-registers-its-own-segment.md): "a
segment cannot arrive over the air"). So a new *kind* of widget will always need a release, in
this design and in every tag-based design. The only way the owner's sentence is ever true in
practice is the rule [ADR-006](ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md)
§2.1 already states: *if you could write it by composing existing pieces, it's yours and it's
free.* That rule is only as strong as the pieces, and the pieces were:

- five layout widgets — `Text`, `Column`, `Row`, `Box`, `Spacer` — with **no arrangement, no
  alignment** on the containers;
- `Text` with text, `maxLines`, a style token and a colour, and **no weight, alignment, overflow,
  size or decoration**;
- twelve modifiers, with **no `clickable`** (only `Row` took an `onClick`), no border, offset,
  shadow, `fillMaxSize`, per-side padding, semantics or test tag.

A Switch, a Checkbox, a tappable Card, a Tab row or a status pill could not be composed from that
in the payload. Meanwhile `developer-experience.md` §1 said the host knew "about two-thirds of the
widget surface" — the measured ceiling of a generator over the androidx sources, which the roadmap
lists as generator v2 and which is not built. The claim and the vocabulary were a long way apart,
and the vocabulary was the half that could be fixed in a day.

## 2. Decision

**Segment 0 is grown deliberately, on every host at once, and the layout segment moves to version
2.** The bindings live in `dogwood-host`'s common source set and render on Android, iOS, desktop
and the web through one implementation ([ADR-041](ADR-041-one-host-core-split-at-the-zipline-seam.md)),
so the growth is one change rather than four.

### 2.1 `clickable` on any node, carried by element index

`Modifier.clickable(enabled = true, onClick)` reuses the mechanism animation completions already
use: the chain carries per-element callbacks keyed by position, `applyModifier(id, modifier)`
registers them, and the host sends `EventTag(ELEMENT_EVENT_BASE + index)` — the constant animation
had named for itself, renamed because it now means what it always meant, *the element at this
index reports*. Only the `enabled` flag crosses; the handler never does. Handlers stay outside the
chain's structural equality, so a fresh lambda each recomposition does not re-cross the chain.
`Row(onClick)` stays, as the pre-`clickable` form.

### 2.2 Layout and text properties, absent by default

`Column(verticalArrangement, horizontalAlignment)`, `Row(horizontalArrangement, verticalAlignment)`,
`Box(contentAlignment)`; `Text(fontWeight, textAlign, overflow, sizeSp, textDecoration,
lineHeightSp)` on both overloads, and the recipe overload gains the `color` it never had. Every one
is optional and absence is the host-default sentinel, as everywhere in this protocol.

Arrangement crosses as **one string** — `start | center | end | spaceBetween | spaceAround |
spaceEvenly | spacedBy:<dp>` — so a client that has never heard of a name degrades to Compose's
default and reports it, and a negative gap (which Compose throws on) is clamped and reported.
Alignments cross as ordinals, matching the encoding `Modifier.align` already uses for the same
three enumerations rather than introducing a second. Text names cross as strings because they are
new closed sets with no prior encoding.

### 2.3 Fifteen modifiers, appended

Tags 13–27: `clickable`, `border(widthDp, color)`, `offset(x, y)`, `fillMaxHeight`, `fillMaxSize`,
per-side and symmetric `padding`, `shadow`, `aspectRatio`, `contentDescription`, `testTag`,
`wrapContentWidth`, `wrapContentHeight`, `defaultMinSize`, `widthIn`, `heightIn`. Every value
Compose would throw on is clamped through the existing helper and reported. `-1` means
`Dp.Unspecified` where Compose has one.

### 2.4 The skew rule, and where `clickable` is contained

An unknown modifier tag is ignored by an older host (`else -> modifier`, unchanged). The new
properties are cosmetic and ride the ordinary rule: an older host ignores them. `clickable` is the
one addition that is not cosmetic — a node nobody can tap is a screen that does nothing, silently
— and it is contained *upstream* rather than in the binding: the layout segment moves to version 2,
a payload using anything here declares `androidx.layout:2` in its signed manifest, and a version-1
host refuses it before `start` ([ADR-061](../layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md)).
The web sample's "too new" fixture had to move past 2 to stay refused, and its comment now says
what it actually exercises.

## 3. Rationale & Research

**Why the primitive tier and not generator v2.** Generator v2 — parsing the androidx sources with
resolved types and the general defaults-expression problem — is the roadmap's least certain
estimate and would still leave 25.2% of the surface behind holders and 7.2% unreachable. It also
grows every client globally, by the web page-weight rule ([ADR-066](ADR-066-the-pickers-cost-half-a-second.md)).
The primitive tier is what a design system is *made of*. Measured against the owner's design
system rather than against Compose's API list, it is the shorter path to the sentence being true.

**Evidence, all watched to fail first.** Nine guest tests on Node (`PrimitiveTierTest` in
`dogwood-compose`): every new modifier crosses with its tag and argument; `clickable` crosses only
its flag and routes a synthetic event on `1000 + index` to the handler; a disabled `clickable` says
so and keeps its element; a rebound handler does not re-cross the chain; absence sends nothing.
Thirteen host render tests (`PrimitiveTierTest` in `renderTest`, run on the Java Virtual Machine,
in headless Chrome and on the iOS simulator): a `performClick` through the new `testTag` sends
event 1002; a disabled `clickable` swallows the tap; per-side padding and `fillMaxSize` lay out
60×40 at (10, 20); a row with `spacedBy` puts its second child at 30 dp and `spaceBetween` pushes
the last to 80 dp; a column aligns end and a box centres at (40, 40); a column that says nothing
lays out as it always did (the control); `contentDescription` reaches the semantics tree; an
unknown text name is reported. The negative controls: replacing the host's `CLICKABLE` branch with
an unreachable one failed exactly the click test with `expected [1002] but was []`; emptying the
guest's `fontWeight` recording failed exactly the two text tests.

**A defect the first run produced.** `aspectRatio` clamped to `Float.MIN_VALUE` made Compose
compute a 2,147,483,647-pixel height and throw `Can't represent a width of 100 and height of
2147483647 in Constraints` — the crash the clamp existed to prevent, arriving from the other side.
The clamp is 0.01..100 and the comment in `Modifiers.kt` records why.

**Page weight.** Measured with `tools/conformance/from_web_weight.py`: 3,766,502 → 3,770,785 bytes
brotli, **+4,283 bytes** for fifteen modifiers, twelve properties and their resolvers, all in the
application WebAssembly. The G5 ceiling of 3,900,000 does not move.

## 4. Unstated Assumptions

- **Assumes the tier is sized against a design system, not against Compose.** The next additions
  should come from an audit of the owner's own components — which cannot be composed, and why —
  rather than from the androidx list. `clickable` with a semantics role, `border` with a shape and
  an animated per-side padding are each one more tag when a component needs them.
- **Assumes unknown text and arrangement names may ride `unknownTextStyles`** with a prefix
  (`fontWeight:heavy`, `arrangement:middle`) rather than a new `SkewKind`. A new kind is a new
  metric name, and this keeps the dashboard stable until somebody wants the split.
- **Assumes a version-1 host refusing every version-2 payload is the right trade.** It is the
  judgement [ADR-061](../layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md) already made;
  a payload that uses none of this may keep declaring version 1 by building against the previous
  stubs, which is the ordinary "target the oldest client you support" rule.
- **Assumes no device screen exercises the additions yet.** The render tests are the evidence; the
  About screen composes a row of them so the Android and iOS drills see them on a device.

## 5. Updated Documents

- [`developer-experience.md`](../../developer-experience.md) §1 — the vocabulary table that replaces
  the "two-thirds" claim; §5 rule 1; §8.
- [`high-level-tech-spec-final.md`](../../high-level-tech-spec-final.md) — the core thesis sentence.
- [`docs/authoring.md`](../../docs/authoring.md) — composing components in the payload.
- [ADR-009](ADR-009-modifier-subsystem.md) — status line: extended.
- [`adrs/README.md`](../README.md) — index entry.

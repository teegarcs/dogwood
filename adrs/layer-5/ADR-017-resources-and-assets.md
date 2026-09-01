# ADR-017: Resources and Assets — Named by the Guest, Owned by the Host

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

`roadmap.md` Phase 4 and [ADR-005](ADR-005-corrected-coverage-and-bespoke-subsystem-list.md)
enumerate this subsystem as four things: Uniform Resource Locator (URL)-keyed host image loading,
**an icon dictionary**, **a font story**, and **a localized-strings story**. The roadmap adds that
under design-system-first the first of those is already covered by a registered `AsyncImage(url)`,
so this record is about the other three — and about a fourth that the enumeration does not name and
that turns out to be the hardest.

The reason all of them exist is the same sentence, and it is worth stating once: **the sandboxed
guest has no filesystem, no network, no stable host resource identifiers** — integer resource
identifiers change across host builds, and the payload ships months apart from the host —
**and the pinned QuickJS (2021-03-27, via Zipline 1.27.0) ships no ECMA-402 `Intl`**.

`Bindings.kt` had a comment recording the first half of that: "`Icon` is deliberately absent: its
required `Painter` is asset-gated." The second half had never been confronted at all. The sample
screen showed prices as `"$612"` — strings the *server* had formatted, which quietly decided the
currency symbol, the decimal separator, the grouping separator and the number of decimal places on
behalf of every device the payload would ever reach.

## 2. Decision

Four pieces, and three of them are the same idea: **the guest names an intent, the host owns the
meaning.** That is the pattern [ADR-010](ADR-010-deferred-expression-grammar.md) established for
colour tokens, applied to everything else a payload cannot carry.

### 2.1 An icon dictionary

`IconSet` maps names to `ImageVector`, published through `LocalIconSet`. A registered `Icon(name,
contentDescription, sizeDp, tint)` component — the tenth in the design-system segment, and the
first *generated* one whose absence was previously documented as a limitation. `tint` is a colour
*token name*, not a colour, because a literal could not follow dark mode. An unknown name draws the
set's fallback glyph and is recorded.

The Material set is a **default, not a requirement**: a product's icons are its own, and the whole
point of a dictionary is that the host chooses what is in it.

### 2.2 Typography tokens, which are also the font story

`Typography` names ten text styles; `Text(style = "titleLarge")` sends the name. This is the
design-system-first answer to fonts, and it is answer enough: a payload cannot ship a font file
because no font file crosses this boundary, so the useful question is not "how does a guest get a
font" but "how does a guest ask for the *right* font". Naming a style does that, and the family
comes with it.

### 2.3 Localized strings are payload-carried, not host-resolved

`StringTable` selects on `DogwoodConfiguration.language`. This is guest code with **no protocol at
all**, and the reason is the architecture's central promise: a screen's copy changes with the
screen. A host-resolved string table would put the words in the application binary and the layout
in the payload, so adding a row to a screen would need a store release to name it — which is
exactly the coupling Dogwood exists to remove.

Three fallbacks, in order: the device's language, the table's fallback language, then **the key
itself**. The key rather than an empty string, because a missing translation should be visible in a
screenshot rather than a gap somebody has to notice.

### 2.4 Formatting is a recipe, not a service — and this is the piece the enumeration missed

Money, dates, percentages, decimal separators, relative times. The guest cannot produce any of
them. A **host service** was the obvious answer and is the wrong one: formatting is needed *during
composition*, once per value, so six prices on a screen would be six suspending crossings before
anything could be drawn, and a guest rendering placeholders while it waited would be worse than one
that could not format at all.

So `Formats` extends the deferred-expression grammar with seven text factories, evaluated
host-side at the moment of drawing. **The number crosses, not the rendered string.**

Money crosses as **minor units and an ISO 4217 code**, never a decimal amount, because the number
of decimal places is a property of the currency and the host is the side that knows it: `USD` has
two, `JPY` has none, `KWD` has three. A guest sending `"612.00"` has already made that decision, and
made it wrongly for most of the world.

### 2.5 One place for skew

`SkewReport`, on the experience. Every rule in this project says an unknown thing degrades *and is
reported*; the first half was implemented in several places and the second in one, where nobody
could read it. Unknown widget tags, expression factories, colour tokens, text styles and icon names
now land in one object a host can ship as telemetry — which is how a team learns that a design
system update reached payloads before it reached devices.

## 3. Rationale & Research

### The generator handed a new component a tag that was already in use

Adding `Icon` as the tenth entry in the design-system surface allocated it local tag **10**. The
hand-written `VerticalList` already answers to local tag 10 in that same segment, and the generated
dispatch runs *first* — so every vertical list on every screen would have rendered as an icon.

That is precisely the failure the tag lock exists to prevent, and the lock did not catch it: it
knew about the nine generated components and nothing about the two hand-written ones sharing their
segment. [ADR-011](ADR-011-generator-emits-the-bridge.md) had recorded that the lazy containers are
hand-written "because the generator does not model lazy layouts"; what it had not recorded is that
their *tags* are therefore not the generator's to give.

Three changes, all in the generator:

- The dictionary carries `reservedLocalTags`, and the allocator skips them.
- The lock **fails the build** if a generated tag lands on a reserved one, and if a reservation is
  ever withdrawn — a reservation may be added but never removed, or the next component added takes
  a published tag.
- The lock also fails if components were added without raising the segment version. Guest code
  branches on `segmentVersions["dogwood.designsystem"]`; adding a component clients cannot detect
  is worse than not adding it.

The bad lock entry was corrected by discarding it rather than by editing around it. It had existed
for two minutes and had never left the machine, which is the only circumstance in which a
published-tag file may be rewritten.

Separately, `DogwoodDictionary.knows` stopped keeping a hand-maintained copy of the generated tags
and now unions the generated `DogwoodDesignSystemTags` set. The failure mode of the copy was a
component the client renders correctly while *reporting* it as unknown.

### Verified on the emulator, in two languages

| Observation | Result |
|---|---|
| `en-US` diagnostics | `$612.00`, `¥61,200`, `1,234,567.891`, `7.5%`, `Sep 1, 2026, 9:39 AM`, `3 days ago` |
| The same recipes, `JPY` | `¥61,200` — no decimal places, decided by the host from the currency code |
| Per-app locale set to `ja-JP` | banner reads `ja-JP`; the payload string table switches to 航空券と宿泊 / 往復 / 宿泊先: / 1泊あたり; the flight icon renders tinted by the `primary` token; prices re-render |
| Dictionary segment | `dogwood.designsystem v3`, with `Icon` at local tag 12 |

Twenty tests were added. The ones worth naming: currency fraction digits come from the currency and
not the guest; the same recipe renders differently in `en-US` and `de-DE`; the format memo is keyed
on the locale, not only the recipe, because a memo keyed on the recipe alone would keep showing
prices in the locale the screen opened in and nobody would report that as a formatting bug; an
unknown currency degrades to the amount and the code; an unknown text style still renders and is
recorded; and every format kind has a distinct factory identifier, because two kinds sharing one
would render as each other on a client that implemented only the first.

## 4. Unstated Assumptions

- **Formatting recipes reach `Text` and nothing else.** Generalising them to every generated
  `String` parameter — so `Price(price = Formats.currency(…))` works — needs a `DogwoodText` value
  type in the surface language, because a parameter typed `String` cannot accept a recipe. That is
  named, bounded, remaining work.
- **`formatRelativeTime` returns English.** There is no relative-time formatter in `java.time`, and
  pulling in the International Components for Unicode for one string is not a trade worth making
  here. The *number* crosses correctly; a product shipping other languages supplies its own
  implementation of that one function or puts the phrase in its own string table. Said plainly
  because a reader could otherwise assume the whole surface is localized.
- **String tables have no plural rules and no interpolation.** A table holds words; a recipe holds
  a number. Plurals are the natural next thing to want and they need a rule set the guest does not
  have.
- **`AsyncImage` still has no placeholder or error slot.** The image half of the subsystem is
  covered by a registered component, as [ADR-006](ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md)
  says, but Redwood's shape includes those slots and this one does not yet.
- **`compose.materialIconsExtended` is a large dependency** for a default icon set most products
  will replace. It is here so the dictionary has something real in it; an application shipping its
  own `IconSet` should drop it.
- **The host formats in the host's locale, read from `Locale.current`**, not from the
  `DogwoodConfiguration` value the guest holds. They agree today. They are separate on purpose: the
  guest carries the locale so it can *branch*, and trusting a guest-supplied value for formatting
  would let a payload decide how the host renders money.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — bespoke subsystem 8 marked delivered,
  with the named-resource pattern, the skew report, and the reserved-tag rule.
- [`roadmap.md`](../../roadmap.md) — Phase 4's resources row.
- [`adrs/README.md`](../README.md) — index entry.

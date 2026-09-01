# ADR-024: Formatting the Payload Can Configure, and Plurals It Cannot

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-017](ADR-017-resources-and-assets.md) established that locale-aware text crosses as a recipe
the host renders, because the pinned QuickJS ships no ECMA-402 `Intl`. It left the recipes fixed:
the guest could ask for "a number" or "a currency", but not for a *particular shape* of number, and
not for a count in the language's own plural form.

Both gaps land on the same line — **what belongs to the payload and what belongs to the host** — and
they fall on opposite sides of it.

## 2. Decision

### 2.1 Recipes take guest-supplied parameters

`Formats.number(value, pattern = "#,##0.0")`. The **pattern is the payload's**, shipped over the
air; the **symbols are the device's**. One set of wire bytes renders `1,234.6` in the United States
and `1.234,6` in Germany.

This is the dial that ships new formatting behaviour without a host release. **The factory set
stays closed** — an open one would be remote code in a costume, and the host must know what a
recipe means before it evaluates one — but each factory takes parameters, and that is enough for
the cases products actually have.

**A guest pattern is untrusted input.** `DecimalFormat` throws on a malformed one at construction,
which is where it is caught: the value still renders in the locale's own form, and the payload's
mistake is recorded as skew. A payload can be replaced over the air without review, so a pattern
that crashed a render would be a payload that could crash a screen.

**Dates deliberately take no free-form pattern.** A date pattern hard-codes field order, which
defeats the locale it was sent to — `MM/dd/yyyy` is wrong in most of the world. Dates keep named
styles. Skeleton-based reordering (`DateTimePatternGenerator`) would be the right extension and
exists on Android but not in desktop `java.time`; recorded as an assumption rather than built.

### 2.2 Plurals: the host picks the category, the payload owns the words

```kotlin
Formats.plural(nights, mapOf("one" to "# night", "other" to "# nights"))
```

Only the **category selection** is host work, because only that needs locale data — English has two
forms, Arabic has six, and the rules are a table the sandbox does not carry. The **words stay the
payload's**, from its own string table, for exactly the reason
[ADR-017](ADR-017-resources-and-assets.md) made string tables payload-carried: a host-resolved
plural would put the copy in the application binary and the layout in the payload, so changing a
word would need a store release.

`#` is replaced by the **formatted** count, so a four-figure one is grouped the way the device
groups it — `1,200 nights` and `1.200 nights` from one recipe.

Three fallbacks, in order: the category the locale uses, then `other`, then the bare count with the
gap recorded. A payload that translated nothing still renders a number rather than nothing.

### 2.3 The desktop plural fallback is wrong, and says so

Android carries the International Components for Unicode, so the real rules are used there. A
desktop Java Virtual Machine does not, and vendoring the whole library for one lookup is not a
trade worth making — so desktop falls back to the English rule. **That is wrong for most
languages.** It is stated here, in the source, and in the assumptions below, because a silent wrong
plural is exactly the kind of defect that ships. A product shipping desktop in many languages
supplies its own implementation of that one function.

## 3. Rationale & Research

**Why a pattern rather than more named styles.** Named styles are right when the host owns the
meaning (`titleLarge`, `primary`, `flight`). A number's shape is a *presentation* decision that
belongs to whoever designed the screen, and screens change over the air. Adding a named style for
every shape a product might want would put the payload's design choices in the host's release
cycle, which is the coupling this architecture exists to remove.

**Why the plural split is where it is.** It is the only split that keeps both properties: copy
changes over the air, and grammar is correct in languages the payload's authors do not speak.

### Verified

Ten tests added. Patterns: one recipe renders with each device's symbols; a pattern changes shape
and not merely symbols; a malformed pattern degrades to the locale's own form and is reported; no
pattern behaves exactly as before, so the parameter is additive. Plurals: the category comes from
the host and the words from the payload; the count is *formatted*, not merely interpolated
(`1,200` against `1.200`); an untranslated category falls back to `other`; a payload that
translated nothing still renders the count and the gap is recorded; a template with no `#` renders
as written, because "no nights available" is a legitimate zero form.

On the emulator, the same screen reads **6 properties** under `en-US` and **6件の宿泊先** under a
`ja-JP` app locale — one recipe, one payload, and a plural rule the payload never expressed.

## 4. Unstated Assumptions

- **Desktop plural categories are English-only.** Repeated here because it is the sharpest limit in
  this record and the easiest to forget.
- **`#` is the whole interpolation language.** No named arguments, no nested plurals, no gender
  selection. That is deliberately short of ICU MessageFormat: the full grammar is a dependency and
  a parser, and should be adopted when something needs it rather than in advance.
- **Patterns reach `number` only.** Currency keeps the locale's own form, because a currency
  pattern that moved the symbol would produce prices that look wrong to the people reading them.
- **A pattern is validated by attempting it.** There is no separate checker, so a pattern that is
  legal but nonsensical (`"###"` on a price) renders nonsense rather than being rejected.
- **The plural category is computed per render**, not memoised separately — it rides the text memo,
  which is keyed on the recipe and the locale, so the cost is one lookup per distinct count.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — the host-resolved values section.
- [`roadmap.md`](../../roadmap.md) — Phase 4's resources row.
- [`plans/text-and-animation.md`](../../plans/text-and-animation.md) — T1b and T2 marked done; the
  plan is complete.

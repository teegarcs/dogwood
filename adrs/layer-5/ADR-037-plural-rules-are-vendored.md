# ADR-037: Plural Rules Are Vendored, And Checked Against ICU

**Date:** 2026-09-03
**Status:** Accepted

## 1. Context & Problem Statement

A plural splits cleanly in two: the host picks the Unicode **category** — `zero`, `one`, `two`,
`few`, `many`, `other` — because that is locale data the sandbox has no table for, and the payload
supplies the **words** ([ADR-024](ADR-024-configurable-formatting-and-plurals.md)). That split is
settled. Where the category comes from was not.

Android answered it. `android.icu.text.PluralRules.select` is real Unicode Common Locale Data
Repository (CLDR) data, reached by reflection because the source set is shared with a desktop Java
Virtual Machine that has no such class. **Neither iOS nor desktop had any answer at all.** Both fell
back to `if (count == 1) "one" else "other"`, so a single payload rendered correct Polish `few` on
Android and the wrong category on the other two — silently, and only for a reader of Polish.

The platform review named the two candidate answers and asked for the cost: borrow Foundation's
plural rules, or vendor CLDR data for the locales products actually ship.

## 2. Decision

**Vendor the CLDR cardinal rules in common code, and verify them against International Components
for Unicode for Java (ICU4J) in a test rather than trusting the copy.**

`PluralRules.kt` implements `cldrPluralCategory(count, languageTag)` for about a hundred and eighty
language subtags, returning `null` for a language it does not name. iOS uses it as its whole
implementation; the desktop Java Virtual Machine uses it when the reflective ICU lookup finds
nothing; Android is unchanged and still prefers the platform's own rules.

**Integers only, and that is what makes it tractable.** `pluralCategory` takes an `Int`, so the CLDR
operands collapse: `n` is the absolute value, `i` equals `n`, and `v`, `f`, `t` and `e` are all zero.
Most of the apparatus in a published rule — the clauses separating "1" from "1.0" — has no reachable
case, so Czech and Slovak never reach `many`, Lithuanian never reaches `many`, and Serbo-Croatian
collapses to three categories rather than four. Each of those is stated in a comment where it
happens, because a reader comparing this file against `plurals.xml` will otherwise think something
is missing.

**The oracle is the point.** `PluralRulesAgreementTest` runs the table against ICU4J across every
language it claims and sixty counts chosen to land on rule boundaries — the teens, each last-digit
band, the hundreds, and the round million the newer Romance `many` clause turns on. ICU4J is a
**test-only** dependency: it is the same CLDR data the platform ships, and putting a library that
size into an application to answer one question would be a strange trade — it also does not exist
for Kotlin/Native, which is the platform that needed the answer.

That comparison earned its place immediately. It found **European Portuguese**: `pt-PT` and `pt-BR`
differ in whether zero is singular, which the table had, but they *share* the round-million `many`
clause, which the table had given only to Brazilian. Two disagreements out of about eleven thousand
comparisons — exactly the kind of thing a hand-copied table gets wrong and no reviewer notices.

**An unknown language returns `null` rather than a guess**, and the caller reports the gap:
`SkewReport.untranslatedPlurals` now distinguishes "the payload had no words for the category the
host picked" from "the host had no rules for this language and used the English one, so the category
itself may be wrong". Those were the same line before, which read as a payload problem when it was a
host one.

## 3. Rationale & Research

### Foundation was measured, not assumed, and it cannot do this

Foundation *does* apply plural rules — it applies them while rendering a `.stringsdict` entry rather
than answering a question. So the obvious trick is to build a `.stringsdict` whose every category
maps to the literal name of that category, render it for a count, and read the category back out.

`FoundationPluralProbeTest` does exactly that, on the iOS 17.5 simulator: it writes a stringsdict at
run time into a one-language bundle, prefixes every value with the language so a reader can tell
"wrong rules" from "table never loaded", and runs two passes — one offering all six category names,
one withholding `zero`, `one` and `two`, which double as literal-count keys.

Every language answered identically:

```
PROBE pl offering=zero/one/two/few/many/other  0=pl-zero 1=pl-one 2=pl-other 3=pl-other 5=pl-other …
PROBE ar offering=zero/one/two/few/many/other  0=ar-zero 1=ar-one 2=ar-other 3=ar-other 5=ar-other …
PROBE pl offering=few/many/other               0=pl-other 1=pl-other 2=pl-other 3=pl-other …
```

The `pl-` and `ar-` markers prove each language's table really was the one loaded. Arabic two is
`other`, not `two`. Polish three is `other`, not `few`. In the second pass, where the literal-count
keys are withheld, **`few` and `many` are never selected for any language at any count**. A
`.stringsdict` matches the literal keys `zero`, `one` and `two` against the count and falls through
to `other`; it does not select a Unicode category. There is no public equivalent of
`PluralRules.select` on this platform.

**The honest limit of that run:** it used a bundle constructed at run time on a simulator. A
`.stringsdict` compiled into an application bundle by Xcode may behave differently. That does not
change the decision, because of the architectural objection below, which holds either way.

### Foundation would have been the wrong shape even if it worked

Any Foundation path needs the plural table **in the application bundle**. Dogwood's strings come
from the payload, over the air, and the set of languages a payload carries is not known when the
application is compiled — that decoupling is the entire reason localized strings are payload-carried
rather than host-resolved ([Layer 5](../../specs/layer-5-host.md)). Reaching plural support through
a bundled resource would put the categories back on the store-release cycle the architecture exists
to get them off.

### Why not keep reflecting into ICU

Android already does, and keeps doing it — it has the platform's own data, which is more current than
any copy. But `android.icu` does not exist on a desktop Java Virtual Machine and cannot exist on
Kotlin/Native, so it answers one platform of three.

### The cost

The table is a single `when` over language subtags with integer arithmetic in each branch: no
allocation, no I/O, no initialisation, and no dependency in any shipped artifact. Against the
alternative — a per-locale resource bundle in every application binary — it is not close.

## 4. Unstated Assumptions

- **CLDR plural rules change, and this copy will not.** They change rarely and the agreement test is
  the tripwire: when ICU4J is upgraded and a rule has moved, the test fails with the language and the
  count. What it cannot catch is a *new* language being added upstream, since the table only claims
  the languages it names.
- **The oracle is assumed to be the same data the devices use.** ICU4J and the platform ICU both
  come from CLDR, but not necessarily from the same release. A device on an older CLDR could
  disagree with both this table and the test — which is a divergence the test cannot see, and the
  reason `SkewReport` remains the place a real fleet would notice.
- **Only the language subtag is consulted**, plus the region for Portuguese. CLDR keys cardinal
  rules by language, so this holds today; a future rule split on region for another language would
  need the same treatment `pt` already gets.
- **The English fallback is still there** for a language the table does not name. It is now reported
  as a missing-rules gap rather than left to be discovered, but it is still an English answer for a
  language that may not want one.

## 5. Updated Documents

- [Layer 5: Host](../../specs/layer-5-host.md)
- `engine/dogwood-host/src/commonMain/kotlin/dev/dogwood/host/PluralRules.kt` (new)
- `engine/dogwood-host/src/commonMain/kotlin/dev/dogwood/host/Expressions.kt`
- `engine/dogwood-host/src/iosMain/kotlin/dev/dogwood/host/Format.ios.kt`
- `engine/dogwood-host/src/jvmAndroidMain/kotlin/dev/dogwood/host/Format.jvmAndroid.kt`
- `engine/dogwood-host/src/jvmTest/kotlin/dev/dogwood/host/PluralRulesAgreementTest.kt` (new)
- `engine/dogwood-host/src/iosTest/kotlin/dev/dogwood/host/FoundationPluralProbeTest.kt` (new)

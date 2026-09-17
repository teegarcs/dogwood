# ADR-074: A segment version has two authors, and a binding omits a default rather than quoting one

**Date:** 2026-09-16
**Status:** Accepted — group 2 of [`plans/close-the-backlog.md`](../../plans/close-the-backlog.md).

## 1. Context & Problem Statement

[ADR-072](ADR-072-the-compose-surface-is-generated-from-the-artifact-it-binds.md) generates a
dictionary segment from a library's own sources, and the coverage report counts what that rule
accepts. At Material 3 1.9.0 it accepted 79 of 186 composables. Thirty-nine more were *generable,
excluded* — the rule accepted them and something downstream did not — and **twenty-six of those
carried one reason**: a default expression named a symbol the library keeps `internal`, so the
binding could not copy it.

That number was the largest single blocker in the report, and the plan expected removing it to
return most of twenty-six components. Removing it did two things the plan did not expect, and the
second is the reason this record exists at all.

## 2. Decision

**A binding omits a host-default-only argument instead of quoting the library's default.**

A binding quotes a default because absence is the sentinel: the guest *might* not send a
parameter, and the host has to pass something. For a **host-default-only** parameter the guest can
never send it — it is not on the stub at all — so the binding can leave the argument off the call
and let Kotlin pass the library's own default. That is better in every way that matters: shorter,
incapable of drifting from the library, and it works when the default names something `internal`,
which a quote cannot.

One exception, and it is load-bearing: a parameter that another *emitted* default names. Material 3
writes `contentColor = contentColorFor(containerColor)`, and a binding that dropped `containerColor`
would not compile. So the kept set starts from the names every always-emitted default mentions and
closes over itself, because a kept default may name a third parameter.
`ClassifiedComposable.keptHostDefaults` is that set, and the internal-symbol check now reads only
`emittedDefaults`.

**A name counts as `internal` only when nothing public claims it too.** The parser recorded a name
as internal if any declaration with that name was internal. `MaterialTheme` is the public object
every Material 3 default reads its colours from, and `MaterialTheme.kt` also declares an
`internal fun MaterialTheme(` — an overload of the composable. Seven components were refused
because of a name collision with a function nobody was calling.

**A segment version carries two authors: the library's version and the generator's revision.**
`1090001` is Material 3 1.9.0 at generator revision 1. The first three components are the
library's, two digits each; the last two are the revision. Nobody types the revision: the generator
reads the previous one out of the lock and raises it when the bound set changes at an unchanged
library version, so it lands in the diff beside the components that caused it. A different library
version starts again at zero, because the first three components already distinguish it.

**Property, slot and event tags come from the lock, not from position.**
[ADR-073](ADR-073-a-generated-tier-derives-its-version-and-keeps-its-tags.md) made *component*
tags lock-derived for exactly this reason one level up. A property's tag was its position among
the properties that cross, so teaching the generator to cross one more type renumbered every
property after it. A name the lock knows keeps its number wherever the library moved it; a new name
takes the next one above every number in use.

## 3. Rationale & Research

**The plan's expectation was wrong, and the report is why we know.** Removing the internal-default
block returned **two** components, not twenty-six. The other twenty-four had a second, deeper
reason that the internal-default check was reporting over: they were deprecated in the library, or
they were the controlled-text-input family that ADR-019 replaced with a versioned `TextInput`. The
reasons a component is unbound are a chain, and reading the first link as the whole story
overstated what one fix could do. The coverage report's buckets now say so: `deprecated` went from
59 to 70 and `controlled text input` from 11 to 17 as those components fell through to their real
reason.

**The name collision, found by looking rather than reasoning.** Seven components remained, all
blocked on `MaterialTheme`. The obvious reading — Material 3 keeps its theme object internal — is
absurd on its face, which is what prompted a search of the fetched sources rather than another
rule. `MaterialTheme.kt:90` declares `internal fun MaterialTheme(`. Making the check conservative
in the other direction is safe: being wrong now costs a binding that does not compile, which the
build catches at once and `exclusions.txt` records, where being wrong before cost a component
silently absent from the vocabulary with a reason that was not true. `Scaffold`, `Surface` in four
overloads, `NavigationBar` and `DropdownMenu` are bound because of this one line.

**Why the version needed a second axis, stated as the failure it prevents.** The generator got
better at binding *the same library version*. Material 3 1.9.0's segment gained eight components
while the number stayed `10900`. A payload built after the change and a host shipped before it
would both have called themselves `10900`; the payload would have declared a version the host
claimed to implement, passed the pre-flight check ([ADR-061](../layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md)),
and then drawn placeholders for components the host had never heard of. That is precisely the
failure a declared version exists to prevent, arriving through the declaration itself.

Two axes were chosen over the alternatives for one reason each. A hand-typed revision reintroduces
the number ADR-073 removed. Hashing the surface into the version makes it unordered, and the
pre-flight check compares integers — "is this host behind?" has to be answerable with `<`.

**The mapping table grew by three types, in the order the report ranked their reasons**, and the
measurement is the argument for each:

| Type | Crosses as | What it unlocked |
|---|---|---|
| `BorderStroke` | width and a colour recipe | `border` settable on buttons, cards and chips, and it follows the host's palette rather than freezing the author's |
| `ClosedFloatingPointRange<Float>` | two numbers, as a property **and as a callback argument** | `RangeSlider`, whose `onValueChange` carries one — the first composite this protocol lets an event carry |
| a rounded shape with four corners | its own expression factory | per-corner shapes, with the single-radius form untouched for clients that predate it |

**Two candidate rows were considered and not taken**, each with its reason:

- **`GridCells` / `StaggeredGridCells`.** Binding them would let a payload *name* a grid's columns,
  and change no number in the report: every lazy container is unbound because its content lambda is
  invoked inside a frame, which is the bindability rule's actual boundary and not a mapping-table
  gap. The parameter is not what blocks them.
- **`ButtonColors` and its kin.** This is a subsystem, not a table row, and calling it one in the
  plan was the error. A colour bundle is constructed by a library factory (`ButtonDefaults.buttonColors(...)`)
  whose parameter names differ per component, so binding it means parsing non-composable factory
  signatures out of the library and emitting a call with only the roles a payload set — the shape
  of the holder work in [ADR-043](ADR-043-holders-are-declared-on-the-surface.md), not of a `SIMPLE_KINDS` entry.
  It is also worth less than it looks: the components that matter already expose `containerColor`
  and `contentColor` as separate settable parameters, which cross today. Recorded in
  `plans/close-the-backlog.md` as its own item rather than folded in here.

## 4. Unstated Assumptions

- **Assumes omitting an argument is always equivalent to passing its default.** True for Kotlin
  defaults, which is what these are. It would not be true for a parameter whose default is
  evaluated per call with a side effect; no library default in the parsed surface is.
- **Assumes a public declaration's name is never a *different* thing from an internal one it
  shadows.** If a library declared an internal type and an unrelated public function with the same
  name, and a default referred to the internal type, the binding would not compile — caught by the
  build, recorded in `exclusions.txt`.
- **Assumes ninety-nine generator revisions per library version is enough.** Refused rather than
  truncated when it is not.
- **Assumes the lock is the only record of published tags.** It is, and it is committed. A tier
  generated without its lock present starts numbering from one, which is why the lock is never
  deleted to "start clean".

## 5. Updated Documents

- [`plans/close-the-backlog.md`](../../plans/close-the-backlog.md) — group 2 is this decision's plan.
- [`docs/upgrading-compose.md`](../../docs/upgrading-compose.md) — the version's two axes, and what a
  revision bump means for a fleet.
- [`tools/generator-v2/coverage.md`](../../tools/generator-v2/coverage.md) — regenerated; the counts
  quoted here come from it.
- [`adrs/layer-5/ADR-072`](ADR-072-the-compose-surface-is-generated-from-the-artifact-it-binds.md) and
  [`ADR-073`](ADR-073-a-generated-tier-derives-its-version-and-keeps-its-tags.md) — D-C and the tag
  rule are amended by this record.
- [`adrs/README.md`](../README.md) — index entry.

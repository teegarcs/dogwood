# ADR-072: The Compose surface is generated from the artifact it binds

**Date:** 2026-09-15
**Status:** Accepted — M0 and M1 of [`plans/generator-v2.md`](../../plans/generator-v2.md); later
milestones amend this record with what they measured.

## 1. Context & Problem Statement

Every coverage figure this project has quoted — 81.1%, then 67.6% — was the ceiling of a generator
that did not exist. [ADR-003](ADR-003-opaque-handle-binding-surface.md) measured the surface,
[ADR-005](ADR-005-corrected-coverage-and-bespoke-subsystem-list.md) corrected the measurement, and
the roadmap deferred the generator that would realise it as "v2", with an estimate it called a
floor. Meanwhile the shipped vocabulary was five hand-written primitives, twenty-one design-system
components and, since [ADR-069](ADR-069-the-primitive-tier-is-the-lever.md), a primitive tier rich
enough to compose with. `developer-experience.md` §1 says so now, and says why: a payload composes
from a vocabulary, and the vocabulary is what the host was built knowing.

The owner's goal is the vocabulary being most of Compose. That is this decision.

## 2. Decision

**The generator reads the `commonMain` sources of the exact Compose Multiplatform artifacts the
host resolves, and emits a dictionary segment per library.** The decisions that make that work are
D-A through D-I in the plan, and the ones that are load-bearing are restated here so this record
stands alone.

**The source of truth is the `-sources.jar` of the resolved artifact.** Not a checkout of androidx
at a commit, not a metalava dump. The artifact is what the host compiles against, its sources jar is
published beside it, and a library upgrade is therefore a version bump followed by a regeneration
and whatever the lock refuses. Reading source rather than a signature dump was
[ADR-002](ADR-002-standalone-codegen-tool-not-ksp.md)'s choice, for defaults; this extends it to
the library itself.

**Generated tiers allocate segment identifiers downward from 255; products upward from 2.**
`androidx.material3` is 255, `androidx.foundation` 254, `androidx.foundation.layout` 253,
`androidx.ui` 252, and `LAST_PRODUCT_SEGMENT = 200` is declared and refused past, so the two
directions cannot meet. A tier's version is the library's version encoded as an integer (`1.9.0`
is `10900`), declared by a payload in its signed manifest like any segment and refused pre-flight
by a host behind it ([ADR-061](../layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md)).

**The host evaluates the library's own default.** The generated binding calls the real library
function, in host code, with the library on the classpath, so an optional parameter the guest did
not send is passed the default expression copied verbatim from the library source. The guest stub
defaults every optional parameter to `null` — absence is the sentinel, as everywhere in this
protocol. This is what collapses the "defaults-expression problem in its general form" that the
roadmap feared: the generator never evaluates a default, it quotes one.

**A type the mapping table cannot cross is a parameter the guest cannot set, not a component the
guest cannot call.** With a default, the parameter is omitted from the guest stub and the reference
says it is not settable from a payload; the component stays bound. Without a default, the component
is unbindable and the coverage report says why. The table starts small on purpose — primitives,
`Dp`, `TextUnit`, `Color`, `Shape`, `PaddingValues`, the existing arrangement, alignment and text
tokens, `Modifier`, slots, events — and grows by measured demand.

**Overloads are told apart by parameter names.** 106 names cover 171 Material 3 composables. The
dictionary entry is `Name` for the first declaration in a file and `Name~<hash of parameter names>`
after, so a library release that reorders declarations moves nothing and one that changes a
signature is caught by the lock as a retype.

**The tier is a separate module, registered explicitly.** `dogwood-material3` depends on
`dogwood-host`, not the reverse, so a host that does not want the bytes can leave it out — which
is what makes the page-weight measurement in M3 a measurement rather than a thought experiment.

**The primitive tier stays.** Segment 0 and the generated `androidx.foundation.layout` will both
offer a `Column`. Every payload in the field uses the first; the second has the library's exact
signature. Both are callable, a payload picks by import, and the manuals will point new code at the
generated one once it is proven on a device.

## 3. Rationale & Research

**Established against the resolved artifacts on 2026-09-15**, not against documentation:

- `compose.material3` under Compose Multiplatform 1.10.3 resolves to
  `org.jetbrains.compose.material3:material3:1.9.0` — Material 3 has been versioned independently
  since 1.10 — while `foundation`, `foundation-layout`, `ui`, `ui-text`, `ui-graphics` and `ui-unit`
  resolve at 1.10.3. Read off `~/.gradle/caches/modules-2`.
- Every one publishes `<artifact>-<version>-sources.jar` on Maven Central, organised by source set.
  Fetched with `curl` and listed: material3 carries 218 common Kotlin files.
- Public uppercase `@Composable` functions in `commonMain`: material3 171 (106 names), foundation
  46, foundation-layout 12, ui 12 — **241**, against ADR-005's 445 over ten androidx modules that
  included Material 2 and animation, which the host does not link.
- `Button`'s signature at 1.9.0: nine optional parameters, every default a public expression
  (`ButtonDefaults.shape`, `ButtonDefaults.buttonColors()`, `ButtonDefaults.ContentPadding`,
  `null`). That is the observation the "quote the default" decision rests on; M1's coverage report
  is where the observation becomes a count.

**Why not the Kotlin analysis API.** Full type resolution would need the library's dependency
closure on the generator's classpath and a K2 session per module. The host binding needs only that
names resolve *in the host*, which they do if the generated file carries the library file's own
imports; the guest stub needs only the mapping table. Resolution is a risk for a later milestone if
a default expression turns out to need it, and the plan names it as one.

**Why a separate module rather than generated into `dogwood-host`.** The design-system segment is
generated into `dogwood-host` and registered as a built-in; the owner decided
([ADR-066](ADR-066-the-pickers-cost-half-a-second.md)) that catalogue components cost every client
globally. A generated Material 3 tier references 171 composables and defeats dead-code elimination
for the whole library on the web, which is a different size of question, and the plan's M3 puts a
number on it before anyone decides. A separate module is what makes "without the tier" buildable.

## 4. Unstated Assumptions

- **Assumes a library's default expressions are public API.** Where one is not, the binding does not
  compile, the component goes into `exclusions.txt` with the compiler's reason, and the report
  counts it. Generate, compile, refuse — in that order, because a library is not a surface an
  author can edit.
- **Assumes `commonMain` is the surface every host shares.** Platform source sets add API a payload
  could not rely on everywhere; they are not read.
- **Assumes the mapping table's first cut is enough to render something worth having.** `Button`,
  `Switch`, `Checkbox`, `Card`, `Text`, progress indicators, dividers, badges and chips all pass on
  primitives, slots, events and the existing tokens. Colours, elevations and interaction sources
  are not settable on day one, by design.
- **Assumes an experimental annotation is opt-in noise, not a signal.** The bindings opt in
  file-wide; the reference marks each experimental component, because the library may change it.
- **Assumes 241 is the right denominator.** It is the pinned versions' common surface; a payload
  cannot call what the host does not link.

## 5. Updated Documents

- [`plans/generator-v2.md`](../../plans/generator-v2.md) — the plan; the consolidated backlog.
- [`plans/engineering-backlog.md`](../../plans/engineering-backlog.md) — `D1` points here.
- [`roadmap.md`](../../roadmap.md) — Phase 3's "generator v2" points here.
- [`adrs/README.md`](../README.md) — index entry.
- Amended by M2 and M3 with what they measured.

# ADR-073: A generated tier derives its version and keeps its tags

**Date:** 2026-09-16
**Status:** Accepted — section 3 of [`plans/material3-proof.md`](../../plans/material3-proof.md).

## 1. Context & Problem Statement

[ADR-072](ADR-072-the-compose-surface-is-generated-from-the-artifact-it-binds.md) says the
generator reads "the `commonMain` sources of the exact Compose Multiplatform artifacts the host
resolves". A review of the merged result found the implementation did not do that, and could not
express two things a library upgrade routinely does.

**The version was typed, three times.** `dogwood-codegen/build.gradle.kts` carried the coordinate
`org.jetbrains.compose.material3:material3:1.9.0`; the same file passed `--version 10900`; and
`v2/Main.kt` carried a `PINNED_VERSIONS` map with `1.9.0` in it again, for the coverage report's
header. Three numbers a person had to move together.

They also guarded against a fourth nobody could see. The host does not name a Material 3 version:
it writes `compose.material3`, and the Compose Multiplatform plugin maps that alias to a version of
its own choosing — 1.10.3 maps to 1.9.0, because Material 3 has been versioned independently since
1.10. A Compose Multiplatform patch release can move that mapping without changing a line of this
repository, and the generator would have gone on reading 1.9.0's sources for a host linking 1.9.1.
Nothing would have said so. The symptom would arrive later, as a payload declaring a dictionary
version no host has.

**Tags followed position.** `buildDictionary` allocated local tags 1, 2, 3 … in the order
components were parsed. That is right for a hand-written surface, whose author controls the order.
For a generated tier the list belongs to a library, and a release that drops one composable shifts
every tag after it — which the lock correctly refuses, so the tier could not be upgraded across a
removal at all. The lock also treated any removal as a violation, with no way to express the
outcome its own error message named: *its tag must stay retired*.

This was not hypothetical for long. Fixing an unrelated generator defect — Material 3's two
`Slider` overloads take the same guest-visible parameters in the opposite order, so both were
generated, both compiled, and every call that supplied only the required ones was an
overload-resolution ambiguity — removed one component from the tier, and the build could not
proceed.

## 2. Decision

**A generated tier derives its version from what the host resolves, and never from a literal.**
`fetchComposeSources` reads each module's version off `dogwood-host`'s `jvmCompileClasspath`,
fetches that sources jar, and writes `versions.json` beside the extracted sources. The generator
reads the tier's version from that file and encodes it: `1.9.0` is `10900`, `1.9.1` is `10901`. The
coverage report reads its header from the same file. The three literals are deleted.

The resolved version is a **task input**, not a value read while the build is configured, so a
version that moved while the coordinates did not invalidates the fetch. That is the whole point: a
stale extraction is the failure this indirection exists to prevent.

**A pre-release is refused, and so is a component of 100 or more.** `1.10.0-beta01` is republished
under its own name, and a payload declares a segment version as an integer a host compares — "the
beta from Tuesday" is not expressible. And since the encoding gives the minor and patch two digits
each, `1.100.0` and `2.0.0` would both be `20000`; two libraries sharing a segment version is the
one arithmetic failure this number cannot survive, so it is a build failure rather than a silent
truncation.

**`checkGeneratedTierVersions` refuses a committed lock whose version is not the one this checkout
resolves.** It runs in `check`, so tier S covers it, and it was watched to fail on a hand-edited
lock before it was believed.

**A generated tier takes its tags from its lock.** Names the lock knows keep their tags; names it
does not get new ones above everything taken or retired. Nothing renumbers, whatever the library
does to its own declaration order.

**A component that is gone has its tag retired, and that is a recorded event rather than a wall.**
The generator adds the tag to the dictionary's reserved list; `checkAgainstLock` permits a removal
**only** when the tag is retired in the dictionary being written, and reports it. Payloads in the
field that still send the tag get an inert placeholder and a skew report, which is the containment
rule already graded as `A2`.

**A version going backwards is refused unless somebody says it is intended.** For a generated tier
a lower version means the library the host resolves went backwards; payloads in the field may
declare the higher one, and this host would refuse them at launch. `--accept-downgrade`
(`-PdogwoodAcceptTierDowngrade=true`) is how that decision is expressed.

## 3. Rationale & Research

**Why derive rather than pin and check.** Pinning and checking keeps a number a person must move in
step with a number the plugin moves; the check tells you afterwards. Deriving removes the number.
The one argument for a pin is reproducibility, and the lock already answers it: the lock is the
record of what was generated, and a regeneration that disagrees is refused or reported.

**Measured on 2026-09-16, against this checkout.** With the literals deleted,
`fetchComposeSources` resolved `material3 1.9.0, foundation 1.10.3, foundation-layout 1.10.3,
ui 1.10.3` — the same versions that had been typed, which is the result that makes the change safe
to take. `checkGeneratedTierVersions` then passed, and failed as intended when the lock's version
was edited to `10901`:

> `androidx.material3.lock.json` says version 10901 (1.9.1) and this host resolves material3 1.9.0,
> which is version 10900. Regenerate the tier and commit the lock, the exclusions, the coverage
> report and the reference together.

**The retirement, measured.** Making the classifier's overload key order-insensitive dropped
`Slider~d234905b`. With lock-derived tags the diff was one component removed and one reservation
added; no tag moved. Material 3's bound count went from 80 to 79, and the coverage report says so.

**Why the `Slider` defect existed at all.** The overload key hashed parameter names *in declaration
order*, so two overloads a guest cannot tell apart were two components. The compiler accepted the
pair, because swapping two parameters of different types is a different JVM signature. No test
caught it: the tier's tests compose wire trees rather than Kotlin calls, and no sample had called
`Slider`. It was found by writing a screen that used it, which is the argument of
`plans/material3-proof.md` in one example.

## 4. Unstated Assumptions

- **Assumes `dogwood-host`'s JVM compile classpath is the authority on what the host links.** It is
  the module every client's host depends on, and the module that declares `compose.material3`. A
  host application that overrode the Material 3 version itself would be generating against its own
  resolution, which is the intended behaviour.
- **Assumes the sources jar is published at the root module coordinate.** It is for every Compose
  Multiplatform artifact today; the resolution falls back to the platform variant's name and fails
  loudly if neither is there.
- **Assumes two digits each for minor and patch is enough.** Refused rather than truncated when it
  is not, so the day it stops being true is a build failure with a sentence in it.
- **Assumes a retired tag is never wanted back.** If a library removes a component and restores it
  in a later release, it gets a new tag; the old one stays retired forever. That is the
  conservative direction, and the only one compatible with payloads in the field.

## 5. Updated Documents

- [`docs/upgrading-compose.md`](../../docs/upgrading-compose.md) — the procedure, the four lock
  outcomes and the compatibility matrix; written because of this decision.
- [`plans/material3-proof.md`](../../plans/material3-proof.md) — section 3 is this decision's plan.
- [`docs/checks.md`](../../docs/checks.md) — `checkGeneratedTierVersions`.
- [`adrs/layer-5/ADR-072`](ADR-072-the-compose-surface-is-generated-from-the-artifact-it-binds.md) —
  D-A and D-C are amended by this record.
- [`adrs/README.md`](../README.md) — index entry.

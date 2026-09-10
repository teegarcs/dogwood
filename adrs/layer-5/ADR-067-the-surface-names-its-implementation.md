# ADR-067: The surface names its implementation

**Date:** 2026-09-09
**Status:** Accepted

## 1. Context & Problem Statement

The generated host binding calls `${implPackage}.${name}Impl(…)` — a function the adopter writes,
by convention. For this repository's own components the convention is invisible: the `Impl` *is*
the component. For an adopter who already owns a design system it is ceremony: the `Impl` is a
function whose entire body is a call-through to their real composable, with the same parameter
names, repeated once per component, managed forever.

An adopter evaluating Dogwood for a mobile prototype asked the obvious question: can the wrapper go
away? Two facts make the answer yes, cheaply. The generated call already uses **named arguments**,
so the only thing the convention contributes is the *name* of the target. And the surface is
**parsed, never compiled**, so it can carry an annotation naming a function the parser has no need
to resolve — the host compiler resolves it later, which is the same enforcement the wrapper had.

## 2. Decision

**`@Implementation("fully.qualified.Name")` on a surface component points the generated binding at
that function directly. The convention remains the fallback, per component.**

```kotlin
@Composable
@Implementation("dev.umbra.design.UmbraChip")
fun UmbraChip(label: String, modifier: Modifier = Modifier) {}
```

generates

```kotlin
dev.umbra.design.UmbraChip(label = …, modifier = modifier)
```

with no `UmbraChipImpl` anywhere in any build.

Three properties, each deliberate:

- **Per component, not per segment.** A real surface mixes both: the wrapper earns its keep exactly
  where a wire type needs mapping before the real component can be called — a `String` that becomes
  a sealed type, a token name that becomes a `Color` — and is ceremony everywhere else.
- **Host-side only.** The target never reaches the dictionary, the lock, or the wire. Two clients
  may bind the same component to different implementations, and a payload cannot tell. Pinned by
  test: the dictionary encodings of a bound and an unbound component are identical.
- **The contract is relocated, not weakened.** The target must exist with the surface's parameter
  names and wire-compatible types, and the *host compiler* enforces it — an `@Implementation`
  pointing at nothing fails the build with an unresolved reference, exactly as a missing `Impl`
  does. The parser's only job is refusing what it cannot pass through honestly: a non-literal
  argument is rejected at parse, because a constant reference would arrive as its source text and
  the generator would emit a call to a name that exists nowhere.

## 3. Rationale & Research

**Verified end to end rather than argued.** Umbra — the standalone consuming product, a separate
Gradle build resolving from a repository — gained `UmbraChip`: a design-system composable with no
wrapper, bound directly. The generated binding calls `dev.umbra.design.UmbraChip(` (read from the
generated file, not inferred), and `tools/standalone-check/run.sh` now requires `UmbraChip#` in the
render transcript, so the direct path is proven to *render*, not merely compile:

```
UMBRA UmbraChip#5 UmbraChip
UMBRA CHECK PASS
```

Umbra's other two components deliberately keep their wrappers, so the sample shows the mixed form a
real surface takes.

**What this changes for an adopter with an existing design system**, which is the population that
asked: per component, the work drops from a surface declaration plus a managed wrapper to a surface
declaration alone — provided names and wire types line up. Where they do not, nothing changed, and
the wrapper is doing the work it always did: type mapping, not dispatch.

**Why a string literal rather than a class/function reference.** The surface is parsed by a
standalone parser with no compilation, no classpath, and no symbol resolution. A `::function`
reference or a constant would have to be resolved by machinery the parser does not have, and
half-resolving it — taking its source text — would generate calls to wrong names silently. The
literal is honest about what the parser can actually know, and the four-test suite pins the refusal
(`ImplementationTargetTest`).

## 4. Unstated Assumptions

- **That named-argument compatibility is enough of a contract.** It is the same contract wrappers
  had; what is lost is a place to put a breakpoint, which an adopter can recover by… writing a
  wrapper for that component.
- **That defaults on the real component are acceptable dead weight.** The generated call passes
  every surface parameter explicitly, so the target's own defaults are never used from this path.
  A component whose behaviour depends on *not* passing a parameter needs a wrapper.
- **That adopters will not point two segments' components at one function.** Nothing prevents it,
  and nothing needs to: the call sites are independent and the dictionary is untouched.

## 5. Updated Documents

- [`docs/getting-started.md`](../../docs/getting-started.md) §2 — the adopter path, with when the
  wrapper still earns its keep
- [`samples-standalone/umbra`](../../samples-standalone/umbra) — `UmbraChip`, the worked example
- [`tools/standalone-check/run.sh`](../../tools/standalone-check/run.sh) — requires the directly
  bound component in the render transcript
- [`dogwood-codegen`](../../engine/dogwood-codegen) — parser, model, emitter,
  `ImplementationTargetTest`

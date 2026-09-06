# ADR-042: Numeric Ranges Are Declared on the Surface

**Date:** 2026-09-06
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-035](ADR-035-hostile-property-values.md) established that a value the host recognises perfectly
and cannot use is a distinct and worse failure than a name it does not recognise. Compose enforces
some numeric ranges by throwing — `maxLines` below one, negative padding, a weight of zero — and the
throw lands **inside composition**. Because a payload is delivered over the air without a store
review, an off-by-one in one property is not a degraded screen but no screen, on every client that
receives it, at the same moment.

It shipped clamping readers and named its own limit:

> The durable fix is a range declared on the surface so the generator emits the clamp — that is not
> built, and it is the reason this ADR names the readers "the hand-written half".

The gap is precise. A clamp written by hand protects the properties somebody thought of. The
generated bindings are emitted from the surface, so a component gaining a numeric parameter with an
enforced range gets an ordinary reader and reintroduces the vector — silently, for that one
property, until a payload happens to send a bad value to every client at once.

## 2. Decision

**A numeric parameter declares its own range on the surface, and the generator emits the clamp.**

```kotlin
@Composable
fun Icon(
  name: String,
  @Range(min = 1.0) sizeDp: Int = 24,
  …
)
```

`@Range(min, max)` is read by `dogwood-codegen` into `ParsedParameter.range`, and the emitter
produces `node.intClamped(tag, default, min = 1, max = Int.MAX_VALUE, what = "Icon.sizeDp")` where
it would otherwise produce `node.int(tag, default)`. The clamp lands in `SkewReport.clampedValues`
with the value that arrived and the range it was forced into, exactly as the hand-written ones do.

Four decisions inside that are not obvious:

**Bounds are doubles on the surface and narrowed by the emitter**, because that is how an
annotation can carry both an `Int` and a `Float` bound with one declaration, and the literal is then
parsed once rather than twice.

**An unbounded maximum emits the type's own constant.** `@Range(min = 1.0)` reaches the emitter as
`Double.MAX_VALUE`; narrowing that by cast would produce `Int.MAX_VALUE` on the Java Virtual Machine
and something else elsewhere. Writing `Int.MAX_VALUE` says "no upper bound" in the generated code
rather than leaving a reader to work out whether `2147483647` was chosen or fallen into.

**Nullable parameters are not clamped**, and that is a decision rather than an oversight. Absence is
the "use host default" sentinel; a clamp cannot improve a value that is not there, and emitting one
would mean inventing a value the payload deliberately omitted.

**Positional arguments are read as well as named ones.** `@Range(2.0, 7.0)` means what it says, and
a parser that quietly ignored the short form would drop a clamp without a word — the exact silence
this record exists to remove.

## 3. Rationale & Research

**Why the surface rather than the binding.** The surface is already the single source of truth from
which the guest stubs, the host bindings and the versioned dictionary are all generated, precisely
so the two ends of the boundary cannot drift. A range is a fact about the parameter, so it belongs
where the parameter is declared. Putting it in the binding is what produced the gap ADR-035 recorded.

**Why the hand-written readers stay.** `Text.maxLines` lives in the hand-written layout tier, and
the modifier clamps guard arguments that arrive as elements of a chain rather than under a property
tag — there is nothing for a surface annotation to attach to. Both remain, and this ADR does not
pretend otherwise.

**Verified against the generated binding, not the hand-written one.** `GeneratedClampTest` drives a
real tree through a real composition using `Icon`, whose `sizeDp` carries `@Range(min = 1.0)`, and
asserts that zero and negative sizes are clamped and reported rather than thrown.

**The negative control runs the whole chain.** Removing `@Range` from the surface — one annotation,
not a line of generator or host code — turns two of those three tests red. That is the property
worth having: the protection is attached to the declaration, so the test fails when the declaration
changes, which is the only place somebody could remove it by accident.

The generator's own tests add a second control at the emitter: dropping the range from the reader
turns three of six red.

## 4. Unstated Assumptions

- **A declared range is assumed correct.** Nothing checks it against what Compose actually enforces,
  and nothing could — the constraint lives in a third-party implementation. A range invented to look
  tidy silently changes a payload's legitimate value, which is why the annotation's documentation
  says to declare only what the host genuinely cannot render.
- **The lock does not record ranges.** Widening or narrowing one changes what a payload renders
  without changing any tag, so the dictionary lock will not notice. That is a real hole and it is
  recorded rather than papered over: it is a host-side behaviour change and the wire is unaffected,
  so the lock's rules do not obviously extend to it. If a range ever changes in a way that matters,
  this is the sentence that should have stopped it.
- **Only `Int` and `Float` are ranged.** No other numeric type appears on the surface today. A
  `Long` or `Double` parameter would need a branch in the emitter, and would get an ordinary reader
  until it did — silently, which is the same failure mode one level up.

## 5. Updated Documents

- [ADR-035: Hostile Property Values](ADR-035-hostile-property-values.md) — its stated assumption is
  closed here.
- [Layer 5: Host](../../specs/layer-5-host.md)
- [Roadmap](../../roadmap.md) — removed from Phase 7's deferred list.
- `engine/surface/dev/dogwood/surface/DesignSystemSurface.kt`
- `engine/dogwood-codegen/src/main/kotlin/dev/dogwood/codegen/{Surface,Parser,Emitter}.kt`

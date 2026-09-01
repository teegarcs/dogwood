# ADR-021: Host-Resolved Values Are First-Class Types

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

The architecture has one rule for anything that depends on the device:

> **A value that depends on the environment crosses the wire as a *recipe*, not a result. The host
> resolves it at the moment it draws, against the environment in force. When that environment
> changes, resolution changes — with no wire traffic and no guest recomposition.**

That rule is [ADR-010](ADR-010-deferred-expression-grammar.md)'s and it was already load-bearing in
five places: colour tokens (dark mode's proof case), typography tokens, the icon dictionary,
formatting recipes, and animation — where the environment input is the host's clock.

The rule was not the problem. **The generated surface's type system could not express it.** Every
component parameter was a raw Kotlin primitive, so:

- `Price(price = Formats.currency(61_200, "USD"))` did not compile. The one component that exists
  to display money was the one place correctly formatted money could not go. The sample had
  abandoned `Price` and hand-assembled rows out of raw `Text`, losing the strike-through, the
  baseline alignment, and everything else the component provided.
- `Icon(tint = "primary")` compiled, and was worse: a `String` that *happened* to hold a token
  name. Host-resolved in fact, invisible to the compiler, uncheckable, and unanimatable.

Two latent generator defects were found while planning this and are fixed here: a host-resolved
surface type emitted code that **did not compile** (classified `VALUE`, wrapped in
`JsonPrimitive`), and the nullable form of the recipe type misclassified because the `?` was never
stripped — so that parameter kind had been dead code for nullable declarations since it was
written.

## 2. Decision

### 2.1 A `HOST_RESOLVED` parameter family, and the guest API drops the prefix

Three surface types, one parameter kind, one emitter rule, one host accessor shape:

| Type | Resolved against | Literal form |
|---|---|---|
| `TextValue` | locale, time zone, currency data | `TextValue("From $612")` |
| `Color` | the palette in force | `Color(0xFF0770E3)` — the deliberate opt-out |
| `Shape` | the host's shape vocabulary | — |

They are named for what they are, and where Compose has the concept they take **Compose's exact
name**. The guest classpath carries `compose.runtime` only — no `compose.ui`, no `foundation` — so
there is nothing to collide with, which was verified rather than assumed. `Modifier`'s companion is
now unnamed and *is* a `Modifier`, as Compose's is, so `modifier: Modifier = Modifier` and
`Modifier.padding(8)` are spelled identically on both sides of the boundary. The rule: **if Compose
has the concept, use Compose's name; if the concept is Dogwood's own, name it semantically; the
brand appears only at the integration boundary** — where `DogwoodSurface` and `DogwoodSession` live
among real Compose and the prefix is genuine disambiguation.

`DogwoodExpression` is gone from the public API entirely. It is an internal `Recipe`, and guest
code names a `Shape`, a `Color` or a `TextValue`.

### 2.2 No protocol change

One property tag carries either form: a literal as a JavaScript Object Notation primitive, a recipe
as an array. Unambiguous, and the positional codec already passes both through. This is the same
dual encoding the modifier channel uses for animated values.

### 2.3 The implementations never learn

`PriceImpl(price: String)` and `IconImpl(tint: Color?)` are unchanged in shape — the *generated
binding* resolves before calling. The part that requires taste never sees a recipe, which is the
same split [ADR-011](ADR-011-generator-emits-the-bridge.md) draws between the bridge and the widget.

### 2.4 The convenience overload, and why it is conditional

Widening `price: String` to `price: TextValue` would break every existing literal call site for no
benefit, since most text really is a literal. So the generator emits one extra overload per
component with every text parameter as a `String`, delegating.

**Emitted only when at least one text parameter is required**, and that condition is not a nicety —
it was found by the compiler. Two overloads whose parameters all have defaults are *both*
applicable to a call that omits them, and Kotlin rejects it as ambiguous. `StarRating(rating = x)`
stopped compiling. A required text parameter is what tells the two apart: supply it as a `String`
and only the convenience overload matches; supply a `TextValue`, and only the primary one does. A
component whose text is entirely optional gets no overload and is called with
`TextValue("…")` — verbose, rare, and unambiguous.

### 2.5 The lock now tracks parameter types

Widening a type moves no tag and adds no component, so **every existing lock check was silent about
it** — while a client built before the change reads a recipe with a primitive reader and quietly
renders its default. Every price on an older client would have gone blank, with nothing failing.

The dictionary now records each property's declared type, and a retype without a version bump fails
the build with the same discipline as an addition. This was found by making the change and noticing
the lock said nothing.

## 3. Rationale & Research

**Why not a formatting service.** Formatting is needed *during composition*, once per value: six
prices on a screen would be six suspending crossings before anything could be drawn, and a guest
rendering placeholders while it waited would be worse than one that could not format at all. A
recipe rides the property that was already crossing.

**Why money crosses as minor units and an ISO 4217 code.** The number of decimal places is a
property of the currency and the host is the side that knows it — `USD` has two, `JPY` has none,
`KWD` has three. Confirmed on device: the same wire bytes render `$612.00` and `¥61,200`.

**Why `Shape` and `Color` left the parser's asset-gated rejection list.** That rejection was
correct before the recipe grammar existed and has been wrong since ADR-010. Leaving it would have
kept a product's own components from ever accepting a themed colour.

### Verified

Fifteen tests added. Guest: a recipe reaches a generated component's text parameter; a literal
still crosses as a plain string through the same tag; the convenience overload keeps literal call
sites working; a literal and a recipe mix on one component; unset optionals still send nothing; a
colour crosses as a recipe rather than a token name; an ARGB literal is still a recipe and says so.
Host, through the real generated bindings: `Price` renders `$612.00` from `[6, 61200, "USD"]`;
`JPY` renders `¥61,200` from identical bytes; a literal renders through the same property; a colour
token **re-resolves on a theme change**; absence stays the host-default sentinel. Codegen: the
declared type is recorded, a retype without a version bump fails, with one passes, and an unchanged
surface still reports unchanged.

On the emulator, the sample's destination cards use `Price` again — strike-through `$740.00`,
`$612.00`, `return` — and under a `ja-JP` app locale the same cards render with the payload's
Japanese suffix (`往復`) beside host-formatted money.

## 4. Unstated Assumptions

- **An explicit `null` for an optional text parameter is ambiguous** between the two overloads.
  Omitting it — what a caller means, since `null` is the default — is not. Inherent to overloading,
  not to this emitter.
- **`Shape` is plumbed but unused by any surface parameter.** The accessor and the parser entry
  exist; nothing declares one yet.
- **Animated colour is not yet expressible** through a component parameter, but the type is now the
  right shape for it: `Color.animate(spec)` returning a `Color` would make every `Color` parameter
  animation-eligible with no signature change. That is the next record.
- **Skew degrades quietly.** A recipe arriving at a client built before version 5 is read by a
  primitive reader and renders the declared default. A guest that must not degrade branches on
  `segmentVersions["dogwood.designsystem"] >= 5`. Documented, not enforced — enforcing it would
  mean the host refusing to render a screen it could mostly draw.
- **`Formats.*` returns `TextValue`, so it cannot be used where a `Shape` or `Color` is expected**,
  which is correct and worth saying: the family shares a rule, not a type.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — "Named Resources" becomes the
  host-resolved-values rule, with the type table and the lock's new obligation.
- [`specs/layer-1-authoring.md`](../../specs/layer-1-authoring.md) — the guest API's names.
- [`roadmap.md`](../../roadmap.md) — the resources row's remaining item is closed.
- [`plans/text-and-animation.md`](../../plans/text-and-animation.md) — T0 and T1 marked done.

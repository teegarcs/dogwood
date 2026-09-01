# ADR-010: The Deferred-Expression Grammar

**Date:** 2026-08-31
**Status:** Accepted for the modifier-argument case; the general case is scoped but unbuilt.

*Written jointly with [ADR-009](ADR-009-modifier-subsystem.md).*

## 1. Context & Problem Statement

Some parameters are not values. `clip(RoundedCornerShape(8.dp))` does not pass a number, it passes
an object. `background(brush)`, `TextStyle(...)`, and every `@Composable` default expression are
the same shape of problem. The guest cannot construct any of them: `Shape`, `Brush` and `Color`
are host types, Compose's implementations are `internal`, and none has a serializable form.

[ADR-005](ADR-005-corrected-coverage-and-bespoke-subsystem-list.md) measured how much of the
surface this touches: **76.4% of the generable tier also requires the deferred-expression
protocol**. It is not an edge case; it is most of the work of generation.

The immediate forcing function is smaller. Phase 2's modifier set cannot include `clip` or
`background` without it, and [roadmap.md](../../roadmap.md) requires the grammar be settled with
the modifier tag space so Phase 3 consumes a settled pair.

## 2. Decision

**2.1 An expression is a recipe, not a value.** It crosses as a positional array —
`[factory, arg, arg, …]` — matching the wire form of
[ADR-007](../layer-4/ADR-007-v1-wire-format-positional-json.md). The factory identifier names a
host-side constructor; the arguments are values or nested expressions.

**2.2 The factory space is closed and host-owned.** Registered factories today:

| Factory | Builds | Arguments |
| ---: | --- | --- |
| 1 | rounded-corner shape | radius in density-independent pixels |
| 2 | circle shape | — |
| 3 | colour, literal | alpha-red-green-blue integer |
| 4 | colour, **named token** | token name |

**2.3 Named tokens are the point, not literals.** Factory 4 resolves a name against the host's
design system. A guest that says `Colors.token("primary")` gets the host's primary colour
*in context* — including dark mode, which a literal cannot follow. This is the grammar earning
its keep rather than merely working around serialization: the guest names an intent and the host
resolves it. A guest that hard-codes `argb(0xFF0770E3)` has silently opted out of theming, which
is why literals exist but are documented as the lesser option.

**2.4 Evaluation is memoized and bounded.** Results are cached per experience, keyed on the
recipe. A shape rebuilt per node per frame is allocation the frame budget cannot afford; an
unbounded cache keyed on guest-supplied data is a guest-controlled memory leak. The cache has a
hard cap and clears wholesale on reaching it — this is a memo, not a working set.

**2.5 An unknown factory degrades; it does not throw.** It renders a documented fallback and is
recorded as telemetry, exactly as an unknown widget tag becomes a placeholder. A guest built
against a newer dictionary must not be able to crash a host.

**2.6 The general case is explicitly out of scope here.** This ADR settles expressions *as
modifier arguments*. It does **not** settle:

- `@Composable` default expressions, which need the "use host default" sentinel and are the hard
  half of Phase 3;
- expressions whose arguments are themselves live host state;
- lambdas, which are not expressions at all and are governed by the bindability rule.

Those are Phase 3 problems, and pretending this grammar already covers them would understate
Phase 3 — which [roadmap.md](../../roadmap.md) already warns is its least certain estimate.

## 3. Rationale & Research

The alternative designs were considered and rejected:

- **Send the constructed value.** Impossible; there is nothing to send. This is the premise.
- **Enumerate every shape and colour as a widget property token.** Works for a fixed catalogue and
  collapses the moment a parameter takes a nested value, which is most of them.
- **Ship a small expression language the guest evaluates.** Puts host-object construction in the
  guest, which cannot hold host objects. It is the same impossibility one level down.

A recipe evaluated host-side is the only shape that composes: an argument can itself be a recipe,
so `clip(RoundedCornerShape(...))` and a future `background(Brush.linearGradient(colors))` are the
same mechanism at different depths.

The evaluator is tested in both directions that matter for a boundary that carries guest-supplied
data: identical recipes return the identical instance rather than reallocating, the cache stays
bounded under three thousand distinct recipes, and unknown factories and unknown tokens fall back
and are reported rather than thrown.

**A bug this decision caught on its first run.** The guest encoder handled primitives and had a
catch-all that stringified anything structured. Before expressions existed nothing was structured,
so the branch was unreachable and correct-looking. The first expression crossed as the JSON
*string* `"[1,12]"` rather than the array `[1,12]` — output that still parsed, still validated,
and was entirely wrong. The encoder now handles arrays and objects natively.

## 4. Unstated Assumptions

- **Assumes recipes are pure.** A factory is assumed to depend only on its arguments and the host
  theme. A factory reading mutable host state would make the memo wrong, and nothing enforces it.
- **Assumes the theme is stable within a composition.** Cached colours resolved from tokens are
  not invalidated when the theme changes; a dark-mode switch mid-composition would serve stale
  colours. The configuration flow exists to signal that, and the cache does not yet listen.
- **Assumes factory identifiers are versioned like tags.** They are numbers in a closed set, and
  nothing yet ties them to a dictionary version the way widget tags are tied.
- **Assumes a bounded clear-all cache is acceptable.** It is, at tens of recipes. A screen with
  hundreds of distinct gradients would thrash it, and no measurement covers that.

## 5. Updated Documents

- [adrs/layer-5/ADR-009-modifier-subsystem.md](ADR-009-modifier-subsystem.md) — the paired decision
- [specs/layer-5-host.md](../../specs/layer-5-host.md) — the deferred-expression protocol exists
  for modifier arguments
- [roadmap.md](../../roadmap.md) — Phase 2's ADR pair is written; Phase 3 inherits the general case
- [`engine/dogwood-compose/.../Expressions.kt`](../../engine/dogwood-compose/src/jsMain/kotlin/dev/dogwood/compose/Expressions.kt),
  [`engine/dogwood-host/.../Expressions.kt`](../../engine/dogwood-host/src/commonMain/kotlin/dev/dogwood/host/Expressions.kt)

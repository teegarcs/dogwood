# ADR-009: The `Modifier` Subsystem — Tag Space, Ordering, and Scope as a Build Error

**Date:** 2026-08-31
**Status:** Accepted

*Written jointly with [ADR-010](ADR-010-deferred-expression-grammar.md), because modifier
arguments are themselves deferred expressions and neither decision is complete without the other.
[roadmap.md](../../roadmap.md) Phase 2 requires the pair, and Phase 3's generator consumes it.*

## 1. Context & Problem Statement

Compose's `Modifier.Element` implementations are `internal`. A guest cannot construct one, cannot
name one, and cannot serialize one. Dogwood therefore defines its own tagged, serializable
modifier type, and the host reconstructs a real Compose modifier from it.

This is called the single highest-leverage deliverable in the project because 62.2% of the
measured widget surface depends on it and on nothing else bespoke
([ADR-005](ADR-005-corrected-coverage-and-bespoke-subsystem-list.md)). Three things had to be
settled: the tag space, the ordering guarantee, and what happens when a scoped modifier is used
outside its scope.

The third was the live defect. `weight` is a member of `RowScope` and `ColumnScope` in Compose,
not a free function, and that is not an API-style accident — a weight outside a row or column has
no meaning. The Phase 1 implementation exposed `weight` as a free function on the guest modifier
and the host silently ignored it when no scope was present. A silently ignored layout instruction
is the worst available outcome: the screen is wrong and nothing says so.

## 2. Decision

**2.1 Tag space.** `ModifierTag` is segment-encoded exactly as `WidgetTag` is
([ADR-004](../layer-4/ADR-004-change-event-protocol-v0.md) §2.1): segment in the top eight bits,
local tag in the low twenty-four. Segment 0 holds the layout modifiers below. Numbering is
additive: a new modifier appends, so a client one dictionary version behind still reconstructs
every chain element it knows and ignores the rest.

| Local tag | Modifier | Argument |
| ---: | --- | --- |
| 1 | `padding` | density-independent pixels |
| 2 | `fillMaxWidth` | fraction |
| 3 | `weight` | float — **row and column scope only** |
| 4 | `size` | density-independent pixels |
| 5 | `alpha` | float |
| 6 | `width` | density-independent pixels |
| 7 | `height` | density-independent pixels |
| 8 | `align` | alignment ordinal — **scope only** |
| 9 | `clip` | **deferred expression** (a shape) |
| 10 | `background` | **deferred expression** (a colour) |

**2.2 A chain is a sequence, and order is a guarantee.** `ModifierSet` carries the whole chain in
order and the host replays it in order. `padding(8).size(48)` and `size(48).padding(8)` are
different layouts; treating a chain as a set would be a correctness bug that shows up as a
mysterious visual difference. Whole-chain replacement remains the v0 behaviour; per-element
diffing is a byte-count optimisation that [ADR-007](../layer-4/ADR-007-v1-wire-format-positional-json.md)
found is *not* the lever it looked like.

**2.3 `then()` semantics.** `DogwoodModifier` is immutable and `then` returns a new chain, so a
modifier value can be hoisted, shared and compared. Equality is structural over the element list,
which is what lets Compose's `Updater.set` skip re-recording an unchanged chain — the reason a
static modifier costs nothing after the first composition.

**2.4 Out-of-scope use is a build error, achieved by making it unwritable.** `weight` and `align`
are members of `DogwoodRowScope`, `DogwoodColumnScope` and `DogwoodBoxScope`, and every container
supplies its scope as the receiver of its content lambda. Writing `weight` outside a row or column
does not compile.

This is deliberately stronger than the roadmap's requirement. The roadmap asks for "a build error
rather than a silent drop"; a diagnostic would satisfy that. Scope receivers make the mistake
**unwritable**, which needs no diagnostic, no generator support, and no lint rule — and it is the
mechanism Compose itself uses, so the guest API reads exactly like the API it mirrors.

The host retains its no-op branch for a scoped modifier arriving without a scope. That is now
unreachable from a well-formed guest and is kept because a host must tolerate a guest built
against a different dictionary rather than crash on one.

## 3. Rationale & Research

That `Modifier.Element` implementations are `internal` is the premise the whole subsystem rests
on and is stated in [Layer 5](../../specs/layer-5-host.md). That `weight` is scope-scoped is
visible in Compose's own signatures: `RowScope.weight` and `ColumnScope.weight` are interface
members, which is why [Layer 5](../../specs/layer-5-host.md) warns that generated dispatch for a
children slot must be emitted *inside* the parent's scope.

The [Backpack audit](ADR-008-design-system-audit-backpack.md) supports the tag list from the other
direction: of the eleven components audited, the modifiers their call sites actually need are
padding, sizing, weight and alignment — the value-class set — plus `clip` and `background`, which
are exactly the two that forced the expression grammar.

**What is asserted, and what is not.** Phase 2's gate asks for output "pixel-identical to the same
chain written statically". Pixels are **not** asserted. The tests assert the protocol half: an
arbitrary chain crosses in order with its arguments intact, two chains differing only in order
cross differently, a scoped modifier crosses with its scope intact, and an expression argument
crosses as a recipe. Screenshot testing does not exist in this project, and claiming pixel
identity without it would be a claim nobody checked.

## 4. Unstated Assumptions

- **Assumes the value-class set is the useful set.** It covers the audit's call sites and 62.2%
  of the measured surface, but `offset`, `border`, `rotate` and the rest are unbuilt, and each
  will need to be classified as value-argument or expression-argument.
- **Assumes structural equality on chains is cheap enough.** Chains are two to four elements, so
  comparison is trivial today. A chain of thirty would make `Updater.set` the expensive part.
- **Assumes scope receivers survive generation.** These are hand-written; Phase 3 must emit the
  same shape, and a generator that emitted free functions would silently reintroduce the defect
  this decision removes.
- **Assumes alignment ordinals are stable.** They cross as the guest enumeration's ordinal, so
  reordering that enumeration is a wire-breaking change. It should be treated like a tag.

## 5. Updated Documents

- [specs/layer-5-host.md](../../specs/layer-5-host.md) — the `Modifier` subsystem is built
- [roadmap.md](../../roadmap.md) — Phase 2 steps 1, 2, 3 and 4
- [adrs/layer-5/ADR-010-deferred-expression-grammar.md](ADR-010-deferred-expression-grammar.md) —
  the paired decision
- [`engine/dogwood-compose/.../Stubs.kt`](../../engine/dogwood-compose/src/jsMain/kotlin/dev/dogwood/compose/Stubs.kt),
  [`engine/dogwood-host/.../Modifiers.kt`](../../engine/dogwood-host/src/commonMain/kotlin/dev/dogwood/host/Modifiers.kt)

# ADR-011: The Generator Emits the Bridge, Not the Widget; Tags Are Locked

**Date:** 2026-09-01
**Status:** Accepted for generator v1 (registered modules). The Material tier is v2 and is not
covered here.

## 1. Context & Problem Statement

Dogwood's thesis is that the component bridge is required but does not have to be written by hand.
Phase 3 had to turn that into a tool, and two questions had to be answered before any code was
generated.

**What exactly gets generated?** A binding is two things wearing one name: the *dispatch* — unpack
a node's properties, slots and events, and call something — and the *implementation* — decide what
a badge looks like. Generating both is impossible; a generator cannot invent visual design.
Generating neither is the treadmill.

**What stops tags from moving?** Every guarantee in the protocol rests on tags being permanent. A
client one dictionary version behind resolves tags numerically, so a renumbered tag does not fail
to render — it renders **the wrong widget**, silently, which is the worst failure this
architecture can produce. Declaration order gives that property only while nobody reorders the
source, and "nobody will reorder the source" is not a guarantee.

## 2. Decision

**2.1 The generator emits the bridge. A human writes the implementation.**

From one parsed surface the generator emits guest stubs, the host dispatch layer, and the
versioned dictionary. The dispatch calls an implementation named by convention — `Badge` in the
surface calls `BadgeImpl` in host code — and the implementation is an ordinary Compose composable
taking ordinary parameters, knowing nothing of tags, nodes, batches or events.

This is the line the thesis actually needs. What grows without bound as a design system grows is
the dispatch: every component, every parameter, every event, on both sides of the boundary. That
is now generated. What requires taste does not grow the same way and is not generated. Redwood
draws the same line, and it is what "the registry stops being a treadmill; it does not stop
existing" means in practice.

**2.2 The surface is the source of truth, and it is not compiled.** `surface/` holds ordinary
Kotlin `@Composable` declarations with empty bodies. Nothing compiles them; the generator reads
them. Generated output goes to a build directory and is **not committed**, because generated code
in version control is a copy that drifts.

**2.3 Tags are locked, and the build enforces it.** Every generation compares against a committed
lock file. Additions update the lock; renumberings and removals fail the build with the specific
component and tag named. A removed component's tag stays retired rather than being reused by the
next addition.

This is the Layer 1 build-time dictionary check, and making it mechanical rather than a review
convention is the point: reordering two declarations in a source file is an ordinary, innocent
edit that a reviewer has no reason to flag.

**2.4 What v1 does not generate**, stated so the gap is not mistaken for completeness:

- **Host bindings for lazy containers.** A lazy list's children must be composed *inside* the
  host's `items` block, which is a slot shape the generator does not model. Two of eleven
  components in the registered segment remain hand-written for this reason, and it is the same
  boundary the [Backpack audit](ADR-008-design-system-audit-backpack.md) found: laziness is not
  absorbable by registration.
- **Scope receivers on generated slots.** A generated `Card` supplies a plain content lambda, not
  a `ColumnScope`. This loses a capability rather than reintroducing a hazard — scoped modifiers
  are still unwritable outside a scope — but it is the risk [ADR-009](ADR-009-modifier-subsystem.md)
  named, and it is real.
- **Guest-side value-type stand-ins** (`Dp`, `Color`, `TextStyle`). The registered surface uses
  primitives deliberately, so v1 does not need them. Generator v2's Material tier will, because
  those types appear throughout its signatures and Google publishes no Kotlin/JavaScript artifact
  for them.
- **Layer 2 per-dictionary-version builds.**

## 3. Rationale & Research

The generator parses Kotlin *source* with the compiler's own frontend rather than a metalava dump,
for the reason [ADR-002](ADR-002-standalone-codegen-tool-not-ksp.md) gives: dumps carry no default
expressions and 73.9% of parameters are optional. A generator that cannot see a default cannot
decide whether the host must resolve it, and absence-as-sentinel depends entirely on that decision.

**The gate is met, and it was met by substitution rather than by assertion.** Nine of the eleven
components in the registered segment are now bound by generated code with no hand-written dispatch
on either side. The forty existing tests pass unchanged, and the sample screen renders identically
to the hand-written version — which is what "reproduce the Phase 1 and 2 behaviour exactly" asks
for. A Compose version bump is not reproducible in a test, but the property that matters under one
is, and is tested: when the surface gains a component and an optional parameter, every existing
tag keeps its number.

**Two bugs the substitution found, both of which had been latent.** The generated bindings sent an
explicit JSON null for an unset optional parameter, and the host's nullable reader treated
`JsonNull` — whose `content` is the four-character string `"null"` — as a value. The result was
the word "null" rendered in every price row. Neither the hand-written path nor any test had
exercised an absent optional string end to end; the generator did it nine times on its first run.
Absence is now genuinely absence on the wire, per [ADR-004](../layer-4/ADR-004-change-event-protocol-v0.md).

## 4. Unstated Assumptions

- **Assumes convention-named implementations are enough.** `Badge` → `BadgeImpl` is resolved by
  string concatenation, so a missing implementation is a compile error in generated code, which
  reads confusingly. An interface the generator emits and the implementation implements would
  diagnose better.
- **Assumes declaration-order tagging survives contact with more than one author.** The lock
  enforces it after the fact; nothing prevents the edit, it only fails the build.
- **Assumes the parse is enough.** This reads declarations, not resolved types. A type alias, a
  star import, or a type whose name is ambiguous would be misclassified, and the classifier would
  do it silently. That is acceptable for first-party curated sources and is **not** acceptable for
  the full Compose surface, which is generator v2's problem and the reason the roadmap calls its
  estimate a floor.
- **Assumes the lock file is reviewed.** It is committed, so a renumbering shows up as a diff —
  but only if someone reads it.

## 5. Updated Documents

- [roadmap.md](../../roadmap.md) — Phase 3 steps 1, 2, 4 and 5, and the gate
- [specs/layer-5-host.md](../../specs/layer-5-host.md) — `dogwood-codegen` exists and what it emits
- [engine/README.md](../../engine/README.md) — the surface, the generated artifacts, the lock

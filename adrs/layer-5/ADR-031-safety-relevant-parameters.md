# ADR-031: Safety-Relevant Parameters, and the One Kind of Skew That Must Not Degrade

**Date:** 2026-09-02
**Status:** Accepted

## 1. Context & Problem Statement

Section 6 item 3 of the [technical specification](../../high-level-tech-spec-final.md) has said this
since the first draft, in the imperative:

> Parameters whose absence changes safety or affordance — `enabled`, `checked`, `readOnly`,
> `selected` — **must** be marked in the dictionary so that a widget with an unknown one is replaced
> by a declared fallback rather than rendered wrong.

It was never implemented. `safetyRelevant` appeared in the specification as a required dictionary
field and existed nowhere in the code — not in the parser, the dictionary, the lock, or the host.
Worse, the generated binding did the *opposite* of what the rule asks:

```kotlin
enabled = node.boolean(2, true),
```

An absent affordance defaulted to **enabled**. A payload trying to withhold an action, talking to a
client that could not read the instruction, got a working button.

This was found while auditing coverage for the Phase 5 hardening drills, not by anything failing.

## 2. Decision

**A widget that owns an affordance is withheld rather than drawn when it arrives carrying a property
this client cannot read.**

1. **The marking is on the surface, read not guessed.** `@Affordance` annotates the parameter in
   `engine/surface/DesignSystemSurface.kt`. A rule that inferred from the name `enabled` would
   silently miss `interactive`, `locked` or `isEditable`, and the cost of missing one is a control
   that lies about what it will do.
2. **The dictionary records it**, as section 6 requires, in a `safetyRelevant` set per component —
   so the side that compiles payloads can see the marking, not only the side that renders them.
3. **The decision is made per widget, not per property.** This follows from what a client can
   actually observe, and is the crux of the design; see below.
4. **The fallback is the inert placeholder an unknown widget tag already produces**, drawn with the
   guest's own modifier so the gap is the size the guest asked for.
5. **Changing the marking is a compatibility event.** The lock fails the build if a parameter is
   marked or unmarked without raising the segment version, which took the design system to
   version 7.

## 3. Rationale & Research

### Why the decision cannot be per property

The obvious design is to mark individual properties and have the host treat a missing safety-relevant
one as a fallback trigger. It does not work, because it inverts the direction the skew actually runs.

The dangerous case is a **newer payload on an older client**. The payload sends property tag 7 on a
button; this client's dictionary stops at 6; the binding never reads tag 7 and draws a normal button.
The client cannot ask "was tag 7 the one that disables this?" — the tag comes from a dictionary it
has never seen. There is no per-property question available to it.

What the client *can* observe is that **a property arrived that it cannot interpret**. That is a
whole-node fact, so the policy has to be a whole-node policy: a widget whose contract includes an
affordance refuses to draw when anything on it is unreadable, because one of those unreadable things
might be the affordance.

The reverse case — an older payload on a newer client — is harmless and deliberately left alone. The
old payload never had the concept, so `enabled = true` is the correct rendering rather than a guess.

### Why it is not applied to every widget

Because the cost is asymmetric, and applying it everywhere would make the cure worse than the
disease. Every other kind of skew in this system degrades appearance: an unknown text style becomes
body text, an unknown icon becomes the fallback glyph, an unknown colour token becomes unspecified.
Withholding a whole widget over cosmetic skew would turn a slightly wrong screen into a screen with
holes in it.

`Badge` is the worked example, and it carries `selected` — one of the parameter names section 6
lists. It is **deliberately not** marked, because a badge is read and not operated: nothing the user
does depends on it, so getting it wrong costs appearance. `Chip` carries the same parameter name
bound to a handler, and *is* marked. **The rule is what the parameter governs, not what it is
called**, and the surface says so at the declaration.

Marked today: `PrimaryButton.enabled`, `Chip.selected`, `TextInput.enabled`.

### The fallback draws

`withholdUnsafe` composes `Box(modifier)` rather than returning without composing. Returning early
would let the node vanish and everything after it move up, turning a contained safety problem into a
visibly broken screen. Keeping the guest's modifier means the gap occupies the space the guest asked
for — the same reasoning that makes an unknown widget tag a placeholder rather than a dropped node.

### Verification

`AffordanceTest`, five tests, each of which fails without the guard where it should:

- a button with only known properties renders — the control, without which the rest proves nothing;
- a button carrying an unreadable property does not draw, and lands in `SkewReport.withheldWidgets`;
- **tapping the exact pixel the button occupied sends no event**, with the readable button's own
  measured coordinates and a negative control asserting that same tap does fire when the button is
  drawn;
- a `Badge` with the same unreadable property still renders;
- the published dictionary records the markings, and records that `Badge` has none.

The tap test is the one that matters and the one that was initially wrong: its first version clicked
the surface's own semantics node, which dispatches to nothing, and passed just as happily with the
guard removed. Clicking a measured *position* is what makes it real.

## 4. Unstated Assumptions

- **A client that knows a property tag can read it correctly.** This handles unknown tags, not
  misinterpreted known ones; the retype check added in [ADR-021](ADR-021-host-resolved-values.md)
  covers the other half.
- **Guests send affordance properties explicitly.** Compose's `Updater.set` runs on first
  composition, so a created node carries its affordance even at the default. If that ever changes,
  absence would become ambiguous.
- **Withholding is the right failure.** For a payment button it plainly is. A product that would
  rather show a disabled control than nothing would need a per-widget declared fallback, which this
  design leaves room for and does not build.

## 5. Consequence: a hand-written version number that had already drifted

`DogwoodDictionary.segmentVersions` carried `"dogwood.designsystem" to 6` as a literal, typed by
hand, next to a generated dictionary that this change moved to 7. The client would have advertised
one version to guests while binding another — precisely the confusion the version exists to prevent,
in the one number a guest uses to decide what it may safely use.

The version is now emitted from the dictionary and read from there. It is worth noting how it was
found: not by a test, but by changing the number and noticing that something else claimed to own it.

## 6. Updated Documents

- [Layer 5: The Native Host & Generated Binding Layer](../../specs/layer-5-host.md) — the dictionary
  gains `safetyRelevant`, and the skew section gains the withholding rule.
- [Technical specification](../../high-level-tech-spec-final.md) — section 6 item 3 now describes
  what was built, including why the decision is per widget.

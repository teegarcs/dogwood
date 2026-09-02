# ADR-025: Where an Environment-Dependent Decision Lives

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

Review of [ADR-021](ADR-021-host-resolved-values.md) raised the right objection: a colour token is
a **contract**. `Color.token("primary")` only works if "primary" means something on the client, and
every token added widens a surface both sides must keep in step. That is acceptable — wanted, even
— for a design system, which is already a versioned, deliberately-managed vocabulary
([ADR-006](ADR-006-guest-composed-vs-host-registered-and-multi-design-system.md) gives each one its
own dictionary segment and version). It is the wrong tax for everything else: a one-off tint, a
promo background, a chart palette that belongs to one screen and no client release.

The question is what the non-design-system path is. Three candidates were considered.

## 2. Decision

**Two paths, chosen by one question: *who owns what this value means?* The third candidate —
shipping logic for the host to evaluate — is rejected.**

### 2.1 The design system owns it → a token

Brand colours, the type ramp, spacing, icons. The contract is the point: when the design system
updates, every payload's "primary" updates with it, and a theme flip costs **zero traffic**
because the host re-resolves the name itself. The contract is bounded the way the rest of the
dictionary is — per segment, versioned, unknown names degrade and land in the `SkewReport`, and a
guest can branch on `LocalSegmentVersions` when it must not degrade.

### 2.2 This screen owns it → guest logic over the injected environment, sending literals

The host already injects its environment into the guest runtime — dark mode, locale, viewport,
density, all of it, since [ADR-012](ADR-012-host-environment-subsystem.md). So a screen that owns a
colour decides it **in ordinary Kotlin** and sends the finished answer:

```kotlin
val accent = if (isSystemInDarkTheme()) Color(0xFF7FD8BE) else Color(0xFF8A4FFF)
Text("…", color = accent)
```

**No name to agree on, nothing to version, nothing that can skew.** The wire carries `[3, argb]` —
a factory and a number — and the host never learns there was an `if`.

This ADR closes the two ergonomic gaps that path had: `isSystemInDarkTheme()` now exists in the
guest under Compose's own name, and the layout `Text` takes a direct `color: Color?` — absent
meaning the host's ink, as absence always means.

The cost, measured rather than described: **one small batch when the environment changes**, because
the guest re-runs its logic and resends the literal. The test pins it at exactly one batch, and
Phase 0 priced a one-change batch at 0.12 ms. The environment push it rides on happens anyway.

### 2.3 Rejected: logic over the wire

The third candidate is sending the *decision procedure* as data — `["if", ["darkMode"], A, B]` —
for the host to evaluate. Rejected, for three reasons in increasing order of weight:

1. **It buys almost nothing.** Its sole advantage over §2.2 is avoiding one 0.12 ms message on a
   theme flip — a message whose transport fires regardless, since the environment push and the
   palette repaint already happen.
2. **It grows without a stopping point.** One conditional invites a comparison (`width > 600`),
   then arithmetic, then string operations for locale, and each step is individually reasonable.
   The end state is an expression interpreter inside the host.
3. **It maximises the exact problem it was meant to solve.** The objection to tokens was contract
   surface. An expression grammar is the largest contract surface available: every operator is a
   name both sides must implement identically and version forever, and — unlike an unknown token,
   which degrades to one wrong colour — an unknown *operator* breaks every expression containing
   it. Remote code in a costume, bought at the price of the concern it answered.

The guest already has a programming language. It is Kotlin, it updates over the air, and the
environment is injected into it. §2.2 *is* logic-driven rendering — with the logic where logic
belongs.

## 3. Rationale & Research

**The two paths are per-value choices, not modes.** One composition tokens its brand colour and
hand-picks its chart colours side by side; a test pins both factories crossing from one screen.

**The first frame is already right.** The environment is present *before* the first composition
(ADR-012's test), so guest logic never paints a wrong-theme launch frame — the failure mode that
usually pushes people toward host-evaluated conditionals.

### Verified

Five tests: the first frame is themed correctly in both modes; a theme flip re-runs the logic for
**exactly one batch** carrying the re-decided literal; the wire carries a factory and a value with
no conditions and no branches; an uncoloured text still sends nothing; tokens and literals mix on
one screen. On the emulator: the diagnostics screen's swatch reads "violet, because light", the
device flips to dark mode, and the same running guest — **zero reloads** — re-decides to "seafoam,
because dark".

## 4. Unstated Assumptions

- **A literal is a commitment.** A screen that hand-picks colours has opted out of the design
  system's future changes for those values, which is what owning a decision means. Nothing warns.
- **The environment is the vocabulary of §2.2.** Guest logic can branch on anything
  `HostEnvironment` carries; a decision needing a host fact it does not carry (battery saver,
  reduced-motion) needs that field added — a protocol addition, not a new mechanism.
- **`Text` is the only layout primitive with a direct colour today.** Modifier `background` takes
  one everywhere; other colour-bearing layout parameters get the same treatment when something
  needs them.
- **The rejection in §2.3 is of *general* expressions.** The recipes that exist — formatting,
  animation targets — are fixed-shape declarations with no control flow, and stay that way.

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — the host-resolved values section gains
  the two-path rule and the rejection.
- [`specs/layer-1-authoring.md`](../../specs/layer-1-authoring.md) — the guest author's decision
  rule.

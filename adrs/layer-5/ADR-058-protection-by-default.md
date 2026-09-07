# ADR-058: A parameter with a default is a parameter nobody is asked about

**Date:** 2026-09-07
**Status:** Accepted

## 1. Context & Problem Statement

The adoption audit ([`plans/adoption-audit.md`](../../plans/adoption-audit.md) A3) found that
`ReleaseGuard` — the crash-loop quarantine, the last-known-good record, the kill switch: half the
architecture's selling point per [ADR-049](ADR-049-surviving-a-bad-publish.md) — was wired in
exactly one host of five. Every integration point existed: `DogwoodDelivery.loadGuarded`,
`DogwoodShell(releaseGuard = …)`, the session's persist-before-run ordering. All of it was opt-in
behind `= null` defaults, and four hosts, including the shell sample a product is told to copy,
did not opt in. An adopter following the documentation shipped with no bad-publish protection and
would discover that during their first bad publish — the one moment the mechanism exists for.

## 2. Decision

**The defaults are gone.** `DogwoodShell` now *requires* `releaseGuard` and `onRefused`. Passing
`null` is still accepted — a host may genuinely have nowhere to persist a record — but it is a
decision somebody writes, not an omission nobody notices. `onRefused` is required alongside it
because a refusal with a defaulted no-op handler is a screen that silently shows nothing, and the
whole point of the guard is that somebody finds out.

**Every host in the repository now rides the guarded path**, and the shell-less ones demonstrate
it for bare-delivery adopters: the desktop slice and Umbra's `:app` use `loadGuarded`, branch on
`Refused`, and call `succeeded` only after the guest started and the host mounted it — because "it
loaded" is not success; a payload that throws on its first composition has loaded.
`docs/getting-started.md`'s host outline now opens with the guard, and Umbra — the worked example
an adopter copies — copies the protection with it.

## 3. Rationale & Research

**The mechanism was never the gap; the default was.** Removing it turned the gap into two compile
errors (`TabsActivity`, the iOS host), which is the entire argument: a compile error today is
cheaper than a crash loop in the field, and it is paid by the person with the context to fix it.

The behavioural claims themselves were verified by ADR-049 on a device and are graded as capability
group H; this decision changes who gets them, not what they are.

## 4. Unstated Assumptions

- **The web host remains unguarded**, and is now the only one. Its delivery is `WebDelivery`, a
  different mechanism with no release-version bookkeeping, and its storage story
  (`BrowserFileSystem`) exists but is unwired here. Recorded as the remaining slice of A3 rather
  than silently absorbed: a bad publish on the web today relies on the pre-flight dictionary check
  and the browser's own recovery, not on quarantine.
- **`succeeded` in the bare-delivery hosts fires on mount, not on first batch** — the same moment
  `DogwoodSession` uses. A payload that renders once and then wedges is forgiven by both; the
  guard's contract was always "failed to *start*", not "misbehaved later".
- **Store failures stay non-fatal** (`runCatching` in `FileReleaseStore`), per ADR-049: a guard
  that takes the launch down is worse than no guard.

## 5. Updated Documents

- [`plans/adoption-audit.md`](../../plans/adoption-audit.md) — A3 closed, web remainder noted.
- [`engine/dogwood-host/.../Shell.kt`](../../engine/dogwood-host/src/ziplineMain/kotlin/dev/dogwood/host/Shell.kt)
  — the parameters, and why they have no defaults.
- [`engine/samples/`](../../engine/samples/) — `TabsActivity`, the iOS host, the desktop slice.
- [`samples-standalone/umbra/app/`](../../samples-standalone/umbra/app/) — the guarded bare path,
  as the copyable example.
- [`docs/getting-started.md`](../../docs/getting-started.md) — the outline opens with the guard.

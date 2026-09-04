# ADR-039: Accessibility Is Asserted, Not Inspected

**Date:** 2026-09-04
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-033](ADR-033-the-ios-host-profile.md) shipped an accessibility *probe*: it walks the
`UIAccessibility` tree and prints what an assistive technology would find. The platform review
recorded the limit honestly — "the automated tree dump is already evidence for structure, not
experience" — and left **accessibility interaction on iOS** as the single item marked *needs a
human*.

Two problems with leaving it there.

The first is that "needs a human" is indistinguishable, over time, from "not checked". A person
runs it once, at the end of a phase, on one screen. Every build after that is unverified, and the
regression that matters — a control that stops being reachable — is exactly the kind nothing else
catches, because the screen still *looks* right.

The second was found while writing this: **the probe was never called.** `dumpAccessibilityTree`
existed, was documented, and had no caller anywhere in the repository. That is the third time this
project has found evidence-producing code that produced no evidence — the dead `skew` variable in
the tabs sample, the `--gufa` gate that had never fired, and now this.

## 2. Decision

**Assert on the accessibility surface, in a gate that fails, and be precise about which half of the
problem it covers.**

The decision rests on one fact about how VoiceOver works. VoiceOver does not read a screen; it reads
the `UIAccessibility` surface, and it *acts* on that surface through a small set of public
methods — `accessibilityActivate`, `accessibilityScroll`, `accessibilityIncrement`, and the
`accessibilityCustomActions` the rotor lists. Those are ordinary Objective-C methods. **Anything
VoiceOver can invoke, a test can invoke**, and the consequence is observable in the same tree.

`AccessibilityDrill.kt` therefore asserts, rather than prints:

- Guest-composed text reaches the accessibility layer at all.
- Every element announces something — an element with neither label nor value is announced as its
  trait alone, and is reachable, focusable and anonymous.
- A **guest-composed** control carries the button trait, as distinct from the host shell's own.
- The screen scrolls through the accessibility layer, so a screen taller than the display has a
  reachable bottom.
- **Activating a control through the accessibility layer drives the guest.** This is the one a dump
  can never make. The sample's `Expand` button relabels itself when tapped, so its own label is the
  observable consequence: the activation goes to the host binding, the binding sends an event across
  the Zipline boundary, the guest recomposes, a batch returns, the host applies it, and Compose
  rebuilds the tree. Every step must work for the label to change.
- And back again, because a control that switches on and not off is half-broken.

**What it does not settle**, stated in the file rather than left to be assumed: whether the speech
is *good* (nothing here hears anything — an element labelled `Expand` proves the label arrived, not
that it is the right word); reading order *as experienced*, since the rotor and heading navigation
build on top of publication order; and typing and selection, which are driven by touch and keyboard
that `simctl` cannot supply.

So the human pass is not retired. What is retired is the part a person should never have been asked
to do by hand — checking, on every build, that every control is still reachable and still operable.

## 3. Rationale & Research

**The drill found a real defect on its second run**, which is the strongest argument for it:

```
A11Y FAIL every element announces something -- 1 anonymous: traits=0x800000040000
```

The sample's card-number field passes `label = TextValue("Card number")` from the payload. Material
3 draws that label and **does not fold it into the field's own semantics**, so the element VoiceOver
lands on had no name: it announced "text field" and stopped. A screen-reader user met an anonymous
control on a screen that had, by every other measure, done everything right — the label was
authored, crossed the wire, was resolved host-side and was drawn on screen.

`TextInputImpl` now states the label in semantics as well as drawing it. The element reads
`"Card number"`, and the typed text remains its *value* rather than being overwritten, which is the
division VoiceOver expects: name from the label, value from the content.

**It also found a bug in its own first draft**, which is worth recording because it is the failure
mode automated accessibility checks are most prone to. The button check counted four buttons and
passed — and all four were the host's own tab bar. A check that counts controls without asking
whose they are goes green on a screen where no guest control is exposed at all. It now excludes the
shell's labels and reports `guest buttons: [Expand]`.

**The gate fires.** Reintroducing the semantics defect and re-running gives `exit=1` with the single
expected failure; restoring the fix gives `exit=0`. That is checked because this project has shipped
a gate that could not fail before.

**VoiceOver has to be running, and that is a real dependency rather than a detail.** Compose
Multiplatform builds the accessibility tree only while an assistive technology is active; with it
off, the walk finds the rendering view and nothing beneath it. Every assertion would then fail for
one uninteresting reason, so the drill **refuses** rather than fails — twenty failures for one cause
reads as a broken screen rather than an unconfigured simulator. `tools/a11y-drill/run.sh` enables it
first, and `defaults write` alone is not enough: the accessibility daemon caches those values, and
the `notifyutil -p` posts are what make it re-read them.

## 4. Unstated Assumptions

- **The simulator's accessibility stack behaves as a device's does.** It is the same UIKit
  implementation, but VoiceOver on a device also has gesture handling and speech that the simulator
  does not exercise. Nothing here depends on those; a check that did would need a device.
- **The sample screen stands in for real screens.** The drill asserts against the slice's Diagnostics
  tab because that is what exists. A product would point it at its own screens, and the checks are
  written as questions about a screen rather than about this screen — except for the `Expand`
  activation, which names the sample's control deliberately, since the observable consequence has to
  be known in advance.
- **Publication order is a proxy for reading order.** It is what VoiceOver starts from, so a check
  on it would catch gross misordering. No such check is written, because "reasonable order" is a
  judgement and asserting a specific order would fail on every legitimate layout change.
- **Material 3's semantics behaviour may change.** The fix in `TextInputImpl` states the label
  explicitly, which is correct whether or not Material starts merging it — but if Material begins
  merging it *and* the explicit value disagrees, the explicit one wins. The drill is what would
  notice.

## 5. Updated Documents

- [Layer 5: Host](../../specs/layer-5-host.md)
- [ADR-033: The iOS Host Profile](ADR-033-the-ios-host-profile.md) — its accessibility section
  described a probe; that probe now has a caller and a gate.
- [`tools/a11y-drill/README.md`](../../tools/a11y-drill/README.md) — the runner, its limits, and the
  results.
- `engine/dogwood-host/src/commonMain/kotlin/dev/dogwood/host/DesignSystemImpl.kt` — the text-input
  label reaches semantics.
- `engine/samples/slice-ios/src/iosMain/kotlin/dev/dogwood/slice/ios/AccessibilityDrill.kt` (new)
- `engine/samples/slice-ios/src/iosMain/kotlin/dev/dogwood/slice/ios/Accessibility.kt` — the walk is
  reusable, and is now used.

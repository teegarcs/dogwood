# Asserting on VoiceOver's surface, without a person holding the phone

The platform review left **accessibility interaction on iOS** as the one item marked *needs a
human*: typing through the input method editor, selection handles, the rotor, spoken order. It also
noted, correctly, that the existing probe — which walks the tree and prints it — is "evidence for
structure, not experience."

This closes the mechanical half of that gap and is deliberate about which half it is.

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
tools/a11y-drill/run.sh          # exits non-zero on any failed check
```

## Why a machine can do this at all

VoiceOver does not read a screen. It reads the `UIAccessibility` surface, and it *acts* on that
surface through a small set of public methods — `accessibilityActivate`, `accessibilityScroll`,
`accessibilityIncrement`, and the `accessibilityCustomActions` the rotor lists. Those are ordinary
Objective-C methods. **Anything VoiceOver can invoke, a test can invoke**, and the consequence is
observable in the same tree.

So "does activating this control actually drive the guest?" has a mechanical answer. That is the
question worth the most here: a label that reads beautifully on a control that cannot be operated
is worse than no label, because it is invisible to review.

## What it asserts

| Check | Why it exists |
|---|---|
| Guest-composed text reaches the accessibility layer | End-to-end evidence that a node composed *inside the sandbox* is readable by a screen reader with no Dogwood-specific accessibility code |
| Every element announces something | An element with neither label nor value is announced as its trait alone — "text field", with no indication of which one. Reachable, focusable, anonymous |
| A **guest-composed** control carries the button trait | A button without the trait is announced as a label: it reads correctly and cannot be operated |
| The screen scrolls through the accessibility layer | A screen taller than the display that the rotor cannot scroll has a bottom half no screen-reader user can reach |
| **Activating through VoiceOver drives the guest** | The whole round trip: accessibility layer → host binding → event across the Zipline boundary → guest recomposition → batch → host applies → Compose rebuilds the tree. Every step has to work for the label to change |
| …and back again | A control that switches on and not off is half-broken |

The activation check uses the sample's `Expand` button because it **relabels itself** when tapped,
so its own label is the observable consequence. No instrumentation, no back channel: the assertion
reads the same surface a screen reader does.

## What it cannot settle, and this is not a formality

- **Whether the speech is good.** Nothing here hears anything. An element labelled `Expand` is proof
  the label reached the platform, not proof it is the right word.
- **Reading order as experienced.** The walk sees publication order, which is what VoiceOver starts
  from — but rotor navigation, grouping and heading jumps build on top of it.
- **Typing and selection.** The input method editor and selection handles are driven by touch and
  keyboard, and `simctl` can do neither.

So the human pass is not retired. What is retired is the part a person should never have been asked
to do by hand: checking, on every build, that every control is still reachable and still operable.

## Two things the runner has to do first

1. **Turn VoiceOver on.** Compose Multiplatform builds the accessibility tree *only* while an
   assistive technology is running — with it off, the walk finds the rendering view and nothing
   under it, and every assertion below would fail for one uninteresting reason. The drill therefore
   **refuses** rather than fails in that case. `defaults write` alone is not enough: the
   accessibility daemon caches those values, and the `notifyutil -p` posts are what make it re-read
   them on an already-running simulator.
2. **Serve the guest.** The screen under test is composed in the sandbox. That is the point — the
   labels the drill looks for were written in guest code and crossed the wire.

## Results, 2026-09-04

**10 checks, 0 failures** — iOS 17.5 simulator. Full log in `result-2026-09-04.log`.

The drill found a real defect on its second run, which is the best argument for it existing:

> `A11Y FAIL every element announces something -- 1 anonymous: traits=0x800000040000`

The sample's card-number field passes `label = TextValue("Card number")` from the payload. Material 3
draws that label but **does not fold it into the field's own semantics**, so the element VoiceOver
lands on had no name at all — it announced "text field" and stopped. Fixed in `TextInputImpl` by
stating the label in semantics as well as drawing it; the element now reads `"Card number"`, and the
typed text remains its *value* rather than being overwritten.

It also found a bug in **its own first draft**: the button check counted four buttons and passed,
but all four were the host's tab bar. A check that counts controls without asking whose they are
would have gone green on a screen where no guest control was exposed at all. It now excludes the
shell's own labels, and reports `guest buttons: [Expand]`.

### The gate fires

A gate that has never failed is not a gate. Reintroducing the semantics defect and re-running:

```
NEGATIVE-CONTROL exit=1
A11Y FAIL every element announces something -- 1 anonymous: traits=0x800000040000
A11Y RESULT passed=9 failed=1
```

With the fix restored, `exit=0`.

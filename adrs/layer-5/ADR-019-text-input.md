# ADR-019: Text Input — The Host Owns the Text, and the Version Says Who Is Behind

**Date:** 2026-09-01
**Status:** Accepted

## 1. Context & Problem Statement

`roadmap.md` Phase 4: "**Text input.** A version vector plus optimistic host state. **Do not attempt
a naive controlled `TextField`.** Scope must also cover declarative masks/formatting (card numbers)
and host-computed counters — per-keystroke guest round trips are forbidden by the Layer 4
invariant."

The prohibition is the whole design. A controlled text field asks the guest what the text should be
after every keystroke. In this architecture that is a boundary crossing per character with a
composition on the far side of it, racing the next keystroke — and when it loses, the caret jumps,
a character is swallowed, or the input method's composing region is torn apart mid-word. Every
framework that has tried it has the same bug report.

This is also the one live-state holder that **cannot** use the pattern
[ADR-014](ADR-014-live-state-holders.md) established. A scroll target is level-triggered: the guest
says where it wants to be and the newest wish wins. Text is not. "The text is `hello`" from a guest
that has not seen the user type `hellos` is not a stale wish to be overridden — it is an
instruction that would *delete a character the user just typed*.

## 2. Decision

**The host is authoritative for the text. The guest holds a version-stamped mirror.**

This is Redwood's shape — its `TextFieldState` carries a `userEditCount` and its host binding
discards stale guest updates outright — with the conflict rule written down:

- The host binding counts user edits. Every edit event carries the raw text **and** that count.
- The guest records both, so it always knows which edit it is answering.
- Every value the guest sends carries the count it last acknowledged.
- **A guest value stamped older than the host's count is discarded**, because the user has typed
  since and the user wins.

A programmatic set — clearing a field, filling one from a saved address — is therefore honoured
exactly when the guest is up to date, which is exactly when it should be. That is why the rule is a
version rather than a flat "the host always wins": the latter would make `state.clear()` impossible.

**The guest's value is always raw.** Digits, not `4242 4242 4242 4242`. Masking is a display
transform, so guest code validating a card number never strips anything, and changing the mask
cannot change what validation reads.

**Masks, length limits and counters never round trip.** The guest *declares* them once; the host
applies them where the typing is. A card field that asked the guest where to put the spaces would
be the forbidden per-keystroke crossing wearing a hat, and a guest-computed "12/50" counter would
be the same thing again.

**Both halves of the state survive a code update.** The guest's saver carries the text **and** the
acknowledged count. The count is the one that is easy to forget and the one that breaks silently:
the host's edit count survives a code update because its binding keeps the same composition group
([ADR-016](ADR-016-leak-detection.md) is about the consequences of that reuse), so a guest that came
back stamped zero would have every value it sent discarded as stale, for the life of the screen,
with nothing in the logs.

Supporting this needed one small widening: `canBeSaved` now accepts lists of saveable values, which
is what Compose's own `listSaver` produces and therefore the idiomatic way to save a holder with
more than one field.

## 3. Rationale & Research

**Why the host counts rather than the guest.** The count has to be minted where the edits happen,
or it cannot order them. A guest-minted version would be a version of the guest's *belief*, which
is precisely the thing that is behind.

**Why discarding is silent.** A discarded update is not an error: it is the normal outcome of a
user typing while a guest was composing. Reporting it would produce telemetry proportional to
typing speed.

### The device found a bug the test harness could not

The first implementation set the field's `value` to the masked string and stripped it back on every
edit. Every unit test passed. On the emulator, typing `4242424242` produced a guest value of
`4242424224` — transposed digits, reliably.

With a display transform applied to the *value*, the caret arithmetic becomes the binding's
problem, and the input method's idea of where the cursor is drifts from the field's within a few
keystrokes. `androidx.compose.ui.test.performTextInput` does not reproduce it because it does not
go through an input method; only a real keyboard does.

The fix is the shape Compose provides for exactly this: the field's text stays **raw** and a
`VisualTransformation` renders the mask, with an `OffsetMapping` handing the caret arithmetic back
to Compose — which knows what the input method is doing and this binding does not. Retyping sixteen
digits on the device then produced `4242424242424242`, displayed as `4242 4242 4242 4242`, counter
`16/16`.

**The regression test is on the mechanism, not the symptom**, and it is worth being clear about
why. The unit harness cannot reproduce the race, so a test that typed and compared would have
passed before the fix as well. What is testable is the offset mapping the transformation hands
back — four raw digits map to caret position four, five map to six across the separator, and the
reverse — which is the thing that was previously being done by hand and wrongly.

### Verified end to end

| Observation | Result |
|---|---|
| Typing 16 digits into a masked field | displayed `4242 4242 4242 4242`, counter `16/16`, guest sees `4242424242424242` |
| A search field over the stays list | filters as the user types; the field never stutters, because the guest is not in its loop |
| Length limit of 5, typing 10 characters | field holds `abcde`, host-drawn counter reads `5/5` |
| A guest value stamped behind the host | discarded; the user's typing stands |
| A guest value stamped current | adopted, so `clear()` and autofill work |

## 4. Unstated Assumptions

- **The unit harness types differently from a keyboard.** Stated as a decision above because it is
  the reason a whole class of text-input bug will only ever be found on a device. Any future change
  to this binding wants a device pass, not just a green suite.
- **One mask language, and it is simple.** `#` for a digit, `A` for a letter, everything else a
  literal. No optional groups, no alternation, no locale-aware phone formats. A richer language is
  a grammar, and grammars belong in an ADR of their own.
- **`stripMask` is coarse on purpose** — it keeps letters and digits and drops the rest, rather than
  matching the mask position by position. Paste, autofill and an input method rewriting a whole
  word all arrive with formatting of their own, and a position-matching reader breaks on all three.
- **Selection and cursor position are not in the protocol.** The guest cannot place the caret, and
  cannot read it. That is the invariant holding, not an omission — but a "move the cursor to the
  end after autofill" request will eventually want a declared target of the ADR-014 kind.
- **No input-method composing-region protocol.** The host's field owns it entirely. A guest cannot
  observe a half-composed word, which is correct and also means it cannot implement
  as-you-type transliteration.
- **Assumes one edit count per field, remembered positionally.** Two fields swapping position in a
  reorder would swap counts. `key(node.id)` prevents it for the same reason it preserves scroll
  position ([ADR-015](ADR-015-node-identity-and-reuse.md)).

## 5. Updated Documents

- [`specs/layer-5-host.md`](../../specs/layer-5-host.md) — bespoke subsystem 3 marked delivered.
- [`roadmap.md`](../../roadmap.md) — Phase 4's text-input row.
- [`adrs/README.md`](../README.md) — index entry.

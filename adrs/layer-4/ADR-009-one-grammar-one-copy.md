# ADR-009: One Grammar, One Copy

**Date:** 2026-09-02
**Status:** Accepted

## 1. Context & Problem Statement

Dogwood locked its vocabulary and left its grammar unlocked.

The dictionary — widget tags, property tags, component names, and since
[Layer 5 ADR-031](../layer-5/ADR-031-safety-relevant-parameters.md) affordance markings — is
versioned, locked, and enforced by a build that fails on a violation. The *encoding* those tags
travel in had none of that:

- **The grammar was written five times.** The discriminators `0..5` were declared in the guest's
  encoder and again in the host's decoder, as two `const` blocks in two modules with nothing
  comparing them. A third decoder sat in the guest's own test source set, a fourth in the Phase 0
  harness. The expression-factory identifier space was declared in four more places, all `private`.
- **The two halves were never compared.** No source imported both the encoder and the decoder. Every
  host test fed the decoder hand-typed wire strings; every guest test read batches back through the
  copy next door. A change to the encoder plus a matching change to that copy left every suite green
  while the real host decoder was wrong.
- **The decoder indexed positionally and trusted.** No arity check anywhere. `ChildAdd` read indices
  1–4 and never looked at `fields.size`.
- **A batch it could not read killed the screen.** `error("unknown change kind")` threw from inside
  a `map`, with no `try`/`catch` above it, out through an unhandled coroutine.

The last two compound into the failure that matters. **Every interesting element of a change tuple
is an integer**, so a payload whose tuples have shifted by one parses perfectly and means something
else: a child identifier read as an insertion index, a count read as a position. No exception, no
missing field, no entry in any report — a tree that is quietly wrong, and every later batch
compounding against it.

The hazard is not hypothetical. The guest is delivered over the air, independently of the host, so
version skew across this boundary is the normal case rather than the exception. React Native hit the
identical problem at its own module boundary — RFC 0011's second stated motivation is that with
over-the-air updates "there is no easy way to check if a newer version of JavaScript calls the right
method with the correct set of arguments" — and answered it with a shared schema and generated
encoders on both sides.

## 2. Decision

1. **The grammar moves to `dogwood-protocol`**, which both sides already depend on and which targets
   JavaScript as well as the Java Virtual Machine and Android. `ChangeKind` is declared once; the
   decoder exists once.
2. **The encoder deliberately does not move.** [ADR-007](ADR-007-v1-wire-format-positional-json.md)'s
   whole finding is that the guest must build native JavaScript arrays and hand them to QuickJS's own
   `JSON.stringify`, because a crossing's cost is dominated by how much interpreted Kotlin runs while
   encoding it. That is irreducibly platform-specific. It stays in `dogwood-compose` and imports its
   discriminators rather than redeclaring them.
3. **Every change tuple's arity is checked.** `ChangeKind.arity` records the expected element count
   per kind, and a mismatch is rejected.
4. **An undecodable batch is rejected whole, reported, and survived.** `ProtocolMismatch` is typed so
   a caller can tell protocol skew from a bug; `sendChanges` catches it, records the reason in
   `SkewReport.rejectedBatches`, and returns — leaving the tree that is already on screen.
5. **The third decoder is deleted.** Guest tests read batches back through the host's actual decoder.

## 3. Rationale & Research

### Why rejecting the whole batch is right, and where the precedent comes from

Every other kind of skew in this system degrades: an unknown widget tag becomes a placeholder, an
unknown text style becomes body text, an unknown colour token becomes unspecified. The instinct is to
do the same here and skip the offending change.

That is wrong, and the reason is structural. **The changes in a batch are ordered and
interdependent.** Skip a `Create` and the `ChildAdd` two changes later references a node that does
not exist. Skip a `ChildRemove` and every subsequent index in that slot is off by one. There is
nothing to degrade *to*, because a batch is not a list of independent facts — it is a sequence of
mutations that only means anything applied in full.

The delivery layer settled this exact shape already, for the same reason: a manifest that fails
verification is rejected **whole**, and "failure is not silent, and it is not fatal to the
application — a rejected update leaves the previously cached, previously validated payload in place."
An undecodable batch now behaves identically: the last good tree keeps rendering, and the reason
lands in the skew report rather than in a stack trace.

### Deleting the third decoder was the change that mattered

Moving the decoder made every guest test that reads a batch into a round-trip test, because
`RecordingHost.decoded()` now calls the same function the host calls. That is 100-odd assertions
that previously proved nothing about the host and now do.

The comment that justified the copy is worth recording, because the reasoning is one anybody would
accept: *"a common decoder is worth building when a third caller appears, not before."* It was
correct about cost and wrong about risk. The copy was not a convenience with a small maintenance
tax; it was the thing that made drift invisible.

### Verification

`GrammarTest`, in the guest's own target, running the real encoder against the real decoder:

- every change kind survives a round trip, with no hand-typed wire string anywhere;
- a field inserted mid-tuple is rejected — the silent-corruption case, and it fails without the
  arity check, verified by removing it;
- a truncated tuple is rejected, where it previously surfaced as `IndexOutOfBoundsException` from
  inside a `map`;
- a kind from a newer protocol is rejected by name;
- a malformed envelope is rejected;
- **a well-formed batch is still accepted** — the control, without which the five above would pass
  against a decoder that rejected everything.

The full suite is 403 tests, and the slice was re-verified on device after the move: four tabs, no
crashes, warm switching intact.

## 4. Unstated Assumptions

- **Strict arity is a deliberate forward-compatibility choice.** A future guest that *appends* a
  field to a tuple will be rejected by today's hosts rather than tolerated. That is the intent: a
  tolerant reader cannot distinguish an appended field from a shifted one, so tolerance buys
  compatibility in one case by buying silent corruption in the other. Extending a tuple is a
  protocol revision, and it should be loud.
- **The containment path is verified by inspection and on device, not by a unit test.** Reaching
  `sendChanges` requires a live Zipline guest, which the host test source set cannot stand up. The
  decoder is thoroughly tested; the six lines that catch and report are not.
- **The expression-factory identifier space is still declared in four places.** This ADR consolidates
  the change grammar, not the deferred-expression grammar. That remains open and is the same class of
  hazard, with the added detail that identifier 13 is skipped on both sides with nothing recording
  why.
- **The lock still records event tags but not event signatures.** Changing an event lambda's
  parameters moves no tag and passes the lock unchanged. Also open.

## 5. Updated Documents

- [Layer 4: The Guest Runtime](../../specs/layer-4-sandbox.md) — where the grammar lives and how a
  batch that cannot be decoded is contained.

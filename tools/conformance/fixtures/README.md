# Frozen payloads

A payload built and **signed** by the toolchain of the day named in its directory, committed as a
binary artifact on purpose.

## Why a frozen artifact rather than a rebuild

Three toolchains are coupled — the host application's, the engine's, and the payload's — and the
one pairing a real deployment *always* has is a **payload built earlier than the host running it**:
users update applications slowly, so today's host meets payloads compiled by last quarter's Kotlin
and Zipline. The adoption audit's A6 recorded that this pairing was exercised by nothing, and that
a policy ("hosts first, payloads after the fleet") is not a test.

A rebuild cannot test it. Rebuilding produces a payload from *today's* toolchain, which is the
pairing that already works and is already tested by every drill in this repository. Only an
artifact that stops changing can be old.

## What the drill asserts

`tools/conformance/cross-version.sh` serves this directory to a host built from current sources and
requires that the payload **loads, verifies against the committed development keys, and renders**.
It fails when a change to the engine breaks compatibility with payloads already in the field — the
failure whose cost is measured in un-updatable installations rather than in a red build.

## Retiring one

A fixture that no longer loads is either a real compatibility break (fix the engine, or state the
break in an ADR and in `docs/getting-started.md`'s version policy) or a deliberate protocol change
past its support window (retire it in the same commit that documents the window). Adding a newer
fixture beside this one, rather than replacing it, widens what is covered — which is the point.

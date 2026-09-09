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

## What the drills assert

`tools/conformance/cross-version.sh` (desktop) and `tools/conformance/cross-version-mobile.sh`
(`android` | `ios`) serve this directory to a host built from current sources and require that the
payload **loads, verifies against the committed development keys, and renders** — claims `K1` and
`K2`. They fail when a change to the engine breaks compatibility with payloads already in the field:
the failure whose cost is measured in un-updatable installations rather than in a red build.

## When to freeze a new one

**Every time the dictionary version is bumped, freeze that day's signed payload here.**

The rule is deliberately mechanical, because the judgement version of it does not work. "Freeze one
when something significant changes" means nobody freezes one, and the window this directory covers
stops widening the day somebody stops thinking about it — while the toolchain keeps moving. A
dictionary bump is the right trigger because it is the moment the surface changed, which is exactly
the change most likely to break an older payload, and because it is already a deliberate, reviewed
act with a lock file behind it.

Freezing one is:

```
./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline
cp -R engine/samples/slice-guest/build/zipline/ProductionWebpack \
      tools/conformance/fixtures/payload-$(date +%F)
```

**Add, never replace.** Each fixture widens the window; replacing one narrows it back to a single
point and throws away the only evidence that the older pairing still works. The drills serve the
**newest** fixture by default, so adding one does not slow the ordinary run — running the older ones
is a matter of pointing `FIXTURE` at them.

## Retiring one

A fixture that no longer loads is either a real compatibility break (fix the engine, or state the
break in an ADR and in `docs/getting-started.md`'s version policy) or a deliberate protocol change
past its support window (retire it in the same commit that documents the window). Adding a newer
fixture beside this one, rather than replacing it, widens what is covered — which is the point.

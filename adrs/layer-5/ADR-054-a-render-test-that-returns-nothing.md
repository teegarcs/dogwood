# ADR-054: A render test that returns nothing composes nothing

**Date:** 2026-09-07
**Status:** Accepted

## 1. Context & Problem Statement

`plans/conformance.md` Part 7 carried an unexplained result: **a hostile value was clamped and
reported on the Java Virtual Machine and not on the web.** It was written down honestly — the value
was known to reach the reader, the cause was known not to be isolated, and the reproduction was kept
in `jvmTest` alone rather than weakened until it passed everywhere.

It was also the wrong conclusion drawn from a real observation, and the thing it was hiding was
larger than the thing it described.

## 2. Decision

**Every shared render test is an expression body that returns its `runComposeUiTest` result, and a
check fails the build on any that is not.**

```kotlin
// Before -- green on the web, composing nothing.
@Test
fun aThing() {
  render(tree)
  assertEquals(1, tree.skew.clampedValues.size)
}

// After.
@Test
fun aThing() = rendered(tree) {
  assertEquals(1, tree.skew.clampedValues.size)
}
```

[`tools/render-shape/check.py`](../../tools/render-shape/check.py) rejects a render test with a
block body, and one whose last statement is not the render block. It runs in the tier-S job on every
pull request, before the build, because it answers a question the build cannot.

## 3. Rationale & Research

**`runComposeUiTest` has a different return type on every target.** Verified rather than assumed, by
asking the compiler on each:

```
jvm:    Return type mismatch: expected 'Int', actual 'Unit'
wasmJs: Return type mismatch: expected 'Int', actual 'Promise<JsAny?>'
```

The test framework awaits that promise **only if the test function returns it**. Written as a
statement, the block's body is scheduled and the test function returns immediately — so the
composition never happens. Assertions placed after the call read an un-composed tree; assertions
placed inside it are never observed at all.

**The decisive evidence is a failure that passed.** Two tests, one target:

| Test | Result on Kotlin/WebAssembly |
|---|---|
| `fun x() { runComposeUiTest { fail("...") } }` | **passed**, in 0.001 s |
| `fun x() = runComposeUiTest { fail("...") }` | failed, as it should |

That is what settles it, and it is why this is written as a decision rather than as a suspicion.
The intermediate probe was equally direct: a counter incremented inside `setContent` read
`enteredSetContent=0`.

**The scale of what was green for nothing.** Six files, 49 tests, on Kotlin/WebAssembly. They passed
because of what they happen to assert:

- `unknownWidgetTags` is recorded by `HostTree.apply`, not by composition — so the skew tests that
  assert it pass without a composition.
- Several assert an **emptiness** — "a healthy client reported nothing", "an unwatched container
  reported nothing" — which an un-composed tree satisfies perfectly.

**The clamp test was the only one that could notice**, because it is the only assertion in that
file about something a *binding* records while drawing. It was doing its job. The conclusion drawn
from it was that the web's clamp was broken; the correct conclusion was that the web's *tests* were.

**The fix was watched to work, not inspected.** After the change, one assertion in
`ScrollMirrorTest` was deliberately broken and the wasm run reported
`Report(offsetDp=0, maxOffsetDp=320, scrolling=false)` — a real measurement from a real browser
layout, which the previous shape could never have produced. The check itself was watched to fail on
a reintroduced block body and to pass when it was restored.

**Why a grep and not a compiler check.** There is no portable return type to declare: `Unit` on two
targets and `Promise<JsAny?>` on the third, with no common supertype worth naming in common code, so
`fun x(): SomeType` cannot be written. The check is a grep with a reason attached, and its header
says so: it catches the shape, not every way of dropping a promise.

## 4. Unstated Assumptions

- **`kotlin.test` awaits a returned promise on Kotlin/WebAssembly.** Demonstrated by the table
  above rather than read from documentation.
- **The Kotlin/Native (iOS) target behaves like the Java Virtual Machine.** Its return type is
  `Unit`, so the old shape was correct there; the iOS suite passed before and after, which is
  consistent with that and does not prove it.
- **The check knows this source set's render helpers by name** (`render`, `rendered`, `show`,
  `showing`, `scrolling`, `mounted`). A helper with a new name and a block-bodied caller would pass
  it. That is a real limit of a grep and is named in the file.
- **This says nothing about `dogwood-web`'s own tests**, which do not use this harness.

## 5. Updated Documents

- [`plans/conformance.md`](../../plans/conformance.md) — Part 7's clamp entry, resolved and
  reversed.
- [`docs/checks.md`](../../docs/checks.md) — the new tier-S check.
- [`.github/workflows/conformance.yml`](../../.github/workflows/conformance.yml) — it runs before
  the build.
- [`engine/dogwood-host/src/renderTest/`](../../engine/dogwood-host/src/renderTest/) — all six
  files; `ClampedValueReportingTest` moved back out of `jvmTest` and into
  `SkewReportingTest`, where it now runs on three targets.
- [`tools/render-shape/check.py`](../../tools/render-shape/check.py) — new.

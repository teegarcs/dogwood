# Upstream reports, drafted and not filed

Four defects found while building and grading the Web host. All are reproduced, and **none has been
filed**. The first two have a workaround in this repository; the third does not, because it is not
something a consumer can work around; the fourth is worked around by a tool that reports the
discrepancy rather than hiding it.

They are drafted rather than submitted deliberately: filing a public bug report publishes this
project's name, a reproduction, and an implicit claim about a vendor's product. That is an
outward-facing action and it belongs to a person, not to an automated change. The text below is
ready to paste; the URLs go into the ADRs that promise them once someone files them.

Until then, `ADR-032` and `ADR-033` say "drafted, not filed" rather than "reported upstream", which
is the honest tense.

| # | Defect | Reproduction | Workaround in this repo |
|---|---|---|---|
| 1 | `wasm-opt` GUFA miscompiles `String.toCharArray()` | `tools/web-weight/bridge/run.sh` | `--gufa` filtered from the pass list |
| 2 | Kotlin/Wasm klib checker crash | any rebuild after an edit to a source set | clear `build/kotlin` and `build/classes` (the documented flag is **not** enough — see the correction) |
| 3 | Compose Multiplatform for Web publishes no disabled state to the accessibility tree | `tools/conformance/run-web.sh`, claim `D7` | none available to a consumer |
| 4 | Kotlin/JavaScript emits source-map positions that are not positions in the file they name | `tools/symbolicate/resolve.py` against any `error(...)` frame | the symbolicator flags the frame and names the nearest in-range mapping |

---

## 1. GUFA miscompiles `String.toCharArray()` to an array of zeros

**Where:** Kotlin 2.3.20, Kotlin/Wasm, production `wasm-opt` pass list (Binaryen).
**Severity:** wrong answer in a release build. No error, no warning; the call returns an array of
the correct length filled with zeros.

**What happens.** `String.toCharArray()` is compiled to the imported `wasm:js-string`
`intoCharCodeArray` builtin, which writes into a WebAssembly GC array. GUFA does not model that
builtin as mutating its destination, concludes the array is never written, and replaces every read
with the default value.

**Reproduction.** `tools/web-weight/bridge/run.sh` in this repository builds the same module twice —
once with Kotlin's default pass list, once with `--gufa` removed — and runs both in headless Chrome:

```
default pipeline:  viaBulkCopy=0           viaPerChar=1219597841   -> miscompiled
gufa-free build:   viaBulkCopy=1219597841  viaPerChar=1219597841   -> correct
```

Both figures are checksums over the same 28-character input; `viaPerChar` reads with `charCodeAt`
and `viaBulkCopy` reads through `toCharArray()`.

**Conditions.** Requires `--closed-world` and `-O3` before `--gufa`. **It is context-sensitive**:
adding an unrelated caller of `toCharArray()` made it disappear, and removing that caller brought it
back — so it cannot be reasoned about locally, and the blast radius is unknown. Any WebAssembly GC
array written by an imported builtin is a candidate.

**Workaround.** Remove `--gufa` from `BinaryenExec.binaryenArgs`. This repository also ships a
runtime gate (`BulkCopyGate`) that refuses to render on a miscompiled binary, validated against the
reproduction above.

---

## 2. Incremental Kotlin/Wasm klib compilation crashes on every rebuild after an edit

**Where:** Kotlin 2.3.20, Kotlin/Wasm.
**Severity:** build failure, fully reproducible, cleared by a clean build.

```
java.lang.ArrayIndexOutOfBoundsException
  at org.jetbrains.kotlin.ir.backend.js.WasmIrFileMetadata.fromByteArray
```

**What happens.** The first build of a Kotlin/Wasm module succeeds. Any subsequent build after a
source edit fails while reading incremental metadata. A clean build always succeeds.

**Workaround.** `kotlin.incremental.js.klib=false` in `gradle.properties`. Note that the
per-task `incremental` property does not reach the klib path, and `incrementalJsKlib` is `internal`,
so the global flag is the only reachable switch.

**Correction, 2026-09-03: that workaround is incomplete, and the stack above was recorded
imprecisely.** The flag has been set in `engine/gradle.properties` since it was written, and the
crash reproduced anyway while adding a test to `dogwood-web`. It is deterministic:

```
:dogwood-web:compileTestKotlinWasmJs   # first build of the source set -- succeeds
<edit any file in wasmJsTest>
:dogwood-web:compileTestKotlinWasmJs   # fails
```

The real stack names a different package and a different caller than the one recorded above — the
klib *checker*, not the incremental compiler's own metadata read, which is why disabling incremental
klib compilation does not prevent it:

```
java.lang.ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0
  at org.jetbrains.kotlin.ir.backend.js.wasm.WasmIrFileMetadata$Companion.fromByteArray(WasmIrFileMetadata.kt:33)
  at org.jetbrains.kotlin.ir.backend.js.wasm.WasmKlibExportingDeclaration$Companion.collectDeclarations(WasmKlibExportingDeclaration.kt:43)
  at org.jetbrains.kotlin.ir.backend.js.wasm.WasmKlibCheckers$makeChecker$1.visitModuleFragment(WasmKlibCheckers.kt:31)
  at org.jetbrains.kotlin.backend.common.serialization.SerializeModuleIntoKlibKt.runIrLevelCheckers(serializeModuleIntoKlib.kt:223)
```

**The workaround that does work:** `rm -rf <module>/build/classes <module>/build/kotlin`, then
rebuild. Worth knowing before assuming a Kotlin/WebAssembly compile error is in your own code — this
one arrives with no source location and no message beyond an array index.

This correction is why the entry is worth keeping rather than closing: the original text would have
sent a reader to a flag that is already on.

---

## 3. Compose Multiplatform for Web publishes no disabled state to the accessibility tree

**Where:** Compose Multiplatform 1.10.3, `wasmJs` target, Chrome 152 headless.
**Severity:** an accessibility defect in shipped output. A screen reader user is not told that a
control is disabled, tries to operate it, and is met with nothing.

**What happens.** Compose draws to a canvas and publishes a parallel DOM for assistive technology.
A `Button(enabled = false)` reaches that DOM as

```html
<div role="button" style="position: fixed; left: 16px; top: 379px; width: 868px; height: 40px;">
```

with a correct accessible name and **no properties at all** — no `aria-disabled`, and nothing in
Chrome's accessibility tree either. It is byte-for-byte the same shape as the enabled button beside
it. The composition knows the control is disabled; the platform drops that on the way out.

**Reproduction.** `tools/conformance/run-web.sh` in this repository. The shared Diagnostics screen
carries two deliberately disabled buttons, `Unavailable` and `Acme unavailable`, and the drill
walks the whole screen through `Accessibility.getFullAXTree`:

```
CONF D7 SKIP -- 2 disabled controls are on screen and none of them is announced as disabled:
               Compose publishes role and name only, with no properties at all.
```

Directly, over the Chrome DevTools Protocol, for the same node:

```
BUTTON 'Ask for a route this client does not have' props= {}
BUTTON 'Delete a row'                              props= {}
```

Every button on the screen carries an empty property set, enabled or not.

**What the other platforms do.** The identical composition, from the same source, announces the
state correctly on iOS: `UIAccessibilityTraitNotEnabled` is set, and `tools/a11y-drill` grades `D7`
on it. So this is the web accessibility layer specifically rather than a Compose semantics gap.

**Expected.** `aria-disabled="true"` on the published element when the composition's semantics carry
`Disabled`, in the same way the role and name are published.

**Not worked around here.** There is nothing a consumer can do: the DOM is Compose's, built inside
the framework, and a host has no seam to add an attribute to it.


---

## 4. Kotlin/JavaScript emits source-map positions past the end of the file they name

**Where:** Kotlin 2.3.20, Kotlin/JavaScript IR, production webpack build with source maps.

**What happens:** a stack frame for a `kotlin.error(...)` call resolves, through the build's own
source map, to a line and column that do not exist in the source the map names.

`samples/slice-screens/src/jsMain/kotlin/dev/dogwood/slice/CrashScreen.kt` is 70 lines long and its
longest line is 96 characters. The frame for its `error(...)` call at generated column 419445 maps
to:

```
CrashScreen.kt:71:436
```

Line 71 does not exist. Column 436 does not exist on any line of the file. The map's own
`sourcesContent` entry for that source confirms both.

The mappings immediately around it, decoded from the `mappings` field by hand:

```
gencol=419397 -> CrashScreen.kt:65:5
gencol=419438 -> CrashScreen.kt:71:430     <- not a position in the file
gencol=419444 -> CrashScreen.kt:71:436     <- not a position in the file (the frame lands here)
gencol=419447 -> CrashScreen.kt:71:466     <- not a position in the file
gencol=419450 -> CrashScreen.kt:68:11      <- the `error(...)` call
```

So a correct source-map consumer, following the specification exactly, reports a position that does
not exist — while the true position is six bytes further into the bundle. The apparent cause is that
`error(...)` is inlined and the synthesised code carries a position from somewhere other than the
call site; whatever the cause, the emitted position is outside the named file.

**Why it matters:** this is the frame a crash report exists to produce. A team symbolicating a
production crash gets a file name that is right and a line number that is wrong and cannot exist,
which is worse than no line number: it sends somebody to look at a place, and the place is not
there.

**Reproduction:**

```
./gradlew :samples:web-guest:jsBrowserProductionWebpack
tools/symbolicate/resolve.py \
  engine/samples/web-guest/build/kotlin-webpack/js/productionExecutable/guest-kotlin.js.map \
  <a stack containing a frame for CrashScreen.kt's error(...) call>
```

**Workaround in this repository:** `tools/symbolicate/resolve.py` validates each resolved position
against the map's embedded copy of the source — both the line count and that line's length — and
when a mapping falls outside the file it prints the specification's answer, marks it, and names the
nearest in-range mapping for the same source. It does not silently substitute: inventing a plausible
answer is how a symbolicator becomes something nobody can trust.

---

## 5. Compose Multiplatform's accessibility bridge, on the web and on iOS (drafted 2026-09-16)

**Status: drafted, not filed.** The owner's standing instruction is that upstream reports stay
here rather than being opened against other projects' trackers.

Found by running `plans/material3-proof.md`'s Material catalogue -- a screen of generated Material 3
bindings -- through each client's own accessibility layer. None of these is about the generated
bindings: the same components, composed from the same wire, toggle and confirm under
`runComposeUiTest` on the Java Virtual Machine, WebAssembly and the iOS simulator. What differs is
what the platform bridge publishes.

**a. Web: selection controls are unnamed and stateless.** `androidx.compose.material3.Checkbox`,
`Switch` and `RadioButton` reach Chrome's accessibility tree as `button` nodes with an empty name
and no properties -- no `checkbox`, `switch` or `radio` role and no checked state. A screen reader
is told neither what the control is nor whether it is on. Setting a `contentDescription` on the
control gives it a name; nothing observed gives it a state or a role.

*Reproduce:* any Compose Multiplatform 1.10.3 wasm page with a `Switch`, read with
`Accessibility.getFullAXTree` over the DevTools protocol and `--force-renderer-accessibility`.

**b. Web: a Slider publishes no node at all.** Not an unnamed one -- none. There is nothing for an
assistive technology to find or to move.

**c. Web: a dialog blocks input from outside the process.** While a Compose dialog is open, the
page answers neither a click synthesised on an accessibility node, nor a mouse event dispatched at
that node's own box model, nor the Escape key. The accessibility tree holds the dialog's own names
and nothing else, and the page cannot be driven further. Whether a real pointer on a real display
behaves differently is not something this environment can settle, and the drill says so rather than
claiming a defect it did not watch.

**d. iOS: a Switch announces no value.** Under VoiceOver a Material 3 `Switch` publishes its label
and the button trait, and an empty `accessibilityValue`. It can be activated and it does toggle --
the payload's own state changes -- but VoiceOver is not told whether it is on.

**e. iOS: a Slider is not adjustable.** No element announces itself as the slider, so there is
nothing for `accessibilityIncrement` to move.

Each of these is a `SKIP` carrying its observation in `plans/conformance.md`'s `M` family, on the
precedent report 3 set for Android's disabled-state announcements: a red cell that can never go
green teaches people to ignore the column.

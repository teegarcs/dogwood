# Upstream reports, drafted and not filed

Two toolchain defects found while building the Web host. Both are reproduced, both have a
workaround in this repository, and **neither has been filed**.

They are drafted rather than submitted deliberately: filing a public bug report publishes this
project's name, a reproduction, and an implicit claim about a vendor's product. That is an
outward-facing action and it belongs to a person, not to an automated change. The text below is
ready to paste; the URLs go into the ADRs that promise them once someone files them.

Until then, `ADR-032` and `ADR-033` say "drafted, not filed" rather than "reported upstream", which
is the honest tense.

| # | Defect | Reproduction | Workaround in this repo |
|---|---|---|---|
| 1 | `wasm-opt` GUFA miscompiles `String.toCharArray()` | `tools/web-weight/bridge/run.sh` | `--gufa` filtered from the pass list |
| 2 | Incremental Kotlin/Wasm klib crash | any rebuild after an edit | `kotlin.incremental.js.klib=false` |

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

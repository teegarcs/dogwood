# Web page-weight harness

`roadmap.md` Phase 5 gates the Web host on page weight, and until now quoted a community figure for
it: *"community-measured Skiko WebAssembly payloads run ~8 MB uncompressed / ~3 MB compressed —
page-weight viability is unproven and gates this phase."*

A figure nobody here measured cannot gate anything. This harness measures it.

## What it builds

Two modules render a trivial screen and differ only in what they link, so the difference between
their distributions is attributable rather than inferred.

- **`:floor`** — `compose.runtime`, `compose.foundation`, `compose.ui`. The smallest page any
  Compose Multiplatform host can be. If this is already too heavy, nothing a Dogwood host adds can
  rescue it.
- **`:material`** — the same, plus `compose.material3`. This is what a Dogwood host actually links:
  the design-system bindings in `dogwood-host` are written in terms of `MaterialTheme`, `Text`,
  `Button` and their relatives.

## Running it

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # Gradle 8.14 will not run on JDK 25
./gradlew :floor:wasmJsBrowserDistribution :material:wasmJsBrowserDistribution
./measure.sh
```

`measure.sh` needs `brotli` on the path (`brew install brotli`).

Versions are pinned in `gradle/libs.versions.toml` to match `engine/gradle/libs.versions.toml`. A
page-weight number is only meaningful for the toolchain that produced it, so keep them in step.

## Results — Compose Multiplatform 1.10.3, Kotlin 2.3.20, Skiko 0.9.37.4

Bytes a first-time visitor downloads. The source map is excluded: browsers fetch it only when
developer tools are open, so counting it would overstate the page.

| Configuration | Raw | gzip -9 | brotli -q 11 |
|---|---|---|---|
| `:floor` (runtime + foundation + ui) | 9.54 MB | 3.50 MB | **2.77 MB** |
| `:material` (+ material3) | 10.23 MB | 3.69 MB | **2.92 MB** |

Broken down, for `:material`:

| Artifact | Raw | brotli | Share of raw |
|---|---|---|---|
| `skiko.wasm` | 8.24 MB | 2.48 MB | **81%** |
| application WebAssembly | 1.43 MB | 0.36 MB | 14% |
| `app.js` glue | 0.56 MB | 0.08 MB | 5% |

### What this says

**The community figure was right about compressed and understated raw.** ~3 MB compressed matches;
~8 MB uncompressed is the Skiko blob alone, not the page, which is 10.23 MB.

**Four fifths of the page is a prebuilt binary that Dogwood cannot shrink.** `skiko.wasm` ships as
a build artifact inside `skiko-js-wasm-runtime-0.9.37.4.jar` at exactly 8,642,989 bytes. It is not
produced by the Kotlin compiler here, so neither the Binaryen pass nor dead-code elimination
touches it, and it is byte-identical between the two modules. Writing less Dogwood code does not
make this page meaningfully smaller.

**The Binaryen optimisation did run.** `compileProductionExecutableKotlinWasmJsOptimize` is part of
`wasmJsBrowserDistribution`, so these are optimised production numbers rather than debug ones.

**Material 3 costs 0.69 MB raw / 0.15 MB brotli** over the floor. That is a useful scale for
estimating Dogwood's own host code, which is comparable in size to a design system — an estimate,
not a measurement.

## A second question, in `results/bridge.md`

`:bridge` is a third module in this build, and it does not weigh anything. It measures what a
per-frame tree-diff costs to cross from JavaScript into Kotlin/WebAssembly, which is the web
counterpart of the mobile Zipline `CallChannel` measurement. It links neither Compose nor Skiko,
and that is what makes it runnable in a real headless browser when the modules above are not.

Run it with `./bridge/run.sh`; the write-up is [`results/bridge.md`](results/bridge.md).

## What is NOT measured, and why

**Time to first frame.** Bytes are a proxy for waiting, and the thing that actually gates adoption
is how long a user stares at nothing. This harness does not measure it, and the gap is real.

Two approaches were tried and both were rejected rather than reported badly:

- **A real browser.** Compose Multiplatform draws through Skiko, which needs a WebGL context, and
  headless Chrome refuses one by default. Working around that with software rendering measures
  SwiftShader rather than a user's machine, and the sandbox this was run in cannot bind a listening
  socket to serve the page at all.
- **`WebAssembly.compile` under Node.** This looked promising and is a trap. V8 compiles WebAssembly
  lazily, so the default figure measures decoding, not compilation — it reported 8 ms for 8.24 MB.
  Forcing eager compilation with `--no-wasm-lazy-compilation` makes the asynchronous `compile` never
  settle on Node's main thread, and the synchronous `new WebAssembly.Module` then reports 1–3 ms
  because V8 caches compiled modules by content across iterations. Every one of those numbers is
  wrong in a way that flatters the result.

Measuring this properly means a real browser on a real machine with a graphics context, ideally
throttled to a representative network and CPU. **Phase 5 should not be considered unblocked until
someone does it.**

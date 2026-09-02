# What a tree-diff costs to cross into Kotlin/WebAssembly

Dogwood's guest program emits a per-frame tree-diff as a positional JavaScript Object Notation
(JSON) string, and the host renders it with Compose. On mobile that string crosses an embedded
QuickJS interpreter boundary through Zipline's `CallChannel.call(String): String`, and the measured
breakdown there is **99.4% guest-side encoding, 1.0% transport**. Positional JSON wins on mobile
because `JSON.stringify` is native C inside QuickJS while a binary encoder would be interpreted
Kotlin.

**On web that analysis is void.** The planned web architecture has no QuickJS, no Zipline and no
`CallChannel`: the guest is ordinary JavaScript in the browser's own just-in-time (JIT) compiled
engine, and the host is Kotlin/WebAssembly. Every premise that decided the mobile format is gone.
This harness measures what replaces it.

## What it builds

`:bridge` is a Kotlin/WebAssembly module that links **neither Compose nor Skiko**. That is not an
oversight, it is the point. `README.md` records that a browser measurement was previously abandoned
because Compose draws through Skiko, Skiko needs a WebGL context, and headless Chrome refuses one.
A bridge measurement needs no graphics at all, so dropping both makes a real-browser run possible.
The only dependency is the Kotlin/WebAssembly standard library.

The module exports one function per candidate transport. A page generates representative diffs,
times every export against them, and posts the results back.

## Running it

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # Gradle 8.14 will not run on JDK 25
./bridge/run.sh
```

`run.sh` builds the module, stages two variants of it (see *The production optimiser is wrong*
below), serves each over loopback Hypertext Transfer Protocol (HTTP), drives headless Chrome at
it, and writes `results/bridge-default.json` and `results/bridge-gufa-safe.json`.

The page is served rather than opened as a `file://` Uniform Resource Locator (URL) for two
reasons. `WebAssembly.instantiateStreaming` rejects anything not served as `application/wasm`. And
the server sets `Cross-Origin-Opener-Policy` and `Cross-Origin-Embedder-Policy` so the page is
cross-origin isolated, which is what raises Chrome's `performance.now()` resolution from 100
microseconds to 5.

## Method

**Browser.** Chrome 152.0.7977.65, headless (`--headless=new`), on macOS 26.5.1, Apple silicon,
8 cores. The page confirmed `crossOriginIsolated === true` in every run reported here.

**Payloads.** Positional JSON in the shape the guest emits — a frame identifier followed by a list
of changes, each change a short array of small integers with a short string roughly one time in
eight, e.g. `[1,[[0,1,2],[1,1,1,"text"],[3,0,1,1,0]]]`. Three sizes, from a seeded generator so two
runs are comparable:

| Payload | Changes | JSON bytes | Flat 32-bit integers |
|---|---:|---:|---:|
| small (steady state) | 5 | 106 | 30 |
| medium | 240 | 5,013 | 1,504 |
| large (screen open) | 772 | 16,002 | 4,844 |

The payloads are pure ASCII, so their character count and byte count are equal, which keeps the
16-bit and 8-bit paths comparable.

**Timing.** Every scenario is warmed for 300 milliseconds, then a batch size *K* is calibrated so
one batch takes at least 4 milliseconds, then 150 batches are timed. Reported figures are
percentiles of the **per-operation time within a batch**, in microseconds. Batching is necessary
because the smallest operations here are under 10 nanoseconds and even an isolated page only
resolves 5 microseconds. The consequence is stated plainly: for the small payload, where *K* runs
to tens of thousands, p95/p99/max describe variation between batches and smooth over single-call
tails. *K* is reported in every table so the reader can see how much smoothing happened. Every
return value is accumulated into a sink the page reads at the end, which is what stops V8 deleting
the work being measured.

**Correctness gate.** All three textual parsers must return an identical checksum for the same
payload before any number from a run is used. This gate is what caught the compiler bug below;
without it this document would have reported a fabricated 4x win for path 1b.

**Reproducibility.** The two build variants are separate browser runs, and 42 of their rows are
untouched by the difference between them, so they double as a repeat measurement. Median deviation
between runs is **1.3%**, and 41 of 42 rows agree within 5%. The one that does not is the pure
JavaScript `flatten tree to Int32Array` scenario at 15.7%, which allocates and is therefore
garbage-collection sensitive; it was the worst row in earlier runs too. **Treat differences under
about 5% as noise, and treat the flatten row — and therefore path 4's JavaScript half — as good to
roughly 15%.** Path 4 wins by 2.8x, far outside that.

## Results

Kotlin 2.3.20, Binaryen 125, Chrome 152. All figures microseconds, `gufa-safe` build.

### small (steady state) — 5 changes, 106 bytes

| Scenario | p50 | p95 | p99 | max | batch K |
|---|---:|---:|---:|---:|---:|
| js: `JSON.stringify(tree)` | 0.131 | 0.133 | 0.137 | 0.138 | 32808 |
| js: `TextEncoder.encode(json)` | 0.423 | 0.867 | 9.194 | 9.283 | 10956 |
| js: `JSON.parse(json)` | 0.245 | 0.248 | 0.250 | 0.251 | 16641 |
| js: flatten tree to `Int32Array` | 0.299 | 0.348 | 0.363 | 0.686 | 22187 |
| **1** string: pass only | 0.009 | 0.009 | 0.009 | 0.009 | 461143 |
| **1a** string: pass + parse in place | 0.304 | 0.307 | 0.315 | 0.318 | 16901 |
| **1b** string: pass + bulk copy + parse | 0.170 | 0.174 | 0.175 | 0.176 | 27041 |
| **1c** string: pass + bulk copy only | 0.065 | 0.066 | 0.066 | 0.066 | 66561 |
| 1a′ parse in place, no crossing | 0.304 | 0.309 | 0.312 | 0.316 | 17169 |
| 1b′ parse copied array, no crossing | 0.111 | 0.115 | 0.116 | 0.116 | 45423 |
| **2** bytes: copy in, no parse | 0.046 | 0.047 | 0.048 | 0.048 | 112013 |
| **2a** bytes: copy in + parse | 0.144 | 0.148 | 0.151 | 0.151 | 36983 |
| **2b** bytes: `encodeInto` + parse | 0.292 | 0.416 | 0.525 | 0.529 | 17841 |
| **3a** `JsArray` walk, flat, no dispatch | 0.680 | 0.688 | 0.690 | 0.692 | 8321 |
| **3b** `JsArray` walk, nested + dispatch | 0.802 | 0.814 | 0.823 | 0.826 | 6412 |
| **4** `Int32Array` into memory + read | 0.051 | 0.052 | 0.052 | 0.053 | 100616 |

### medium — 240 changes, 5,013 bytes

| Scenario | p50 | p95 | p99 | max | batch K |
|---|---:|---:|---:|---:|---:|
| js: `JSON.stringify(tree)` | 6.316 | 7.137 | 7.550 | 7.589 | 1041 |
| js: `TextEncoder.encode(json)` | 3.115 | 3.637 | 3.776 | 3.800 | 1687 |
| js: `JSON.parse(json)` | 9.616 | 9.769 | 10.269 | 10.355 | 1041 |
| js: flatten tree to `Int32Array` | 4.121 | 4.207 | 4.280 | 4.280 | 1041 |
| **1** string: pass only | 0.009 | 0.009 | 0.009 | 0.009 | 532481 |
| **1a** string: pass + parse in place | 17.854 | 18.008 | 18.123 | 18.142 | 261 |
| **1b** string: pass + bulk copy + parse | 7.743 | 7.810 | 7.882 | 7.963 | 1041 |
| **1c** string: pass + bulk copy only | 2.530 | 2.574 | 2.611 | 2.611 | 2057 |
| 1a′ parse in place, no crossing | 17.853 | 17.983 | 18.285 | 18.401 | 347 |
| 1b′ parse copied array, no crossing | 5.365 | 5.423 | 5.457 | 5.457 | 1040 |
| **2** bytes: copy in, no parse | 0.101 | 0.109 | 0.113 | 0.116 | 53092 |
| **2a** bytes: copy in + parse | 5.134 | 5.375 | 5.576 | 5.749 | 1041 |
| **2b** bytes: `encodeInto` + parse | 7.181 | 7.243 | 7.402 | 9.822 | 1041 |
| **3a** `JsArray` walk, flat, no dispatch | 32.416 | 32.718 | 33.020 | 33.020 | 149 |
| **3b** `JsArray` walk, nested + dispatch | 36.846 | 37.081 | 37.383 | 38.154 | 149 |
| **4** `Int32Array` into memory + read | 0.587 | 0.593 | 0.600 | 0.601 | 8905 |

### large (screen open) — 772 changes, 16,002 bytes

| Scenario | p50 | p95 | p99 | max | batch K |
|---|---:|---:|---:|---:|---:|
| js: `JSON.stringify(tree)` | 20.562 | 21.239 | 21.398 | 21.484 | 347 |
| js: `TextEncoder.encode(json)` | 8.381 | 9.841 | 11.407 | 11.729 | 1041 |
| js: `JSON.parse(json)` | 31.121 | 31.695 | 31.983 | 32.011 | 174 |
| js: flatten tree to `Int32Array` | 13.876 | 14.813 | 15.216 | 15.259 | 347 |
| **1** string: pass only | 0.009 | 0.009 | 0.009 | 0.009 | 532481 |
| **1a** string: pass + parse in place | 58.793 | 59.253 | 59.483 | 60.632 | 87 |
| **1b** string: pass + bulk copy + parse | 25.409 | 26.226 | 26.851 | 27.236 | 208 |
| **1c** string: pass + bulk copy only | 7.432 | 7.539 | 7.660 | 7.974 | 701 |
| 1a′ parse in place, no crossing | 58.895 | 59.211 | 59.474 | 59.579 | 95 |
| 1b′ parse copied array, no crossing | 17.925 | 18.055 | 18.300 | 18.473 | 347 |
| **2** bytes: copy in, no parse | 0.199 | 0.214 | 0.220 | 0.222 | 22187 |
| **2a** bytes: copy in + parse | 16.657 | 16.744 | 16.801 | 16.873 | 347 |
| **2b** bytes: `encodeInto` + parse | 22.981 | 23.769 | 24.558 | 24.885 | 260 |
| **3a** `JsArray` walk, flat, no dispatch | 105.000 | 105.769 | 107.212 | 108.173 | 52 |
| **3b** `JsArray` walk, nested + dispatch | 118.095 | 119.643 | 121.190 | 142.619 | 42 |
| **4** `Int32Array` into memory + read | 1.830 | 1.863 | 1.881 | 2.210 | 2842 |

### End to end

The rows above are pieces. This is what a frame actually costs, from the guest holding a diff as a
JavaScript array to the host holding it as Kotlin data. Composed from the p50 figures above; the
percentage is of a 16.7 millisecond frame at 60 hertz.

| Pipeline | small | medium | large | large, as a frame |
|---|---:|---:|---:|---:|
| **1a** stringify → pass String → parse in place | 0.435 | 24.170 | 79.355 | 0.48% |
| **1b** stringify → pass String → bulk copy → parse | 0.301 | 14.059 | 45.971 | 0.28% |
| **2a** stringify → `TextEncoder.encode` → copy in → parse | 0.698 | 14.566 | 45.600 | 0.27% |
| **2b** stringify → `encodeInto` linear memory → parse | 0.423 | 13.497 | 43.543 | 0.26% |
| **3a** walk flat `JsArray`, no encoding at all | 0.680 | 32.416 | 105.000 | 0.63% |
| **3b** walk nested `JsArray`, no encoding at all | 0.802 | 36.846 | 118.095 | 0.71% |
| **4** flatten to `Int32Array` → copy in → read | **0.350** | **4.708** | **15.706** | **0.09%** |

## What this says

**Passing a String across the boundary is free, and stays free at every size.** Path 1 with no
parse costs 0.009 microseconds for 106 bytes and for 16,002 bytes alike — identical to three decimal
places across a 150-fold size increase. That is the cost of an empty exported call, not of
a copy.

This is not luck. In Kotlin/WebAssembly 2.3.20 a `kotlin.String` **is** a JavaScript string:
`String` is declared as a wrapper over a `JsString`, which is an `externref`
([`libraries/stdlib/wasm/js/builtins/kotlin/String.kt`](https://github.com/JetBrains/kotlin/blob/v2.3.20/libraries/stdlib/wasm/js/builtins/kotlin/String.kt)).
Kotlin's browser output is instantiated with `{ builtins: ['js-string'] }`, so `length` becomes the
engine's native `length` builtin, `s[i]` becomes native `charCodeAt`, and `toCharArray()` becomes
one native `intoCharCodeArray` bulk copy
([`libraries/stdlib/wasm/js/internal/JsStringBuiltins.kt`](https://github.com/JetBrains/kotlin/blob/v2.3.20/libraries/stdlib/wasm/js/internal/JsStringBuiltins.kt),
[`StringWasmCharArray.kt`](https://github.com/JetBrains/kotlin/blob/v2.3.20/libraries/stdlib/wasm/js/src/kotlin/util/StringWasmCharArray.kt)).
**There is no transcoding copy to measure, because there is no transcoding.** The premise that
motivated this experiment — "Kotlin/Wasm must transcode the JS string into linear memory" — is
false for this compiler version.

**The cost did not vanish, it moved into the parse.** Per character of the large payload:

| Operation | ns per character |
|---|---:|
| copy bytes into linear memory (`Uint8Array.set`) | 0.012 |
| bulk copy string to character array (`intoCharCodeArray`) | 0.46 |
| parse from linear memory bytes | 1.03 |
| parse from a WebAssembly garbage-collected character array | 1.12 |
| parse in place off the JavaScript string (`charCodeAt` per character) | 3.68 |

Reading a JavaScript string one character at a time from Kotlin costs about **3.3x** what reading a
WebAssembly array costs. The 3.3x ratio is *measured*; the reason for it is *inferred* — a builtin call
that V8 did not inline would cost far more than three array reads, so the engine is evidently
inlining it and the residue is the bounds check `String.get` performs on every access. Either way
it is the wrong loop to write. Any host that parses should copy in bulk
first: 1b beats 1a by 1.7x on the full pipeline at 16 KB, and by 2.3x on the WebAssembly side alone
(25.409 against 58.793 microseconds), for a one-line change and no protocol change whatsoever.

**Strings and bytes are a tie.** At 16 KB, 1b costs 45.971 microseconds and 2b costs 43.543. That
is a 5.3% gap, at the edge of the noise floor established above. The "stream bytes" option that is
impossible on mobile and trivial here **buys nothing measurable.** Transport is not the reason:
copying 16 KB into linear memory takes 0.199 microseconds, 0.4% of the pipeline. Both paths are
dominated by encode plus parse, and encoding to Unicode Transformation Format 8-bit (UTF-8) is
simply a different way to spend the same work.

One genuine advantage of the byte path is tail behaviour, not throughput. `TextEncoder.encode`
allocates a fresh `Uint8Array` per call, and on the small payload its p99 is 9.194 microseconds
against a p50 of 0.423 — a **22x tail** from garbage collection. `encodeInto` writing straight into
linear memory allocates nothing and has a p99 of 0.525. If a byte path is chosen, it must be
`encodeInto`, never `encode`.

**The structured object path is possible, copy-free, and the worst option measured.** Kotlin/Wasm's
`JsArray` does let Kotlin walk a JavaScript array with nothing serialised and nothing copied. It is
also 2.4x slower than the best textual path at 16 KB, and 6.7x slower than path 4. The reason is
structural: `JsArray` is an external type, so `a[i]` is an imported JavaScript function call rather
than a memory read. At 21.7 nanoseconds per element for the flat, dispatch-free variant, the cost
is roughly two boundary crossings per element — one to fetch, one to unbox. Adding the type
dispatch a real positional diff needs (`Array.isArray`, `typeof`) costs a further 12%. **Avoiding
serialisation entirely is not the same as being fast**, and here it is exactly backwards: the
per-element boundary is more expensive than serialising, copying, and parsing.

**The only material win is abandoning text.** Flattening the diff into an `Int32Array` and copying
it into linear memory costs 15.706 microseconds at 16 KB against 43.543 for the best textual path —
**2.8x**, and 5.1x against the mobile-style path 1a. The transport half of that is almost free
(1.830 microseconds); the cost is the 13.876-microsecond JavaScript-side flatten, which is itself
33% cheaper than `JSON.stringify`. This is a bound, not a proposal: it presumes strings in the diff
are replaced by indices into a side table, and this harness does not model the cost of maintaining
that table. **A protobuf or Concise Binary Object Representation (CBOR) encoder was not measured**;
path 4 is a fixed-width integer array, which is the cheapest such format can possibly be.

**The mobile breakdown inverts.** For the same logical work:

| | mobile (QuickJS + Zipline) | web (V8 + Kotlin/Wasm), path 1b at 16 KB |
|---|---:|---:|
| guest-side encoding | 99.4% | 44.7% |
| transport | 1.0% | **0.02%** |
| host-side decoding | — | 55.3% |

On mobile, encoding is everything and the transport is a rounding error. On web the transport is a
rounding error *of the rounding error*, and decoding — a cost that barely registered on mobile —
becomes the larger half. **The mobile conclusion survives as an outcome and dies as an argument.**
Positional JSON is still a defensible web format, but not for any of the three reasons it won on
mobile.

**And all of it is small.** The worst pipeline measured, at the largest payload, is 118 microseconds
— **0.71% of a 16.7 millisecond frame**. The best is 0.09%. In steady state every option is under
0.9 microseconds, or 0.005% of a frame. The gap between the best and worst web transport, at
screen-open size, is under 100 microseconds once per screen. Whatever decides this question, it
should not be frame budget.

## The production optimiser is wrong, and it is silent about it

`String.toCharArray()` **returns an array of the correct length filled with zeros** in a default
Kotlin/WebAssembly 2.3.20 production build.

This is not a Dogwood bug and not a browser bug. Kotlin finishes a production build by running
Binaryen's `wasm-opt`, and the Gradle task logs its exact pass list:

```
wasm-opt --enable-gc --enable-reference-types --enable-exception-handling --enable-bulk-memory
         --enable-nontrapping-float-to-int --closed-world
         --no-inline=kotlin.wasm.internal.throwValue
         --no-inline=kotlin.wasm.internal.getKotlinException
         --no-inline=kotlin.wasm.internal.jsToKotlinStringAdapter
         --inline-functions-with-loops --traps-never-happen --fast-math --type-ssa
         -O3 -O3 --gufa -O3 --type-merging -O3 -Oz
```

Bisecting that list against a probe that copies a string both ways and compares:

| Pass list | Bulk copy correct? |
|---|---|
| none (unoptimised) | yes |
| `--gufa` alone | yes |
| `--gufa -O3` | yes |
| `-O3` alone, `-Oz` alone | yes |
| `-O3 -O3 -O3 --type-merging -O3 -Oz` (full list, no `--gufa`) | yes |
| **`-O3 --gufa`** | **no** |
| **full list as shipped** | **no** |
| `-O3 --gufa` **without** `--closed-world` | yes |

The failure needs `--closed-world`, and it needs an inlining pass to run *before* `--gufa`. The
mechanism follows from that. `String.getChars()` allocates a WebAssembly garbage-collected
`array i16` and fills it by calling the imported `wasm:js-string` `intoCharCodeArray` builtin, which
writes into the array it is handed. Once `-O3` has inlined that into one function, Grand Unified
Flow Analysis can see the allocation and the import call together — and under `--closed-world` it
does not model an import as able to mutate a garbage-collected object. It concludes the array only
ever holds its default value and constant-folds every read to zero.

**It is context-sensitive, which makes it worse.** Adding an unrelated exported function that also
calls `toCharArray()` made the failure disappear from the same build; removing it brought the
failure back, repeatably. So this is not a stable "this function is broken" that a test suite pins
down once. It can appear and disappear across edits that have nothing to do with the affected code.

**Consequences for this harness.** `results/bridge-default.json` is a real production build and its
paths 1b, 1b′ and 1c measure a parse over an all-zero array — 4.3 microseconds instead of 17.9 at
16 KB, a fabricated 4x win. `run.sh` therefore also builds `gufa-safe`, the identical pass list
minus `--gufa`, and every number quoted above comes from that. Removing the pass costs 231 bytes of
WebAssembly (6,422 → 6,653) and, on the 42 rows it does not affect, changes nothing measurable
(median deviation 1.2%).

**Consequences for Dogwood.** A web host runs on the default pass list, so this is live. Two things
follow, and only one of them is settled:

1. **Settled.** Any Dogwood web code path that bulk-copies a JavaScript string into a Kotlin
   `CharArray` cannot be trusted under a default production build, and the harness reproduces this
   on demand. This deserves an upstream report against Binaryen or the Kotlin Gradle plugin.
2. **Not settled.** The blast radius is unknown. A probe exercising eight further text operations
   — `StringBuilder`, `substring`, `split`, character iteration, UTF-8 round trips, number
   formatting, and both bulk-copy directions — reported all eight correct. **That result is worth
   very little**, because adding the probe to the module is itself what suppressed the original
   failure. It is evidence about the probed build, not about the unprobed one. That probe is
   deliberately **not** part of `:bridge` for exactly that reason — leaving it in would have made
   the harness stop reproducing the bug it exists to document. Establishing what else `--gufa`
   breaks, in particular whether Compose Multiplatform's text stack is affected, needs a
   deliberate investigation this harness did not do.

## What is NOT measured, and why

**Any browser but Chrome.** Every figure here is Chrome 152 on Apple silicon. Kotlin/WebAssembly
needs WebAssembly garbage collection, which Safari and Firefox now ship, but the two behaviours
that dominate these results — how well V8 inlines the JavaScript-string builtins, and how expensive
an imported call from WebAssembly is — are engine-specific and there is no reason to expect them to
transfer. The `JsArray` result in particular could move a long way on another engine. Safari cannot
be driven headlessly here, so this was not attempted rather than attempted badly.

**Anything but a fast desktop machine.** These are 8 performance-class cores with no throttling. A
mid-range Android phone running Chrome would change every absolute number and could change the
ordering, because the paths differ in how much they lean on the JIT versus on native builtins.
Nothing here should be quoted as a mobile-web figure.

**The guest's own cost of producing the diff.** Every pipeline above starts from a JavaScript array
that already exists. Whatever the guest spends building it is common to all seven paths, so it
cancels for comparison purposes — but it does not cancel for a frame budget, and it is not here.

**A real binary codec.** Path 4 is a hand-rolled flat `Int32Array`, the cheapest a binary format can
be. Protobuf and CBOR libraries carry framing, varints and schema handling that this does not, and
they were not measured. Path 4 bounds them from below; it does not stand in for them.

**Single-call tail latency at small payloads.** Batching is what makes sub-microsecond operations
measurable at all against a 5-microsecond clock, and the price is that the small payload's tail
percentiles are batch means. A true single-call p99 at 106 bytes is not available from this harness
and would need a different instrument.

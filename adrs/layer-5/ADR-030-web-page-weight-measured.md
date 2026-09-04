# ADR-030: What a Compose Multiplatform Web Page Actually Weighs

**Date:** 2026-09-02
**Status:** Accepted

## 1. Context & Problem Statement

`roadmap.md` Phase 5 makes the Web host the second shipping target, and gates it on one number it
had never measured:

> community-measured Skiko WebAssembly payloads run ~8 MB uncompressed / ~3 MB compressed —
> page-weight viability is **unproven** and gates this phase.

A figure nobody here measured cannot gate anything, and this project's first rule is that a claim
presented as fact needs proof. So it was measured, against the same toolchain the engine is pinned
to.

## 2. Decision

**Page weight does not block Phase 5. Startup time is still unmeasured, and does.**

The harness is committed at [`tools/web-weight/`](../../tools/web-weight/) with its results, so the
number can be re-taken whenever Compose Multiplatform or Skiko moves rather than being quoted from
this document forever.

## 3. Rationale & Research

### The measurement

Compose Multiplatform 1.10.3, Kotlin 2.3.20, Skiko 0.9.37.4. Two modules render a trivial screen
and differ only in what they link, so the difference between them is attributable rather than
inferred. Bytes are what a first-time visitor downloads; the source map is excluded, because
browsers fetch it only when developer tools are open.

| Configuration | Raw | gzip -9 | brotli -q 11 |
|---|---|---|---|
| Runtime + foundation + user interface | 9.54 MB | 3.50 MB | **2.77 MB** |
| The same, plus Material 3 | 10.23 MB | 3.69 MB | **2.92 MB** |

The second row is the one that matters: `dogwood-host`'s design-system bindings are written in
terms of `MaterialTheme`, `Text`, `Button` and their relatives, so a Dogwood web host links
Material 3.

Broken down:

| Artifact | Raw | brotli | Share of raw |
|---|---|---|---|
| `skiko.wasm` | 8.24 MB | 2.48 MB | **81%** |
| Application WebAssembly | 1.43 MB | 0.36 MB | 14% |
| JavaScript glue | 0.56 MB | 0.08 MB | 5% |

These are optimised production numbers:
`compileProductionExecutableKotlinWasmJsOptimize` — the Binaryen `wasm-opt` pass — is part of
`wasmJsBrowserDistribution` and was confirmed to run.

### What the numbers say

**The community figure was right about compressed and understated raw.** About 3 MB compressed
matches what was measured. About 8 MB uncompressed turns out to be the Skiko blob *alone*, not the
page, which is 10.23 MB.

**Four fifths of the page is a prebuilt binary Dogwood cannot shrink.** `skiko.wasm` ships as a
build artifact inside `skiko-js-wasm-runtime-0.9.37.4.jar` at exactly 8,642,989 bytes. It is not
compiler output here, so neither Binaryen nor dead-code elimination touches it, and it is
byte-identical between the two modules. This has a consequence for how Dogwood is built: **writing
less host code does not meaningfully shrink this page.** Adding all of Material 3 costs 0.69 MB raw
and 0.15 MB brotli, and Dogwood's own host code is of that order — so the difference between a
minimal Dogwood web host and a generous one is noise against a fixed 8.24 MB.

**2.9 MB compressed is heavy but not disqualifying.** It is the scale of a large single-page
application, it is cached after the first visit, and it does not grow with the product. The
qualitative point is that it is a *fixed entry toll* rather than a cost that scales with how much
of the design system a team registers, which is the opposite of how page weight usually behaves and
is worth knowing before anyone tries to optimise their way out of it.

### What was not measured, and why it still gates the phase

**Time to first frame.** Bytes are a proxy for waiting, and waiting is what actually decides whether
a web host is adoptable. Two approaches were tried and both were rejected rather than reported
badly:

- **A real browser.** Compose Multiplatform draws through Skiko, which needs a WebGL context;
  headless Chrome refuses one by default. Forcing software rendering measures SwiftShader rather
  than a user's machine, and the sandbox this ran in cannot bind a listening socket to serve the
  page at all.
- **`WebAssembly.compile` under Node.** This is a trap, and it is recorded so the next person does
  not fall into it. V8 compiles WebAssembly lazily, so the default figure measures decoding, not
  compilation — it reported 8 milliseconds for 8.24 MB. Forcing eager compilation with
  `--no-wasm-lazy-compilation` makes the asynchronous `compile` never settle on Node's main thread,
  and the synchronous `new WebAssembly.Module` then reports 1–3 milliseconds because V8 caches
  compiled modules by content across iterations. Every one of those numbers is wrong in the
  direction that flatters the result.

The harness leaves a `#dogwood-first-frame` probe in the page, reported from inside the composition
on the first frame Compose paints, so that measuring this needs a browser and a network — not new
code.

## 4. Unstated Assumptions

- **A Dogwood web host links Material 3.** True today, because the design-system bindings are
  implemented in terms of it. A host that registered components implemented without Material 3
  would save about 0.15 MB compressed, which does not change any decision.
- **Skiko's size is a fixed input, not a variable.** It is a JetBrains build artifact. Dogwood has
  no lever on it beyond choosing a Skiko version, and no reason to expect one.
- **The measurement machine is not the user's.** These are byte counts, which are machine
  independent. Nothing here says anything about how long a phone takes to compile them, which is
  exactly the gap above. **Half of that gap is now closed**
  ([ADR-038](ADR-038-first-frame-is-transfer-bound.md)): under Chrome's own throttling, the first
  frame arrives in 17.4 s on Fast 3G and 3.2 s on 4G, against 135 ms unthrottled — so transfer
  outweighs everything the host does by about a hundred to one, and page weight is the only lever
  that matters. What remains open is the *compilation* half on a low-powered device, which the
  throttle does not touch.
- **Brotli is what actually ships.** Every mainstream content delivery network serves it and every
  current browser accepts it, so the brotli column is the honest one; gzip is quoted for hosts that
  have not turned it on.

## 5. Updated Documents

- [Roadmap](../../roadmap.md) — Phase 5's page-weight gate now cites a measurement rather than a
  community figure, and names the startup measurement that remains.
- [`tools/web-weight/README.md`](../../tools/web-weight/README.md) — the harness and its results.

# ADR-045: Web Page Weight — Where the Levers Are, and Which of Them Pay

**Date:** 2026-09-06
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-038](ADR-038-first-frame-is-transfer-bound.md) established that the web profile's first frame
is **transfer-bound**: the number moves with the connection and barely with the machine, so page
weight is the only lever the profile has. [ADR-030](ADR-030-web-page-weight-measured.md) measured what a
Compose Multiplatform page costs, and `plans/conformance.md` holds the shipped figure in place with
a budget.

`roadmap.md` closed Phase 7 with an investigation rather than an answer. It named four lines and a
gate — *"a decision recorded per line above — measured saving, or a reason it does not pay"* —
because the shipped slice is **3,576,606 bytes brotli**, all of it fetched before the first frame,
and the tail hurts:

| Connection | First frame |
|---|---|
| unthrottled | 139 ms |
| 5G — 100 Mbit/s | 520 ms |
| 4G — 9 Mbit/s | 3,606 ms |
| Fast 3G — 1.6 Mbit/s | 19,822 ms |

This is that investigation. Every line below is a measurement rather than an estimate, and the
answer to four of the five is no.

## 2. Decision

**No line the roadmap named pays. Two levers that do were not on the list, and both act on the
first frame rather than on the byte count: serve brotli, and preload the WebAssembly.**

Where the bytes are, measured on the shipped slice at this commit:

| Chunk | Raw | brotli | Share |
|---|---:|---:|---:|
| `skiko.wasm` | 8,642,989 | 2,596,146 | **73%** |
| application WebAssembly | 3,881,843 | 893,710 | 25% |
| `app.js` glue | 595,741 | 86,203 | 2% |
| `index.html` | 1,420 | 547 | — |
| **total** | **13,121,993** | **3,576,606** | |

### Line 1 — Load the design-system half after the first frame. **Does not pay: the split does not exist.**

The premise was that the page's two WebAssembly chunks are two halves of the application, one of
which could wait. They are not. `skiko.wasm` is **8,642,989 bytes**, and that is byte-for-byte the
artifact JetBrains ships inside `skiko-js-wasm-runtime-0.9.37.4.jar` — not something this build
produced. The other chunk is the *whole* application: `dogwood-protocol`, `dogwood-wire`,
`dogwood-host`, `dogwood-web` and the slice are five Gradle modules and compile to **one**
WebAssembly module.

Kotlin/Wasm has no code splitting. The toolchain emits one binary per program, and JetBrains
describes multi-module support as still being worked on
([Kotlin/Wasm overview](https://kotlinlang.org/docs/wasm-overview.html);
[Present and Future of Kotlin for Web](https://blog.jetbrains.com/kotlin/2025/05/present-and-future-kotlin-for-web/)).
There is no "design-system half" to defer, and nothing supported that would create one.

### Line 2 — A registration seam, so a product links only the components it uses. **Ceiling measured at 297 KB; does not pay, and would trade page weight for skew.**

Measured directly, by removing the generated dispatch from `RenderNode` and rebuilding the slice:

| Build | application WebAssembly (brotli) |
|---|---:|
| shipped | 893,710 |
| with the design-system binding unlinked | 596,459 |
| **the design system's cost** | **297,251** |

Two things follow, and the first is good news. **Dead-code elimination works** — once the dispatch
was unreachable, 297 KB of it went. What defeats it is the shape of the generated bridge:
`bindDogwoodDesignSystem` is a `when` over every widget tag, called unconditionally, so every
implementation is reachable by construction however few a product uses.

So a registration seam is buildable, and 297 KB is its **ceiling** — the saving if a product used
*no* components at all. A product using half of them saves around 150 KB: 4% of the page, and about
0.75 s on Fast 3G.

**The reason to decline is not the size, it is what a seam would cost elsewhere.** A client that
links a subset can meet a payload naming a component it does not have — which is precisely the skew
case [ADR-031](ADR-031-safety-relevant-parameters.md) handles by withholding a widget, and today it
is *impossible* rather than merely rare, because every client links the whole dictionary segment it
advertises. A seam turns an impossible failure into a routine one, on an architecture whose central
risk is skew, in exchange for 4% of a page. That is the wrong direction.

### Line 3 — Skiko's own configuration. **Does not pay: measured across four versions, it has grown.**

| Skiko | Raw | brotli |
|---|---:|---:|
| **0.9.37.4** (pinned) | 8,642,989 | **2,596,146** |
| 0.144.6 | 8,652,729 | 2,613,260 |
| 0.148.2 | 8,626,060 | 2,614,555 |
| 0.150.1 | 8,640,316 | 2,618,182 |

The pinned version is the smallest of the four, and the newest costs 22 KB more. The jar contains
exactly one `skiko.wasm` and no alternative build — `skikod8.mjs` is a d8-shell variant of the
*glue*, not a smaller renderer. There is no configuration here, only a version, and the version
this project already has is the best of them.

### Line 4 — A Document Object Model tier for text-and-layout screens. **The only line that moves the number, and it is declined on architecture rather than on size.**

A DOM renderer would sidestep Skiko entirely, which is 73% of the page. Dogwood is unusually well
placed to have one: the wire format is a widget tree over a dictionary, and the host renderer is
already one implementation among four.

It is declined because **it is a second design-system implementation for the web**, and
[ADR-041](ADR-041-one-host-core-split-at-the-zipline-seam.md) has just finished deleting one — 627
lines of `WebTree` and `WebBindings` — for reasons that have not changed. Every `*Impl` would exist
twice, in two rendering models with different layout semantics; every conformance claim would need
evidence on both; and the two would drift, because that is what two implementations of one
specification do. The conformance rollout found four cross-client defects in *one* implementation
per platform.

Recorded rather than closed: this is the lever, it is large, and the condition under which it pays
is a product judgement rather than an engineering one — see §4.

### Line 5 — Serve brotli. **Not on the list, free, and worth more than every line above combined.**

| Encoding | Total | 5G | 4G | Fast 3G |
|---|---:|---:|---:|---:|
| brotli −q 11 | 3,576,606 | 0.32 s | 3.26 s | 18.45 s |
| gzip −9 | 4,563,713 | 0.40 s | 4.14 s | 23.38 s |
| **penalty** | **+987,107 (27.6%)** | +0.08 s | +0.88 s | **+4.94 s** |

Serving gzip instead of brotli costs **987 KB** — more than three times the entire design system,
and nearly five seconds on a slow connection. It costs nothing to fix: it is one server setting, and
the bytes already exist.

This project's own measurement harness has always had it right — `tools/web-ttff/run.sh` refuses to
measure at all unless the server is actually sending `Content-Encoding: br`, so no number in ADR-038
or in the budget was ever taken against uncompressed files. **What was missing is the instruction to
whoever deploys it.** A budget expressed in brotli that a deployment silently misses by 27% is a
budget met on paper.

### Line 6 — Re-optimise the WebAssembly for size. **Measured at 0.6%; does not pay against the risk.**

The Kotlin toolchain finishes with Binaryen using its own pass list, which is tuned for speed.
`-Oz` and `-Os` are the size-first settings, and `skiko.wasm` — 73% of the page — was optimised by
JetBrains before publication and has never been through a pass this project chose.

| Chunk | baseline brotli | `-Oz` | `-Os` |
|---|---:|---:|---:|
| `skiko.wasm` | 2,596,146 | 2,577,488 | 2,576,313 |
| application | 893,710 | 892,946 | 893,327 |

The best case is **19,833 bytes**, 0.6% of the page, and about a tenth of a second on Fast 3G.

Against that: re-optimising a vendor's shipped binary is exactly the class of change that produced
this project's worst defect. A Binaryen pass in the toolchain's own list miscompiled
`String.toCharArray()` to an array of zeros — silently, in a release build, context-sensitively
([ADR-032](ADR-032-the-web-profile.md)). Running more passes over a renderer nobody here can read,
for six tenths of one per cent, is a trade this project has already learned not to make.

### Line 7 — Preload the WebAssembly chunks. **Pays, costs nothing, and is built.**

The page is `<script src="app.js">`, so the browser learns the two `.wasm` URLs only after `app.js`
has been fetched, decompressed and parsed. The 2.6 MB renderer cannot start downloading until a
smaller file has finished. On a link with a 562 ms round trip that serialisation is not free.

`<link rel="preload">` in the head starts both fetches immediately. Ten cold loads per preset, the
same harness and method as ADR-038:

| Connection | before | after | change |
|---|---:|---:|---:|
| unthrottled | 139 ms | 142 ms | — |
| 5G | 520 ms | **454 ms** | **−13%** |
| 4G | 3,606 ms | **3,473 ms** | −133 ms |
| Fast 3G | 19,822 ms | **19,186 ms** | −636 ms |

**It moves no bytes at all** — the transferred figure is 3,496 KiB in every load, before and after.
What it removes is a round trip and a parse from the critical path, which is why the *relative*
gain is largest on the fast connection, where transfer no longer dominates. 5G is also the
connection most users actually have.

It is a build step rather than two lines of HTML because the filenames are content-hashed — which
is also what makes them cacheable, so the two facts are the same fact. `as="fetch"` rather than
`as="script"`, because the modules are instantiated through `WebAssembly.instantiateStreaming` over
a `fetch`; a mismatched `as` makes the browser download each file **twice**, which is worse than no
hint.

---

*Lines 6 and 7 were added after this record was first accepted. The first version answered the four
questions the roadmap asked and stopped, which was the wrong shape for a page whose first visit is a
product-level concern: "the four planned lines are no" is not the same as "there is nothing to do".
Both of the levers that pay — brotli and preload — were found by asking what else touches the first
frame rather than what else shrinks the page.*

## 3. Rationale & Research

**Why removing the dispatch is the right way to measure a registration seam.** The alternative was
to estimate from the spike modules in `tools/web-weight`, which measure `compose.material3` against
a floor — 0.15 MB brotli, and a number about *Material 3*, not about Dogwood's bindings over it.
Removing the call measures the thing under discussion: everything reachable only through the
generated `when`, which is exactly what a seam would make optional.

**Why the spike modules could not answer any of this.** They are `:floor` and `:material`,
artifacts no product change can move — the same trap `from_web_weight.py` fell into when it graded
the budget against them and reported a steady 2.92 MB through a change that grew the real page by
463 KB. Every number in this record comes from the shipped slice.

**What the shape of the page implies, and it is worth stating plainly.** Three quarters of it is a
prebuilt binary this project cannot shrink, cannot configure, and cannot defer. The quarter Dogwood
controls is 894 KB, which is unremarkable for an application of its kind, and the nearest
architectural peer — Flutter Web with CanvasKit — pays a comparable 1.5–2 MB for the same reason: a
canvas renderer instead of the DOM. **The page is not heavy because of anything this project did**,
and the levers that exist are therefore small, absent, or architectural.

## 4. Unstated Assumptions

- **These numbers are for this toolchain.** Compose Multiplatform 1.10.3, Kotlin 2.3.20, Skiko
  0.9.37.4, brotli quality 11. A page-weight figure is only meaningful for the toolchain that
  produced it, which is why `tools/web-weight` pins its versions to the engine's.
- **Kotlin/Wasm code splitting is absent, not impossible.** If the toolchain gains it, line 1
  reopens — and line 2 reopens with it, because a deferred design-system module is a different
  proposition from a subset one: it defers bytes without making a component absent, so it does not
  create the skew case that declines the seam.
- **The DOM tier's saving is not measured.** A DOM page would plausibly be 200–400 KB rather than
  3.6 MB, an order of magnitude, but nobody here has built one and that figure is an estimate.
- **What would make it urgent.** A product whose *first* visit on a slow connection is the product:
  a landing page, anything search-driven. The profile suits a returning-user application surface,
  where the bytes are cached, far better than a first-impression page. That is a product judgement
  and belongs with whoever chooses the profile — which is why it is written down rather than left to
  be discovered at 18 seconds.
- **Nothing enforces brotli at deployment time.** The measurement harness refuses to run without it;
  a real deployment has no such check, and the failure is silent — the page works, and is 27%
  heavier than every number in these records.

## 5. Updated Documents

- [`roadmap.md`](../../roadmap.md) — Phase 7's page-size investigation, closed with these decisions.
- [`tools/web-weight/README.md`](../../tools/web-weight/README.md) — the shipped-page measurements,
  alongside the spike ones.
- [`docs/checks.md`](../../docs/checks.md) — the deployment note on `Content-Encoding`.
- [`adrs/README.md`](../README.md) — index entry.

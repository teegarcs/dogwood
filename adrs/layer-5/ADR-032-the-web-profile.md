# ADR-032: The Web Profile

**Date:** 2026-09-02
**Status:** Accepted

> **Partially superseded (2026-09-05):** the "rebuild the small part of the host" economy below
> assumed web stayed layout-only. With design-system parity committed, that assumption inverted,
> and [ADR-041](ADR-041-one-host-core-split-at-the-zipline-seam.md) splits `dogwood-host` at the
> Zipline seam instead. The Worker bridge, sidecar loader, isolation model and fast decoder here
> all stand.

## 1. Context & Problem Statement

Web is the second shipping target, and it is the first one where the substrate underneath Dogwood
changes rather than the platform on top of it. On Android and iOS the guest runs as bytecode inside
an interpreter Dogwood embeds, delivered through a loader Dogwood controls, isolated by a sandbox
Dogwood gets for free. None of that is true in a browser.

`roadmap.md` Phase 5 step 1 requires this design as an Architecture Decision Record before any host
is built, and three measurements taken since then determine most of it:

- **Page weight** ([ADR-030](ADR-030-web-page-weight-measured.md)): 10.23 MB raw, 2.92 MB brotli, of
  which 81% is a prebuilt `skiko.wasm` that no amount of Dogwood code discipline shrinks.
- **The bridge** (`tools/web-weight/results/bridge.md`): passing a string from JavaScript into
  Kotlin/Wasm costs 0.009 µs regardless of size, because `kotlin.String` is a `JsString` externref.
  String versus bytes is a tie; the structured object path is the *worst* option. A per-platform
  transport is not justified.
- **A toolchain defect**: Kotlin 2.3.20's production `wasm-opt` pass list silently miscompiles
  `String.toCharArray()` to an array of the correct length filled with zeros.

## 2. Decision

**The guest is ordinary JavaScript in a Web Worker. The host is Kotlin/WebAssembly with Compose
Multiplatform on the main thread. They speak the same positional protocol they already speak, over
`postMessage`.**

1. **No QuickJS and no Zipline.** The guest is the same Kotlin, compiled to JavaScript and loaded by
   the browser. No `.zipline` bytecode, no `CallChannel`, no interpreter to embed.
2. **The guest runs in a Worker**, not on the main thread.
3. **The bridge is `postMessage`**, carrying the identical positional JSON batch. One logical
   protocol across all platforms; only the mechanism differs.
4. **Delivery rides same-origin HTTPS**, and **the dictionary check moves to a sidecar manifest**
   fetched and verified before the guest script is executed.
5. **Isolation is the Worker plus a Content Security Policy**, and the specification's claim about
   sandboxing is amended rather than quietly weakened.
6. **`--gufa` is removed from the `wasm-opt` pass list**, with a correctness gate in the build and an
   upstream report.

## 3. Rationale & Research

### Why a Worker, and what it costs

The alternative — guest and host both on the main thread — is genuinely simpler. The bridge becomes a
direct synchronous call, host services can cross as real object references the way Zipline passes
them, and nothing in the protocol has to become asynchronous.

It was rejected on one property: **a guest that blocks would freeze the page.** On mobile a blocked
guest stalls the Zipline thread while the user-interface thread keeps drawing, so the failure is a
stuck screen on a live application. In a browser with one thread it is a dead tab, and the user's
only recourse is to kill it. That is a categorically worse failure, and guest code is exactly the
code most likely to produce it, because it is delivered over the air and changes without a store
review.

The Worker also keeps the threading contract honest. `DogwoodThreads.checkUi()` and `checkZipline()`
assert on every crossing today; collapsed onto one thread they would still compile, still run, and
mean nothing — the worst state for an assertion to be in.

The costs are real and are accepted:

- **Everything on the boundary becomes asynchronous**, including `snapshotState()`, which is
  synchronous today and is called from `DogwoodSession` and the shell's eviction path.
- **Services can no longer cross as object references.** `DogwoodGuestUi.start` takes a
  `DogwoodHost` and a `DogwoodServices` today; across a Worker boundary those become a message
  protocol with correlation identifiers.
- `postMessage` structured-clones its payload. For a string that is cheap, and the measurement says
  the whole transport is 0.02% of a frame, so this is affordable.

### Why the protocol does not change

The bridge measurement is unambiguous: the worst path costs 0.71% of a frame and the best 0.09%, with
steady state under 0.005%. Nothing about the difference justifies a second wire format.

What *does* change is where the time goes. On mobile the split is encoding 99.4% / transport 1.0%;
on web it is encoding 44.7% / transport 0.02% / **decoding 55.3%**. Decoding, invisible on mobile
because it happens in compiled host code, becomes the larger half. Optimisation effort on web belongs
there, and the cheapest known win needs no protocol change at all: read the batch through
`toCharArray()` once rather than `charCodeAt` per character — which is, with some irony, the exact
function the toolchain miscompiles.

### Where the dictionary check runs

On mobile the segment-version vector rides `ZiplineManifest.metadata`, inside the signed body, and the
loader rejects a payload naming a version the client does not implement. There is no signed manifest
on the web, so that gate has to be rebuilt somewhere, and it must run **before the guest executes**:
a payload built against a dictionary this client does not have renders a mostly-empty screen, which is
precisely the failure the check exists to prevent.

It runs in the host, on the main thread, before the Worker is created:

1. Fetch a sidecar `dogwood-manifest.json` from the same origin as the guest script.
2. Compare its segment-version vector against `DogwoodDictionary.segmentVersions`.
3. Refuse to start the Worker on a version this client does not implement, and report it.
4. Only then create the Worker with the guest script.

**Integrity is the weakest part of this profile and is stated rather than glossed.** HTTPS plus
same-origin gives transport integrity and authenticity of the server, which is what ordinary web
deployment relies on. It does not give the property Ed25519 manifest signing gives on mobile, which is
that a compromised or substituted server cannot make a client run code the signing key never approved.
A product wanting parity can fetch the script, verify a hash carried in the signed sidecar, and
construct the Worker from a blob — `new Worker(url)` supports no Subresource Integrity attribute, so
the verification has to be explicit. That is left unbuilt and named as the gap.

### Isolation, and an honest amendment

The specification says a guest "has no filesystem, no sockets, no clock it can trust, no logger, and
no way to learn anything about the account or the build it is running in. Everything it can reach, it
reaches through here." On mobile that is enforced by QuickJS: there is genuinely no `fetch`, no DOM,
no storage. **In a browser page it would be false.** Guest JavaScript could call `fetch`, read
`document.cookie`, touch `localStorage`, and rewrite the host's own page, and `OkHttpNetwork`'s
default-deny allow-list — written because "this payload was downloaded and can be replaced over the
air without a store review" — would become advice.

Two mechanisms recover most of it:

- **The Worker removes the DOM.** No `document`, no `window`, no access to the host page or its
  storage. This is a property of the threading decision rather than an addition to it.
- **A Content Security Policy makes the network allow-list enforcement again.** `connect-src` is
  applied by the browser to `fetch` from the Worker, so a guest cannot reach an origin the deployment
  did not name, whatever its code says.

The service surface still routes through the host, so guest code is identical across platforms and
the host remains the policy point it is on mobile. The Content Security Policy is what stops a guest
bypassing that surface, which on mobile is impossible and on web is merely forbidden.

What is *not* recovered: a Worker shares the page's origin, so it can reach same-origin endpoints and
carries the page's credentials on requests the policy permits. Full origin isolation needs the guest
served from a separate origin in a cross-origin frame, which was considered and rejected for now on
complexity — a second origin to deploy, a second message hop, and materially harder local
development. It remains the option if a product needs it.

### The toolchain defect

`--gufa` is removed from the production `wasm-opt` pass list, because we control that list and its
removal demonstrably fixes the miscompilation. Reproduced from a clean build: the default pipeline
returns `viaBulkCopy = 0` where a per-character read of the same 28-byte input returns `1219597841`.

This is accepted with two conditions rather than as a clean fix. The bug is **context-sensitive** —
adding an unrelated caller of `toCharArray()` made it vanish and removing that caller brought it back
— so it cannot be reasoned about locally, and its blast radius is unknown: any WasmGC array written by
an imported builtin is a candidate. So the build keeps a **correctness gate** that fails if the output
disagrees with a reference implementation, and the defect is **drafted for upstream but not yet
filed** — the text and the reproduction are in [`tools/upstream-reports/`](../../tools/upstream-reports/),
and filing it publishes this project's name against a vendor's product, which is a person's decision
rather than an automated one. Losing `--gufa` costs some optimisation; against a page that is 81% prebuilt Skiko, that
is not a number worth defending.

**The gate is unexercised, and that is a real caveat rather than a footnote.** Building the web
sample's WebAssembly with Kotlin's full default pass list — `--gufa` included — and swapping it in
produces a passing gate and a correctly rendered tree. So the miscompilation does **not** reproduce
in that module, which is consistent with this record's own claim that the defect is
context-sensitive, and which means the gate's *detection* path has never been observed to fire on a
real miscompilation. It is a smoke alarm nobody has held a match under. Two things follow: removing
`--gufa` remains correct precisely because the defect cannot be reasoned about locally, and the gate
should be validated against the known reproduction in `tools/web-weight/bridge/` before anyone
relies on it to catch a recurrence.

## 4. Unstated Assumptions

- **Compose Multiplatform for Web is Beta**, and `material3-wasm-js` trails at `1.12.0-alpha03`. The
  design-system-first path leans on registered components rather than Material, which narrows the
  exposure but does not remove it.
- **2.92 MB compressed is acceptable for the products that would use this.** It is a *fixed* entry
  toll — it does not grow with the product — and it caches after the first visit. Whether that is
  affordable is a product judgement this record does not make.
- **Time to first frame remains unmeasured**, and it is the figure that actually decides adoption.
  ADR-030 explains why two plausible shortcuts produce numbers that flatter the result. The page
  carries a `#dogwood-first-frame` probe so measuring it needs a browser and a network, not new code.
- **The guest cannot use `Intl` even though browsers have it.** The same guest source ships to mobile,
  where QuickJS has none, so the host-resolved formatting recipes stay mandatory.
- **A Worker's `postMessage` ordering is assumed sufficient** for the sequence-number protocol.
  Messages are delivered in order per channel; the existing sequence numbers remain the authority.

## 5. Consequences for Existing Code

- `DogwoodGuestUi` and `DogwoodHost` need asynchronous variants, or a message-protocol adapter that
  presents the existing interfaces over `postMessage`. The latter is preferred: it leaves guest code
  unchanged.
- `snapshotState()` becomes suspending on the guest side as well as the host side.
- `DogwoodDelivery` is mobile-only. The web profile needs its own loader implementing the sidecar
  check above.
- `DogwoodThreads` keeps both identities and gains a web implementation where "the Zipline thread" is
  the Worker.

## 6. Updated Documents

- [Roadmap](../../roadmap.md) — Phase 5 step 1.
- [Layer 3: Delivery](../../specs/layer-3-delivery.md) — the web delivery path and what it does not
  give.
- [Layer 4: The Guest Runtime](../../specs/layer-4-sandbox.md) — the sandbox claim, amended per
  platform.

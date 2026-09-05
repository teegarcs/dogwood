# ADR-041: One Host Core, Split at the Zipline Seam

**Date:** 2026-09-05
**Status:** Accepted

## 1. Context & Problem Statement

The web host is a 2,375-line reimplementation of the parts of the 6,170-line mobile host it needed:
its own tree, five layout widgets, its own decoder, no design system, no state store, no skew
report, no host resolution. [ADR-032](ADR-032-the-web-profile.md) recorded why: `dogwood-host`
declares `api(zipline)` in its common source set, and Zipline publishes no WebAssembly artifacts.

Two things have changed since. **Design-system parity on web is committed** (conformance plan,
decision 2), which turns every reimplemented piece into a *pair* of implementations that drift.
And the drift is no longer hypothetical: the conformance matrix's first honest generation showed
web graded on 8 of 28 claims precisely because almost nothing proven about the mobile host
transfers, and the one cross-client defect found so far (the double-announced text-field label) was
exactly a two-implementations divergence. The project owner's direction: **bias toward shared
code.**

The question: build the missing web pieces as more `dogwood-web` code, or compile the mobile
host's code for the browser?

## 2. Decision

**Split `dogwood-host` at the Zipline seam.** A transport-free core — the tree, the bindings,
modifiers, expressions, theme, plurals, state store, skew report, the warm pool — gains a `wasmJs`
target and becomes the one implementation everywhere. A Zipline layer on top keeps `Delivery`,
`Experience`, the Zipline-backed service host, the session, the shell and the leak detector, for
the platforms Zipline serves. `dogwood-web` keeps what is genuinely web-shaped — the Worker bridge,
the sidecar loader, the hand-tuned decoder — and deletes `WebTree` and `WebBindings` in favour of
core.

**The split is a source-set boundary inside `dogwood-host`, not two Gradle modules.** This ADR was
written naming `dogwood-host-core` and `dogwood-host-zipline`; building it showed the module
boundary bought nothing the source-set boundary does not. A `ziplineMain` source set that
`jvmAndroidMain` and `iosMain` depend on gives the same seam with **no consumer changes, no package
moves and no new coordinates** — the module already used exactly this shape for `jvmAndroidMain`.
Enforcement is identical and better than review: `commonMain` has no Zipline dependency, so
anything reaching for one fails the `wasmJs` compilation immediately. That was checked by adding a
`ZiplineService` reference to a core file, watching `Unresolved reference 'app'`, and reverting.
Kotlin Multiplatform resolves per target, so a `wasmJs` consumer of `dogwood-host` links the core
variant and never sees the Zipline layer.

This is **not** "move the host onto Wasm". The web guest keeps running as JavaScript in a Web
Worker; Zipline is not ported; no mobile client changes execution substrate. It is Kotlin
Multiplatform doing the one thing it is for: one more compilation target for source that was
already portable.

## 3. Rationale & Research

**The seam was measured, not assumed.** A scratch module compiled `dogwood-host`'s common source
for `wasmJs` (2026-09-05, Kotlin 2.3.20, Compose Multiplatform 1.10.3):

- **Only 2 of 26 common files import Zipline** (`Delivery.kt`, `Experience.kt`); 4,467 of 6,170
  lines are Zipline-free.
- **Every protocol type those files use lives in `dogwood-wire` already** — except two constants
  (`SERVICES_SEGMENT`, `SERVICES_VERSION`), stranded in `dogwood-protocol`.
- Errors confined to exactly **5 files, each a named seam**: `HostServices`/`Session`/`Shell`
  (they hold `DogwoodServices : ZiplineService` and the concrete `DogwoodDelivery` — the split
  introduces a transport-free service interface and an experience-source interface that the
  Zipline layer implements); `Leaks.kt` (`redwood-leak-detector` publishes no wasm artifact — the
  `DogwoodLeakWatcher` interface moves to core, the Redwood-backed implementation to the platform
  layer); `Bindings.kt` (only the two stranded constants).
- With those addressed and stub actuals for the ~10 existing `expect` declarations, **everything
  else compiled unmodified** — including the generated design-system bindings, `DesignSystemImpl`
  with Material 3, and Coil, all of which resolve for `wasmJs`.

**The remaining real work is the actuals**, and one of them is an upgrade rather than a port:
`Format`'s seven functions wrap ECMA-402 `Intl`, which the browser *has* and QuickJS does not —
the web host gets real locale formatting more directly than mobile did. `StateStore` needs a
browser-storage `FileSystem`; `ThreadIdentity` is trivial on a single-threaded main.

**Why not extend `dogwood-web` (option A).** Lower cost per piece, but every future feature is
built twice and verified twice, forever — against a parity commitment that makes "forever" literal.
The project has now been bitten by the two-implementations shape twice: the 21-false-green-cells
incident (a `shared` claim scope that web did not actually share) and the label defect. The whole
conformance premise — one implementation, graded once — argues for B; under A the web column is
earned one hand-written test at a time.

**Page weight was the predicted cost, and it did not materialise.** Core brings Material 3, which
[ADR-030](ADR-030-web-page-weight-measured.md) sized at ~0.15 MB compressed. Measured after the
switch: **10.23 MB raw, 2.92 MB brotli — identical to ADR-030's figure to the reported precision.**
Material 3 was already being linked, because the web slice already rendered through Compose
Multiplatform; what changed is which bindings call it. The `web-weight` harness stays on the
gate list regardless, since the next design-system component is the one that could move it.

### Scope boundary: this does not reopen the mobile substrate decision

Asked directly by the project owner. **No — and the alignment principle is the reason, stated
precisely: bias toward shared *source*, not shared *runtime*.** This ADR is shared source. Wasm on
mobile would be shared runtime bytes, which the wire protocol already makes unnecessary — both
sides only ever see the positional format, and the guest *source* is already one Kotlin/JS codebase
serving QuickJS and the Worker alike. [Layer 4 ADR-002](../layer-4/ADR-002-adopt-zipline-quickjs-substrate.md)
rejected Wasm as the guest substrate on measured grounds (WasmGC eliminates every fast interpreter;
iOS forbids the JIT that would compensate; the primitives-only `@WasmImport` bridge) and set a
revisit condition — Kotlin/Wasm Stable plus a fast specialized interpreter. Running core on
`wasmJs` daily supplies continuous evidence on the first half; today's reading argues *against*
acceleration (the toolchain still crashes deterministically on incremental klib compilation,
upstream report 2). And Wasm is no hedge against the Apple risk: the historical carve-outs are for
JavaScript specifically, so a Wasm payload is a worse position there, not a better one.

## 4. Unstated Assumptions

- **The generated bindings stay target-agnostic.** They compiled for `wasmJs` today because the
  generator emits only reader calls and Compose. A future binding that reaches a platform API
  would break the property silently; the split makes core's compilation the tripwire.
- **Kotlin/Wasm toolchain friction lands on this path.** The klib checker crash already lives
  here; the split roughly doubles the wasm-compiled surface. Accepted, with the workaround
  documented in `engine/gradle.properties`.
- **Web state persistence is durable, on `localStorage`.** Okio publishes no browser-backed
  `FileSystem`, so `BrowserFileSystem` is one — the five operations a saved-state store performs,
  with the rest refused by name rather than approximated. The Origin Private File System is the
  better fit on paper and was rejected for a concrete reason: its interface is asynchronous and
  Okio's is not, so bridging it needs a write-behind cache that would let `write` return before
  the bytes are durable, which is the one property the file exists to provide. Where storage is
  unreachable — a private-browsing window, or a Worker — it degrades to in-memory rather than
  failing every write, because a browser refusing storage means exactly that.
- **Web leak detection exists, and the reason it was nearly deferred is worth keeping.** This ADR
  first recorded it as blocked: `redwood-leak-detector` publishes no WebAssembly artifact, and
  Kotlin/Wasm objects live in the WebAssembly garbage-collected heap rather than being JavaScript
  values, so whether their liveness could be observed from JavaScript at all looked like an open
  research question. It was not. `WeakRefBridgeProbeTest` answered it in one run:
  `Subject().toJsReference()` handed to a `WeakRef` is collected and observed as collected. The
  deferral rested on a property that had been reasoned about and never tried — the same mistake
  `AGENTS.md` §1.5 now names, made in the paragraph next to the one that names it.
  `BrowserLeakWatcher` is the result, thirty lines, with the two disciplines the probe exposed:
  yield before asking, because a `WeakRef` keeps its target alive for the current job; and do not
  hold the subject in an inlining caller's frame. What it genuinely cannot do is force a
  collection — no browser offers one — so a report means "still reachable when asked, after the
  threshold", and the threshold must outlast a plausible *collection* rather than a plausible leak.
- **The fast web decoder remains worth its duplication.**- **The fast web decoder remains worth its duplication.** Decoding is the larger half of the web
  crossing (ADR-032), so `FastPositionalDecoder` stays even though core carries `decodePositional`.
  If that ratio ever inverts, the decoder is the next candidate for deletion.
- **Zipline never publishing wasm artifacts.** If it someday did, `dogwood-host-zipline` could gain
  the target — nothing in this split blocks that; it only stops waiting for it.

## 5. Updated Documents

- [Roadmap](../../roadmap.md) — Phase 7's parity paragraph now names this decision and its plan.
- [`plans/conformance.md`](../../plans/conformance.md) — the blocked-on-parity claims route here.
- [Layer 5: Host](../../specs/layer-5-host.md)
- [ADR-032: The Web Profile](ADR-032-the-web-profile.md) — its "why this is not `dogwood-host`"
  paragraph is superseded by the split.

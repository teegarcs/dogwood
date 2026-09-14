# ADR-070: Every shipping platform is consumable, and the Worker guest is a library

**Date:** 2026-09-13
**Status:** Accepted

## 1. Context & Problem Statement

A production review asked, per platform, whether a product outside this repository could consume
Dogwood, and read the build files rather than the claims.

- **Web: no.** `engine/dogwood-web/build.gradle.kts` applied no `maven-publish`, declared no group
  and no version. The module that every web page has to depend on was not an artifact at all, and
  nothing noticed because the only web consumer was the in-repository sample. `DECISIONS-FOR-THE-OWNER.md`
  §5 listed the published modules and the web host was not among them, in a sentence nobody had
  read as a gap.
- **Web guest: a copy.** The code that carries a batch from `sendChanges` to `postMessage` and
  answers the host's envelope — `start`, `configuration`, `event`, `frame`, `snapshotState`,
  services, crash encoding — was 416 lines in `samples/web-guest/Main.kt`. A product would have
  copied it, and every product's copy would have drifted from the envelope on its own schedule.
- **iOS: unproven.** `samples/ios-embed` produces an XCFramework inside the engine's build, and its
  check is header inspection. No build outside the repository had ever resolved the iOS artifacts.
  `samples-standalone/umbra`, the only outside consumer, had a desktop application and (since
  2026-09-09) an Android compile probe, and its design system was `kotlin("jvm")` — so the
  generated bindings and the `@Implementation` target they call had only ever been compiled for the
  desktop host.

Three shipping platforms; one proven from outside. The same class of finding as the Android
artifact ten days earlier ([run 3](../../tools/framework-grade/results/2026-09-09-run3.md)), from
the same cause: the proof covered the platform somebody happened to try.

## 2. Decision

### 2.1 `dogwood-web` publishes, with its module name pinned

`maven-publish`, `group = "dev.dogwood"`, `version = "0.1.0"` — exactly as `dogwood-host` — and
`outputModuleName.set("dogwood-web")` on the WebAssembly target for the reason `dogwood-compose`
pins its JavaScript module name: `internal` declarations are name-mangled against the module name,
which is derived from the group, and adding a group renamed the module out from under the browser
tests once already. The browser tests are the negative control for that hazard and stay green.

### 2.2 The Worker transport is `runInWorker`, in `dogwood-compose`

`dogwood-compose` is the guest runtime and is JavaScript-only, so the Worker bridge belongs in it.
A product's Worker entry point is now its entry-point list plus one call:

```kotlin
fun main() = runInWorker(DogwoodGuest("home" to { _ -> HomeScreen() }))
```

Inside: `WorkerGuestBridge(guest, services, post)` owns the inbound envelope and is constructed
with the function that posts, so the message handling runs on Node without a Worker;
`defaultWorkerServices(start, egress)` answers what a Worker can answer on its own (log, clock,
`fetch` inside the page's origin) and crosses the two calls only the page can answer (analytics,
navigation) through a `WorkerEgress`. The envelope constants remain a **mirror** of
`dogwood-web/WorkerProtocol.kt` — the host is Kotlin/WebAssembly and this is Kotlin/JavaScript, and
they do not link — defended by the `ready` handshake carrying `WORKER_ENVELOPE_REVISION`; the file
says to bump both together. Every comment that carried a reason moved with the code: the even/odd
correlation rule, why `onmessage` is a function reference and not a `js()` string, the network
caveat, the stack encoding of ADR-063.

`samples/web-guest/Main.kt` is 51 lines.

### 2.3 Umbra is a multiplatform product with a probe per platform

`:design` is `kotlin("multiplatform")` with `jvm`, `androidTarget { publishLibraryVariants("release") }`,
`iosArm64`, `iosSimulatorArm64` and `wasmJs` — every target the engine's host has — and its sources
moved to `commonMain` unchanged. Three probe modules join `:android`, each a bounded proof that
states its own limit:

- **`:web`** — a WebAssembly library depending on `dev.dogwood:dogwood-web:0.1.0` and `:design`,
  whose one file constructs a `WebDelivery` and a `DogwoodWebExperience` and registers the binding.
  The verdict is a compile, and the standalone check asserts the **resolved variant** is
  `dogwood-web-wasm-js`, for the reason the Android section does: a module that publishes the
  wrong variant compiles anyway.
- **`:web-guest`** — the same `HomeScreen` as `:guest`, built for a Worker with `runInWorker`. One
  set of screens, two transports; the screens moved to `guest/screens/kotlin` and both modules
  read them as a source directory. The verdict is the bundle webpack emits, and that it carries
  `postMessage`.
- **`:ios`** — a static `UmbraEmbed` framework with the three lines `ios-embed` learned by link
  failure (`isStatic`, `-lsqlite3`, `export`), and a Swift-facing `umbraViewController` factory.
  The verdict is a **link**, because linking is where `-lsqlite3` earns its keep, and the check
  asserts the binary and the header exist. Skipped, not passed, on a machine without Xcode.

`tools/standalone-check/run.sh` runs all of it after the existing desktop render and Android
resolution, and its closing line says what it proved.

### 2.4 The same Umbra now proves the two other decisions of the day

`UmbraTone` is declared on the surface with `@Implementation("dev.umbra.design.UmbraTone")`, so
the host decodes the name straight into the design system's own enumeration and `UmbraBadge` is
called with the type it was written against, with no wrapper and no generated copy on the host
([ADR-068](ADR-068-the-generator-refuses-what-it-cannot-bind.md)). `UmbraPill` is a component
nobody registered — `clickable`, `border`, per-side `padding`, `spacedBy`, `fontWeight` — composed
in the payload from the primitive tier ([ADR-069](ADR-069-the-primitive-tier-is-the-lever.md)),
consuming `dogwood-compose` from a repository. The desktop render check requires `UmbraBadge#` in
its transcript.

## 3. Rationale & Research

**What the first run found.** Linking the iOS framework outside the engine ran out of memory in
the Kotlin/Native compiler's dependency cache — `Java heap space` from inside `ZipFileSystem`,
reported as `Failed to build cache for … dogwood-host-iosSimulatorArm64Main-0.1.0.klib`. The engine's
own build carries `org.gradle.jvmargs=-Xmx4g` and `kotlin.native.jvmArgs=-Xmx3g`; Umbra did not,
and neither will an adopter's Xcode-adjacent Gradle build until it does. Both lines are in Umbra's
`gradle.properties` with the reason, which is the first time the requirement was written down for
someone outside the engine.

**And the second thing the first run found.** Once `:ios` existed, the check's generic
`build` step linked four frameworks at once — debug and release, device and simulator — and the
machine ran out of memory twice before the section it was meant to reach. The generic step now
excludes `:ios:assemble` and runs with two workers; the iOS section links the one framework that
proves anything, on its own. A check that fails by exhausting the machine proves nothing, and a
probe module's *default* task graph is part of what an adopter inherits.

**The run, end to end** (`w3-standalone.log`): desktop render `UMBRA CHECK PASS`; Android resolved
`dev.dogwood:dogwood-host-android:0.1.0`; `:design` compiled for WebAssembly and the iOS simulator;
`:web` resolved `dev.dogwood:dogwood-web-wasm-js:0.1.0`; the Worker bundle is 782,205 bytes and
carries `postMessage`; the iOS framework linked (346 MB, debug, simulator) with the factory in its
header. Fifteen minutes twenty seconds.

**Negative control for the web section.** Removing `maven-publish` from `dogwood-web` and
re-running the section fails at `:web:compileKotlinWasmJs` with the message the script prints —
"Is dogwood-web still published?" — which is the failure the whole section exists to produce.
Recorded in `tools/standalone-check/run.sh` beside the assertion.

**Six bridge tests on Node** (`WorkerGuestBridgeTest`): a configuration starts the composition and
a `changes` post comes out; an unknown kind is answered with an error naming it; the `start`
payload's feature flags reach `available()` and are honestly absent without them; a snapshot
request answers on its own correlation; `ready` announces the envelope revision.

## 4. Unstated Assumptions

- **Assumes a mirror with a handshake is an acceptable substitute for a shared definition.** It is
  the same trade ADR-032 made, now with the mirror in a library rather than a sample. A third copy
  — a product's own — no longer exists, which is the point.
- **Assumes a compile or a link is the right proof for a probe.** None of the three probes renders.
  Rendering on each platform is what the engine's own drills grade; what they cannot grade is
  consumption from outside, which is the only thing these prove.
- **Assumes the iOS link is run where Xcode is.** The check skips it elsewhere and says so; a
  skipped section is not a passed one and the output makes the difference visible.
- **Assumes `mavenLocal()` until the owner decides otherwise.** `DECISIONS-FOR-THE-OWNER.md` §5 is
  unchanged in substance: one more module publishes, and none of them publishes anywhere yet.

## 5. Updated Documents

- [`docs/getting-started.md`](../../docs/getting-started.md) — the web section: `runInWorker`, and
  the artifact a page depends on.
- [`docs/checks.md`](../../docs/checks.md) — the standalone check's three new sections.
- [`DECISIONS-FOR-THE-OWNER.md`](../../DECISIONS-FOR-THE-OWNER.md) §5 — `dogwood-web` joins the list.
- [`plans/adoption-audit.md`](../../plans/adoption-audit.md) — B9.
- [`adrs/README.md`](../README.md) — index entry.

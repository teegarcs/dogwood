# Getting started

For somebody adding Dogwood to an application that already exists. It assumes you know Compose and
nothing about this project.

By the end you will have two builds that ship on different schedules: an **application** that goes
through a store, and a **payload** of screens that does not.

---

## What you are actually building

| | What it is | How it ships |
|---|---|---|
| **The host** | your existing application, plus a Dogwood surface and your component bindings | an app store |
| **The payload** | a Kotlin module compiled to JavaScript — your screens | your server, as often as you like |

The payload is ordinary Kotlin Compose. It runs in a sandbox on the device, composes, and sends
tree changes to the host, which replays them against native Compose Multiplatform. **The host draws
everything.** The payload never ships a pixel, a font or a widget implementation — which is why it
can be a few hundred kilobytes and why a store review is not in its path.

Consequences worth knowing before you write anything:

- **A payload cannot add a component the installed host does not have.** New screens, new logic, new
  layout: yes. A new kind of widget: only in the next application release.
- **Everything crossing the boundary is data.** A payload passes numbers, strings and enums, not
  lambdas over host objects.
- **Skew is the normal case**, not the exception. Your payload will outrun your installed hosts by
  months, and the containment rules in [operating](operating.md) §2 are what make that survivable.

---

## 1. The host

Take the runtime and the generator. There is no `includeBuild` and no path into this repository —
everything resolves from a repository, which
[`samples-standalone/umbra`](../samples-standalone/umbra/) exists to prove: **a complete worked
example** of everything on this page — a product's own surface (`:design`), a signed payload
(`:guest`) and a host application that renders it (`:app`), all against published artifacts. When a
step below is unclear, Umbra is the same step with real files around it.

```kotlin
plugins {
  id("dev.dogwood.codegen") version "0.1.0"
}

val dogwoodGenerator by configurations.creating

dependencies {
  // The generator is a *tool*: it runs before compilation and nothing you write links against it.
  dogwoodGenerator("dev.dogwood:dogwood-codegen:0.1.0")
  implementation("dev.dogwood:dogwood-host:0.1.0")
}
```

Then render a surface. The shortest complete host is
[`slice-desktop/Main.kt`](../engine/samples/slice-desktop/src/main/kotlin/dev/dogwood/slice/desktop/Main.kt);
in outline it is four things:

```kotlin
val guard = ReleaseGuard(                                          // 1. survive a bad publish
  store = FileReleaseStore(file = cachePath(".../dogwood-release.json")),
  onReport = { yourLog.warn(it) },
)

DogwoodEnvironment(Modifier.fillMaxSize()) { configuration ->     // 2. tell the guest its viewport
  val guarded = DogwoodDelivery(                                   // 3. fetch, verify, cache, load
    dispatcher = ziplineDispatcher,                                //    ONE thread, 8 MB of stack
    trustedPublicKeys = yourPublicKeys,
    cache = ZiplineCache(...),
  ).loadGuarded(applicationName = "your-app", manifestUrl = MANIFEST_URL, guard = guard)

  when (guarded) {
    is GuardedLoad.Refused -> showYourOwnScreen(guarded.reason)    //    quarantined, kill-switched,
                                                                   //    or needs a newer client
    is GuardedLoad.Running -> {
      val experience = DogwoodExperience(guarded.guest.zipline, ziplineDispatcher, uiScope)
      DogwoodSurface(experience, Modifier.fillMaxSize())           // 4. draw it
      guard.succeeded(guarded.version)                             // 5. "loaded" is not success; this is
    }
  }
}
```

Three of those deserve a sentence:

- **`DogwoodEnvironment`** measures the slot and hands the guest its viewport, dark mode, locale and
  insets. A guest cannot work out its own size, and this is the only thing that can tell it.
- **The Zipline dispatcher is one thread and it is the only thread allowed to touch the guest.** It
  needs eight megabytes of stack, because interpreted composition is deeply recursive; Apple gives a
  background thread 512 kilobytes by default, which is why `DogwoodZiplineDispatcher` exists on iOS.
- **`trustedPublicKeys`** is what makes a payload yours. See §4.
- **The guard is not optional to think about.** A payload ships without a store review, so a bad one
  ships fast too; the guard persists an attempt *before* the release runs, which is what makes a
  crash-on-launch loop terminate, and it honours the kill switch in the manifest's signed metadata
  ([operating](operating.md) §2–3). `DogwoodShell` requires the parameter — passing `null` is
  accepted and is a decision you write, not an omission nobody notices.

**If you have several entry points** — tabs, a deep-link target, a settings section — use
`DogwoodShell` instead of a bare experience. It keeps a bounded number of them warm, restores their
saved state, and evicts the rest.

### Adding it to an Android application

The reference is [`TabsActivity.kt`](../engine/samples/slice-android/src/main/kotlin/dev/dogwood/slice/android/TabsActivity.kt)
— written to be copied, and every non-obvious line carries its reason in place. What is genuinely
Android-specific, so you know what you are looking for in it:

- **The Zipline thread is yours to make**: one `newSingleThreadExecutor` with an **8 MB stack** —
  interpreted composition is deeply recursive and the platform default is not enough.
- **`onStop` writes the state snapshot**, because it is the last callback guaranteed before Android
  may reclaim the process; `onTrimMemory` drops warm experiences down to the visible one. Both are
  activity callbacks, which is why the shell is held by the activity rather than the composition.
- **Your dev server is `10.0.2.2` from an emulator**, and cleartext to it needs *both* ends opened:
  the host's allow-list (`allowHosts("10.0.2.2", allowCleartextHosts = setOf("10.0.2.2"))`) and the
  platform's `networkSecurityConfig`. Production traffic is HTTPS and needs neither.
- **Register your design system in `Application.onCreate`**, not in an activity — an activity is
  not the first thing that can render.
- **Ship minified.** The engine needs no keep rules of yours
  ([ADR-056](../adrs/layer-5/ADR-056-the-engine-survives-code-shrinking.md)); the whole conformance
  suite runs against the R8 build.

### Adding it to an iOS application

The reference is [`slice-ios/Main.kt`](../engine/samples/slice-ios/src/iosMain/kotlin/dev/dogwood/slice/ios/Main.kt).
What is genuinely iOS-specific:

- **Use `DogwoodZiplineDispatcher`** rather than rolling a thread: Apple gives background threads
  512 KiB of stack and interpreted composition needs 8 MB.
- **`UIApplicationDidEnterBackgroundNotification` is your `onStop`** — the last guaranteed moment
  to snapshot state — and the memory-warning notification is where you `trimMemory`.
- **`localhost` reaches your machine from a simulator; a device needs your machine's LAN address**
  plus the matching App Transport Security exception in `Info.plist`.

**Embedding into an existing Xcode project**: copy
[`samples/ios-embed`](../engine/samples/ios-embed/) — a library module, not an application, that
produces `DogwoodEmbed.xcframework` (device and both simulator architectures) with one function
visible to Swift:

```swift
import DogwoodEmbed

let screen = DogwoodEmbedKt.dogwoodViewController(
    manifestUrl: "https://payloads.example.com/manifest.zipline.json",
    entryPoint: "checkout",
    trustedKeys: ["release-1": "…hex…"],
    onFailure: { print($0) }
)
navigationController.pushViewController(screen, animated: true)
```

A Dogwood screen is a `UIViewController` from Swift's side; nothing about the sandbox or the
protocol reaches your application's architecture. Build it with
`./gradlew :samples:ios-embed:assembleDogwoodEmbedXCFramework`. **Three lines of that module's
build file are worth keeping verbatim** and each was learned from a link failure: `isStatic = true`
(a dynamic framework carrying Skiko must be embedded *and* signed by your target, and pays dynamic
linking at every launch), `linkerOpts += "-lsqlite3"` (Zipline's cache is SQLDelight over SQLiter),
and `export(project(...))` for anything whose types appear in your API — without it the framework
compiles and the header declares a factory taking types the consumer cannot name.

**On the web the shape is the same and the parts have different names**, because a Web Worker
boundary carries no object references. There is no `DogwoodServiceHost` to hand across: the page
declares what it knows in one value and the guest answers the rest itself.

```kotlin
val experience = DogwoodWebExperience(
  environment,
  report = { line -> console.log(line) },
  services = WebStartPayload(
    entryPoint = "home",
    launchParams = buildJsonObject { put("apiBaseUrl", JsonPrimitive(window.location.origin)) },
    featureFlags = yourFlags,
    routes = yourRoutes,
    segmentVersions = DogwoodDictionary.segmentVersions,
  ),
  onAnalytics = { event -> yourTelemetry.track(event.name, event.properties) },
  onNavigate = { request -> yourRouter.go(request.route) },   // return whether you handled it
)
// `start` is suspending — it fetches the manifest and checks it before any guest code runs.
scope.launch {
  val delivery = WebDelivery(
    clientSegmentVersions = DogwoodDictionary.segmentVersions,
    report = { refusal -> console.log("delivery refused: ${refusal.message}") },
    // No defaults on either of these, deliberately. A protection you have to ask for is one most
    // pages do not have, so the compiler asks instead.
    releaseGuard = ReleaseGuard(
      store = FileReleaseStore(file = "/dogwood/release.json".toPath()),
      onReport = { console.log("release guard: $it") },
    ),
    // The keys this page will accept a sidecar from. `emptyMap()` is the written way to say
    // "believe the origin" — see below.
    trustedPublicKeys = yourTrustedKeys,
  )
  when (val outcome = delivery.start("dogwood-manifest.json", experience)) {
    is DeliveryOutcome.Started -> experience.attach(outcome.bridge)
    is DeliveryOutcome.Refused -> Unit   // nothing was created; the page shows its own screen
  }
}
```

**The sidecar is signed, and you choose whether to check it.** A detached Ed25519 signature sits
beside the manifest as `<manifest>.json.sig`, and `WebDelivery` verifies it against
`trustedPublicKeys` over the bytes it already fetched, **before the document is parsed** — so no
field of an unverified document is ever read, including the address of the script the page will
execute. A *missing* signature is a refusal when you pass keys, because an attacker who can replace
a manifest can delete the file beside it. Passing `emptyMap()` means "believe the origin", which is
what every web page did before [ADR-062](../adrs/layer-3/ADR-062-a-signed-web-sidecar.md) and is a
decision somebody wrote rather than a default they inherited. Signing needs `crypto.subtle`, so the
page must be on HTTPS or `localhost`. The guest *script* is still fetched without an integrity
check; that half is recorded as open.

**`network` is the browser's guarantee, not Dogwood's.** Your guest calls `fetch` inside the page's
origin, so set a `connect-src` Content Security Policy if you want the mobile allow-list's
behaviour. To publish an update to a live page, call `experience.update(newBridge)`; it carries the
running guest's saved state into its successor.

**Enable code shrinking; you need no Dogwood-specific rules.** That is a verified finding, not an
assurance: the conformance drills run against the R8-minified build and pass with zero keep rules
for the engine — its reflective surfaces belong to Zipline and kotlinx-serialization, which ship
their own consumer rules ([ADR-056](../adrs/layer-5/ADR-056-the-engine-survives-code-shrinking.md)).
The keep rules you will find in the sample serve its *test harness*, not the engine; do not copy
them into a product.

## 2. Your own components

Dogwood's design system is not the point; **your** components are. You declare a *surface* — a file
of empty `@Composable` signatures — and the generator emits both halves of the boundary from it.

```kotlin
// surface/dev/yourco/surface/DesignSystemSurface.kt
@Composable
fun PriceTag(
  amount: Int,
  currency: String,
  @Affordance enabled: Boolean = true,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
) {}
```

```kotlin
dogwood {
  segment("yourDesignSystem") {
    wireName.set("yourco.designsystem")
    segmentId.set(2)          // 0 and 1 are Dogwood's. THIS NUMBER IS PERMANENT.
    version.set(1)            // raise it when you add a component
    guestPackage.set("dev.yourco.guest")
    hostPackage.set("dev.yourco.design")
    referenceFile.set("docs/components.md")   // optional; a generated component reference
  }
}
```

For each component you either write one `PriceTagImpl` — the real Compose — or, **if you already
own a design system**, skip the wrapper and point the binding at your component directly:

```kotlin
@Composable
@Implementation("com.yourco.designsystem.PriceTag")
fun PriceTag(
  amount: Int,
  currency: String,
  modifier: Modifier = Modifier,
) {}
```

The generated binding then calls `com.yourco.designsystem.PriceTag(amount = …, currency = …,
modifier = …)` with named arguments — no `PriceTagImpl` exists anywhere. The contract is unchanged,
only relocated: the target must have those parameter names with wire-compatible types, and the
compiler enforces it — a target that drifts fails your host build with a named-argument mismatch,
exactly as a missing wrapper does today.

**When the wrapper still earns its keep:** whenever a wire type needs mapping before your component
can be called — a `String` that becomes your sealed `ButtonStyle`, a token name that becomes a
`Color`. `@Implementation` is per component, so a real surface mixes both freely; Umbra's does
(`UmbraChip` binds directly, its two siblings keep wrappers).

Either way, register the generated binding once, before anything renders:

```kotlin
DogwoodRegistry.register(YourDesignSystemBinding)
```

That is the whole of it. You write no dispatch, no property decoding, no tag arithmetic and no skew
handling; those are generated. Three things to get right the first time:

- **`segmentId` is permanent.** It is half of every widget tag your segment will ever ship. Changing
  it is indistinguishable, on a client one release behind, from every component being replaced.
- **`@Affordance` on anything that governs what a user may do** — `enabled`, `checked`, `readOnly`.
  It changes how a client degrades: unreadable *cosmetic* skew is ignored, and unreadable
  *affordance* skew withholds the whole control. See the authoring guide.
- **A lock file appears beside your surface. Commit it.** It fails the build if a tag ever moves,
  and a moved tag does not fail to render — it renders the wrong widget on a client one version
  behind.

## 3. The payload

A Kotlin/JavaScript module that depends on the generated guest stubs and on `dogwood-compose`. Its
entry point binds one object and names the screens it offers
([`slice-guest/Main.kt`](../engine/samples/slice-guest/src/jsMain/kotlin/dev/dogwood/slice/Main.kt)):

```kotlin
@JsExport
fun main() {
  Zipline.get().bind<DogwoodGuestUi>(
    name = "dogwood.guest",
    instance = DogwoodGuest(
      "home" to { params -> HomeScreen(params) },
      "checkout" to { params -> CheckoutScreen(params) },
    ),
  )
}
```

Screens are ordinary `@Composable` functions using your components and `rememberSaveable`. The host
names which one it wants and hands it launch parameters; a name the payload does not offer is
reported back with the names it does, rather than rendering nothing.

**Run the authoring check on this module.** Applying `dev.dogwood.guest` fails the build on the
handful of APIs that do not work in a sandbox — per-frame animation, resource loaders — and names
the replacement. Finding those at build time rather than on a device is the whole point.

## 4. Signing, serving, and the first run

The payload is **code that your application will execute**, so it is signed and the host verifies
before running a byte of it. Two keys, not one, so you can rotate without stranding installed
clients.

**Never commit a signing key.** The ones in this repository's samples are throwaway development
keys, committed on purpose and labelled as such so the sample builds for anyone who clones it. Yours
go in `~/.gradle/gradle.properties` or your build's secret store:

```
./gradlew :your-guest:jsBrowserProductionWebpackZipline \
  -PdogwoodSigningKey=<hex> -PdogwoodRotationKey=<hex>
```

For development, one Gradle task serves the payload on `localhost:8080`:

```
./gradlew :your-guest:serveProductionWebpackZipline
```

Point the host's `MANIFEST_URL` at it and run. On an Android emulator the machine is `10.0.2.2`, not
`localhost`; on an iOS simulator it is `localhost`. Only the host knows which name reaches the
machine serving the payload, which is why the address is the host's to supply.

**There is no publish pipeline here** — see [operating](operating.md) §5 for what a real one owes
you, including the two things that are silent when wrong: cache headers that match immutability, and
`Content-Encoding: br` on the web.

---

## Supported versions, and the one rule about upgrading

The engine is built and verified with exactly one set of toolchains, and a product's safest
position is to match it. Nothing off this table is known to work, because nothing off this table
has ever been run — which is a statement about evidence, not about compatibility.

| Toolchain | Version | Who must match it |
|---|---|---|
| Kotlin | 2.3.20 | the host application **and** the payload build |
| Compose Multiplatform | 1.10.3 | the host application |
| Zipline | 1.27.0 | the host application **and** the payload build |
| Java toolchain | 21 | builds |

`samples-standalone/umbra`'s root build file is this table as code — the one place an adopter
declares all of it.

**The rule: hosts first, payloads after the fleet.** A payload meets *installed* hosts, including
every user who has not updated the app in months. So the three version streams move in a fixed
order:

1. **Upgrade the engine and your host application together**, ship through the stores, and wait for
   fleet coverage.
2. **Only then move the payload's toolchain**, because a payload built with a newer Kotlin or
   Zipline will be served to hosts still running the old one — and that pairing is exercised by
   nothing. The dictionary has versioning discipline for *component* skew; toolchain skew across
   the over-the-air gap has no equivalent check and no test anywhere.
3. **`0.1.0` makes no stability promise.** Nothing is API-frozen; a Dogwood upgrade is an
   engine-and-host upgrade, and belongs in step 1.

This is the honest whole of the policy today. What a cross-version guarantee would take — a
conformance row that loads a payload built at engine N with a host at N−1 — is recorded in
[`plans/adoption-audit.md`](../plans/adoption-audit.md) A6 rather than promised here.

## Where to go next

- **[Authoring guide](authoring.md)** — what payload code may and may not do, and why.
- **[Operating](operating.md)** — stopping a bad release, and what the device does on its own.
- **[Component reference](api/)** — generated from the surface, so it cannot disagree with it.

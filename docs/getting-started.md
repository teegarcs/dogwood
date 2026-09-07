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
[`samples-standalone/umbra`](../samples-standalone/umbra/) exists to prove.

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
DogwoodEnvironment(Modifier.fillMaxSize()) { configuration ->     // 1. tell the guest its viewport
  val delivered = DogwoodDelivery(                                 // 2. fetch, verify, cache, load
    dispatcher = ziplineDispatcher,                                //    ONE thread, 8 MB of stack
    trustedPublicKeys = yourPublicKeys,
    cache = ZiplineCache(...),
  ).load(applicationName = "your-app", manifestUrl = MANIFEST_URL)

  val experience = DogwoodExperience(delivered.zipline, ziplineDispatcher, uiScope)
  DogwoodSurface(experience, Modifier.fillMaxSize())               // 3. draw it
}
```

Three of those deserve a sentence:

- **`DogwoodEnvironment`** measures the slot and hands the guest its viewport, dark mode, locale and
  insets. A guest cannot work out its own size, and this is the only thing that can tell it.
- **The Zipline dispatcher is one thread and it is the only thread allowed to touch the guest.** It
  needs eight megabytes of stack, because interpreted composition is deeply recursive; Apple gives a
  background thread 512 kilobytes by default, which is why `DogwoodZiplineDispatcher` exists on iOS.
- **`trustedPublicKeys`** is what makes a payload yours. See §4.

**If you have several entry points** — tabs, a deep-link target, a settings section — use
`DogwoodShell` instead of a bare experience. It keeps a bounded number of them warm, restores their
saved state, and evicts the rest.

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
WebDelivery(DogwoodDictionary.segmentVersions).start("dogwood-manifest.json", experience)
```

Two things differ from mobile and both are worth knowing before you rely on them. **The client checks
the payload's declared dictionary versions before creating the Worker**, so a payload that honestly
declares itself newer is refused rather than degraded — the mobile hosts have no such pre-flight
check. And **`network` is the browser's guarantee, not Dogwood's**: your guest calls `fetch` inside
the page's origin, so set a `connect-src` Content Security Policy if you want the mobile allow-list's
behaviour. To publish an update to a live page, call `experience.update(newBridge)`; it carries the
running guest's saved state into its successor.

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

You then write one `PriceTagImpl` per component — the real Compose — and register the generated
binding once, before anything renders:

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

## Where to go next

- **[Authoring guide](authoring.md)** — what payload code may and may not do, and why.
- **[Operating](operating.md)** — stopping a bad release, and what the device does on its own.
- **[Component reference](api/)** — generated from the surface, so it cannot disagree with it.

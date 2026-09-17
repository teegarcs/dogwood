/*
 * Project Dogwood -- the preview back end: the same guest Application Programming Interface (API),
 * implemented by delegating to real Compose on the Java Virtual Machine (JVM).
 *
 * `plans/close-the-backlog.md` Group 4 (generator v2 milestone M6), and the design is
 * [Layer 1](../../specs/layer-1-authoring.md) section 3: **the authoring module compiles twice
 * from one source set.** The deployment path targets Kotlin/JavaScript and links
 * `dogwood-compose`, where every `Text`, `Column` and `Button` is a recording stub that emits wire
 * operations for a host to replay. The preview path targets the JVM and links *this* module, where
 * every one of those names is an ordinary Kotlin function whose body calls the genuine
 * `androidx.compose` function. Identical call sites, two back ends. Nothing here records anything,
 * nothing here serialises anything, and no host tree, wire or protocol exists between a payload's
 * source and the pixels.
 *
 * **What it is not.** A preview is one Compose runtime in one process. There is no protocol, no
 * batching and no thread hop, so a preview cannot show the two failure modes that matter most: a
 * payload that crosses the boundary every frame, and a payload rendered by a client that predates
 * a component it uses. Those need a device and a real client; `developer-experience.md` section 4
 * says so at length and this module's existence does not change it.
 *
 * **Stand-ins, declared.** Three Dogwood concepts have no real-Compose equivalent, because they are
 * questions only a host can answer. Each is answered here by something fixed and obvious rather
 * than by a guess dressed up as an answer:
 *
 * 1. **Colour and shape tokens.** `Color.token("primaryContainer")` asks the *host* for a role from
 *    its palette. The preview resolves tokens against a fixed Material 3 colour scheme
 *    (`PreviewPalette.kt`) and against nothing else. A token this palette does not carry renders
 *    magenta, so a misspelling is visible rather than silently black.
 * 2. **`Formats`.** Currency, dates and relative times cross as numbers precisely because the
 *    sandbox has no `Intl`; the host formats them in its own locale. The preview formats with the
 *    JVM's own `java.text` and `java.time` in the JVM's default locale, which is *a* host's answer
 *    and not *the* host's answer.
 * 3. **Host services.** `services()` is a live Zipline bridge to the client. There is no client
 *    here, so `HostServices.None` is what a payload sees and `AboutScreen`'s service lines say so
 *    on screen -- "this client offers no services at all" is the honest reading of a preview.
 *
 * **Not published.** No `maven-publish`, on purpose: the preview path is not a shipping artifact
 * and Layer 1 has not chosen between a desktop window and an Android library variant driving the
 * Android Studio pane. The desktop window is built (`:samples:slice-screens:preview`); the pane is
 * not.
 */
plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.composeMultiplatform)
}

kotlin { jvmToolchain(21) }

dependencies {
  // The guest vocabulary's value types -- `HostEnvironment`, `WidthClass`, the service interfaces --
  // are the protocol's, and a payload imports them by their real names on both paths. The JVM
  // variant of the same multiplatform module is what the preview links.
  api(project(":dogwood-protocol"))

  // Real Compose. `desktop.currentOs` rather than the bare artifacts because the preview's only
  // consumer today is a desktop window; an Android library variant for the Android Studio pane
  // would swap this line and keep every file below unchanged.
  api(compose.runtime)
  api(compose.runtimeSaveable)
  api(compose.foundation)
  api(compose.material3)
  api(compose.desktop.currentOs)
  api(libs.coroutines.core)
  api(libs.serialization.json)
}

/*
 * Project Dogwood -- the whole Swift-facing surface of an embedded Dogwood screen.
 *
 * One function. A product's existing iOS application calls it, gets a `UIViewController`, and
 * pushes or presents it like any other -- which is the point: from Swift's side a Dogwood screen is
 * a view controller, and nothing about the sandbox, the payload or the protocol leaks into the
 * application's architecture.
 *
 * ```swift
 * import DogwoodEmbed
 *
 * let screen = DogwoodEmbedKt.dogwoodViewController(
 *     manifestUrl: "https://payloads.example.com/manifest.zipline.json",
 *     entryPoint: "checkout",
 *     trustedKeys: ["release-1": "…hex…"],
 *     onFailure: { message in print(message) }
 * )
 * navigationController.pushViewController(screen, animated: true)
 * ```
 *
 * **What a product changes:** the registration line (their own design system) and the launch
 * parameters. **What a product keeps:** everything else in this file and every line of the build
 * configuration beside it.
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.dogwood.embed

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import app.cash.zipline.loader.ZiplineCache
import dev.dogwood.host.CallbackLog
import dev.dogwood.host.DogwoodDelivery
import dev.dogwood.host.DogwoodEnvironment
import dev.dogwood.host.DogwoodExperience
import dev.dogwood.host.DogwoodServiceHost
import dev.dogwood.host.DogwoodSurface
import dev.dogwood.host.DogwoodZiplineDispatcher
import dev.dogwood.host.FileReleaseStore
import dev.dogwood.host.GuardedLoad
import dev.dogwood.host.NSDateClock
import dev.dogwood.host.ReleaseGuard
import dev.dogwood.host.cachePath
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

/**
 * A Dogwood screen, as a view controller an existing application can present.
 *
 * @param manifestUrl where the signed payload lives.
 * @param entryPoint which experience in that payload to open.
 * @param trustedKeys the public halves of the keys you sign with, by name. Delivery refuses
 *   anything it cannot verify against one of them, before a byte of guest code runs.
 * @param launchParams what to open the experience with.
 * @param onFailure told when the screen cannot be shown, with a reason to log. It is a `(String) ->
 *   Unit` rather than an exception because Swift cannot catch a Kotlin one, and because a screen
 *   that fails to load is an ordinary product situation rather than a crash.
 */
private var tiersRegistered = false

/**
 * The Material 3 tier (plans/generator-v2.md), registered once per process rather than per screen:
 * the registry refuses a segment registered twice, and a product opens more than one screen.
 * Explicit rather than built in, so a host can leave the tier out.
 */
private fun registerTiersOnce() {
  if (tiersRegistered) return
  tiersRegistered = true
  dev.dogwood.host.DogwoodRegistry.register(dev.dogwood.material3.Material3Binding)
}

fun dogwoodViewController(
  manifestUrl: String,
  entryPoint: String,
  trustedKeys: Map<String, String>,
  launchParams: JsonObject = JsonObject(emptyMap()),
  onFailure: (String) -> Unit = {},
): UIViewController {
  registerTiersOnce()
  return ComposeUIViewController {
  val uiScope = rememberCoroutineScope()
  var experience by remember { mutableStateOf<DogwoodExperience?>(null) }
  var failure by remember { mutableStateOf<String?>(null) }

  // The dispatcher exists because Apple gives a background thread 512 kibibytes of stack and
  // interpreted composition needs eight megabytes. It is the one piece of platform knowledge a
  // product would otherwise have to discover from a stack overflow in production.
  val dispatcher = remember { DogwoodZiplineDispatcher() }

  // Surviving a bad publish is on by default here, as it is everywhere else since ADR-058.
  // Application support rather than caches: a purgeable record is a guard with amnesia.
  val guard = remember {
    ReleaseGuard(
      store = FileReleaseStore(
        file = cachePath("${directory(NSApplicationSupportDirectory)}/dogwood-release.json"),
      ),
      onReport = { onFailure("release guard: $it") },
    )
  }

  DogwoodEnvironment(Modifier.fillMaxSize()) { configuration ->
    LaunchedEffect(Unit) {
      try {
        val guarded = withContext(dispatcher) {
          DogwoodDelivery(
            dispatcher = dispatcher,
            trustedPublicKeys = trustedKeys,
            cache = ZiplineCache(
              fileSystem = FileSystem.SYSTEM,
              directory = cachePath("${directory(NSCachesDirectory)}/dogwood-cache"),
              maxSizeInBytes = 32L * 1024 * 1024,
            ),
          ).loadGuarded(applicationName = "dogwood", manifestUrl = manifestUrl, guard = guard)
        }
        when (guarded) {
          is GuardedLoad.Refused -> {
            failure = "release ${guarded.version} refused: ${guarded.reason}"
            onFailure(failure!!)
            return@LaunchedEffect
          }
          is GuardedLoad.Running -> {
            val created = DogwoodExperience(guarded.guest.zipline, dispatcher, uiScope)
            withContext(dispatcher) {
              created.start(
                entryPoint = entryPoint,
                services = DogwoodServiceHost(
                  log = CallbackLog { level, tag, message -> onFailure("[$level] $tag: $message") },
                  clock = NSDateClock(),
                ),
                configuration = configuration,
                launchParams = launchParams,
              )
            }
            experience = created
            // "It loaded" is not success: a payload that throws on its first composition has
            // loaded. Started and mounted is (ADR-049).
            guard.succeeded(guarded.version)
          }
        }
      } catch (e: Throwable) {
        failure = "could not load the payload: ${e.message}"
        onFailure(failure!!)
      }
    }

    MaterialTheme {
      Surface(Modifier.fillMaxSize()) {
        experience?.let { DogwoodSurface(it, Modifier.fillMaxSize()) }
          ?: failure?.let { Text(it, Modifier.fillMaxSize()) }
      }
    }
  }
}
}

private fun directory(kind: platform.Foundation.NSSearchPathDirectory): String =
  NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).first() as String

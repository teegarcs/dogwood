/*
 * Umbra's Swift-facing surface: one function, the shape `engine/samples/ios-embed` documents.
 *
 * ```swift
 * import UmbraEmbed
 *
 * let screen = UmbraEmbedKt.umbraViewController(
 *     manifestUrl: "https://payloads.example.com/manifest.zipline.json",
 *     entryPoint: "home",
 *     trustedKeys: ["release-1": "…hex…"],
 *     onFailure: { message in print(message) }
 * )
 * ```
 *
 * What a product changes from the engine's sample is exactly what this file changes: the
 * registration line names its own design system, and the entry point names its own screen.
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.umbra.ios

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
import dev.dogwood.host.DogwoodRegistry
import dev.dogwood.host.DogwoodServiceHost
import dev.dogwood.host.DogwoodSurface
import dev.dogwood.host.DogwoodZiplineDispatcher
import dev.dogwood.host.FileReleaseStore
import dev.dogwood.host.GuardedLoad
import dev.dogwood.host.NSDateClock
import dev.dogwood.host.ReleaseGuard
import dev.dogwood.host.cachePath
import dev.umbra.design.UmbraDesignSystemBinding
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

private var registered = false

/** Once, before anything renders. Idempotent because Swift may build several screens. */
private fun registerOnce() {
  if (registered) return
  registered = true
  DogwoodRegistry.register(UmbraDesignSystemBinding)
}

fun umbraViewController(
  manifestUrl: String,
  entryPoint: String = "home",
  trustedKeys: Map<String, String>,
  launchParams: JsonObject = JsonObject(emptyMap()),
  onFailure: (String) -> Unit = {},
): UIViewController {
  registerOnce()
  return ComposeUIViewController {
    val uiScope = rememberCoroutineScope()
    var experience by remember { mutableStateOf<DogwoodExperience?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    // Apple gives a background thread 512 kibibytes of stack; interpreted composition needs eight
    // megabytes. The dispatcher is the one piece of platform knowledge a product would otherwise
    // discover from a stack overflow in production.
    val dispatcher = remember { DogwoodZiplineDispatcher() }
    val guard = remember {
      ReleaseGuard(
        store = FileReleaseStore(
          file = cachePath("${directory(NSApplicationSupportDirectory)}/umbra-release.json"),
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
                directory = cachePath("${directory(NSCachesDirectory)}/umbra-cache"),
                maxSizeInBytes = 32L * 1024 * 1024,
              ),
            ).loadGuarded(applicationName = "umbra", manifestUrl = manifestUrl, guard = guard)
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

/*
 * Project Dogwood -- the Compose Multiplatform iOS host (roadmap Phase 6, step 1).
 *
 * The counterpart of `slice-desktop/Main.kt`, and deliberately as close to it as the platform
 * allows: a window, a dispatcher, a delivery, and a `DogwoodSurface`. Everything that decides
 * what appears on screen lives in `dogwood-host` and is common Kotlin, which is the whole claim
 * [Layer 5 ADR-004](../../../../../../../../adrs/layer-5/ADR-004-compose-multiplatform-sole-host-target.md)
 * makes. Three things below are genuinely iOS-only, and each is here rather than in the engine
 * because it is a property of the *application*, not of Dogwood:
 *
 *  1. **The `UIApplicationMain` entry point and its delegate.** Kotlin/Native can be the whole
 *     application binary, so there is no Swift and no Xcode project. A product embedding Dogwood
 *     in an existing application replaces this file with a `UIViewController` it already owns.
 *  2. **The Zipline thread.** `DogwoodZiplineDispatcher` (in `dogwood-host`) exists because Apple
 *     gives background threads 512 kibibytes of stack and interpreted composition needs eight
 *     megabytes.
 *  3. **`NSURLSession` in place of OkHttp**, for both the payload fetch and the guest's network
 *     service. Zipline supplies the adapter for the first; `UrlSessionNetwork` is the second.
 *
 * **What this renders is Skia through Metal, not UIKit widgets.** A `Button` here is Compose's
 * button drawn by Skiko onto a `CAMetalLayer`, exactly as it is on Android and desktop -- which
 * is the point (one binding implementation reaches every host) and also the cost ADR-004 records
 * (an iOS team gets Compose's rendering, not the system's).
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.dogwood.slice.ios

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import app.cash.zipline.loader.ZiplineCache
import dev.dogwood.host.CallbackAnalytics
import dev.dogwood.host.CallbackLog
import dev.dogwood.host.DogwoodDelivery
import dev.dogwood.host.DogwoodEnvironment
import dev.dogwood.host.DogwoodExperience
import dev.dogwood.host.DogwoodServiceHost
import dev.dogwood.host.DogwoodSurface
import dev.dogwood.host.DogwoodZiplineDispatcher
import dev.dogwood.host.MapFeatureFlags
import dev.dogwood.host.NSDateClock
import dev.dogwood.host.Palette
import dev.dogwood.host.UrlSessionNetwork
import dev.dogwood.host.allowUrlHosts
import dev.dogwood.host.cachePath
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.widthClass
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCValues
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSStringFromClass
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDelegateProtocol
import platform.UIKit.UIApplicationDelegateProtocolMeta
import platform.UIKit.UIApplicationMain
import platform.UIKit.UIResponder
import platform.UIKit.UIResponderMeta
import platform.UIKit.UIScreen
import platform.UIKit.UIWindow

/**
 * The public half of the key that signs the guest -- the same two keys the desktop and Android
 * hosts trust, because it is the same payload. Serve it with
 * `./gradlew :samples:slice-guest:serveProductionWebpackZipline`.
 */
private val TRUSTED_KEYS = mapOf(
  "dogwood-development" to "f9037012d6cd2446ec3025da7320bfb593641880b9339d316ba10da2aa18d102",
  "dogwood-development-2" to "64fcb07226f6b538ec7d09510f9a5073aeb43a50916cfc625761cc9b9a99b097",
)

/**
 * `localhost` reaches the development machine from the simulator, which shares its network stack.
 *
 * On a physical device it does not: that needs the machine's address on the local network, and
 * App Transport Security then wants the corresponding `Info.plist` exception. This is the same
 * `localhost` / `10.0.2.2` split the Android sample documents -- only the host knows which name
 * reaches the machine serving the payload.
 */
private const val DEV_SERVER = "http://localhost:8080"
private const val MANIFEST_URL = "$DEV_SERVER/manifest.zipline.json"

class DogwoodAppDelegate : UIResponder, UIApplicationDelegateProtocol {
  companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta

  @OverrideInit
  constructor() : super()

  private var _window: UIWindow? = null
  override fun window() = _window
  override fun setWindow(window: UIWindow?) {
    _window = window
  }

  override fun application(
    application: UIApplication,
    didFinishLaunchingWithOptions: Map<Any?, *>?,
  ): Boolean {
    val created = UIWindow(frame = UIScreen.mainScreen.bounds)
    created.rootViewController = ComposeUIViewController { SliceApp() }
    created.makeKeyAndVisible()
    _window = created
    return true
  }
}

@OptIn(ExperimentalForeignApi::class)
fun main() {
  val args = arrayOf("DogwoodSlice")
  memScoped {
    autoreleasepool {
      UIApplicationMain(
        args.size,
        args.map { it.cstr.ptr }.toCValues().ptr,
        null,
        NSStringFromClass(DogwoodAppDelegate),
      )
    }
  }
}

@Composable
private fun SliceApp() {
  val dark = isSystemInDarkTheme()
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    Surface(Modifier.fillMaxSize(), color = (if (dark) Palette.Dark else Palette.Light).canvas) {
      // The application insets the slot itself -- the notch and the home indicator are the host's
      // chrome, not the experience's -- so the environment is told the slot is already inset.
      // `DogwoodEnvironment` documents this case explicitly: Compose's inset *consumption* is not
      // visible to a composition read, so a host that pads and does not say so makes the guest pad
      // a second time and the screen gains a band of dead space.
      DogwoodEnvironment(
        Modifier.fillMaxSize().safeDrawingPadding(),
        windowInsets = WindowInsets(0, 0, 0, 0),
      ) { configuration ->
        SliceHost(configuration)
      }
    }
  }
}

@Composable
private fun SliceHost(configuration: HostEnvironment) {
  val uiScope = rememberCoroutineScope()
  var experience by remember { mutableStateOf<DogwoodExperience?>(null) }
  var failure by remember { mutableStateOf<String?>(null) }
  val latestConfiguration by rememberUpdatedState(configuration)

  // Rotating the device, or the operating system flipping to dark mode, pushes the new
  // environment into the running guest. Equal values are dropped inside the experience.
  LaunchedEffect(configuration, experience) {
    experience?.updateConfiguration(configuration)
  }

  // The only thread allowed to touch the guest, with an eight-megabyte stack. See
  // `DogwoodZiplineDispatcher`: Apple's 512-kibibyte default is not enough for QuickJS.
  val dispatcher = remember { DogwoodZiplineDispatcher() }

  LaunchedEffect(Unit) {
    try {
      val delivered = withContext(dispatcher) {
        DogwoodDelivery(
          dispatcher = dispatcher,
          trustedPublicKeys = TRUSTED_KEYS,
          // The same Layer 3 path Android and desktop run. Only the cache directory differs,
          // because naming a per-application cache directory is a platform question.
          cache = ZiplineCache(
            fileSystem = FileSystem.SYSTEM,
            directory = cachePath("${cachesDirectory()}/dogwood-cache"),
            maxSizeInBytes = 32L * 1024 * 1024,
          ),
        ).load(applicationName = "dogwood-slice", manifestUrl = MANIFEST_URL)
      }
      println("loaded version ${delivered.manifest.version}, verified by ${delivered.verifiedByKey}")
      // Constructed here, on the user-interface thread, because that is the thread it binds.
      val created = DogwoodExperience(delivered.zipline, dispatcher, uiScope)
      withContext(dispatcher) {
        created.start(
          entryPoint = "explore",
          services = DogwoodServiceHost(
            log = CallbackLog { level, tag, message -> println("[$level] $tag: $message") },
            clock = NSDateClock(),
            analytics = CallbackAnalytics { name, properties -> println("analytics: $name $properties") },
            featureFlags = MapFeatureFlags(mapOf("explore.showWasPrice" to "true")),
            // Default-deny, opened for the development server only, and cleartext named
            // explicitly. App Transport Security has to agree as well -- see `Info.plist`.
            network = UrlSessionNetwork(
              allow = allowUrlHosts("localhost", allowCleartextHosts = setOf("localhost")),
            ),
          ),
          configuration = latestConfiguration,
          launchParams = buildJsonObject {
            put("city", "Tokyo")
            put("country", "Japan")
            put("apiBaseUrl", DEV_SERVER)
          },
        )
      }
      experience = created
      // The Phase 6 accessibility evidence: wait for the first tree to be applied and drawn, then
      // print what `UIAccessibility` exposes. See `Accessibility.kt` for what this does and does
      // not settle.
      kotlinx.coroutines.delay(2_000)
      UIApplication.sharedApplication.keyWindow?.let { dumpAccessibilityTree(it) }
    } catch (e: Throwable) {
      failure = "could not load the guest: ${e.message}\n\n" +
        "Is the development server running?\n" +
        "  ./gradlew :samples:slice-guest:serveProductionWebpackZipline"
      println("dogwood: $failure")
      e.printStackTrace()
    }
  }

  failure?.let { Text(it, Modifier.fillMaxSize()) }
  experience?.let {
    Column(Modifier.fillMaxSize()) {
      Text(
        "${configuration.viewportWidthDp}×${configuration.viewportHeightDp}dp " +
          "(${configuration.widthClass}) · ${if (configuration.darkMode) "dark" else "light"} · " +
          "${configuration.locale}",
        style = MaterialTheme.typography.labelSmall,
      )
      // No scrolling wrapper: the guest's root is a lazy list and owns its own scrolling.
      DogwoodSurface(it, Modifier.fillMaxSize())
    }
  }
}

/** The application's own caches directory, which is where a downloaded payload belongs. */
private fun cachesDirectory(): String =
  NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
    .first() as String

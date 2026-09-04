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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.dp
import dev.dogwood.host.DogwoodShell
import dev.dogwood.host.DogwoodStateStore
import kotlinx.coroutines.launch
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.Foundation.NSProcessInfo

/**
 * The public half of the key that signs the guest -- the same two keys the desktop and Android
 * hosts trust, because it is the same payload. Serve it with
 * `./gradlew :samples:slice-guest:serveProductionWebpackZipline`.
 */
private val TRUSTED_KEYS = dev.dogwood.protocol.DogwoodTrust.DEVELOPMENT_KEYS

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

/**
 * Entry points this host offers, as tabs.
 *
 * The same shape `TabsActivity` has on Android, and for the same reason: the machinery worth
 * exercising is the shell, and a single-screen host never touches it.
 */
private val TABS = listOf(
  "explore" to "Explore",
  "feed" to "Stays",
  "app" to "Trips",
  "about" to "Account",
)

@Composable
private fun SliceHost(configuration: HostEnvironment) {
  val uiScope = rememberCoroutineScope()
  var shell by remember { mutableStateOf<DogwoodShell?>(null) }
  var current by remember { mutableStateOf(TABS.first().first) }
  var failure by remember { mutableStateOf<String?>(null) }
  var note by remember { mutableStateOf("starting…") }
  val latestConfiguration by rememberUpdatedState(configuration)

  // The only thread allowed to touch the guest, with an eight-megabyte stack. See
  // `DogwoodZiplineDispatcher`: Apple's 512-kibibyte default is not enough for QuickJS.
  val dispatcher = remember { DogwoodZiplineDispatcher() }
  // Disposed, because this file offers itself as the thing a product copies. An undisposed
  // dispatcher holds an eight-megabyte thread; moot for a single-screen sample and wrong in the
  // template it is meant to be.
  DisposableEffect(dispatcher) { onDispose { dispatcher.close() } }

  /*
   * Saved state, read exactly once per process.
   *
   * Read outside the composition for the reason ADR-010 records: `consume` deletes what it
   * returns, so a `LaunchedEffect` cannot promise it runs once -- and on Android it demonstrably
   * did not, restoring into a shell that was then thrown away while both halves reported success.
   */
  val store = remember {
    DogwoodStateStore(
      file = cachePath("${applicationSupportDirectory()}/dogwood-saved-state.json"),
      onProblem = { println("dogwood: saved state: $it") },
    )
  }
  val carried = remember { store.consume(nowEpochMillis) }

  LaunchedEffect(configuration, shell) { shell?.updateEnvironment(configuration) }

  LaunchedEffect(Unit) {
    try {
      val delivery = withContext(dispatcher) {
        DogwoodDelivery(
          dispatcher = dispatcher,
          trustedPublicKeys = TRUSTED_KEYS,
          cache = ZiplineCache(
            fileSystem = FileSystem.SYSTEM,
            directory = cachePath("${cachesDirectory()}/dogwood-cache"),
            maxSizeInBytes = 32L * 1024 * 1024,
          ),
        )
      }
      val built = DogwoodShell(
        delivery = delivery,
        applicationName = "dogwood-slice",
        manifestUrl = MANIFEST_URL,
        ziplineDispatcher = dispatcher,
        uiScope = uiScope,
        environment = latestConfiguration,
        services = DogwoodServiceHost(
          log = CallbackLog { level, tag, message -> println("[$level] $tag: $message") },
          clock = NSDateClock(),
          analytics = CallbackAnalytics { name, properties -> println("analytics: $name $properties") },
          featureFlags = MapFeatureFlags(mapOf("explore.showWasPrice" to "true")),
          network = UrlSessionNetwork(
            allow = allowUrlHosts("localhost", allowCleartextHosts = setOf("localhost")),
          ),
        ),
        capacity = 3,
        onSwap = { entry, status ->
          note = "[$entry] loaded, restored ${status.restoredKeys} state keys"
          println("dogwood: $note")
        },
        onEvict = { entry, keys ->
          note = "[$entry] evicted, kept $keys state keys"
          println("dogwood: $note")
        },
        onFailure = { entry, e -> println("dogwood: [$entry] failed: ${e.message}") },
      )
      if (carried.isNotEmpty()) {
        built.restoreAll(carried)
        note = "restored state for ${carried.keys} from a previous process"
        println("dogwood: $note")
      }
      shell = built
    } catch (e: Throwable) {
      failure = "could not load the guest: ${e.message}\n\n" +
        "Is the development server running?\n" +
        "  ./gradlew :samples:slice-guest:serveProductionWebpackZipline"
      println("dogwood: $failure")
      e.printStackTrace()
    }
  }

  /*
   * The verification drill, run only when asked for.
   *
   * `xcrun simctl` cannot tap or type, so the shell's behaviour on this platform cannot be driven
   * the way the Android drills drive it. Rather than assert nothing, the sample exercises the
   * shell directly when launched with `--dogwood-drill` and prints what happened -- the same
   * arrangement the Phase 0 harness uses, and the same reason: a property nobody can observe is a
   * property nobody has verified.
   *
   * It is opt-in so that an ordinary launch is an ordinary launch.
   */
  /*
   * The accessibility drill, which asserts rather than prints.
   *
   * Separate from `--dogwood-drill` because it answers a different question and needs the screen
   * left alone while it runs: it activates controls through the accessibility layer and watches
   * the tree for the consequence, so a drill switching tabs underneath it would look like a
   * failure. See `AccessibilityDrill.kt` for what this can and cannot settle.
   */
  LaunchedEffect(shell) {
    if (!NSProcessInfo.processInfo.arguments.contains("--dogwood-a11y")) return@LaunchedEffect
    shell ?: return@LaunchedEffect
    // The Diagnostics screen carries the controls the drill asserts on.
    current = "about"
    kotlinx.coroutines.delay(6_000)
    // The delegate owns the window, so it is asked rather than `UIApplication.keyWindow` --
    // which is deprecated for multi-scene applications and absent from the Kotlin bindings.
    val root = (UIApplication.sharedApplication.delegate as? DogwoodAppDelegate)?.window()
    if (root == null) {
      println("A11Y REFUSED there is no key window to walk")
    } else {
      val failures = runAccessibilityDrill(root)
      println("A11Y DONE failures=$failures")
    }
  }

  LaunchedEffect(shell) {
    val live = shell ?: return@LaunchedEffect
    if (!NSProcessInfo.processInfo.arguments.contains("--dogwood-drill")) return@LaunchedEffect
    kotlinx.coroutines.delay(3_000)
    println("DRILL warm-after-first=${live.warm}")

    // Visit every tab, which at a capacity of three must evict the least recently used.
    for ((entry, _) in TABS) {
      current = entry
      kotlinx.coroutines.delay(4_000)
      println("DRILL activated=$entry warm=${live.warm}")
      // Sampled rather than observed: `SkewReport` is plain sets written during composition, so
      // nothing invalidates when an entry lands. Reading it after the tab has settled is the only
      // way to see what the client had to contain.
      live.active.value?.skew?.takeIf { !it.isEmpty }?.let { println("DRILL skew=$it") }
    }

    // Back to one that is still warm: this must not produce another `loaded` line.
    current = live.warm.first()
    kotlinx.coroutines.delay(2_000)
    println("DRILL returned-to=${current} warm=${live.warm}")

    // Memory pressure, and then what the store actually writes.
    live.trimMemory(keep = 1)
    kotlinx.coroutines.delay(1_500)
    println("DRILL after-trim warm=${live.warm}")

    val states = live.snapshotAll()
    store.write(states, nowEpochMillis)
    println("DRILL wrote-state-for=${states.keys}")
    println("DRILL state-file=${applicationSupportDirectory()}/dogwood-saved-state.json")
  }

  LaunchedEffect(shell, current) {
    shell?.activate(
      current,
      launchParams = buildJsonObject {
        put("city", "Tokyo")
        put("country", "Japan")
        put("apiBaseUrl", DEV_SERVER)
      },
    )
  }

  /*
   * Backgrounding and memory pressure, which on iOS arrive as notifications rather than callbacks.
   *
   * `didEnterBackground` is the last moment guaranteed before the system may reclaim the process,
   * so it is where the snapshot has to be taken -- the same reasoning that put it in `onStop` on
   * Android, and equally unable to live anywhere synchronous, since reading a live guest's state
   * crosses to the Zipline thread.
   */
  DisposableEffect(shell) {
    val live = shell
    val centre = NSNotificationCenter.defaultCenter
    val background = centre.addObserverForName(
      name = UIApplicationDidEnterBackgroundNotification,
      `object` = null,
      queue = null,
    ) { _ ->
      val target = live ?: return@addObserverForName
      uiScope.launch {
        val states = target.snapshotAll()
        store.write(states, nowEpochMillis)
        println("dogwood: saved state for ${states.keys} on background")
      }
      Unit
    }
    val memory = centre.addObserverForName(
      name = UIApplicationDidReceiveMemoryWarningNotification,
      `object` = null,
      queue = null,
    ) { _ ->
      println("dogwood: memory warning; dropping all but the visible experience")
      live?.trimMemory(keep = 1)
      Unit
    }
    onDispose {
      centre.removeObserver(background)
      centre.removeObserver(memory)
      // The shell this effect was keyed on, not whatever the variable holds now -- the mistake
      // that, on Android, closed the shell seconds after it was built and discarded the state
      // restored into it.
      live?.close()
    }
  }

  Column(Modifier.fillMaxSize()) {
    failure?.let { Text(it, Modifier.padding(16.dp)) }
    shell?.active?.value?.let { live ->
      DogwoodSurface(live, Modifier.fillMaxWidth().weight(1f))
    }
    Text("warm: ${shell?.warm?.joinToString(", ") ?: "—"}", Modifier.padding(horizontal = 16.dp))
    Text(note, Modifier.padding(horizontal = 16.dp))
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
      for ((entry, label) in TABS) {
        Text(
          if (entry == current) "● $label" else label,
          Modifier.clickable { current = entry }.padding(8.dp),
        )
      }
    }
  }
}

/** Where saved state lives: application support, which is neither purgeable nor a cache. */
private fun applicationSupportDirectory(): String =
  NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
    .first() as String

private fun cachesDirectory(): String =
  NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
    .first() as String

/** Milliseconds since the epoch, from the platform clock the rest of this file already uses. */
private val nowEpochMillis: Long
  get() = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

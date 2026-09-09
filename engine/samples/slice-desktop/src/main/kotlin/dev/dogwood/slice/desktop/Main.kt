/*
 * Project Dogwood -- the Compose Multiplatform desktop host.
 *
 * roadmap.md Phase 1 step 2: "the development loop, not a shipping target." It runs stable
 * Compose Multiplatform on the Java Virtual Machine, where Zipline also runs, giving
 * second-scale iteration without an emulator. It costs almost nothing to maintain because the
 * host layer is common Kotlin -- every line that matters lives in `dogwood-host`, and this file
 * is only a window, a dispatcher, and a file read.
 */
package dev.dogwood.slice.desktop

import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import dev.dogwood.host.RenderTranscript
import dev.dogwood.host.LocalRenderTranscript
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.zipline.loader.ZiplineCache
import dev.dogwood.host.CallbackAnalytics
import dev.dogwood.host.CallbackLog
import dev.dogwood.host.DogwoodEnvironment
import dev.dogwood.host.DogwoodExperience
import dev.dogwood.host.ReleaseGuard
import dev.dogwood.host.GuardedLoad
import dev.dogwood.host.FileReleaseStore
import dev.dogwood.host.DogwoodServiceHost
import dev.dogwood.host.MapFeatureFlags
import dev.dogwood.host.OkHttpNetwork
import dev.dogwood.host.SystemClock
import dev.dogwood.host.allowHosts
import dev.dogwood.host.DogwoodSurface
import dev.dogwood.host.DogwoodDelivery
import dev.dogwood.host.Palette
import dev.dogwood.host.cachePath
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.widthClass
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okio.FileSystem

/**
 * The public half of the key that signs the guest. Serve the guest with
 * `./gradlew :samples:slice-guest:serveProductionWebpackZipline`.
 */
private val TRUSTED_KEYS = dev.dogwood.protocol.DogwoodTrust.DEVELOPMENT_KEYS

private const val DEV_SERVER = "http://localhost:8080"
/**
 * Where the payload comes from, overridable with `-Ddogwood.manifest=…`.
 *
 * A constant until the reference server existed, and the constant made a claim untestable: pointing
 * this host at a real deployment meant editing it. `tools/reference-server/check.sh` uses the
 * override to prove a client loads through the server rather than through the Gradle task -- and
 * the first attempt to prove it, before this existed, silently loaded from `:8080` instead and
 * looked exactly like success.
 */
private val MANIFEST_URL: String =
  System.getProperty("dogwood.manifest") ?: "$DEV_SERVER/manifest.zipline.json"

/**
 * `--dogwood-skew` turns this host into the desktop skew drill's instrument.
 *
 * The desktop is the one client where containment had never met a real skewed payload, for a
 * mechanical reason rather than a considered one: it has no `uiautomator` to dump a hierarchy and no
 * accessibility tree a drill can walk out of process. What it does have is the same instrument the
 * standalone Umbra check uses -- `RenderTranscript`, one line per composed node plus the measured
 * boxes -- and that is a *better* witness for these particular claims than either. A2 is about a
 * placeholder holding a sibling slot, and the transcript records composition order and node
 * identity directly, rather than letting geometry stand in for them.
 *
 * `tools/skew-drill/run-desktop.sh` arranges the two builds; this reads the result and exits.
 */
private fun skewRequested(args: Array<String>): Boolean = "--dogwood-skew" in args

fun main(args: Array<String>) = application {
  val skewCheck = skewRequested(args)
  // Acme's design system, registered before anything renders. One call, with an object the
  // generator emitted from Acme's own surface -- see `samples/product-design-system`.
  dev.dogwood.host.DogwoodRegistry.register(dev.acme.design.AcmeDesignSystemBinding)
  Window(
    onCloseRequest = ::exitApplication,
    state = rememberWindowState(width = 420.dp, height = 900.dp),
    title = "Dogwood — Phase 1 slice",
  ) {
    // A resizable window is the cheapest way to exercise the host environment subsystem: drag
    // the edge and the viewport the guest is told about changes with it, live.
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
      Surface(Modifier.fillMaxSize(), color = (if (dark) Palette.Dark else Palette.Light).canvas) {
        DogwoodEnvironment(Modifier.fillMaxSize()) { configuration ->
          SliceHost(configuration, skewCheck)
        }
      }
    }
  }
}

@androidx.compose.runtime.Composable
private fun SliceHost(configuration: HostEnvironment, skewCheck: Boolean = false) {
  val uiScope = rememberCoroutineScope()
  var experience by remember { mutableStateOf<DogwoodExperience?>(null) }
  var failure by remember { mutableStateOf<String?>(null) }
  // The drill's instrument. Costs one null check per node when nothing is provided, which is what
  // an ordinary launch does.
  val transcript = remember { RenderTranscript() }
  // Read at start time rather than captured at first composition, so a window resized while the
  // first load is still in flight still hands the guest the size it ends up with.
  val latestConfiguration by rememberUpdatedState(configuration)

  // Resizing the window, or the operating system flipping to dark mode, pushes the new
  // environment into the running guest. Equal values are dropped inside the experience's guest,
  // where the configuration is snapshot state, so this costs nothing while nothing moves.
  LaunchedEffect(configuration, experience) {
    experience?.updateConfiguration(configuration)
  }

  // The Zipline dispatcher is a single thread, and it is the only thread that may touch the
  // guest. Eight megabytes of stack because interpreted composition is deeply recursive.
  val dispatcher = remember {
    Executors.newSingleThreadExecutor { runnable ->
      Thread(null, runnable, "zipline", 8L * 1024 * 1024)
    }.asCoroutineDispatcher()
  }

  val guard = remember {
    ReleaseGuard(
      store = FileReleaseStore(
        file = cachePath(File(System.getProperty("java.io.tmpdir"), "dogwood-release-desktop.json").absolutePath),
      ),
      onReport = { println("release guard: $it") },
    )
  }

  LaunchedEffect(Unit) {
    try {
      // Layer 3: fetch, verify the Ed25519 signature, cache, load. The desktop host runs the
      // same delivery path as Android; only the cache factory differs, because the Android one
      // needs a Context for its SQLite driver.
      val guarded = withContext(dispatcher) {
        DogwoodDelivery(
          dispatcher = dispatcher,
          trustedPublicKeys = TRUSTED_KEYS,
          cache = ZiplineCache(
            fileSystem = FileSystem.SYSTEM,
            directory = cachePath(File(System.getProperty("java.io.tmpdir"), "dogwood-cache").absolutePath),
            maxSizeInBytes = 32L * 1024 * 1024,
          ),
        ).loadGuarded(
          applicationName = "dogwood-slice",
          manifestUrl = MANIFEST_URL,
          // A shell carries its own guard; a bare host carries one explicitly. Same record, same
          // reason: written before the release runs, so a crash-on-launch loop terminates
          // (ADR-049). This host ran unguarded until the audit's A3.
          guard = guard,
        )
      }
      val delivered = when (guarded) {
        is GuardedLoad.Refused -> {
          failure = "release ${guarded.version} refused: ${guarded.reason}" +
            (guarded.fallbackVersion?.let { " (last good: $it)" } ?: "")
          println(failure)
          return@LaunchedEffect
        }
        is GuardedLoad.Running -> guarded.guest
      }
      println("loaded version ${delivered.manifest.version}, verified by ${delivered.verifiedByKey}")
      // Constructed here, on the user-interface thread, because that is the thread it binds.
      val created = DogwoodExperience(delivered.zipline, dispatcher, uiScope)
      withContext(dispatcher) {
        created.start(
          // The Diagnostics screen under the skew drill, because that is where the drill's patch
          // composes its markers; `explore` otherwise, which is what a person launching this wants.
          entryPoint = if (skewCheck) "about" else "explore",
          // Default-deny, opened for the development server only, and cleartext named
          // explicitly rather than switched on globally.
          services = DogwoodServiceHost(
            log = CallbackLog { level, tag, message -> println("[$level] $tag: $message") },
            clock = SystemClock(),
            analytics = CallbackAnalytics { name, properties -> println("analytics: $name $properties") },
            featureFlags = MapFeatureFlags(mapOf("explore.showWasPrice" to "true")),
            network = OkHttpNetwork(
              client = OkHttpClient(),
              allow = allowHosts("localhost", allowCleartextHosts = setOf("localhost")),
            ),
          ),
          configuration = latestConfiguration,
          // `localhost` here, `10.0.2.2` on the emulator: the same machine, and only the host
          // knows which name reaches it.
          launchParams = buildJsonObject {
            put("city", "Tokyo")
            put("country", "Japan")
            put("apiBaseUrl", DEV_SERVER)
          },
        )
      }
      experience = created
      // What "worked" means, and it is not "loaded": a guest started and the host mounted it. A
      // payload that throws on its first composition has loaded (ADR-049).
      guard.succeeded(delivered.releaseVersion)
    } catch (e: Throwable) {
      failure = "could not load the guest: ${e.message}\n\n" +
        "Is the development server running?\n" +
        "  ./gradlew :samples:slice-guest:serveProductionWebpackZipline"
      e.printStackTrace()
    }
  }

  /*
   * The skew check. Bounded by the clock, decided by the transcript.
   *
   * Three claims on three separable pieces of evidence, and a control before any of them --
   * every assertion below is satisfied by a screen that never rendered, so the first thing to
   * establish is that one did.
   */
  if (skewCheck) {
    LaunchedEffect(Unit) {
      var waited = 0
      while (transcript.count == 0 && waited < 90_000) {
        delay(250)
        waited += 250
      }
      delay(1_500) // one settle, so measured sizes exist alongside the lines
      val dump = transcript.dump()
      println("SKEW TRANSCRIPT ${transcript.count} bindings")
      println(dump.prependIndent("SKEW "))

      var failed = 0
      fun conform(claim: String, ok: Boolean, detail: String) {
        if (!ok) failed++
        println("CONF $claim ${if (ok) "PASS" else "FAIL"} -- $detail")
      }

      val lines = dump.lines()
      fun indexOfMarker(marker: String) = lines.indexOfFirst { marker in it }
      val before = indexOfMarker("SKEW-BEFORE")
      val after = indexOfMarker("SKEW-AFTER")

      conform(
        "A2-control",
        before >= 0 && after >= 0,
        "the skewed screen rendered: ${transcript.count} bindings",
      )

      // A2 -- an unknown widget tag becomes a placeholder, and the sibling after it keeps its
      // place. On a transcript the evidence is direct rather than geometric: an `Unknown#` line
      // sits between the two markers, in the slot the unknown component occupies. Had the
      // create been skipped rather than placeheld, there would be no line there at all and
      // every later index in that container would have shifted by one.
      val between = if (before >= 0 && after > before) lines.subList(before + 1, after) else emptyList()
      val placeholder = between.any { it.startsWith("Unknown#") }
      conform(
        "A2",
        before >= 0 && after > before && placeholder,
        "SKEW-BEFORE at line $before, SKEW-AFTER at line $after, " +
          "between them: ${between.map { it.substringBefore(' ') }}",
      )

      // A3 -- an unknown property on a widget that owns no affordance is ignored, and it still
      // renders. The badge's own text is `SKEW-BADGE`, but a transcript records node identity
      // rather than every binding's label, so the evidence here is that a `Badge` composed in the
      // drill's block and was **not** withheld.
      val badge = lines.getOrNull(after + 1).orEmpty()
      conform(
        "A3",
        badge.startsWith("Badge#") && "withheld" !in badge,
        badge.ifEmpty { "nothing follows SKEW-AFTER" },
      )

      // A4 -- an unknown property on a widget that *owns* an affordance withholds the widget. One
      // of the things the payload might have been saying is "this is disabled", and this client
      // cannot read it (ADR-031).
      //
      // **Read as `withheld`, not as absence, and the difference is what the first run of this
      // drill got wrong.** A withheld widget draws an empty box with the guest's own modifier --
      // deliberately, so the gap is the size the guest asked for rather than the screen reflowing --
      // which means its binding *runs* and the transcript records it. The Android and iOS drills
      // read an accessibility tree, where an empty box carries no label, so absence is the right
      // test there. Here it is not: the first version of this check asserted that `SKEW-PAY` was
      // missing from a transcript that never carries labels, and passed without testing anything.
      val button = lines.getOrNull(after + 2).orEmpty()
      conform(
        "A4",
        button.startsWith("PrimaryButton#") && "withheld" in button,
        button.ifEmpty { "nothing follows the badge" },
      )

      // And it is reported, not merely survived. Containment nobody can see teaches no team that
      // its payloads have moved ahead of its devices.
      val skewReport = experience?.skew
      conform(
        "A4-reported",
        skewReport != null && skewReport.withheldWidgets.isNotEmpty(),
        skewReport?.toString() ?: "no experience to read",
      )


      println("CONF RESULT client=desktop passed=${5 - failed} failed=$failed skipped=0")
      exitProcess(if (failed == 0) 1.let { 1 } else 1).let { }
    }
  }

  failure?.let { androidx.compose.material3.Text(it, Modifier.fillMaxSize()) }
  experience?.let {
    Column(Modifier.fillMaxSize()) {
      androidx.compose.material3.Text(
        "${configuration.viewportWidthDp}×${configuration.viewportHeightDp}dp " +
          "(${configuration.widthClass}) · ${if (configuration.darkMode) "dark" else "light"} · " +
          "${configuration.locale}",
        style = MaterialTheme.typography.labelSmall,
      )
      // No scrolling wrapper: the guest's root is a lazy list and owns its own scrolling.
      CompositionLocalProvider(LocalRenderTranscript provides transcript.takeIf { skewCheck }) {
        DogwoodSurface(it, Modifier.fillMaxSize())
      }
    }
  }
}

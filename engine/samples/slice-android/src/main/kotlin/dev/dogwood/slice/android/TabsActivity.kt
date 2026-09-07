/*
 * Project Dogwood -- the Path B reference: navigation the HOST owns.
 *
 * Its counterpart is `AppShell.kt` in the guest, which is Path A: one experience whose tab bar is
 * guest Compose, one QuickJS runtime, screens that share state directly because they are the same
 * program. Read them together; the contrast is the point.
 *
 * Here every tab is an independent experience. A separate entry point, a separate runtime, a
 * separate heap, and -- the reason anyone accepts the cost -- a separate deployable owned by a
 * separate team. A team can ship its tab without coordinating a release with the teams either side
 * of it, which is a property no amount of guest-side modularity buys.
 *
 * **An honest caveat about this file.** The tabs here are entry points in one payload, not four
 * payloads from four teams. That is a stand-in, and it is a fair one: the boundary mechanics are
 * identical either way -- a separate manifest URL per experience is configuration, not
 * architecture -- and using one payload keeps the example to a single build so it can be read in
 * one sitting. What it does NOT demonstrate is independent *delivery*, which is the actual reason
 * to choose Path B.
 *
 * What it does demonstrate, all of it verifiable on screen:
 *
 *   - switching to a tab that is already warm, with no reload and its state intact;
 *   - a tab evicted under memory pressure, snapshotted, and restored on return;
 *   - a jump between experiences requested by guest code rather than by the tab bar.
 *
 * Launch with:
 *   adb shell am start -n dev.dogwood.slice.android/.TabsActivity
 */
package dev.dogwood.slice.android

import android.content.ComponentCallbacks2
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.zipline.loader.ZiplineCache
import dev.dogwood.host.CallbackAnalytics
import dev.dogwood.host.CallbackLog
import dev.dogwood.host.CallbackNavigation
import dev.dogwood.host.DogwoodDelivery
import dev.dogwood.host.DogwoodSkewReporter
import dev.dogwood.host.SkewDrain
import dev.dogwood.host.DogwoodEnvironment
import dev.dogwood.host.DogwoodServiceHost
import dev.dogwood.host.DogwoodShell
import dev.dogwood.host.DogwoodSurface
import dev.dogwood.host.MapFeatureFlags
import dev.dogwood.host.OkHttpNetwork
import dev.dogwood.host.Palette
import dev.dogwood.host.SystemClock
import dev.dogwood.host.allowHosts
import dev.dogwood.host.cachePath
import dev.dogwood.protocol.LogLevel
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okio.FileSystem
import androidx.lifecycle.lifecycleScope
import dev.dogwood.host.DogwoodStateStore
import kotlinx.coroutines.launch

private const val TAG = "DogwoodTabs"

/**
 * The tab bar, as data.
 *
 * Each entry names an experience by its entry point. In a real product these would be four
 * manifest URLs as readily as four entry points; the shell keys on the entry point either way.
 */
private val TABS = listOf(
  "explore" to "Explore",
  "feed" to "Stays",
  "app" to "Trips",
  "about" to "Account",
)

/** Routes this host handles. Enumerated, so a guest can ask before it draws a control. */
private val ROUTES = TABS.map { "experience/${it.first}" }.toSet()

class TabsActivity : ComponentActivity() {

  /**
   * Set when the shell exists, so the platform's memory callback can reach it.
   *
   * A plain field rather than composition state: [onTrimMemory] is a platform callback that
   * arrives on the main thread outside any composition, and it has to work whether or not
   * something is currently recomposing.
   */
  private var shell: DogwoodShell? = null

  /**
   * Where this application's experience state waits out a process death.
   *
   * Held by the activity rather than the composition because the write happens in `onStop`, which
   * can be the last thing that runs before Android reclaims the process.
   */
  /**
   * The previous process's state, read exactly once.
   *
   * Read here rather than inside the composition, and that is not tidiness. `consume` deletes what
   * it returns, so it must run once per process -- and a `LaunchedEffect(Unit)` cannot promise that.
   * The effect that builds the shell sits inside `DogwoodEnvironment`, whose `BoxWithConstraints`
   * subcomposes its content, so it can be disposed and restarted as constraints settle. When it
   * did, the second shell called `consume` on a file the first had already deleted, and the state
   * was restored into a shell that was then thrown away -- with both halves reporting success.
   */
  private val carriedState by lazy { stateStore.consume(System.currentTimeMillis()) }

  private val stateStore by lazy {
    DogwoodStateStore(
      file = cachePath(filesDir.resolve("dogwood-saved-state.json").absolutePath),
      onProblem = { Log.w(TAG, "saved state: $it") },
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val dark = isSystemInDarkTheme()
      val palette = if (dark) Palette.Dark else Palette.Light
      MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = palette.canvas) {
          Tabs(
            carried = carriedState,
            startEntryPoint = intent?.getStringExtra("entry"),
            onShell = { shell = it },
          )
        }
      }
    }
  }

  /**
   * Real memory pressure, from the platform rather than from a button.
   *
   * The shell keeps whole interpreters alive, so it is exactly the kind of thing this callback
   * exists for. Trimming to one keeps what is on screen and drops the rest, each snapshotted on
   * the way out, so returning to a dropped tab is a cold start that restores rather than a cold
   * start that forgets.
   */
  /*
   * The one lifecycle hook that matters for state.
   *
   * `onStop` is the last callback guaranteed to run before Android may reclaim the process, so it
   * is where the snapshot has to be taken. It cannot be `onSaveInstanceState`: reading a live
   * guest's state means crossing to the Zipline thread, which is suspending, and that callback is
   * synchronous on the main thread.
   *
   * Launched on a scope that outlives the activity's composition, because the composition is being
   * torn down around it.
   */
  override fun onStop() {
    super.onStop()
    val live = shell ?: return
    lifecycleScope.launch {
      val states = live.snapshotAll()
      stateStore.write(states, System.currentTimeMillis())
      Log.i(TAG, "saved state for ${states.keys} on stop")
    }
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
      Log.i(TAG, "onTrimMemory($level): dropping all but the visible experience")
      shell?.trimMemory(keep = 1)
    }
  }
}

@Composable
private fun Tabs(
  carried: Map<String, dev.dogwood.protocol.StateSnapshot>,
  startEntryPoint: String?,
  onShell: (DogwoodShell?) -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val uiScope = rememberCoroutineScope()

  /*
   * Saveable, not merely remembered, and the distinction is the whole drill.
   *
   * Restoring the guest's state across a process death accomplishes nothing on its own if the host
   * comes back on a different tab: the state is there, correctly, and the user is looking at
   * something else. That failure is invisible in a log -- the save and the restore both report
   * success -- and obvious on a screen.
   *
   * Which tab is open is host state, so the host saves it, using the platform's own mechanism.
   */
  // The entry point may be named by the launching intent, which is how the conformance drill opens
  // the screen it asserts on -- the same role `--dogwood-a11y` plays on iOS. `rememberSaveable`
  // still owns it afterwards, so this only chooses the starting tab.
  var current by rememberSaveable { mutableStateOf(startEntryPoint ?: TABS.first().first) }
  var routeParams by remember { mutableStateOf(JsonObject(emptyMap())) }
  var shell by remember { mutableStateOf<DogwoodShell?>(null) }
  var note by remember { mutableStateOf("starting…") }
  var skew by remember { mutableStateOf("") }

  // One thread for every experience. They are separate heaps, but guest work is serialized, which
  // is what a single-threaded dispatcher gives -- and it is why guest code must never block.
  val dispatcher = remember {
    Executors.newSingleThreadExecutor { runnable ->
      Thread(null, runnable, "zipline", 8L * 1024 * 1024)
    }.asCoroutineDispatcher()
  }

  // Rebound each recomposition: the navigation service is built once and shared by every session,
  // so it must route into the host state as it is now, not as it was when the shell was created.
  val latestNavigate by rememberUpdatedState<(String, JsonObject) -> Unit> { destination, params ->
    current = destination
    routeParams = params
  }

  /*
   * An unknown route is skew, and skew that nobody records is skew nobody learns from.
   *
   * `SkewReport.unknownRoutes` has existed for this since it was written -- *"routes a guest asked
   * for that this client does not handle; the host stayed where it was"* -- and nothing on this
   * platform filled it. The web host records it inside `DogwoodWebExperience`, because there the
   * navigate call passes through the experience; here it does not. A navigation service is
   * constructed by the **application**, before any experience exists, and handed in -- so the
   * engine has no seam at which to record this and the host is the only thing that can.
   *
   * Bound the same way `latestNavigate` is, and for the same reason: the service outlives any one
   * session, so it has to reach the experience that is live *now*.
   */
  val latestUnknownRoute by rememberUpdatedState<(String) -> Unit> { route ->
    Log.w(TAG, "navigate: unknown route '$route', staying put")
    shell?.active?.value?.skew?.unknownRoutes?.add(route)
  }

  val services = remember {
    DogwoodServiceHost(
      log = CallbackLog { level, tag, message ->
        Log.println(
          when (level) {
            LogLevel.Debug -> Log.DEBUG
            LogLevel.Info -> Log.INFO
            LogLevel.Warn -> Log.WARN
            LogLevel.Error -> Log.ERROR
          },
          "$TAG/$tag",
          message,
        )
      },
      clock = SystemClock(),
      // Deliberately unwired, and this host is the only one that leaves anything out.
      //
      // "Every service is optional and its absence is normal" is the surface's founding claim, and
      // it was false until this example was written: a null service cannot cross Zipline's
      // boundary, so the first host to skip one crashed the guest at `start`. Leaving it out here
      // keeps that path exercised by something someone actually runs.
      featureFlags = null,
      analytics = CallbackAnalytics { name, properties -> Log.i(TAG, "analytics: $name $properties") },
      // Default-deny, opened for exactly one host. The payload is downloaded and replaceable over
      // the air, so an open network service would be an exfiltration channel with this
      // application's name on it.
      network = OkHttpNetwork(
        client = OkHttpClient(),
        allow = allowHosts("10.0.2.2", allowCleartextHosts = setOf("10.0.2.2")),
      ),
      // The cross-experience jump. A guest asks for a destination; this host decides that the
      // destination is a tab. It could as easily have been a native screen -- and no guest would
      // be written differently.
      navigation = CallbackNavigation(
        routes = ROUTES,
        uiScope = uiScope,
        onNavigate = { route, params ->
          Log.i(TAG, "navigate: $route $params")
          latestNavigate(route.removePrefix("experience/"), params)
        },
        onUnknownRoute = { latestUnknownRoute(it) },
      ),
    )
  }

  DogwoodEnvironment(
    Modifier.fillMaxSize(),
    // This host draws its own tab bar and status line at the bottom, so the experience above must
    // not inset for them a second time. Consumption is not visible to a composition read, so it is
    // said here rather than inferred.
    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
  ) { environment ->

    // One shell for the whole tab bar, and one delivery inside it. Four shells would mean four
    // caches on one directory; four deliveries would mean the same. Path B is isolation between
    // guests, not duplication of everything around them.
    LaunchedEffect(Unit) {
      val delivery = withContext(dispatcher) {
        DogwoodDelivery(
          dispatcher = dispatcher,
          trustedPublicKeys = TRUSTED_KEYS,
          cache = ZiplineCache(
            context = context,
            fileSystem = FileSystem.SYSTEM,
            directory = cachePath(context.cacheDir.resolve("zipline-tabs").absolutePath),
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
        environment = environment,
        services = services,
        // Three of four tabs stay warm. The fourth is a cold start that restores its snapshot, so
        // the cap costs latency rather than the user's place.
        capacity = 3,
        onSwap = { entry, status ->
          note = "[$entry] loaded, restored ${status.restoredKeys} state keys"
          Log.i(TAG, note)
        },
        onEvict = { entry, keys ->
          note = "[$entry] evicted, kept $keys state keys"
          Log.i(TAG, note)
        },
        onFailure = { entry, failure ->
          note = "[$entry] failed: ${failure.message}"
          Log.e(TAG, note, failure)
        },
      )
      // Before the first activation, because a live experience owns its own state and the shell
      // will not overwrite one. Restoring is something that happens on the way in.
      if (carried.isNotEmpty()) {
        built.restoreAll(carried)
        note = "restored state for ${carried.keys} from a previous process"
        Log.i(TAG, note)
      }
      shell = built
    }

    /*
     * Publishing and disposal in one effect, deliberately.
     *
     * Publishing from the effect that *builds* the shell looks equivalent and is not: when `shell`
     * changes from null to built, Compose disposes the previous `DisposableEffect` before running
     * the new one, so a stale `onShell(null)` would land after the fresh `onShell(built)` and
     * leave the activity holding nothing. The symptom was silent -- `onTrimMemory` ran, called
     * through a null reference, and evicted nothing, while every other path kept working because
     * they read the shell from composition rather than from the field.
     */
    DisposableEffect(shell) {
      // Bound to a local, and both halves depend on it. `onDispose` runs when the key CHANGES, and
      // it reads whatever the variable holds at that moment -- which, on the null-to-built
      // transition, is the shell that was just built. Closing it there clears the shell's entries,
      // and with them the state restored from the previous process, seconds before anything is
      // activated. The symptom was a tab that came back correctly holding a screen that had
      // forgotten everything, with the save and the restore both reporting success.
      val live = shell
      onShell(live)
      onDispose {
        onShell(null)
        live?.close()
      }
    }

    LaunchedEffect(shell, environment) { shell?.updateEnvironment(environment) }

    // The skew report has to be **sampled, not observed**. It is plain sets rather than snapshot
    // state, deliberately: most of it is written *during* composition -- by the binding that could
    // not resolve a colour token, by the reader that had to clamp a value -- and writing snapshot
    // state there is not allowed. So nothing recomposes when an entry lands, and a composable that
    // merely reads it shows whatever was there when its own pass began.
    //
    // The same loop demonstrates the reporting seam, because a banner is not telemetry: it is
    // visible to whoever is looking at this screen and to nobody else. `SkewDrain` hands a
    // `DogwoodSkewReporter` what is **new** since the last drain, and a product implements that
    // interface with whatever it already uses. This one logs, which is the smallest honest example;
    // the point is that Dogwood stores and the host reports, so the transport, the batching and the
    // sampling rate stay the product's business.
    //
    // Drained here, on the user-interface thread, for the reason above: the sets are written during
    // composition and reading them elsewhere is a data race. The list `drain()` returns is a copy
    // and is safe to hand to a background thread.
    val reporter = remember {
      DogwoodSkewReporter { entries ->
        for (entry in entries) android.util.Log.w("DogwoodSkew", "${entry.kind} ${entry.value}")
      }
    }
    var drain by remember { mutableStateOf<SkewDrain?>(null) }
    LaunchedEffect(shell) {
      while (true) {
        delay(1_000)
        val report = shell?.active?.value?.skew
        skew = report?.takeIf { !it.isEmpty }?.toString().orEmpty()
        if (report != null) {
          // One drain per experience: a new generation gets a new report, and a drain that
          // outlived its report would think everything in the new one had already been sent.
          val current = drain?.takeIf { it.isFor(report) } ?: SkewDrain(report).also { drain = it }
          current.drainTo(reporter)
        }
      }
    }

    // Selecting a tab is an activation, not a rebuild. A tab that is already warm is published
    // before this call returns.
    LaunchedEffect(shell, current, routeParams) {
      shell?.activate(
        current,
        launchParams = buildJsonObject {
          put("city", "Tokyo")
          put("country", "Japan")
          put("apiBaseUrl", DEV_SERVER)
          for ((key, value) in routeParams) put(key, value)
        },
      )
    }

    Column(Modifier.fillMaxSize()) {
      shell?.active?.value?.let { live ->
        DogwoodSurface(live, Modifier.fillMaxWidth().weight(1f))
      }

      Divider()

      // The line that makes the claims checkable rather than merely stated: which experiences are
      // warm, and what the last load or eviction did to somebody's state.
      Text(
        "warm: ${shell?.warm?.joinToString(", ") ?: "—"}",
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.labelSmall,
      )
      Text(
        note,
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.labelSmall,
      )
      if (skew.isNotEmpty()) {
        Text(
          skew,
          Modifier.padding(horizontal = 16.dp),
          style = MaterialTheme.typography.labelSmall,
        )
      }

      Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        for ((entryPoint, label) in TABS) {
          TextButton(onClick = { current = entryPoint }) {
            Text(if (entryPoint == current) "● $label" else label)
          }
        }
        // Stands in for the platform's own callback, which is wired in [TabsActivity.onTrimMemory]
        // but is awkward to provoke on demand. Tap it, then return to a dropped tab: it cold-starts
        // and restores, rather than starting over.
        TextButton(onClick = { shell?.trimMemory(keep = 1) }) { Text("trim") }
      }
    }
  }
}

/*
 * Project Dogwood -- the Android host for the Phase 1 vertical slice.
 *
 * Android is the first platform: it is the host's own runtime, Zipline's most exercised target,
 * and native Compose. Note how little is here. Every decision about what a widget means, how a
 * modifier is rebuilt, how a batch is applied, and how an event gets back to the guest lives in
 * `dogwood-host`, which is common Kotlin. This file is an activity, a dispatcher, and an asset
 * read -- which is the claim that one generated binding reaches every platform, made concrete.
 */
package dev.dogwood.slice.android

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Modifier
import app.cash.zipline.loader.ZiplineCache
import dev.dogwood.host.CallbackAnalytics
import dev.dogwood.host.CallbackLog
import dev.dogwood.host.CallbackNavigation
import dev.dogwood.host.DogwoodEnvironment
import dev.dogwood.host.DogwoodServiceHost
import dev.dogwood.host.DogwoodShell
import dev.dogwood.host.MapFeatureFlags
import dev.dogwood.host.OkHttpNetwork
import dev.dogwood.host.Palette
import dev.dogwood.host.SessionStatus
import dev.dogwood.host.SystemClock
import dev.dogwood.host.Theme
import dev.dogwood.host.ThemeStore
import dev.dogwood.host.DogwoodSurface
import dev.dogwood.host.DogwoodDelivery
import dev.dogwood.host.allowHosts
import dev.dogwood.host.cachePath
import dev.dogwood.host.dogwoodLeakDetector
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.LogLevel
import dev.dogwood.protocol.widthClass
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okio.FileSystem
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.padding

private const val TAG = "DogwoodSlice"

// The trust anchor and the payload address live in `Slice.kt`, shared with `TabsActivity`.

/**
 * Entry points this host offers, and a demonstration of both composition models at once.
 *
 * A real application routes on a deep link, a navigation event, or a remote configuration value.
 * The sample offers a toggle, because seeing one payload serve several experiences is the whole
 * point of the entry-point contract.
 *
 * `app` is **Path A**: one experience whose tab bar and navigation are guest Compose. Selecting it
 * mounts a single runtime that never tears down as the user moves between its screens.
 *
 * The rest are **Path B**: an experience per surface, isolated by construction. Switching between
 * them goes through [DogwoodShell], which keeps them warm -- the switch costs the shell nothing
 * and the guest is not reloaded. Watch the log: a `load #` line appears once per entry point, not
 * once per tap.
 */
private val ENTRY_POINTS = listOf("app", "explore", "about", "feed")

/**
 * The experience the "split" toggle composes beneath the active one.
 *
 * Deliberately one of the ordinary entry points rather than a special one: the point being
 * demonstrated is that nothing about an experience has to know it is sharing a screen.
 *
 * `about` is chosen because it prints what it was launched with and what host services and
 * environment it can see. Composed beside another experience launched with different parameters,
 * it is a direct read-out of whether anything crosses between two runtimes on one screen.
 */
private const val SPLIT_COMPANION = "about"

class SliceActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Edge to edge, so the safe-area insets the host environment reports are real rather than
    // always zero. A guest that is never told about the status bar cannot avoid drawing under it.
    enableEdgeToEdge()
    setContent {
      val dark = isSystemInDarkTheme()
      val palette = if (dark) Palette.Dark else Palette.Light
      MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = palette.canvas) { SliceHost() }
      }
    }
  }

  @Composable
  private fun SliceHost() {
    var status by remember { mutableStateOf(SessionStatus()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var environment by remember { mutableStateOf(HostEnvironment()) }
    var entryPoint by remember { mutableStateOf(ENTRY_POINTS.first()) }
    // Composes a second experience beneath the first, from a second runtime. See [SPLIT_COMPANION].
    var split by remember { mutableStateOf(false) }
    // What a guest sent with its last navigation request, merged into the destination's launch
    // parameters. Without this the params would cross the boundary and land nowhere, which would
    // make the route a bare signal rather than a call.
    var routeParams by remember { mutableStateOf(JsonObject(emptyMap())) }

    // The theme is a document. The store's cached copy applies before any fetch -- the first
    // frame is the last brand this device saw, never a flash of the default while the network
    // decides -- and a fetched document swaps in live. The payload is not involved: the wire
    // bytes are identical under every brand, because screens only ever named the tokens.
    val themeStore = remember {
      ThemeStore(cacheDir.resolve("dogwood-theme.json")) { Log.w(TAG, "theme: $it") }
    }
    var theme by remember { mutableStateOf(themeStore.cached()) }
    val themeScope = rememberCoroutineScope()
    val themeClient = remember { okhttp3.OkHttpClient() }

    // The chrome sits below the status bar; the experience below it does not need to.
    Column(
      Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
    ) {
      // A banner, so a code update and the live host environment are both visible without
      // reading Logcat.
      Text(
        "load #${status.loadCount} · v${status.version} · signed by ${status.verifiedByKey} · " +
          "restored ${status.restoredKeys} keys",
        style = MaterialTheme.typography.labelSmall,
      )
      Text(
        "${environment.viewportWidthDp}×${environment.viewportHeightDp}dp " +
          "(${environment.widthClass}) · ${if (environment.darkMode) "dark" else "light"} · " +
          "${environment.locale} · text ×${environment.fontScale} · " +
          "safe ${environment.safeAreaTopDp}/${environment.safeAreaBottomDp}",
        style = MaterialTheme.typography.labelSmall,
      )

      // One payload, two experiences, and the host chooses. Switching restarts the guest, which
      // is correct: a different entry point is a different composition, not a different screen
      // inside one.
      Row {
        for (name in ENTRY_POINTS) {
          TextButton(onClick = { entryPoint = name }) {
            Text(if (name == entryPoint) "● $name" else name)
          }
        }
        // Two experiences on screen at once, from two runtimes, sharing one Zipline thread.
        TextButton(onClick = { split = !split }) {
          Text(if (split) "◨split" else "split")
        }
        // Brands, fetched as documents from the same origin as the payload. Watch the running
        // screen repaint; watch the guest reload count not move.
        for (brand in listOf("ocean", "sunset")) {
          TextButton(onClick = {
            themeScope.launch {
              theme = themeStore.refresh(themeClient, "$DEV_SERVER/theme-$brand.json")
            }
          }) {
            Text(if (theme.name == brand) "◆$brand" else brand)
          }
        }
      }

      failure?.let {
        Text(it, Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()))
      }

      // The environment wraps exactly the slot the experience occupies, and nothing else. The
      // two banner lines above are host chrome; a guest told it had their height would lay out
      // for room it does not have.
      DogwoodEnvironment(
        Modifier.fillMaxSize(),
        theme = theme,
        // This host has already inset the top for its banner, so the experience must not inset
        // it a second time. Consumption is not visible to a composition read, so it is said
        // here explicitly rather than inferred.
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
      ) { configuration ->
        Experience(
          entryPoint = entryPoint,
          split = split,
          theme = theme,
          configuration = configuration,
          onNavigate = { destination, params ->
            entryPoint = destination
            routeParams = params
          },
          routeParams = routeParams,
          onEnvironment = { environment = it },
          onStatus = { status = it },
          onFailure = { failure = it },
        )
      }
    }
  }

  /**
   * Owns the session and keeps it fed with the environment.
   *
   * Two effects, and the split is deliberate. The first stands the session up once. The second
   * runs on every environment change and pushes it in. `rememberUpdatedState` closes the race
   * between them: if the device rotates while the very first load is still in flight, the
   * session is still constructed with the environment the device is in by then, not the one it
   * was in when composition started.
   */
  /**
   * Owns the shell, and keeps it fed with the environment.
   *
   * The shell is created once and retains experiences across tab switches. Before it, this
   * function rebuilt the entire delivery stack on every switch, never closed the session it was
   * replacing, and opened a second `ZiplineCache` on the same directory -- a leaked interpreter
   * per tab. Path B is meant to be isolation, not waste.
   */
  @Composable
  private fun Experience(
    entryPoint: String,
    split: Boolean,
    theme: Theme,
    configuration: HostEnvironment,
    onNavigate: (String, JsonObject) -> Unit,
    routeParams: JsonObject,
    onEnvironment: (HostEnvironment) -> Unit,
    onStatus: (SessionStatus) -> Unit,
    onFailure: (String?) -> Unit,
  ) {
    val uiScope = rememberCoroutineScope()
    var shell by remember { mutableStateOf<DogwoodShell?>(null) }
    val latestConfiguration by rememberUpdatedState(configuration)

    // One thread, eight megabytes of stack, and it is the only thread that may touch the guest.
    // Shared by every experience the shell holds: they are separate heaps, but QuickJS work is
    // serialized, which is exactly what a single-threaded dispatcher gives.
    val dispatcher = remember {
      Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "zipline", 8L * 1024 * 1024)
      }.asCoroutineDispatcher()
    }

    // Leak detection, on in the development slice because the leak worth catching here is
    // peculiar to this architecture: a retained guest generation holds a whole QuickJS heap, and a
    // code update while a screen is live is the normal case.
    val leakDetector = remember(uiScope) {
      dogwoodLeakDetector(uiScope, leakThreshold = 10.seconds) { _, note ->
        Log.w(TAG, "LEAK: $note")
      }
    }
    DisposableEffect(leakDetector) {
      onDispose { leakDetector.close() }
    }

    // Rebound every recomposition so the service, which is built once and shared by every
    // session, always routes into the current host state rather than the one that existed when
    // the shell was created.
    val latestNavigate by rememberUpdatedState(onNavigate)

    // What this client lets the payload reach.
    val serviceHost = remember {
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
        analytics = CallbackAnalytics { name, properties ->
          Log.i(TAG, "analytics: $name $properties")
        },
        featureFlags = MapFeatureFlags(mapOf("explore.showWasPrice" to "true")),
        // Default-deny, opened for exactly one host. The payload is downloaded and replaceable
        // over the air, so an open network service would be an exfiltration channel with this
        // application's name on it.
        network = OkHttpNetwork(
          client = OkHttpClient(),
          allow = allowHosts("10.0.2.2", allowCleartextHosts = setOf("10.0.2.2")),
        ),
        /*
         * Routing, which is this host's business and not the payload's.
         *
         * The routes are enumerated, so a guest can ask before it draws a control and this client
         * can refuse a destination it does not have without a boundary round trip. A production
         * host with a deep-link table it cannot enumerate would pass an empty set instead and take
         * the checking on itself.
         *
         * The guest learns nothing about what happens next. Here a route swaps an experience; it
         * could as easily push a native screen or open a browser, and no payload would notice.
         */
        navigation = CallbackNavigation(
          routes = ENTRY_POINTS.map { "experience/$it" }.toSet(),
          uiScope = uiScope,
          onNavigate = { route, params ->
            Log.i(TAG, "navigate: $route $params")
            latestNavigate(route.removePrefix("experience/"), params)
          },
          // Skew, not a failure: a payload built against a client with more destinations than this
          // one must degrade rather than take a screen down when someone taps its button.
          onUnknownRoute = { Log.w(TAG, "navigate: unknown route '$it', staying put") },
        ),
      )
    }

    LaunchedEffect(configuration) {
      onEnvironment(configuration)
      shell?.updateEnvironment(configuration)
    }

    // Built once. Every tab switch reuses it, which is the difference between a warm swap and a
    // cold start.
    LaunchedEffect(Unit) {
      try {
        val delivery = withContext(dispatcher) {
          DogwoodDelivery(
            dispatcher = dispatcher,
            trustedPublicKeys = TRUSTED_KEYS,
            cache = ZiplineCache(
              context = applicationContext,
              fileSystem = FileSystem.SYSTEM,
              directory = cachePath(cacheDir.resolve("zipline").absolutePath),
              maxSizeInBytes = 32L * 1024 * 1024,
            ),
          )
        }
        shell = DogwoodShell(
          delivery = delivery,
          applicationName = "dogwood-slice",
          manifestUrl = MANIFEST_URL,
          ziplineDispatcher = dispatcher,
          uiScope = uiScope,
          environment = latestConfiguration,
          services = serviceHost,
          leakDetector = leakDetector,
          capacity = 3,
          onSwap = { entry, swapped ->
            onFailure(null)
            onStatus(swapped)
            Log.i(
              TAG,
              "[$entry] load #${swapped.loadCount}: restored ${swapped.restoredKeys} keys, " +
                "warm=${shell?.warm}",
            )
          },
          onEvict = { entry, keys -> Log.i(TAG, "[$entry] evicted, kept $keys state keys") },
          onFailure = { entry, failure ->
            Log.e(TAG, "[$entry] load failed", failure)
            onFailure(
              "could not load '$entry' from $MANIFEST_URL\n\n" +
                "Is the development server running?\n" +
                "  ./gradlew :samples:slice-guest:serveProductionWebpackZipline\n\n" +
                failure.stackTraceToString(),
            )
          },
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        Log.e(TAG, "failed to start the shell", e)
        onFailure(e.stackTraceToString())
      }
    }

    /*
     * Switch cost, measured rather than claimed.
     *
     * The clock starts when the tab is requested and stops on the first frame the host draws
     * afterwards, because that is the interval a person actually experiences. It deliberately
     * spans composition and draw rather than just `activate`, which returns immediately and would
     * flatter every number here. A warm switch should land inside one frame; a cold one pays for
     * a QuickJS instance, a payload load and a state restore, and should say so.
     */
    LaunchedEffect(shell, entryPoint, routeParams) {
      val live = shell ?: return@LaunchedEffect
      val warmAlready = entryPoint in live.warm
      val startedAt = android.os.SystemClock.elapsedRealtimeNanos()
      live.activate(
        entryPoint,
        launchParams = buildJsonObject {
          put("city", "Tokyo")
          put("country", "Japan")
          put("apiBaseUrl", DEV_SERVER)
          // Whatever the guest sent with the route. Last, so a destination can be told something
          // its host would otherwise have decided for it.
          for ((key, value) in routeParams) put(key, value)
        },
      )
      // Two intervals, not one, because they have different owners and only the first is the
      // shell's to defend. The first is how long until the experience exists and is published,
      // which for a warm entry point should be zero -- `activate` republishes synchronously. The
      // second is Compose measuring, laying out and drawing a tree that is already fully applied,
      // which the shell cannot make faster and which a native tab switch would also pay.
      //
      // Reporting only the total would credit the shell with a cost it does not control, and
      // would make an emulator's draw time look like a Zipline problem.
      val ready = live.activeEntryPoint.value == entryPoint && live.active.value != null
      if (!ready) {
        snapshotFlow { live.activeEntryPoint.value == entryPoint && live.active.value != null }
          .first { it }
      }
      val readyMs = (android.os.SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000.0
      withFrameNanos { }
      val drawnMs = (android.os.SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000.0
      Log.i(
        TAG,
        "[$entryPoint] ${if (warmAlready) "warm" else "cold"} switch: " +
          "experience ready in ${readyMs.roundToInt()} ms " +
          "(${if (ready) "synchronously" else "awaited"}), drawn in ${drawnMs.roundToInt()} ms",
      )
    }

    /*
     * The idle claim, audited.
     *
     * A hidden experience shares the window's frame clock with the visible one, so nothing about
     * being off-screen stops it asking for frames. This samples every warm experience twice a
     * second and reports any hidden one whose count moved, which is the only way to tell a guest
     * that is genuinely parked from one that is quietly animating into a surface nobody composes.
     */
    LaunchedEffect(shell) {
      var previous = emptyMap<String, Int>()
      while (true) {
        delay(500)
        val current = shell?.frameRequests() ?: continue
        val busy = current.filter { (key, count) ->
          key != entryPoint && count > (previous[key] ?: count)
        }
        if (busy.isNotEmpty()) Log.w(TAG, "hidden experiences asked for frames: $busy")
        previous = current
      }
    }

    DisposableEffect(shell) {
      onDispose { shell?.close() }
    }

    /*
     * The side-by-side case, on demand.
     *
     * Two experiences from two QuickJS runtimes, composed at the same time, sharing a single
     * Zipline thread. Mounting is what protects the companion from the warm cap: it is not the
     * most recently activated entry point, so recency alone would make it the coldest thing in
     * the pool and evict it while the user is looking at it.
     */
    DisposableEffect(shell, split) {
      if (split) {
        shell?.mount(
          SPLIT_COMPANION,
          // Deliberately unlike the active experience's parameters, so that anything crossing
          // between the two runtimes shows up as the wrong city on screen.
          launchParams = buildJsonObject {
            put("city", "Reykjavik")
            put("country", "Iceland")
            put("apiBaseUrl", DEV_SERVER)
          },
        )
      }
      onDispose { if (split) shell?.unmount(SPLIT_COMPANION) }
    }

    val companion = if (split) shell?.experience(SPLIT_COMPANION) else null
    Column(Modifier.fillMaxSize()) {
      shell?.active?.value?.let { live ->
        // Every surface carries its own environment, wrapping its own slot -- see the companion
        // below for why. Uniform rather than conditional: a rule that only holds when a second
        // surface happens to be present is a rule that is wrong the first time one appears.
        DogwoodEnvironment(
          Modifier.fillMaxWidth().weight(1f),
          theme = theme,
          windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        ) { activeConfiguration ->
          LaunchedEffect(activeConfiguration) {
            shell?.updateEnvironment(entryPoint, activeConfiguration)
          }
          // No scrolling wrapper: the guest's root is a lazy list and owns its own scrolling.
          DogwoodSurface(live, Modifier.fillMaxSize())
        }
      }
      companion?.let {
        HorizontalDivider()
        Text(
          "companion experience '$SPLIT_COMPANION', a second runtime",
          Modifier.padding(horizontal = 12.dp),
          style = MaterialTheme.typography.labelSmall,
        )
        // Its own environment, wrapping its own slot. The host environment describes the space an
        // experience actually occupies, so a second surface measured by the first one's wrapper
        // would be told it has the whole window -- and would lay out for room it does not have.
        DogwoodEnvironment(
          Modifier.fillMaxWidth().weight(1f),
          theme = theme,
          windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        ) { companionConfiguration ->
          LaunchedEffect(companionConfiguration) {
            shell?.updateEnvironment(SPLIT_COMPANION, companionConfiguration)
          }
          DogwoodSurface(it, Modifier.fillMaxSize())
        }
      }
    }
  }
}

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
import app.cash.zipline.loader.ZiplineCache
import dev.dogwood.host.DogwoodEnvironment
import dev.dogwood.host.DogwoodSession
import dev.dogwood.host.Palette
import dev.dogwood.host.SessionStatus
import dev.dogwood.host.DogwoodSurface
import dev.dogwood.host.DogwoodDelivery
import dev.dogwood.host.cachePath
import dev.dogwood.protocol.DogwoodConfiguration
import dev.dogwood.protocol.widthClass
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem

private const val TAG = "DogwoodSlice"

/**
 * The public half of the key that signs the guest, compiled into the host.
 *
 * A public key is meant to be public; this is the anchor the whole delivery path trusts.
 * Changing the signing key in `samples/slice-guest/build.gradle.kts` requires changing this with
 * it, and that coupling is what makes key rotation a deliberate operation rather than an
 * accident.
 */
private val TRUSTED_KEYS = mapOf(
  "dogwood-development" to "f9037012d6cd2446ec3025da7320bfb593641880b9339d316ba10da2aa18d102",
)

/**
 * On the Android emulator, 10.0.2.2 is the development machine. Serve the guest with
 * `./gradlew :samples:slice-guest:serveProductionWebpackZipline`.
 */
private const val MANIFEST_URL = "http://10.0.2.2:8080/manifest.zipline.json"

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
    var environment by remember { mutableStateOf(DogwoodConfiguration()) }

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

      failure?.let {
        Text(it, Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()))
      }

      // The environment wraps exactly the slot the experience occupies, and nothing else. The
      // two banner lines above are host chrome; a guest told it had their height would lay out
      // for room it does not have.
      DogwoodEnvironment(
        Modifier.fillMaxSize(),
        // This host has already inset the top for its banner, so the experience must not inset
        // it a second time. Consumption is not visible to a composition read, so it is said
        // here explicitly rather than inferred.
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
      ) { configuration ->
        Experience(
          configuration = configuration,
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
  @Composable
  private fun Experience(
    configuration: DogwoodConfiguration,
    onEnvironment: (DogwoodConfiguration) -> Unit,
    onStatus: (SessionStatus) -> Unit,
    onFailure: (String?) -> Unit,
  ) {
    val uiScope = rememberCoroutineScope()
    var session by remember { mutableStateOf<DogwoodSession?>(null) }
    val latestConfiguration by rememberUpdatedState(configuration)

    // One thread, eight megabytes of stack, and it is the only thread that may touch the guest.
    val dispatcher = remember {
      Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "zipline", 8L * 1024 * 1024)
      }.asCoroutineDispatcher()
    }

    LaunchedEffect(configuration) {
      onEnvironment(configuration)
      session?.updateConfiguration(configuration)
    }

    LaunchedEffect(Unit) {
      try {
        // Layer 3: fetch over the network, verify the manifest's Ed25519 signature against a key
        // compiled into this application, cache the modules on disk. The session then keeps
        // watching, and swaps the running experience whenever new code is published.
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
        val newSession = DogwoodSession(
          delivery = delivery,
          applicationName = "dogwood-slice",
          manifestUrl = MANIFEST_URL,
          ziplineDispatcher = dispatcher,
          uiScope = uiScope,
          initialConfiguration = latestConfiguration,
          onFailure = { e ->
            Log.e(TAG, "load failed", e)
            onFailure(
              "could not load the guest from $MANIFEST_URL\n\n" +
                "Is the development server running?\n" +
                "  ./gradlew :samples:slice-guest:serveProductionWebpackZipline\n\n" +
                e.stackTraceToString(),
            )
          },
          onSwap = { swapped ->
            onFailure(null)
            onStatus(swapped)
            Log.i(
              TAG,
              "load #${swapped.loadCount}: version ${swapped.version}, " +
                "verified by ${swapped.verifiedByKey}, " +
                "restored ${swapped.restoredKeys} saved state keys",
            )
          },
        )
        session = newSession
        newSession.run()
      } catch (e: CancellationException) {
        // Ordinary teardown -- this effect left the composition -- not a load failure. Reporting
        // it as one paints a stack trace over a screen that is simply going away.
        throw e
      } catch (e: Throwable) {
        Log.e(TAG, "failed to start guest", e)
        onFailure(e.stackTraceToString())
      }
    }

    session?.experience?.value?.let { live ->
      // No scrolling wrapper: the guest's root is a lazy list and owns its own scrolling.
      // Nesting one inside a scrollable parent gives it infinite height and crashes.
      DogwoodSurface(live, Modifier.fillMaxSize())
    }
  }
}

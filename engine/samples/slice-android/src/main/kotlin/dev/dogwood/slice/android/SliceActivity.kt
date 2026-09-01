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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.cash.zipline.loader.ZiplineCache
import dev.dogwood.host.DogwoodSession
import dev.dogwood.host.SessionStatus
import dev.dogwood.host.DogwoodSurface
import dev.dogwood.host.DogwoodDelivery
import dev.dogwood.host.cachePath
import dev.dogwood.protocol.DogwoodConfiguration
import java.util.concurrent.Executors
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
    setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) { SliceHost() }
      }
    }
  }

  @Composable
  private fun SliceHost() {
    val uiScope = rememberCoroutineScope()
    var session by remember { mutableStateOf<DogwoodSession?>(null) }
    var status by remember { mutableStateOf(SessionStatus()) }
    var failure by remember { mutableStateOf<String?>(null) }

    // One thread, eight megabytes of stack, and it is the only thread that may touch the guest.
    val dispatcher = remember {
      Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "zipline", 8L * 1024 * 1024)
      }.asCoroutineDispatcher()
    }

    val density = resources.displayMetrics.density
    val configuration = DogwoodConfiguration(
      density = density,
      fontScale = resources.configuration.fontScale,
      viewportWidthDp = (resources.displayMetrics.widthPixels / density).toInt(),
      viewportHeightDp = (resources.displayMetrics.heightPixels / density).toInt(),
      locale = resources.configuration.locales[0].toLanguageTag(),
    )

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
          uiScope = this,
          configuration = configuration,
          onFailure = { e ->
            Log.e(TAG, "load failed", e)
            failure = "could not load the guest from $MANIFEST_URL\n\n" +
              "Is the development server running?\n" +
              "  ./gradlew :samples:slice-guest:serveProductionWebpackZipline\n\n" +
              e.stackTraceToString()
          },
          onSwap = { swapped ->
            failure = null
            status = swapped
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
      } catch (e: Throwable) {
        Log.e(TAG, "failed to start guest", e)
        failure = e.stackTraceToString()
      }
    }

    failure?.let { Text(it, Modifier.fillMaxSize().verticalScroll(rememberScrollState())) }
    session?.experience?.value?.let { live ->
      androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        // A banner, so a code update is visible without reading Logcat.
        Text(
          "load #${status.loadCount} · v${status.version} · " +
            "signed by ${status.verifiedByKey} · restored ${status.restoredKeys} keys",
          style = MaterialTheme.typography.labelSmall,
        )
        DogwoodSurface(live, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
      }
    }
  }
}

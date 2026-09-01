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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.zipline.loader.ZiplineCache
import dev.dogwood.host.DogwoodExperience
import dev.dogwood.host.DogwoodSurface
import dev.dogwood.host.DogwoodDelivery
import dev.dogwood.host.cachePath
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem

/**
 * The public half of the key that signs the guest. Serve the guest with
 * `./gradlew :samples:slice-guest:serveProductionWebpackZipline`.
 */
private val TRUSTED_KEYS = mapOf(
  "dogwood-development" to "f9037012d6cd2446ec3025da7320bfb593641880b9339d316ba10da2aa18d102",
)

private const val MANIFEST_URL = "http://localhost:8080/manifest.zipline.json"

fun main() = application {
  Window(
    onCloseRequest = ::exitApplication,
    state = rememberWindowState(width = 420.dp, height = 900.dp),
    title = "Dogwood — Phase 1 slice",
  ) {
    MaterialTheme {
      Surface(Modifier.fillMaxSize()) {
        SliceHost()
      }
    }
  }
}

@androidx.compose.runtime.Composable
private fun SliceHost() {
  val uiScope = rememberCoroutineScope()
  var experience by remember { mutableStateOf<DogwoodExperience?>(null) }
  var failure by remember { mutableStateOf<String?>(null) }

  // The Zipline dispatcher is a single thread, and it is the only thread that may touch the
  // guest. Eight megabytes of stack because interpreted composition is deeply recursive.
  val dispatcher = remember {
    Executors.newSingleThreadExecutor { runnable ->
      Thread(null, runnable, "zipline", 8L * 1024 * 1024)
    }.asCoroutineDispatcher()
  }

  LaunchedEffect(Unit) {
    try {
      // Layer 3: fetch, verify the Ed25519 signature, cache, load. The desktop host runs the
      // same delivery path as Android; only the cache factory differs, because the Android one
      // needs a Context for its SQLite driver.
      val delivered = withContext(dispatcher) {
        DogwoodDelivery(
          dispatcher = dispatcher,
          trustedPublicKeys = TRUSTED_KEYS,
          cache = ZiplineCache(
            fileSystem = FileSystem.SYSTEM,
            directory = cachePath(File(System.getProperty("java.io.tmpdir"), "dogwood-cache").absolutePath),
            maxSizeInBytes = 32L * 1024 * 1024,
          ),
        ).load(applicationName = "dogwood-slice", manifestUrl = MANIFEST_URL)
      }
      println("loaded version ${delivered.manifest.version}, verified by ${delivered.verifiedByKey}")
      // Constructed here, on the user-interface thread, because that is the thread it binds.
      val created = DogwoodExperience(delivered.zipline, dispatcher, uiScope)
      withContext(dispatcher) { created.start() }
      experience = created
    } catch (e: Throwable) {
      failure = "could not load the guest: ${e.message}\n\n" +
        "Is the development server running?\n" +
        "  ./gradlew :samples:slice-guest:serveProductionWebpackZipline"
      e.printStackTrace()
    }
  }

  failure?.let { androidx.compose.material3.Text(it, Modifier.fillMaxSize()) }
  experience?.let {
    // No scrolling wrapper: the guest's root is a lazy list and owns its own scrolling.
    DogwoodSurface(it, Modifier.fillMaxSize())
  }
}

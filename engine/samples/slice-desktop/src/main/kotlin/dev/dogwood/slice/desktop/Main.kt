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
import app.cash.zipline.Zipline
import dev.dogwood.host.DogwoodExperience
import dev.dogwood.host.DogwoodSurface
import dev.dogwood.host.GuestBundle
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

private const val GUEST_DIR = "samples/slice-guest/build/zipline/ProductionWebpack"

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

  // The Zipline dispatcher is a single thread, and it is the only thread that may touch the
  // guest. Eight megabytes of stack because interpreted composition is deeply recursive.
  val dispatcher = remember {
    Executors.newSingleThreadExecutor { runnable ->
      Thread(null, runnable, "zipline", 8L * 1024 * 1024)
    }.asCoroutineDispatcher()
  }

  LaunchedEffect(Unit) {
    val dir = File(GUEST_DIR)
    require(dir.isDirectory) {
      "no compiled guest at $dir — run `./gradlew :samples:slice-guest:jsBrowserProductionWebpackZipline`"
    }
    val bundle = GuestBundle(
      manifestText = File(dir, "manifest.zipline.json").readText(),
      files = dir.listFiles()!!.filter { it.extension == "zipline" }.associate { it.name to it.readBytes() },
    )
    // The Zipline instance is created and loaded on its own thread; the experience is
    // constructed here, on the user-interface thread, because that is the thread it binds; and
    // start() goes back to Zipline's thread, because that is where guest work runs.
    val zipline = withContext(dispatcher) {
      Zipline.create(dispatcher).also { bundle.loadInto(it) }
    }
    val created = DogwoodExperience(zipline, dispatcher, uiScope)
    withContext(dispatcher) { created.start() }
    experience = created
  }

  experience?.let {
    DogwoodSurface(it, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
  }
}

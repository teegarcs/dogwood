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
import app.cash.zipline.Zipline
import dev.dogwood.host.DogwoodExperience
import dev.dogwood.host.DogwoodSurface
import dev.dogwood.host.GuestBundle
import dev.dogwood.protocol.DogwoodConfiguration
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

private const val TAG = "DogwoodSlice"

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
    var experience by remember { mutableStateOf<DogwoodExperience?>(null) }
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
        val names = assets.list("zipline").orEmpty()
        val bundle = GuestBundle(
          manifestText = assets.open("zipline/manifest.zipline.json").bufferedReader().readText(),
          files = names.filter { it.endsWith(".zipline") }
            .associateWith { assets.open("zipline/$it").use { input -> input.readBytes() } },
        )
        // The Zipline instance is created and loaded on its own thread; the experience is
        // constructed here, on the user-interface thread, because that is the thread it binds;
        // and start() goes back to Zipline's thread, because that is where guest work runs.
        val zipline = withContext(dispatcher) {
          Zipline.create(dispatcher).also { bundle.loadInto(it) }
        }
        val created = DogwoodExperience(zipline, dispatcher, uiScope)
        withContext(dispatcher) { created.start(configuration = configuration) }
        experience = created
        Log.i(TAG, "guest started")
      } catch (e: Throwable) {
        Log.e(TAG, "failed to start guest", e)
        failure = e.stackTraceToString()
      }
    }

    failure?.let { Text(it, Modifier.fillMaxSize().verticalScroll(rememberScrollState())) }
    experience?.let {
      DogwoodSurface(it, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
    }
  }
}

/*
 * Umbra's host: fetch, verify, render -- against published artifacts only.
 *
 * The shape is `slice-desktop`'s on purpose, because that file offers itself as the thing a
 * product copies; this is the copy, made outside the repository, which is what turns "a product
 * could" into "a product did".
 *
 * Launched with `--check`, it becomes the standalone check's instrument: it waits for the guest's
 * first composition, prints what the RENDER TRANSCRIPT recorded -- which bindings ran, at what
 * measured size -- and exits with the verdict in its exit code. The transcript rather than a
 * screenshot, for the reason every drill in the Dogwood repository gives: "the bindings executed
 * with the guest's data" is a checkable sentence, and a window that merely opened is not.
 */
package dev.umbra.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import app.cash.zipline.loader.ZiplineCache
import dev.dogwood.host.CallbackAnalytics
import dev.dogwood.host.CallbackLog
import dev.dogwood.host.DogwoodDelivery
import dev.dogwood.host.DogwoodEnvironment
import dev.dogwood.host.DogwoodExperience
import dev.dogwood.host.DogwoodRegistry
import dev.dogwood.host.DogwoodServiceHost
import dev.dogwood.host.DogwoodSurface
import dev.dogwood.host.LocalRenderTranscript
import dev.dogwood.host.RenderTranscript
import dev.dogwood.host.SystemClock
import dev.dogwood.host.cachePath
import dev.umbra.design.UmbraDesignSystemBinding
import java.io.File
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem

/**
 * The public halves of the throwaway development pair the guest signs with -- published inside
 * `dogwood-protocol`, which is what lets this build trust them without carrying a copy.
 */
private val TRUSTED_KEYS = dev.dogwood.protocol.DogwoodTrust.DEVELOPMENT_KEYS

private fun manifestUrl(): String =
  System.getProperty("umbra.manifest") ?: "http://localhost:8090/manifest.zipline.json"

fun main(args: Array<String>) {
  val check = "--check" in args
  // One registration, before anything renders -- the same single line every Dogwood host writes.
  DogwoodRegistry.register(UmbraDesignSystemBinding)
  application {
    Window(
      onCloseRequest = ::exitApplication,
      state = rememberWindowState(width = 420.dp, height = 700.dp),
      title = "Umbra",
    ) {
      val uiScope = rememberCoroutineScope()
      var experience by remember { mutableStateOf<DogwoodExperience?>(null) }
      var failure by remember { mutableStateOf<String?>(null) }
      val transcript = remember { RenderTranscript() }
      val dispatcher = remember {
        Executors.newSingleThreadExecutor { runnable ->
          Thread(null, runnable, "zipline", 8L * 1024 * 1024)
        }.asCoroutineDispatcher()
      }

      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          DogwoodEnvironment(Modifier.fillMaxSize()) { configuration ->
            LaunchedEffect(Unit) {
              try {
                val delivered = withContext(dispatcher) {
                  DogwoodDelivery(
                    dispatcher = dispatcher,
                    trustedPublicKeys = TRUSTED_KEYS,
                    cache = ZiplineCache(
                      fileSystem = FileSystem.SYSTEM,
                      directory = cachePath(
                        File(System.getProperty("java.io.tmpdir"), "umbra-cache").absolutePath,
                      ),
                      maxSizeInBytes = 32L * 1024 * 1024,
                    ),
                  ).load(applicationName = "umbra", manifestUrl = manifestUrl())
                }
                println("UMBRA loaded version ${delivered.manifest.version}, verified by ${delivered.verifiedByKey}")
                val created = DogwoodExperience(delivered.zipline, dispatcher, uiScope)
                withContext(dispatcher) {
                  created.start(
                    entryPoint = "home",
                    services = DogwoodServiceHost(
                      log = CallbackLog { level, tag, message -> println("[$level] $tag: $message") },
                      clock = SystemClock(),
                      analytics = CallbackAnalytics { name, properties -> println("analytics: $name $properties") },
                    ),
                    configuration = configuration,
                    launchParams = buildJsonObject { put("product", "umbra") },
                  )
                }
                experience = created
              } catch (e: Throwable) {
                failure = "could not load the payload: ${e.message}"
                e.printStackTrace()
              }
            }

            /*
             * The check. Bounded by the clock, decided by the transcript: a line proves a binding
             * ran with the guest's data, and a non-zero measured box proves the text stack drew
             * real glyphs. `UMBRA-ALIVE` in the dump is a string that exists only in the payload.
             */
            if (check) {
              LaunchedEffect(Unit) {
                var waited = 0
                while (transcript.count == 0 && waited < 60_000) {
                  delay(250)
                  waited += 250
                }
                delay(1_000) // one settle, so measured sizes exist alongside the lines
                println("UMBRA TRANSCRIPT ${transcript.count} bindings")
                println(transcript.dump().prependIndent("UMBRA "))
                val dump = transcript.dump()
                // Three separable claims, each on its own evidence: the payload's data reached a
                // drawn string (the marker, detailed by the engine's Text binding); Umbra's OWN
                // generated bindings ran (their names as transcript lines -- components that exist
                // nowhere in the Dogwood repository); and the text stack measured real glyphs.
                val marker = "UMBRA-ALIVE" in dump
                val ownComponents = "UmbraBanner#" in dump && "UmbraStepper#" in dump
                val measured = Regex("measured [1-9]\\d*x[1-9]\\d*").containsMatchIn(dump)
                val alive = marker && ownComponents && measured
                println(
                  if (alive) "UMBRA CHECK PASS"
                  else "UMBRA CHECK FAIL -- marker=$marker ownComponents=$ownComponents measured=$measured",
                )
                failure?.let { println("UMBRA CHECK FAIL -- $it") }
                exitProcess(if (alive && failure == null) 0 else 1)
              }
            }

            CompositionLocalProvider(LocalRenderTranscript provides transcript) {
              experience?.let { DogwoodSurface(it, Modifier.fillMaxSize()) }
            }
          }
        }
      }
    }
  }
}

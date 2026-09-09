/*
 * Project Dogwood -- one application, two independently shipped payloads, and what the second costs.
 *
 * `DogwoodShell` takes a single `manifestUrl`, so its "several experiences" are entry points inside
 * one payload. Two teams shipping on their own schedules therefore means **two shells**, and the
 * adoption audit (B3) recorded that the real cost of that had never been examined. This examines it.
 *
 * What a second shell actually is:
 *
 *   * a second `ZiplineCache` on its **own directory** -- two shells sharing one directory is the
 *     defect this sample would otherwise demonstrate rather than avoid, because the cache is keyed
 *     by module hash and two applications' modules would evict each other;
 *   * a second `ReleaseGuard` on its **own file**, because a quarantine is per release identity and
 *     one team's bad publish must not quarantine the other team's good one;
 *   * a second single-threaded dispatcher, because guest work is serialized per interpreter and
 *     sharing one thread would make each team's composition a queue behind the other's;
 *   * a second live QuickJS interpreter and heap.
 *
 * There is no shared warm pool and this sample does not build one. Two `DogwoodShell`s each keep
 * their own experiences warm, and an eviction policy spanning both would have to decide between two
 * teams' screens on a signal neither team can see.
 *
 * Run with `--measure` and it prints the numbers in `docs/multi-team.md` instead of waiting for a
 * person to look at it.
 */
package dev.dogwood.two

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.dogwood.host.DogwoodDelivery
import dev.dogwood.host.DogwoodEnvironment
import dev.dogwood.host.DogwoodShell
import dev.dogwood.host.DogwoodSurface
import dev.dogwood.host.FileReleaseStore
import dev.dogwood.host.LocalRenderTranscript
import dev.dogwood.host.ReleaseGuard
import dev.dogwood.host.RenderTranscript
import dev.dogwood.host.cachePath
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.FileSystem
import java.io.File
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import kotlin.time.TimeSource

private val TRUSTED_KEYS = dev.dogwood.protocol.DogwoodTrust.DEVELOPMENT_KEYS

/**
 * One team per entry, and everything that has to be separate is separate *in this table*.
 *
 * Written as data rather than as two copies of the same block, because the interesting property is
 * that nothing about a second team is special -- it is the same four things with different names,
 * and a reader should be able to see that at a glance rather than by diffing two paragraphs.
 */
private data class Team(
  val name: String,
  val manifestUrl: String,
  val entryPoint: String,
  /**
   * Something only this team's payload puts in the render transcript.
   *
   * **Chosen from what the transcript actually carries, which is not the same as what the screen
   * says.** The first version used the checkout screen's heading, "Diagnostics" — a `SectionHeader`,
   * which records its node name and not its text — and the assertion failed against a payload that
   * had rendered perfectly. A transcript line is `Name#id`, refined with the string for a `Text`;
   * anything else has to be identified structurally.
   */
  val marker: String,
) {
  val cacheDirectory: String get() = File(System.getProperty("java.io.tmpdir"), "dogwood-two-$name").absolutePath
  val guardFile: String get() = File(System.getProperty("java.io.tmpdir"), "dogwood-two-$name.json").absolutePath
}

private val TEAMS = listOf(
  // `TextInput#`: the checkout payload's Diagnostics screen has the only text field of the two.
  Team("checkout", System.getProperty("dogwoodManifestA") ?: "http://localhost:8080/manifest.zipline.json", "about", "TextInput#"),
  Team("search", System.getProperty("dogwoodManifestB") ?: "http://localhost:8081/manifest.zipline.json", "second", "SECOND-PAYLOAD"),
)

fun main() {
  /*
   * A system property the *build* forwards from a Gradle property, not a program argument.
   *
   * See this module's build file for why: `args`, `jvmArgs` on the task, and `System.getProperty`
   * in the build script each failed, and each failure looked identical -- an application that
   * loaded both payloads and then waited for a person.
   */
  val measure = System.getProperty("dogwoodMeasure") == "true"
  /*
   * Acme's design system, registered before anything renders -- and the first run of this sample
   * forgot to, which is worth recording because of how it failed.
   *
   * It did not render Acme's components as placeholders. The pre-flight dictionary check refused the
   * whole payload before it started:
   *
   *   [checkout/about] refused: the payload needs a dictionary this client does not have;
   *   unknown segments: [acme.designsystem]
   *
   * That is ADR-061 doing exactly its job, on a real configuration mistake, in a host written after
   * it landed. The old behaviour would have been a screen quietly missing three components.
   */
  dev.dogwood.host.DogwoodRegistry.register(dev.acme.design.AcmeDesignSystemBinding)
  application {
    Window(
      onCloseRequest = ::exitApplication,
      state = rememberWindowState(width = 900.dp, height = 800.dp),
      title = "Dogwood — two payloads",
    ) {
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          DogwoodEnvironment(Modifier.fillMaxSize()) { configuration ->
            val uiScope = rememberCoroutineScope()
            val transcript = remember { RenderTranscript() }
            var shells by remember { mutableStateOf<List<Pair<Team, DogwoodShell>>>(emptyList()) }
            var note by remember { mutableStateOf("starting…") }
            val timings = remember { mutableMapOf<String, Double>() }

            LaunchedEffect(Unit) {
              for (team in TEAMS) {
                val started = TimeSource.Monotonic.markNow()
                // One dispatcher per team. Guest work is serialized per interpreter, so a shared
                // thread would put each team's composition in a queue behind the other's -- and the
                // eight-megabyte stack is not optional: QuickJS composition is deeply recursive.
                val dispatcher = Executors.newSingleThreadExecutor { runnable ->
                  Thread(null, runnable, "zipline-${team.name}", 8L * 1024 * 1024)
                }.asCoroutineDispatcher()
                val delivery = withContext(dispatcher) {
                  DogwoodDelivery(
                    dispatcher = dispatcher,
                    trustedPublicKeys = TRUSTED_KEYS,
                    // Its own directory. Two shells on one directory would have each team's modules
                    // evicting the other's, which looks like a slow cold start and is a collision.
                    cache = ZiplineCache(
                      fileSystem = FileSystem.SYSTEM,
                      directory = cachePath(team.cacheDirectory),
                      maxSizeInBytes = 32L * 1024 * 1024,
                    ),
                  )
                }
                val shell = DogwoodShell(
                  delivery = delivery,
                  applicationName = "dogwood-${team.name}",
                  manifestUrl = team.manifestUrl,
                  ziplineDispatcher = dispatcher,
                  uiScope = uiScope,
                  environment = configuration,
                  // Its own file. A quarantine is per release identity, and one team's bad publish
                  // must not refuse the other team's good one.
                  releaseGuard = ReleaseGuard(
                    store = FileReleaseStore(file = cachePath(team.guardFile)),
                    onReport = { println("[${team.name}] release guard: $it") },
                  ),
                  onRefused = { entry, refusal ->
                    note = "[${team.name}/$entry] refused: ${refusal.reason}"
                    println(note)
                  },
                  onSwap = { entry, status ->
                    timings["${team.name}.firstTreeMs"] =
                      started.elapsedNow().inWholeMicroseconds / 1000.0
                    note = "[${team.name}/$entry] loaded version ${status.version}, " +
                      "verified by ${status.verifiedByKey}"
                    println(note)
                  },
                  onFailure = { entry, failure ->
                    note = "[${team.name}/$entry] failed: ${failure.message}"
                    println(note)
                  },
                )
                shell.activate(team.entryPoint)
                shells = shells + (team to shell)
              }
            }

            if (measure) {
              LaunchedEffect(Unit) {
                // Bounded by the clock and decided by the transcript, like every other check here.
                var waited = 0
                while (timings.size < TEAMS.size && waited < 120_000) {
                  delay(500); waited += 500
                }
                delay(3_000)
                val dump = transcript.dump()
                val lines = dump.lines()
                println("TWO TRANSCRIPT ${transcript.count} bindings")
                // Printed, not merely counted. A count tells you something rendered; the lines tell
                // you *what*, which is the only question worth asking when two guests share a
                // process -- and it is what shows a marker was chosen that the transcript cannot
                // carry, which is how the first version of these assertions was wrong.
                println(dump.prependIndent("TWO "))

                var failed = 0
                fun conform(claim: String, ok: Boolean, detail: String) {
                  if (!ok) failed++
                  println("CONF $claim ${if (ok) "PASS" else "FAIL"} -- $detail")
                }

                // The control that makes the measurements mean anything, and it is specifically
                // about *which* payload rendered: with two live guests in one process, "a screen
                // appeared" does not identify one. Each team's marker is its own.
                for (team in TEAMS) {
                  conform(
                    "M-${team.name}",
                    team.marker in dump,
                    "${team.name}'s own payload composed (marker ${team.marker})",
                  )
                }
                conform(
                  "M-independent",
                  TEAMS.all { timings.containsKey("${it.name}.firstTreeMs") },
                  "both shells reached a first tree: $timings",
                )

                /*
                 * The isolation, read off the transcript rather than asserted about the code.
                 *
                 * Each guest numbers its own nodes from one, so two live payloads in one process
                 * produce two `#1` lines -- and the host tells them apart because each tree belongs
                 * to its own experience, not because the identifiers are globally unique. If the two
                 * shells were sharing anything that mattered, this is where it would show: one id
                 * space, or one tree, or one of them missing.
                 */
                val roots = lines.count { it.endsWith("#1 " + it.substringBefore("#")) }
                conform(
                  "M-isolated",
                  roots == TEAMS.size,
                  "each payload numbers its own nodes from one: $roots root nodes at #1",
                )

                // The costs. Directory sizes are read from disk rather than reported by the cache,
                // because what a deployment pays for is what is on the disk.
                for (team in TEAMS) {
                  val bytes = File(team.cacheDirectory).walkTopDown()
                    .filter { it.isFile }.sumOf { it.length() }
                  println("MEASURE ${team.name}.cacheBytes $bytes")
                  println("MEASURE ${team.name}.firstTreeMs ${timings["${team.name}.firstTreeMs"]}")
                }
                val runtime = Runtime.getRuntime()
                System.gc()
                delay(500)
                println("MEASURE heapUsedBytes ${runtime.totalMemory() - runtime.freeMemory()}")
                println("MEASURE liveShells ${shells.size}")
                println("CONF RESULT client=desktop passed=${TEAMS.size + 2 - failed} failed=$failed skipped=0")
                exitProcess(if (failed == 0) 0 else 1)
              }
            }

            Column(Modifier.fillMaxSize()) {
              Text(note, style = MaterialTheme.typography.labelSmall)
              CompositionLocalProvider(LocalRenderTranscript provides transcript.takeIf { measure }) {
                for ((team, shell) in shells) {
                  Text(team.name, style = MaterialTheme.typography.titleSmall)
                  shell.active.value?.let { DogwoodSurface(it, Modifier.fillMaxWidth()) }
                }
              }
            }
          }
        }
      }
    }
  }
}

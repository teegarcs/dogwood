/*
 * Project Dogwood -- Phase 0 experiment 0.5 entry point, development host.
 *
 * Usage:
 *   ./gradlew :host-jvm:allocGc --args="--label 'MacBook Pro (development host)'"
 *
 * Separate from Main.kt because this run is long and answers a different question: what the
 * wire format ALLOCATES, and what the tail of a sustained stream looks like. Experiments 0.1
 * through 0.4 are unchanged and still run from Main.kt.
 */
package dev.dogwood.host

import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private const val DEFAULT_ZIPLINE_DIR = "guest/build/zipline/ProductionWebpack"

fun main(args: Array<String>) {
  val flags = parseAllocFlags(args)
  val root = File(flags["root"] ?: ".").absoluteFile
  val ziplineDir = flags["zipline-dir"]?.let(::File) ?: File(root, DEFAULT_ZIPLINE_DIR)
  val outDir = flags["out"]?.let(::File) ?: File(root, "results")
  val label = flags["label"] ?: "unnamed host"
  val rows = flags["rows"]?.toInt() ?: 23
  val allocIterations = flags["alloc-iterations"]?.toInt() ?: 2000
  val bigAllocIterations = flags["big-alloc-iterations"]?.toInt() ?: 200
  val tailIterations = flags["tail-iterations"]?.toInt() ?: 5000
  val traceIterations = flags["trace-iterations"]?.toInt() ?: 2000
  // Kibibytes, so the flag reads the way the numbers are discussed. Repeats are allowed and
  // meaningful: `--thresholds 16384,256,16384,256` interleaves two thresholds so an ordering
  // effect cannot masquerade as a threshold effect.
  val thresholds = flags["thresholds"]?.split(',')?.map { it.trim().toLong() * 1024 }
    ?: ALLOC_THRESHOLDS
  val phases = (flags["phases"] ?: "alloc,tail,trace").split(',').map { it.trim() }.toSet()

  require(ziplineDir.isDirectory) {
    "no compiled guest at $ziplineDir -- run `./gradlew :guest:jsBrowserProductionWebpackZipline` first"
  }

  val payload = GuestPayload(ziplineDir)
  val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(null, runnable, "zipline", 8L * 1024 * 1024)
  }
  val dispatcher = executor.asCoroutineDispatcher()

  try {
    runBlocking(dispatcher) {
      val driver = Phase0Driver(dispatcher, payload, warmups = 20, iterations = 200)
      val experiment = AllocationGcExperiment(
        driver = driver,
        rows = rows,
        variants = ALLOC_VARIANTS,
        smallChanges = 3,
        bigChanges = 572,
        allocIterations = allocIterations,
        bigAllocIterations = bigAllocIterations,
        tailIterations = tailIterations,
        traceIterations = traceIterations,
        thresholds = thresholds,
        phases = phases,
        log = ::println,
      )
      println("== 0.5 allocation and garbage collection ==")
      val results = experiment.run(
        label = label,
        platform = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, " +
          "Java ${System.getProperty("java.version")}",
      )
      outDir.mkdirs()
      val slug = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
      val file = File(outDir, "alloc-gc-$slug.json")
      file.writeText(
        Json { prettyPrint = true }
          .encodeToString(AllocationGcResults.serializer(), results),
      )
      println("wrote $file")
    }
  } finally {
    executor.shutdown()
  }
}

private fun parseAllocFlags(args: Array<String>): Map<String, String> {
  val flags = mutableMapOf<String, String>()
  var i = 0
  while (i < args.size) {
    val arg = args[i]
    require(arg.startsWith("--")) { "unexpected argument: $arg" }
    require(i + 1 < args.size) { "flag $arg has no value" }
    flags[arg.removePrefix("--")] = args[i + 1]
    i += 2
  }
  return flags
}

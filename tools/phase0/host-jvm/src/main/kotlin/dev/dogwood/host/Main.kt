/*
 * Project Dogwood -- Phase 0 measurement harness entry point.
 *
 * Usage:
 *   ./gradlew :host-jvm:run --args="--label 'MacBook (development host)'"
 *
 * Every flag has a default that matches the layout the Gradle build produces, so the
 * common case needs no flags at all.
 */
package dev.dogwood.host

import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private const val DEFAULT_ZIPLINE_DIR = "guest/build/zipline/ProductionWebpack"
private const val DEFAULT_JS_DIR = "guest/build/kotlin-webpack/js/productionExecutable"

fun main(args: Array<String>) {
  val flags = parseFlags(args)
  val root = File(flags["root"] ?: ".").absoluteFile
  val ziplineDir = flags["zipline-dir"]?.let(::File) ?: File(root, DEFAULT_ZIPLINE_DIR)
  val jsDir = flags["js-dir"]?.let(::File) ?: File(root, DEFAULT_JS_DIR)
  val outDir = flags["out"]?.let(::File) ?: File(root, "results")
  val label = flags["label"] ?: "unnamed host"
  val warmups = flags["warmups"]?.toInt() ?: 20
  val iterations = flags["iterations"]?.toInt() ?: 200
  val compositionIterations = flags["composition-iterations"]?.toInt() ?: 50
  val coldRuns = flags["cold-runs"]?.toInt() ?: 10
  val churn = flags["churn"]?.toInt() ?: 500
  val rowCounts = (flags["rows"] ?: "23,50").split(',').map { it.trim().toInt() }

  require(ziplineDir.isDirectory) {
    "no compiled guest at $ziplineDir -- run `./gradlew :guest:compileProductionExecutableKotlinJsZipline` first"
  }

  val payload = GuestPayload(ziplineDir.path)
  val minifiedJs = jsDir.listFiles { f -> f.extension == "js" }?.maxByOrNull { it.length() }

  // QuickJS composition is deeply recursive and interpreted frames are heavy, so the thread
  // that calls into it needs a large stack. Zipline itself sets maxStackSize to 6 MiB and
  // its own documentation expects callers to use 8 MiB threads.
  val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(null, runnable, "zipline", 8L * 1024 * 1024)
  }
  val dispatcher = executor.asCoroutineDispatcher()

  try {
    runBlocking(dispatcher) {
      val driver = Phase0Driver(dispatcher, payload, warmups, iterations)
      val notes = mutableListOf<String>()

      println("== 0.1 cold-start cost ==")
      val e1 = driver.experiment01(minifiedJs?.path, coldRuns, rowCounts.first())
      println("  bytecode ${e1.sizes.ziplineBytecodeBytes} B, minified JS ${e1.sizes.minifiedJsBytes} B, gzipped ${e1.sizes.gzippedJsBytes} B")
      println("  module load p50 ${"%.1f".format(e1.moduleLoad.p50Ms)} ms, main() p50 ${"%.1f".format(e1.mainFunction.p50Ms)} ms")
      println("  cold start to first composition p50 ${"%.1f".format(e1.coldStartToFirstComposition.p50Ms)} ms")

      println("== 0.2 composition and recomposition ==")
      val e2 = rowCounts.map { rows ->
        val loaded = driver.load()
        try {
          val result = driver.experiment02(loaded, rows, compositionIterations)
          println(
            "  rows=$rows nodes=${result.widgetNodes} changes=${result.initialChanges} " +
              "initial p50 ${"%.1f".format(result.initialComposition.p50Ms)} ms  " +
              "recompose(1) p95 ${"%.3f".format(result.recomposeOneNodeDiff.p95Ms)} ms  " +
              "recompose(2) p95 ${"%.3f".format(result.recomposeTwoNodeDiff.p95Ms)} ms",
          )
          result
        } finally {
          loaded.zipline.close()
        }
      }

      println("== 0.3 protocol cost per frame ==")
      val loaded3 = driver.load()
      val e3 = try {
        driver.experiment03(loaded3, rowCounts.first(), listOf(1, 10, 100, 1000))
      } finally {
        loaded3.zipline.close()
      }
      for (point in e3.points + e3.initialBatch) {
        println(
          "  changes=${point.changes} bytes=${point.bytes} " +
            "stringify p50 ${"%.3f".format(point.stringify.p50Ms)} ms " +
            "cross p50 ${"%.3f".format(point.crossZiplineSerialized.p50Ms)} ms",
        )
      }

      println("== 0.4 garbage collection ==")
      val e4 = driver.experiment04(
        rows = rowCounts.first(),
        thresholds = listOf(256L * 1024, 8L * 1024 * 1024, 16L * 1024 * 1024),
        churnIterations = churn,
      )
      for (point in e4.points) {
        println(
          "  gcThreshold=${point.gcThresholdBytes / 1024} KiB " +
            "recompose p99 ${"%.3f".format(point.recomposeUnderLoad.p99Ms)} ms " +
            "max ${"%.3f".format(point.recomposeUnderLoad.maxMs)} ms " +
            "forced gc max ${"%.3f".format(point.forcedPause.maxMs)} ms",
        )
      }

      notes += "The gate device named in the roadmap appendix is a low-end 2022-tier Android " +
        "phone. Numbers produced on any other host are NOT gate-valid; they establish the " +
        "harness and bound expectations."
      notes += "Experiment 0.4 used the host-forced gc() fallback, not the patched-QuickJS hook."
      notes += "Experiment 0.3 reports end-to-end crossing plus bytes; the five-pass internal " +
        "breakdown needs a locally patched Zipline build."

      val results = Phase0Results(
        label = label,
        platform = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, " +
          "Java ${System.getProperty("java.version")}",
        toolchain = Toolchain(
          zipline = "1.27.0",
          kotlin = "2.3.20",
          composeRuntimeJs = "1.12.0",
          coroutines = "1.10.2",
          serialization = "1.10.0",
          quickJsVersion = app.cash.zipline.QuickJs.version,
        ),
        referenceRows = rowCounts.first(),
        experiment01 = e1,
        experiment02 = e2,
        experiment03 = e3,
        experiment04 = e4,
        notes = notes,
      )

      outDir.mkdirs()
      val slug = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
      val json = Json { prettyPrint = true }
      File(outDir, "$slug.json").writeText(json.encodeToString(Phase0Results.serializer(), results))
      File(outDir, "$slug.md").writeText(renderMarkdown(results))
      println()
      println("wrote ${File(outDir, "$slug.json")}")
      println("wrote ${File(outDir, "$slug.md")}")
    }
  } finally {
    executor.shutdown()
  }
}

private fun parseFlags(args: Array<String>): Map<String, String> {
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

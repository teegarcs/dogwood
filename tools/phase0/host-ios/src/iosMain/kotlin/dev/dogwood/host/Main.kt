/*
 * Project Dogwood -- Phase 0 on iOS.
 *
 * Usage, from `tools/phase0`:
 *   ./gradlew :guest:jsBrowserProductionWebpackZipline :host-ios:linkReleaseExecutableIosSimulatorArm64
 *   xcrun simctl spawn booted host-ios/build/bin/iosSimulatorArm64/releaseExecutable/phase0.kexe \
 *     --root "$PWD" --label "iOS 17.5 simulator"
 *
 * The experiments themselves are in `host-core` and are the same code the development host and the
 * Android device run. What is here is the entry point and the thread the interpreter runs on.
 *
 * **The thread is not incidental.** QuickJS composition is deeply recursive and interpreted frames
 * are heavy; Zipline's own documentation expects an eight-megabyte stack, and the Java Virtual
 * Machine driver creates one explicitly. A Kotlin/Native executable's main thread does not offer
 * that guarantee, so this creates its own `NSThread` with the stack size set before it starts --
 * which is the same shape `DogwoodZiplineDispatcher` uses in the shipped iOS host, and for the
 * same reason.
 */
package dev.dogwood.host

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread
import kotlin.coroutines.CoroutineContext

private const val DEFAULT_ZIPLINE_DIR = "guest/build/zipline/ProductionWebpack"

/**
 * A single-threaded dispatcher on an `NSThread` with an eight-megabyte stack.
 *
 * Everything the guest does happens here, in order, on one thread -- which is both what QuickJS
 * requires (it is single-threaded and has no lock) and what makes the timings comparable with the
 * other two hosts, which use a single-threaded executor for the same reason.
 */
private class BigStackDispatcher(name: String) : CoroutineDispatcher() {
  private val work = Channel<Runnable>(Channel.UNLIMITED)
  private var closed = false

  val thread: NSThread = NSThread {
    while (true) {
      val runnable = runBlocking { work.receiveCatching().getOrNull() } ?: break
      runnable.run()
    }
  }.apply {
    this.name = name
    stackSize = 8uL * 1024uL * 1024uL
    start()
  }

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    check(!closed) { "the interpreter thread is closed" }
    work.trySend(block)
  }

  fun close() {
    closed = true
    work.close()
  }
}

fun main(args: Array<String>) {
  val flags = parseFlags(args)
  val root = (flags["root"] ?: ".").trimEnd('/')
  val ziplineDir = flags["zipline-dir"] ?: "$root/$DEFAULT_ZIPLINE_DIR"
  val outDir = flags["out"] ?: "$root/results"
  val label = flags["label"] ?: "iOS simulator"
  val warmups = flags["warmups"]?.toInt() ?: 20
  val iterations = flags["iterations"]?.toInt() ?: 200
  val compositionIterations = flags["composition-iterations"]?.toInt() ?: 50
  val coldRuns = flags["cold-runs"]?.toInt() ?: 10
  val churn = flags["churn"]?.toInt() ?: 500
  val rowCounts = (flags["rows"] ?: "23,50").split(',').map { it.trim().toInt() }
  val phases = (flags["phases"] ?: "main,alloc").split(',').map { it.trim() }.toSet()

  val payload = GuestPayload(ziplineDir)
  val dispatcher = BigStackDispatcher("zipline")
  val slug = label.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
    .joinToString("").trim('-').replace(Regex("-+"), "-")
  val json = Json { prettyPrint = true }

  try {
    if ("main" in phases) {
      runBlocking(dispatcher) {
        val driver = Phase0Driver(dispatcher, payload, warmups, iterations)
        val notes = mutableListOf<String>()

        println("== 0.1 cold-start cost ==")
        // No minified JavaScript path: the harness's own webpack output is a development-host
        // artifact and its size is machine independent, so it is measured once, there.
        val e1 = driver.experiment01(null, coldRuns, rowCounts.first())
        println("  bytecode ${e1.sizes.ziplineBytecodeBytes} B")
        println("  module load p50 ${e1.moduleLoad.p50Ms.toFixed(1)} ms, main() p50 ${e1.mainFunction.p50Ms.toFixed(1)} ms")
        println("  cold start to first composition p50 ${e1.coldStartToFirstComposition.p50Ms.toFixed(1)} ms")

        println("== 0.2 composition and recomposition ==")
        val e2 = rowCounts.map { rows ->
          val loaded = driver.load()
          try {
            val result = driver.experiment02(loaded, rows, compositionIterations)
            println(
              "  rows=$rows nodes=${result.widgetNodes} changes=${result.initialChanges} " +
                "initial p50 ${result.initialComposition.p50Ms.toFixed(1)} ms  " +
                "recompose(1) p95 ${result.recomposeOneNodeDiff.p95Ms.toFixed(3)} ms  " +
                "recompose(2) p95 ${result.recomposeTwoNodeDiff.p95Ms.toFixed(3)} ms",
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
              "stringify p50 ${point.stringify.p50Ms.toFixed(3)} ms " +
              "cross p50 ${point.crossZiplineSerialized.p50Ms.toFixed(3)} ms",
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
              "recompose p99 ${point.recomposeUnderLoad.p99Ms.toFixed(3)} ms " +
              "max ${point.recomposeUnderLoad.maxMs.toFixed(3)} ms " +
              "forced gc max ${point.forcedPause.maxMs.toFixed(3)} ms",
          )
        }

        notes += "The gate device named in the roadmap appendix is a low-end 2022-tier Android " +
          "phone. A simulator on an Apple-silicon Macintosh runs on the development machine's " +
          "processor and is NOT gate-valid; it establishes the iOS path and bounds expectations."
        notes += "Experiment 0.4 used the host-forced gc() fallback, not the patched-QuickJS hook."
        notes += "Experiment 0.1's minified-JavaScript sizes are absent here: they are properties " +
          "of the build, measured once on the development host, not of this machine."

        val results = Phase0Results(
          label = label,
          platform = platformDescription(),
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
        writeTextFile("$outDir/$slug.json", json.encodeToString(Phase0Results.serializer(), results))
        writeTextFile("$outDir/$slug.md", renderMarkdown(results))
        println()
        println("wrote $outDir/$slug.json")
        println("wrote $outDir/$slug.md")
      }
    }

    if ("alloc" in phases) {
      runBlocking(dispatcher) {
        val driver = Phase0Driver(dispatcher, payload, warmups = 20, iterations = 200)
        val experiment = AllocationGcExperiment(
          driver = driver,
          rows = rowCounts.first(),
          variants = ALLOC_VARIANTS,
          smallChanges = 3,
          bigChanges = 572,
          allocIterations = flags["alloc-iterations"]?.toInt() ?: 2000,
          bigAllocIterations = flags["big-alloc-iterations"]?.toInt() ?: 200,
          tailIterations = flags["tail-iterations"]?.toInt() ?: 5000,
          traceIterations = flags["trace-iterations"]?.toInt() ?: 2000,
          thresholds = flags["thresholds"]?.split(',')?.map { it.trim().toLong() * 1024 }
            ?: ALLOC_THRESHOLDS,
          phases = (flags["alloc-phases"] ?: "alloc,tail,trace").split(',').map { it.trim() }.toSet(),
          log = ::println,
        )
        val results = experiment.run(label = label, platform = platformDescription())
        writeTextFile(
          "$outDir/alloc-gc-$slug.json",
          json.encodeToString(AllocationGcResults.serializer(), results),
        )
        println()
        println("wrote $outDir/alloc-gc-$slug.json")
      }
    }
  } finally {
    dispatcher.close()
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

/*
 * Project Dogwood -- Phase 0 measurement harness, Android on-device host.
 *
 * This is the gate host: the roadmap's Phase 0 gate is defined on a low-end Android device,
 * and no number produced anywhere else opens or closes it. The activity runs the identical
 * experiment driver the development host runs, writes the same two result files to the
 * application's external files directory, and mirrors progress to Logcat under the tag
 * `Dogwood`.
 *
 * Pull the results with:
 *   adb shell run-as dev.dogwood.host.android ls files/results
 *   adb pull /sdcard/Android/data/dev.dogwood.host.android/files/results
 */
package dev.dogwood.host.android

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import dev.dogwood.host.ALLOC_THRESHOLDS
import dev.dogwood.host.ALLOC_VARIANTS
import dev.dogwood.host.AllocationGcExperiment
import dev.dogwood.host.AllocationGcResults
import dev.dogwood.host.GuestPayload
import dev.dogwood.host.Phase0Driver
import dev.dogwood.host.Phase0Results
import dev.dogwood.host.Toolchain
import dev.dogwood.host.renderMarkdown
import dev.dogwood.host.PauseAttribution
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private const val TAG = "Dogwood"

/** Matches the development host's defaults so the two runs are directly comparable. */
private const val WARMUPS = 20
private const val ITERATIONS = 200
private const val COMPOSITION_ITERATIONS = 50
private const val COLD_RUNS = 10
private const val CHURN = 500
private val ROW_COUNTS = listOf(23, 50)

class Phase0Activity : Activity() {
  private lateinit var output: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    output = TextView(this).apply { textSize = 11f }
    setContentView(ScrollView(this).apply { addView(output) })

    // QuickJS composition is deeply recursive and interpreted frames are heavy; Zipline
    // expects callers to use an eight-megabyte stack.
    val executor = Executors.newSingleThreadExecutor { runnable ->
      Thread(null, runnable, "zipline", 8L * 1024 * 1024)
    }
    // The driver runs on its own thread, NOT on the Zipline executor. The driver calls
    // `runBlocking` on the Zipline dispatcher, and blocking the single Zipline thread while
    // waiting for work dispatched to that same thread deadlocks immediately.
    Thread(
      null,
      {
        try {
          run(executor)
        } catch (e: Throwable) {
          Log.e(TAG, "Phase 0 run failed", e)
          report("FAILED: ${e.stackTraceToString()}")
        } finally {
          executor.shutdown()
        }
      },
      "phase0-driver",
      8L * 1024 * 1024,
    ).start()
  }

  private fun run(executor: java.util.concurrent.ExecutorService) {
    val ziplineDir = stageGuestFromAssets()
    val payload = GuestPayload(ziplineDir.path)
    val dispatcher = executor.asCoroutineDispatcher()

    // Experiment 0.5 is long and answers a different question, so it is opt-in:
    //   adb shell am start -n dev.dogwood.host.android/.Phase0Activity --es experiment alloc
    // With no extra, the activity runs experiments 0.1 through 0.4 exactly as before.
    if (intent?.getStringExtra("experiment") == "pauses") {
      // The one experiment that must run on a phone to mean anything: the outlier it explains was
      // measured on one, and a development machine has never produced it.
      runBlocking(dispatcher) { runPauseAttribution(payload, dispatcher) }
      return
    }

    if (intent?.getStringExtra("experiment") == "alloc") {
      runBlocking(dispatcher) { runAllocationGc(payload, dispatcher) }
      return
    }

    runBlocking(dispatcher) {
      val driver = Phase0Driver(dispatcher, payload, WARMUPS, ITERATIONS)

      report("0.1 cold-start cost")
      val e1 = driver.experiment01(minifiedJs = null, coldRuns = COLD_RUNS, rows = ROW_COUNTS.first())
      report("  bytecode ${e1.sizes.ziplineBytecodeBytes} B, module load p50 ${e1.moduleLoad.p50Ms} ms")
      report("  cold start to first composition p50 ${e1.coldStartToFirstComposition.p50Ms} ms")

      report("0.2 composition and recomposition")
      val e2 = ROW_COUNTS.map { rows ->
        val loaded = driver.load()
        try {
          driver.experiment02(loaded, rows, COMPOSITION_ITERATIONS).also {
            report("  rows=$rows nodes=${it.widgetNodes} recompose(1) p95 ${it.recomposeOneNodeDiff.p95Ms} ms")
          }
        } finally {
          loaded.zipline.close()
        }
      }

      report("0.3 protocol cost per frame")
      val loaded3 = driver.load()
      val e3 = try {
        driver.experiment03(loaded3, ROW_COUNTS.first(), listOf(1, 10, 100, 1000))
      } finally {
        loaded3.zipline.close()
      }
      report("  initial batch ${e3.initialBatch.bytes} B cross p50 ${e3.initialBatch.crossZiplineSerialized.p50Ms} ms")

      report("0.4 garbage collection")
      val e4 = driver.experiment04(
        rows = ROW_COUNTS.first(),
        thresholds = listOf(256L * 1024, 8L * 1024 * 1024, 16L * 1024 * 1024),
        churnIterations = CHURN,
      )
      for (point in e4.points) {
        report("  gcThreshold ${point.gcThresholdBytes / 1024} KiB recompose p99 ${point.recomposeUnderLoad.p99Ms} ms")
      }

      val label = "${Build.MANUFACTURER} ${Build.MODEL}"
      val results = Phase0Results(
        label = label,
        platform = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
          "${Build.SUPPORTED_ABIS.firstOrNull()}, ${Build.HARDWARE}",
        toolchain = Toolchain(
          zipline = "1.27.0",
          kotlin = "2.3.20",
          composeRuntimeJs = "1.12.0",
          coroutines = "1.10.2",
          serialization = "1.10.0",
          quickJsVersion = app.cash.zipline.QuickJs.version,
        ),
        referenceRows = ROW_COUNTS.first(),
        experiment01 = e1,
        experiment02 = e2,
        experiment03 = e3,
        experiment04 = e4,
        notes = listOf(
          "Run on a physical or virtual Android device. Only a physical low-end device of " +
            "the tier the Phase 0 harness appendix names produces gate-valid numbers; an " +
            "emulator runs on the development machine's processor and is not that.",
          "Experiment 0.4 used the host-forced gc() fallback, not the patched-QuickJS hook.",
          "Experiment 0.1 reports no minified-JavaScript size on device; only the bytecode " +
            "is shipped to a device, and the JavaScript size is recorded by the " +
            "development host run.",
        ),
      )

      val outDir = File(getExternalFilesDir(null) ?: filesDir, "results").apply { mkdirs() }
      val slug = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
      File(outDir, "$slug.json")
        .writeText(Json { prettyPrint = true }.encodeToString(Phase0Results.serializer(), results))
      File(outDir, "$slug.md").writeText(renderMarkdown(results))
      report("wrote ${File(outDir, "$slug.md")}")
    }
  }

  /**
   * Experiment 0.5: allocation per batch, the tail of a sustained load, and whether a
   * collection lands inside a frame.
   *
   * Iteration counts are lower than the development host's because an emulator or a low-end
   * device runs the same loops an order of magnitude slower, and a run nobody waits for
   * produces no numbers at all. They are reported in the result file, so nothing about the
   * sample size has to be taken on trust.
   */
  /**
   * Attributes the pauses under load, on the device where the outlier lives.
   *
   * Experiment 0.4 could time a *forced* collection and not say whether a naturally occurring
   * 22 ms frame was one. `PauseWatcher` reads the interval between QuickJS interrupt callbacks and
   * pairs it with the interpreter heap, which separates a collection from the scheduler without
   * the patched native build the Phase 0 appendix specified -- a build that would have needed the
   * Native Development Kit to run here at all.
   */
  private fun runPauseAttribution(
    payload: GuestPayload,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
  ) {
    val driver = Phase0Driver(dispatcher, payload, warmups = 20, iterations = 200)
    val result = driver.pauseAttribution(rows = ROW_COUNTS.first(), churnIterations = 500, rounds = 20)
    Log.i(TAG, "pauses observed=${result.observed} collections=${result.collections}")
    Log.i(TAG, "pauses worstCollection=${result.worstCollectionMs} worstOther=${result.worstOtherMs}")
    Log.i(TAG, "pauses overAFrame collection=${result.collectionsOverAFrame} other=${result.othersOverAFrame}")
    val outDir = File(getExternalFilesDir(null) ?: filesDir, "results").apply { mkdirs() }
    File(outDir, "pauses-${Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-")}.json")
      .writeText(Json { prettyPrint = true }.encodeToString(PauseAttribution.serializer(), result))
    Log.i(TAG, "pauses written to $outDir")
  }

  private fun runAllocationGc(
    payload: GuestPayload,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
  ) {
    val driver = Phase0Driver(dispatcher, payload, WARMUPS, ITERATIONS)
    val experiment = AllocationGcExperiment(
      driver = driver,
      rows = ROW_COUNTS.first(),
      variants = ALLOC_VARIANTS,
      smallChanges = 3,
      bigChanges = 572,
      // Overridable from the launch command, so a run can be retuned without a rebuild:
      //   adb shell am start -n ... --es experiment alloc --ei tail-iterations 6000
      allocIterations = intent.getIntExtra("alloc-iterations", 1000),
      bigAllocIterations = intent.getIntExtra("big-alloc-iterations", 100),
      tailIterations = intent.getIntExtra("tail-iterations", 6000),
      traceIterations = intent.getIntExtra("trace-iterations", 3000),
      thresholds = ALLOC_THRESHOLDS,
      phases = (intent.getStringExtra("phases") ?: "alloc,tail,trace")
        .split(',').map { it.trim() }.toSet(),
      log = ::report,
    )
    report("0.5 allocation and garbage collection")
    val label = "${Build.MANUFACTURER} ${Build.MODEL} emulator-or-device"
    val results = experiment.run(
      label = label,
      platform = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
        "${Build.SUPPORTED_ABIS.firstOrNull()}, ${Build.HARDWARE}",
    )
    val outDir = File(getExternalFilesDir(null) ?: filesDir, "results").apply { mkdirs() }
    val slug = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    val file = File(outDir, "alloc-gc-$slug.json")
    file.writeText(
      Json { prettyPrint = true }.encodeToString(AllocationGcResults.serializer(), results),
    )
    report("wrote $file")
  }

  /** Copies the staged `.zipline` modules and manifest out of assets onto the filesystem. */
  private fun stageGuestFromAssets(): File {
    val dir = File(cacheDir, "zipline").apply { mkdirs() }
    for (name in assets.list("zipline").orEmpty()) {
      assets.open("zipline/$name").use { input ->
        File(dir, name).outputStream().use { input.copyTo(it) }
      }
    }
    return dir
  }

  private fun report(line: String) {
    Log.i(TAG, line)
    runOnUiThread { output.append(line + "\n") }
  }
}

/*
 * Project Dogwood -- Phase 0 pause attribution, development host.
 *
 *   ./gradlew :host-jvm:pauses
 *
 * Answers the question experiment 0.4 left open: when a frame under load takes far longer than the
 * median, was that a garbage collection or was it the scheduler? See `PauseAttribution.kt` for the
 * instrument and for why the appendix's patched-QuickJS build could not answer it.
 */
package dev.dogwood.host

import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
  val flags = parseFlags(args)
  val root = File(flags["root"] ?: ".").absoluteFile
  val ziplineDir = File(root, "guest/build/zipline/ProductionWebpack")
  val label = flags["label"] ?: "unnamed host"
  val rows = flags["rows"]?.toInt() ?: 23
  val churn = flags["churn"]?.toInt() ?: 500
  val rounds = flags["rounds"]?.toInt() ?: 20

  require(ziplineDir.isDirectory) { "no compiled guest at $ziplineDir" }

  val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(null, runnable, "zipline", 8L * 1024 * 1024)
  }
  val dispatcher = executor.asCoroutineDispatcher()
  try {
    runBlocking(dispatcher) {
      val driver = Phase0Driver(dispatcher, GuestPayload(ziplineDir.path), warmups = 20, iterations = 200)
      val result = driver.pauseAttribution(rows, churn, rounds)

      println("== pause attribution ==")
      println("  intervals over the threshold: ${result.observed}")
      println("  attributable to collection:   ${result.collections}")
      println("  worst collection pause:       ${"%.2f".format(result.worstCollectionMs)} ms")
      println("  worst other pause:            ${"%.2f".format(result.worstOtherMs)} ms")
      println("  collection pauses over a frame (16.7 ms): ${result.collectionsOverAFrame}")
      println("  other pauses over a frame:                ${result.othersOverAFrame}")
      println("  bytes reclaimed across collections:       ${result.bytesReclaimed}")

      val out = File(root, "results")
      out.mkdirs()
      val slug = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
      val file = File(out, "pauses-$slug.json")
      file.writeText(Json { prettyPrint = true }.encodeToString(PauseAttribution.serializer(), result))
      println("wrote $file")
    }
  } finally {
    executor.shutdown()
  }
}

private fun parseFlags(args: Array<String>): Map<String, String> {
  val flags = mutableMapOf<String, String>()
  var i = 0
  while (i < args.size) {
    require(args[i].startsWith("--")) { "unexpected argument: ${args[i]}" }
    require(i + 1 < args.size) { "flag ${args[i]} has no value" }
    flags[args[i].removePrefix("--")] = args[i + 1]
    i += 2
  }
  return flags
}

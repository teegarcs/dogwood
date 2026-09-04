/*
 * Project Dogwood -- Phase 0.
 *
 * Prints the bytes that actually cross the Layer 4 boundary, and where they go.
 *
 * This exists because "what are we sending over the bridge?" deserves an answer made of the
 * real string rather than of a description of it. Zipline's `Call.encodedCall` is the literal
 * payload handed to `CallChannel`, so the listener below captures it verbatim.
 *
 *   ./gradlew :host-jvm:dumpWire
 */
package dev.dogwood.host

import app.cash.zipline.Call
import app.cash.zipline.EventListener
import app.cash.zipline.Zipline
import dev.dogwood.protocol.Change
import dev.dogwood.protocol.ChangeBatch
import dev.dogwood.protocol.ChildAdd
import dev.dogwood.protocol.ChildMove
import dev.dogwood.protocol.ChildRemove
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.DogwoodJson
import dev.dogwood.protocol.DogwoodJsonArrayPolymorphic
import dev.dogwood.protocol.ModifierSet
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.Id
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

private val ROOT = Id(0)

private class CallCapture : EventListener() {
  val calls = mutableListOf<Pair<String, String>>()

  override fun callStart(zipline: Zipline, call: Call): Any? {
    calls += call.function.signature to call.encodedCall
    return null
  }
}

fun main(args: Array<String>) {
  val root = File(".").absoluteFile
  val rows = args.firstOrNull()?.toInt() ?: 23
  val payload = GuestPayload(File(root, "guest/build/zipline/ProductionWebpack").path)
  val executor = Executors.newSingleThreadExecutor { r -> Thread(null, r, "zipline", 8L * 1024 * 1024) }
  val dispatcher = executor.asCoroutineDispatcher()
  val capture = CallCapture()
  val out = StringBuilder()

  try {
    runBlocking(dispatcher) {
      val driver = Phase0Driver(dispatcher, payload, warmups = 0, iterations = 1)
      val loaded = driver.load(eventListener = capture)
      try {
        val composition = loaded.guest.composeReferenceScreen(rows, warmups = 0, iterations = 1)

        // A tap-sized crossing: one change.
        capture.calls.clear()
        loaded.guest.crossBatch(1, iterations = 1, encoded = false, warmups = 0)
        val tapCall = capture.calls.last { it.first.contains("sendChanges") }.second

        // A screen-open crossing: the whole initial batch.
        capture.calls.clear()
        loaded.guest.crossBatch(composition.changes, iterations = 1, encoded = false, warmups = 0)
        val openCall = capture.calls.last { it.first.contains("sendChanges") }.second

        val batchJson = loaded.guest.initialBatchJson(rows)
        val batch = DogwoodJson.decodeFromString(ChangeBatch.serializer(), batchJson)

        out.appendLine("# What crosses the Layer 4 boundary")
        out.appendLine()
        out.appendLine("Reference screen at $rows rows: ${composition.widgetNodes} widget nodes, " +
          "${composition.changes} changes.")
        out.appendLine()
        out.appendLine("Everything below is captured from Zipline's `Call.encodedCall`, which is the")
        out.appendLine("literal string handed to `CallChannel.call(callJson: String): String`. It is")
        out.appendLine("JavaScript Object Notation (JSON), UTF-8, uncompressed.")
        out.appendLine()

        out.appendLine("## One tap-sized crossing, verbatim")
        out.appendLine()
        out.appendLine("${tapCall.toByteArray().size} bytes on the wire:")
        out.appendLine()
        out.appendLine("```json")
        out.appendLine(tapCall)
        out.appendLine("```")
        out.appendLine()
        val ziplinePayload = "{\"q\":1,\"g\":[[\"c\",{\"i\":1,\"w\":2}]]}"
        out.appendLine("The outer object is Zipline's call envelope: `service` names the bound service,")
        out.appendLine("`function` is the first six bytes of the SHA-256 of the function signature,")
        out.appendLine("base64-encoded, and `args` is the argument list. Only the contents of `args` are")
        out.appendLine("Dogwood's: ${ziplinePayload.length} of these ${tapCall.toByteArray().size} bytes. The envelope is a fixed")
        out.appendLine("per-call overhead, which matters for small batches and disappears into the noise")
        out.appendLine("for large ones.")
        out.appendLine()

        out.appendLine("## One screen-open crossing")
        out.appendLine()
        out.appendLine("${openCall.toByteArray().size} bytes on the wire. First 600:")
        out.appendLine()
        out.appendLine("```json")
        out.appendLine(openCall.take(600) + " ...")
        out.appendLine("```")
        out.appendLine()

        out.appendLine("## Where the screen-open bytes go")
        out.appendLine()
        out.appendLine("Each change encoded on its own, array-polymorphic, as Zipline encodes it.")
        out.appendLine("Separator bytes are excluded, so the total is slightly under the wire size.")
        out.appendLine()
        val byKind = batch.g.groupBy { kindOf(it) }
        out.appendLine("| Change kind | Count | Bytes | Share | Mean bytes each |")
        out.appendLine("| --- | ---: | ---: | ---: | ---: |")
        val sized = byKind.mapValues { (_, list) ->
          list.sumOf { DogwoodJsonArrayPolymorphic.encodeToString(Change.serializer(), it).toByteArray().size }
        }
        val total = sized.values.sum()
        for ((kind, list) in byKind.entries.sortedByDescending { sized.getValue(it.key) }) {
          val bytes = sized.getValue(kind)
          out.appendLine(
            "| $kind | ${list.size} | $bytes | ${"%.1f".format(100.0 * bytes / total)}% | " +
              "${"%.1f".format(bytes.toDouble() / list.size)} |",
          )
        }
        out.appendLine("| **total** | **${batch.g.size}** | **$total** | | |")
        out.appendLine()

        out.appendLine("## Repetition in the modifier chains")
        out.appendLine()
        // Compare chains by their element list alone, ignoring which node they belong to:
        // two nodes carrying the same chain are the repetition worth counting.
        val chains = batch.g.filterIsInstance<ModifierSet>()
          .map { DogwoodJsonArrayPolymorphic.encodeToString(ModifierSet.serializer(), it.copy(i = ROOT)) }
        val distinct = chains.distinct()
        out.appendLine("${chains.size} modifier chains cross, of which **${distinct.size} are distinct**.")
        out.appendLine("The chains alone are ${sized["ModifierSet"] ?: 0} bytes, " +
          "${"%.1f".format(100.0 * (sized["ModifierSet"] ?: 0) / total)}% of the batch.")
        out.appendLine()
        out.appendLine("| Occurrences | Chain |")
        out.appendLine("| ---: | --- |")
        for ((chain, count) in chains.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10)) {
          out.appendLine("| ${count} | `${chain.take(90)}` |")
        }
      } finally {
        loaded.zipline.close()
      }
    }
  } finally {
    executor.shutdown()
  }

  val file = File(root, "results/wire-format.md")
  file.parentFile.mkdirs()
  file.writeText(out.toString())
  println(out)
  println("wrote $file")
}

private fun kindOf(change: Change): String = when (change) {
  is Create -> "Create"
  is PropertySet -> "PropertySet"
  is ModifierSet -> "ModifierSet"
  is ChildAdd -> "ChildAdd"
  is ChildRemove -> "ChildRemove"
  is ChildMove -> "ChildMove"
}

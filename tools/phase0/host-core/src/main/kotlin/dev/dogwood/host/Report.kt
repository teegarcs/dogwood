/*
 * Project Dogwood -- Phase 0 measurement harness, report writer.
 *
 * Renders a results file that can be pasted into the repository without editing, including
 * the gate evaluation. The gate is evaluated mechanically so nobody has to decide after the
 * fact what the thresholds were.
 */
package dev.dogwood.host

/** The Phase 0 gate, from roadmap.md. Provisional, and renegotiable only BEFORE a run. */
private data class GateLeg(
  val name: String,
  val budgetMs: Double,
  val measuredMs: Double,
  val source: String,
)

fun renderMarkdown(r: Phase0Results): String = buildString {
  val reference = r.experiment02.first { it.rows == r.referenceRows }
  val legs = listOf(
    GateLeg(
      "Guest recomposition of the reference screen, 95th percentile",
      8.0,
      reference.recomposeOneNodeDiff.p95Ms,
      "0.2",
    ),
    GateLeg(
      "Batch crossing, per-frame reading: a steady-state recomposition batch",
      4.0,
      r.experiment03.points.first().crossZiplineSerialized.p50Ms,
      "0.3",
    ),
    GateLeg(
      "Batch crossing, per-screen reading: the whole initial batch",
      4.0,
      r.experiment03.initialBatch.crossZiplineSerialized.p50Ms,
      "0.3",
    ),
    GateLeg(
      "Maximum garbage-collection pause under load, 99th percentile",
      16.7,
      r.experiment04.points.maxOf { it.forcedPause.p99Ms },
      "0.4",
    ),
    GateLeg(
      "Cold start: module load plus first composition",
      500.0,
      r.experiment01.coldStartToFirstComposition.p50Ms,
      "0.1",
    ),
    GateLeg(
      "Cold start through the host holding the tree (composition plus initial crossing)",
      500.0,
      r.experiment01.coldStartToFirstBatchDelivered.p50Ms,
      "0.1 + 0.3",
    ),
  )

  appendLine("# Project Dogwood -- Phase 0 Results")
  appendLine()
  appendLine("**Host:** ${r.label}")
  appendLine()
  appendLine("**Platform:** ${r.platform}")
  appendLine()
  appendLine("> These numbers are gate-valid only on the low-end Android device the Phase 0")
  appendLine("> harness appendix names. On any other host they establish the harness and bound")
  appendLine("> expectations; they do not open or close the gate.")
  appendLine()

  appendLine("## Pinned toolchain")
  appendLine()
  appendLine("| Component | Version |")
  appendLine("| --- | --- |")
  appendLine("| Zipline | ${r.toolchain.zipline} |")
  appendLine("| Kotlin | ${r.toolchain.kotlin} |")
  appendLine("| `androidx.compose.runtime:runtime-js` | ${r.toolchain.composeRuntimeJs} |")
  appendLine("| `kotlinx-coroutines-core` | ${r.toolchain.coroutines} |")
  appendLine("| `kotlinx-serialization-json` | ${r.toolchain.serialization} |")
  appendLine("| QuickJS | ${r.toolchain.quickJsVersion} |")
  appendLine()

  appendLine("## Gate")
  appendLine()
  appendLine("The 0.3 leg is evaluated **both** ways, because roadmap.md sizes a per-tap budget")
  appendLine("with a per-screen quantity: it gives the crossing 4 ms inside a tap-to-repaint path,")
  appendLine("then writes the leg as \"the 150-node batch crossing\" -- and a tap never produces 150")
  appendLine("nodes. Which reading was intended is a human ruling, so both are reported rather than")
  appendLine("one being chosen here. See ADR-006 section 2.4.")
  appendLine()
  appendLine("| Leg | Budget | Measured | Verdict |")
  appendLine("| --- | ---: | ---: | --- |")
  for (leg in legs) {
    val verdict = if (leg.measuredMs <= leg.budgetMs) "within budget" else "**over budget**"
    appendLine(
      "| ${leg.name} (${leg.source}) | ${"%.2f".format(leg.budgetMs)} ms | " +
        "${"%.3f".format(leg.measuredMs)} ms | $verdict |",
    )
  }
  appendLine()

  appendLine("## 0.1 -- Cold-start cost")
  appendLine()
  appendLine("| Measure | Value |")
  appendLine("| --- | ---: |")
  appendLine("| Minified JavaScript | ${r.experiment01.sizes.minifiedJsBytes} bytes |")
  appendLine("| Gzipped JavaScript | ${r.experiment01.sizes.gzippedJsBytes} bytes |")
  appendLine("| QuickJS bytecode | ${r.experiment01.sizes.ziplineBytecodeBytes} bytes |")
  appendLine("| `.zipline` file as delivered | ${r.experiment01.sizes.ziplineFileBytes} bytes |")
  appendLine()
  appendTable(
    listOf(
      r.experiment01.moduleLoad,
      r.experiment01.mainFunction,
      r.experiment01.coldStartToFirstComposition,
      r.experiment01.coldStartToFirstBatchDelivered,
    ),
  )
  appendLine()
  appendLine("`QuickJs.memoryUsage` after module load:")
  appendLine()
  appendMemory(r.experiment01.memoryAfterLoad)
  appendLine()
  appendLine("`QuickJs.memoryUsage` after the first composition:")
  appendLine()
  appendMemory(r.experiment01.memoryAfterFirstComposition)
  appendLine()

  appendLine("## 0.2 -- Composition and recomposition")
  appendLine()
  for (e in r.experiment02) {
    appendLine("### Reference screen at ${e.rows} rows")
    appendLine()
    appendLine("| Property | Value |")
    appendLine("| --- | ---: |")
    appendLine("| Widget nodes (each produces one `Create`) | ${e.widgetNodes} |")
    appendLine("| Children-slot nodes (no protocol cost) | ${e.childrenNodes} |")
    appendLine("| Changes in the initial batch | ${e.initialChanges} |")
    appendLine("| Initial batch, encoded | ${e.initialBatchBytes} bytes |")
    appendLine("| `mutableStateOf` holders | ${e.stateHolders} |")
    appendLine()
    appendTable(
      listOf(
        e.clockOverhead,
        e.initialComposition,
        e.recomposeOneNodeDiff,
        e.recomposeTwoNodeDiff,
      ),
    )
    appendLine()
  }

  appendLine("## 0.3 -- Protocol cost per frame")
  appendLine()
  appendLine("Method: ${r.experiment03.method}")
  appendLine()
  appendLine("Three encodings of the same batch are measured:")
  appendLine()
  appendLine("- **Encode (kotlinx)** -- ADR-004 section 2.2's class-discriminator JavaScript")
  appendLine("  Object Notation (JSON), produced by `kotlinx.serialization`'s pure-Kotlin encoder.")
  appendLine("- **Encode (array)** -- the same schema under array polymorphism, still through the")
  appendLine("  pure-Kotlin encoder.")
  appendLine("- **Encode (native)** -- array polymorphism through `encodeToDynamic` plus QuickJS's")
  appendLine("  native `JSON.stringify`. This is the path Zipline's own `CallChannel` takes on")
  appendLine("  Kotlin/JavaScript, so it is what `sendChanges` actually pays.")
  appendLine()
  appendLine("`Cross (pre-encoded)` sends an already-built string and so isolates transport;")
  appendLine("`Cross (Zipline-serialized)` is the real `sendChanges(batch)` call, end to end.")
  appendLine()
  appendLine("| Changes | Bytes | Bytes (array) | Build p50 | Encode (kotlinx) p50 | Encode (array) p50 | Encode (native) p50 | Cross (pre-encoded) p50 | Cross (Zipline-serialized) p50 |")
  appendLine("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
  for (p in r.experiment03.points + r.experiment03.initialBatch) {
    appendLine(
      "| ${p.changes} | ${p.bytes} | ${p.arrayPolymorphicBytes} | ${"%.4f".format(p.build.p50Ms)} ms | " +
        "${"%.4f".format(p.stringify.p50Ms)} ms | ${"%.4f".format(p.stringifyArrayPolymorphic.p50Ms)} ms | " +
        "${"%.4f".format(p.stringifyNative.p50Ms)} ms | " +
        "${"%.4f".format(p.crossPreEncoded.p50Ms)} ms | " +
        "${"%.4f".format(p.crossZiplineSerialized.p50Ms)} ms |",
    )
  }
  appendLine()

  if (r.experiment03.encodings.isNotEmpty()) {
    val baseline = r.experiment03.encodings.firstOrNull { it.name == "json-v0-native" }
    appendLine("### Encoding bake-off")
    appendLine()
    appendLine("Every candidate encodes the **same** batch of ${r.experiment03.initialBatch.changes}")
    appendLine("changes. `Wire bytes` is what crosses `CallChannel`, which is a string channel -- so a")
    appendLine("binary encoding pays a Base64 surcharge here and a textual one does not. `Encode` is")
    appendLine("guest-side production cost; `Cross` is encode plus transport, end to end.")
    appendLine()
    appendLine("| Encoding | Payload bytes | Wire bytes | vs. today | Encode p50 | Cross p50 | vs. today | Host decode p50 |")
    appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    for (v in r.experiment03.encodings.sortedBy { it.cross.p50Ms }) {
      val sizeDelta = baseline?.let { "${"%+.0f".format(100.0 * v.wireBytes / it.wireBytes - 100)}%" } ?: "--"
      val timeDelta = baseline?.let { "${"%+.0f".format(100.0 * v.cross.p50Ms / it.cross.p50Ms - 100)}%" } ?: "--"
      appendLine(
        "| `${v.name}` | ${v.payloadBytes} | ${v.wireBytes} | $sizeDelta | " +
          "${"%.2f".format(v.encode.p50Ms)} ms | ${"%.2f".format(v.cross.p50Ms)} ms | $timeDelta | " +
          "${v.hostDecode?.let { "%.2f ms".format(it.p50Ms) } ?: "not measured"} |",
      )
    }
    appendLine()
    for (v in r.experiment03.encodings) {
      appendLine("- **`${v.name}`** -- ${v.note}")
    }
    appendLine()
  }

  appendLine("## 0.4 -- Garbage-collection behaviour")
  appendLine()
  appendLine("Method: ${r.experiment04.method}")
  appendLine()
  appendLine("| `gcThreshold` | Recompose p50 | Recompose p99 | Recompose max | Forced pause p99 | Forced pause max | Heap used after churn |")
  appendLine("| ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
  for (p in r.experiment04.points) {
    appendLine(
      "| ${p.gcThresholdBytes / 1024} KiB | ${"%.3f".format(p.recomposeUnderLoad.p50Ms)} ms | " +
        "${"%.3f".format(p.recomposeUnderLoad.p99Ms)} ms | ${"%.3f".format(p.recomposeUnderLoad.maxMs)} ms | " +
        "${"%.3f".format(p.forcedPause.p99Ms)} ms | ${"%.3f".format(p.forcedPause.maxMs)} ms | " +
        "${p.memoryAfterChurn.memoryUsedSize} bytes |",
    )
  }
  appendLine()

  appendLine("## Notes and caveats")
  appendLine()
  for (note in r.notes) appendLine("- $note")
}

private fun StringBuilder.appendTable(stats: List<Stat>) {
  appendLine("| Measurement | n | p50 | p95 | p99 | min | max | mean |")
  appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
  for (s in stats) {
    appendLine(
      "| `${s.label}` | ${s.count} | ${"%.4f".format(s.p50Ms)} ms | ${"%.4f".format(s.p95Ms)} ms | " +
        "${"%.4f".format(s.p99Ms)} ms | ${"%.4f".format(s.minMs)} ms | ${"%.4f".format(s.maxMs)} ms | " +
        "${"%.4f".format(s.meanMs)} ms |",
    )
  }
}

private fun StringBuilder.appendMemory(m: MemorySnapshot) {
  appendLine("| Field | Value |")
  appendLine("| --- | ---: |")
  appendLine("| `memoryUsedSize` | ${m.memoryUsedSize} bytes |")
  appendLine("| `memoryAllocatedSize` | ${m.memoryAllocatedSize} bytes |")
  appendLine("| `objectsCount` | ${m.objectsCount} |")
  appendLine("| `objectsSize` | ${m.objectsSize} bytes |")
  appendLine("| `stringsCount` | ${m.stringsCount} |")
  appendLine("| `stringsSize` | ${m.stringsSize} bytes |")
  appendLine("| `jsFunctionsCount` | ${m.jsFunctionsCount} |")
  appendLine("| `jsFunctionsCodeSize` | ${m.jsFunctionsCodeSize} bytes |")
  appendLine("| `propertiesSize` | ${m.propertiesSize} bytes |")
  appendLine("| `arraysCount` | ${m.arraysCount} |")
}

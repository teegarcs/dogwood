/*
 * Project Dogwood -- roadmap.md Phase 1 step 7.
 *
 * "Decide host rendering strategy by measurement: the snapshot mirror specified in Layer 5
 * against an imperative applier that mutates retained nodes, which is what Redwood does.
 * Compare apply-to-pixel latency at batch sizes 1 / 10 / 100 / 1,000."
 *
 * The tree under test is synthetic rather than loaded from the guest: the same shape and size as
 * the reference screen -- one hundred and sixty widget nodes -- built from hand-written batches,
 * so the benchmark reproduces without a server running. The question here is invalidation
 * strategy, not payload realism; the payload was measured in Phase 0.
 *
 * Three numbers per cell, because on a host at sixty hertz one of them cannot discriminate:
 *
 *   - APPLY is the cost of mutating the tree, and separates the two data structures directly.
 *   - RECOMPOSED is how many bindings Compose re-executed, which is the mechanism the two
 *     strategies actually differ by.
 *   - TO-FRAME is apply to the end of the frame that rendered the change. It is quantised to
 *     the display's refresh interval, so it answers "is the difference visible at frame
 *     granularity" and nothing finer. Both strategies pay the same quantisation.
 */
package dev.dogwood.slice.android

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import dev.dogwood.host.DogwoodDictionary
import dev.dogwood.host.EventSink
import dev.dogwood.host.HostTree
import dev.dogwood.host.LayoutScope
import dev.dogwood.host.LocalRenderCounter
import dev.dogwood.host.PlainTree
import dev.dogwood.host.RenderChildren
import dev.dogwood.host.RenderCounter
import dev.dogwood.protocol.Change
import dev.dogwood.protocol.ChangeBatch
import dev.dogwood.protocol.ChildAdd
import dev.dogwood.protocol.ChildrenTag
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.PropertyTag
import java.io.File
import kotlinx.serialization.json.JsonPrimitive

private const val TAG = "DogwoodBench"

/**
 * Two tree sizes. 23 rows is the reference screen, where both strategies comfortably fit a
 * frame; 200 rows is a stress size, because a comparison in which nothing fails tells you only
 * that you did not push hard enough.
 */
private val ROW_COUNTS = listOf(23, 200)
private val BATCH_SIZES = listOf(1, 10, 100, 1000)
private const val WARMUPS = 10
private const val ITERATIONS = 60

private val CONTENT = ChildrenTag(1)
private val LABEL = PropertyTag(1)

/** The initial batch: a column of rows, each a row of three texts. Shaped like the real screen. */
private fun initialBatch(rows: Int): ChangeBatch {
  val changes = mutableListOf<Change>()
  var next = 1
  fun id() = Id(next++)

  val column = id()
  changes += Create(column, DogwoodDictionary.Column)
  changes += ChildAdd(Id(0), CONTENT, column, 0)

  repeat(rows) { rowIndex ->
    val row = id()
    changes += Create(row, DogwoodDictionary.Row)
    changes += ChildAdd(column, CONTENT, row, rowIndex)
    repeat(3) { cell ->
      val text = id()
      changes += Create(text, DogwoodDictionary.Text)
      changes += PropertySet(text, LABEL, JsonPrimitive("row $rowIndex cell $cell"))
      changes += ChildAdd(row, CONTENT, text, cell)
    }
  }
  return ChangeBatch(1, changes)
}

/** The text node identifiers, so update batches touch nodes that exist. */
private fun textIds(rows: Int): List<Id> =
  initialBatch(rows).g.filterIsInstance<Create>()
    .filter { it.w == DogwoodDictionary.Text }
    .map { it.i }

/** An update batch of exactly [size] property changes, spread over the text nodes. */
private fun updateBatch(sequence: Int, size: Int, ids: List<Id>): ChangeBatch = ChangeBatch(
  q = sequence,
  g = List(size) { index ->
    PropertySet(ids[index % ids.size], LABEL, JsonPrimitive("v$sequence-$index"))
  },
)

private data class Cell(
  val nodes: Int,
  val strategy: String,
  val batchSize: Int,
  val applyMedianUs: Long,
  val recomposed: Int,
  val toFrameMedianUs: Long,
)

class RenderBenchActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme { Surface(Modifier.fillMaxSize()) { Bench() } }
    }
  }

  @Composable
  private fun Bench() {
    val counter = remember { RenderCounter() }
    val events = remember { EventSink { _, _ -> } }

    var rows by remember { mutableStateOf(ROW_COUNTS.first()) }
    var useSnapshot by remember { mutableStateOf(true) }
    var report by remember { mutableStateOf("running...") }

    // Every tree is built once, up front, and selected by size. An earlier version rebuilt them
    // with `remember(rows)`, which the LaunchedEffect below could not see: the effect kept the
    // trees it captured on first composition and went on mutating those while the screen
    // rendered the new ones. Every larger-tree row read zero recompositions, which is what a
    // benchmark measuring nothing looks like.
    val trees = remember {
        ROW_COUNTS.associateWith { count ->
          HostTree().also { it.apply(initialBatch(count)) } to
            PlainTree().also { it.apply(initialBatch(count)) }
        }
    }
    val idsByRows = remember { ROW_COUNTS.associateWith { textIds(it) } }

    LaunchedEffect(Unit) {
      val results = mutableListOf<Cell>()
      var sequence = 2

      suspend fun measure(size: Int, ids: List<Id>, apply: (ChangeBatch) -> Unit): Cell {
        val applies = ArrayList<Long>(ITERATIONS)
        val toFrames = ArrayList<Long>(ITERATIONS)
        var recomposed = 0
        repeat(WARMUPS + ITERATIONS) { iteration ->
          val batch = updateBatch(sequence++, size, ids)
          counter.reset()
          val t0 = System.nanoTime()
          apply(batch)
          val t1 = System.nanoTime()
          // Two frame callbacks bracket one complete frame: the first resumes at the start of
          // the frame that will render the change, the second once that frame is behind us.
          withFrameNanos { }
          withFrameNanos { }
          val t2 = System.nanoTime()
          if (iteration >= WARMUPS) {
            applies += t1 - t0
            toFrames += t2 - t0
            recomposed = counter.count
          }
        }
        return Cell(
          nodes = 22 + 6 * rows,
          strategy = if (useSnapshot) "snapshot mirror" else "imperative",
          batchSize = size,
          applyMedianUs = applies.sorted()[applies.size / 2] / 1000,
          recomposed = recomposed,
          toFrameMedianUs = toFrames.sorted()[toFrames.size / 2] / 1000,
        )
      }

      for (rowCount in ROW_COUNTS) {
        rows = rowCount
        val (snapshotTree, plainTree) = trees.getValue(rowCount)
        val ids = idsByRows.getValue(rowCount)
        // Let the new tree mount and settle before measuring against it.
        repeat(4) { withFrameNanos { } }

        useSnapshot = true
        repeat(3) { withFrameNanos { } }
        for (size in BATCH_SIZES) results += measure(size, ids) { snapshotTree.apply(it) }

        useSnapshot = false
        repeat(3) { withFrameNanos { } }
        for (size in BATCH_SIZES) results += measure(size, ids) { plainTree.apply(it) }
      }

      report = renderReport(results)
      Log.i(TAG, report)
      runCatching {
        File(getExternalFilesDir(null) ?: filesDir, "render-strategy.md").also {
          it.writeText(report)
          Log.i(TAG, "wrote ${it.absolutePath}")
        }
      }
    }

    CompositionLocalProvider(LocalRenderCounter provides counter) {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(report, style = MaterialTheme.typography.bodySmall)
        // The tree under test must genuinely be composed and drawn, or the measurement is of
        // nothing. Only one strategy is mounted at a time so they cannot interfere.
        val (snapshotTree, plainTree) = trees.getValue(rows)
        if (useSnapshot) {
          RenderChildren(snapshotTree.root, 1, LayoutScope(column = this), events)
        } else {
          plainTree.generation.value // the imperative strategy's single invalidation signal
          RenderChildren(plainTree.root, 1, LayoutScope(column = this), events)
        }
      }
    }
  }
}

private fun renderReport(results: List<Cell>): String = buildString {
  appendLine("# Host rendering strategy — snapshot mirror vs imperative")
  appendLine()
  appendLine("**Device:** ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
  appendLine()
  appendLine("$ITERATIONS iterations after $WARMUPS warm-ups; medians.")
  appendLine()
  appendLine("Apply-to-frame is quantised to the refresh interval, so ~33 ms means two vertical")
  appendLine("syncs and therefore that the work fitted inside one frame. A figure above that is")
  appendLine("the strategy missing frames.")
  appendLine()
  appendLine("| Nodes | Strategy | Batch | Apply | Bindings recomposed | Apply to end of frame |")
  appendLine("| ---: | --- | ---: | ---: | ---: | ---: |")
  for (cell in results) {
    appendLine(
      "| ${cell.nodes} | ${cell.strategy} | ${cell.batchSize} | ${cell.applyMedianUs} µs | " +
        "${cell.recomposed} | ${cell.toFrameMedianUs} µs |",
    )
  }
}

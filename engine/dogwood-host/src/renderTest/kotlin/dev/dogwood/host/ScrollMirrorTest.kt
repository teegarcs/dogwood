/*
 * Project Dogwood -- the host half of the scroll holder, in a real composition.
 *
 * `ScrollStateTest` on the guest pins what *crosses*: a target is an offset and a sequence, the
 * quantum is sent rather than assumed, the end is asked for as an intent. None of that touches the
 * part [ADR-044](../../../../../../../adrs/layer-5/ADR-044-scroll-position-is-a-declared-quantum.md)
 * is actually about, which is what the **host** does with it -- the quantising, the exact ends, and
 * the re-report a replacement guest depends on.
 *
 * That half was demonstrated on a device and read off a screenshot. A screenshot is not a gate: it
 * is a person, or a model, looking at pixels once. These are the same claims as assertions, in a
 * composition with real layout, on every build.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.decodePositional
import dev.dogwood.protocol.widgetTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val SCROLL_AREA = widgetTag(1, 15).value
private val TEXT = DogwoodDictionary.Text.value

/** Height of the window the container is measured in, and of each row inside it. */
private const val WINDOW_DP = 160
private const val ROW_DP = 40
private const val ROWS = 12
private const val QUANTUM_DP = 40

/** One report, as the guest would receive it. */
private data class Report(val offsetDp: Int, val maxOffsetDp: Int, val scrolling: Boolean)

private fun List<JsonElement>.toReport() = Report(
  this[0].jsonPrimitive.intOrNull ?: -99,
  this[1].jsonPrimitive.intOrNull ?: -99,
  this[2].jsonPrimitive.content.toBoolean(),
)

@OptIn(ExperimentalTestApi::class)
class ScrollMirrorTest {

  /**
   * A scrolling container with [ROWS] fixed-height rows in it.
   *
   * The rows are sized by a host-side modifier rather than left to the text's own height, because
   * every assertion below is about a *number of density-independent pixels* and a test whose
   * maximum depended on the font metrics of whoever ran it would be a test that asserted nothing
   * twice.
   */
  private fun tree(
    watching: Boolean = true,
    targetDp: Int = 0,
    sequence: Int = 0,
    quantumDp: Int = QUANTUM_DP,
  ): HostTree = HostTree().also { tree ->
    val header = buildString {
      append("[0,1,$SCROLL_AREA]")
      append(",[1,1,5,$watching]")
      append(",[1,1,6,$quantumDp]")
      if (sequence > 0) append(",[1,1,2,$targetDp],[1,1,3,$sequence]")
      append(",[3,0,1,1,0]")
    }
    val rows = (1..ROWS).joinToString(",") { row ->
      val id = row + 1
      // Modifier kind 2: the height modifier, so each row is exactly ROW_DP tall.
      """[0,$id,$TEXT],[1,$id,1,"Row $row"],[2,$id,[[7,$ROW_DP]]],[3,1,1,$id,${row - 1}]"""
    }
    tree.apply(decodePositional("[1,[$header,$rows]]"))
  }

  /** Collects every report the host sends, in order. */
  private class Reports {
    val all = mutableListOf<Report>()
    val sink = EventSink { _, tag, args ->
      if (tag == EventTag(1)) all += args.toReport()
    }
    val offsets: List<Int> get() = all.map { it.offsetDp }
    val last: Report? get() = all.lastOrNull()
  }

  /** The content is [ROWS] × [ROW_DP] tall in a [WINDOW_DP] window. */
  private val expectedMaxDp = ROWS * ROW_DP - WINDOW_DP

  private fun scrolling(
    tree: HostTree,
    reports: Reports = Reports(),
    body: androidx.compose.ui.test.ComposeUiTest.(Reports) -> Unit,
  ) = runComposeUiTest {
    setContent {
      Box(Modifier.size(WINDOW_DP.dp)) {
        DogwoodTree(tree, reports.sink, skew = tree.skew)
      }
    }
    body(reports)
  }

  @Test
  fun aContainerTallerThanItsWindowReportsAMaximumItMeasured() {
    // The control for everything below: without a real measurement the maximum would be zero and
    // every claim about the ends would hold vacuously.
    scrolling(tree()) { reports ->
      onNodeWithText("Row 1").assertIsDisplayed()
      assertEquals(expectedMaxDp, reports.last?.maxOffsetDp, "reports: ${reports.all}")
    }
  }

  @Test
  fun aContainerWithoutAWatchingGuestReportsNothing() {
    // Presence is a property because the host cannot see guest closures. Without this every
    // container on every screen would pay for an observer nobody reads.
    scrolling(tree(watching = false)) { reports ->
      onNodeWithText("Row 1").assertIsDisplayed()
      assertTrue(reports.all.isEmpty(), "reports: ${reports.all}")
    }
  }

  @Test
  fun aDeclaredTargetMovesTheContainer() {
    // Row 1 is scrolled out of the window and a row that was below the fold is on screen. The
    // second half is the one that matters: "Row 1 is gone" is also what a broken container looks
    // like.
    scrolling(tree(targetDp = 200, sequence = 1)) { reports ->
      waitUntil("the container never moved") { reports.last?.offsetDp == 200 }
      // Not `fetchSemanticsNodes().size`: this container is **not lazy**, so every row is composed
      // and present in the semantics tree whether or not it is on screen. What moved is what is
      // *displayed*, which is the assertion this test means to make.
      onNodeWithText("Row 1").assertIsNotDisplayed()
      onNodeWithText("Row 6").assertIsDisplayed()
      assertEquals(200, reports.last?.offsetDp, "reports: ${reports.all}")
    }
  }

  @Test
  fun aTargetPastTheEndSettlesAtTheEndRatherThanThrowing() {
    scrolling(tree(targetDp = 100_000, sequence = 1)) { reports ->
      waitUntil("never settled at the end") { reports.last?.offsetDp == expectedMaxDp }
      assertEquals(expectedMaxDp, reports.last?.offsetDp, "reports: ${reports.all}")
      assertEquals(expectedMaxDp, reports.last?.maxOffsetDp)
    }
  }

  @Test
  fun theEndIsAskedForAsAnIntentAndResolvedAgainstTheLayout() {
    // A guest cannot compute the end: the maximum is host layout and the guest's copy of it is as
    // stale as its last report. The sentinel is resolved here, against the layout that exists.
    scrolling(tree(targetDp = -1, sequence = 1)) { reports ->
      // A held target: the sentinel waits for a layout before it can resolve, which is several
      // frames rather than one settled composition.
      waitUntil("the end sentinel never resolved") { reports.last?.offsetDp == expectedMaxDp }
      onNodeWithText("Row $ROWS").assertIsDisplayed()
      assertEquals(expectedMaxDp, reports.last?.offsetDp, "reports: ${reports.all}")
    }
  }

  @Test
  fun everyReportedOffsetIsAMultipleOfTheQuantumOrAnEnd() {
    // The claim ADR-044 is actually about. A guest that declared a quantum must never be handed a
    // value between two of them -- that is what "the guest declares the quantum" means, as opposed
    // to "the host reports whenever it feels like it".
    scrolling(tree(targetDp = 130, sequence = 1)) { reports ->
      waitUntil("the target never landed") { reports.last?.offsetDp == 120 }
      val offending = reports.offsets.filter {
        it % QUANTUM_DP != 0 && it != 0 && it != expectedMaxDp
      }
      assertTrue(offending.isEmpty(), "off-quantum offsets $offending in ${reports.all}")
      // And it is genuinely quantised rather than merely coincidental: 130 is not a multiple of 40.
      assertEquals(120, reports.last?.offsetDp, "reports: ${reports.all}")
    }
  }

  @Test
  fun theEndIsReportedExactlyEvenWhenItIsNotAMultipleOfTheQuantum() {
    // The row this design exists for. With a quantum that does not divide the maximum, a purely
    // quantised report never equals it -- so "am I at the bottom?", which is the question a
    // paginating guest asks, would be answerable only by accident.
    val quantum = 36
    assertTrue(expectedMaxDp % quantum != 0, "this test needs a maximum the quantum does not divide")
    scrolling(tree(targetDp = -1, sequence = 1, quantumDp = quantum)) { reports ->
      waitUntil("the end was never reported exactly") { reports.last?.offsetDp == expectedMaxDp }
      assertEquals(expectedMaxDp, reports.last?.offsetDp, "reports: ${reports.all}")
    }
  }

  @Test
  fun theTopIsReportedExactlyToo() {
    // No target: a container that has just laid out is at the top, and says so. Declaring a target
    // here would be testing the wrong thing -- the scroll lands before the first report, so the
    // top is never a state the guest is told about.
    scrolling(tree()) { reports ->
      waitForIdle()
      assertEquals(0, reports.all.first().offsetDp, "reports: ${reports.all}")
    }
  }

  @Test
  fun nothingIsReportedBeforeTheContainerHasBeenMeasured() {
    // `ScrollState.maxValue` is `Int.MAX_VALUE` until the first measure -- Compose's "not laid out
    // yet", not a very tall container. Converting it produces a number with no meaning, and a guest
    // cannot tell that from a real one. This is the assertion that keeps it off the wire; on a
    // device the first report always happened to arrive after layout, so the window never opened.
    scrolling(tree()) { reports ->
      waitForIdle()
      val nonsense = reports.all.filter { it.maxOffsetDp > ROWS * ROW_DP }
      assertTrue(nonsense.isEmpty(), "reported a maximum nothing measured: $nonsense in ${reports.all}")
    }
  }

  @Test
  fun aReplacementGuestIsToldWhereTheContainerIsWithoutItHavingMoved() {
    // The defect a device produced, as an assertion.
    //
    // A report is edge-triggered -- that is the whole throttle. A code update leaves the host's
    // value exactly where it was while handing the guest a brand-new holder that knows nothing, so
    // the host has nothing new to say and the new guest never learns what it is looking at. It came
    // back reading `offset 800dp of -1dp`: the offset right because it was saved, the maximum
    // absent because it was only ever reported.
    //
    // Nothing here scrolls between the two generations. That is the point: the fix is not "report
    // more", it is "report again to whoever is listening now".
    val tree = tree(targetDp = 200, sequence = 1)
    val reports = Reports()
    runComposeUiTest {
      var generation by mutableStateOf("first")
      setContent {
        Box(Modifier.size(WINDOW_DP.dp)) {
          DogwoodTree(tree, reports.sink, evaluatorKey = generation, skew = tree.skew)
        }
      }
      waitForIdle()
      val beforeUpdate = reports.all.size
      assertTrue(beforeUpdate > 0, "nothing was reported at all")

      generation = "second"
      waitUntil("a replacement guest was told nothing") { reports.all.size > beforeUpdate }

      val afterUpdate = reports.all.drop(beforeUpdate)
      assertTrue(
        afterUpdate.isNotEmpty(),
        "a replacement guest was told nothing; it would read a maximum of -1. reports: ${reports.all}",
      )
      assertEquals(200, afterUpdate.first().offsetDp)
      assertEquals(expectedMaxDp, afterUpdate.first().maxOffsetDp)
    }
  }

  @Test
  fun theSameGuestIsNotToldTwiceForNothing() {
    // The other half, and the one that keeps the fix from being "report on every recomposition".
    // A generation that has not changed must produce no traffic, or the throttle is gone.
    val tree = tree()
    val reports = Reports()
    runComposeUiTest {
      var unrelated by mutableStateOf(0)
      setContent {
        Box(Modifier.size(WINDOW_DP.dp)) {
          // Read so the recomposition is real rather than optimised away.
          @Suppress("UNUSED_EXPRESSION") unrelated
          DogwoodTree(tree, reports.sink, evaluatorKey = "one", skew = tree.skew)
        }
      }
      waitForIdle()
      val settled = reports.all.size

      repeat(3) { unrelated += 1; waitForIdle() }

      assertEquals(settled, reports.all.size, "recomposition alone produced traffic: ${reports.all}")
    }
  }
}

/*
 * Project Dogwood -- the host half of the first live-state holder, in a real composition.
 *
 * `LazyListState` established the pattern every other holder follows
 * ([ADR-014](../../../../../../../adrs/layer-5/ADR-014-live-state-holders.md)) and was the last
 * mirror without a host test. Its record said the host half "is verified on a device, not
 * unit-tested" because exercising it "needs a Compose UI test harness this project does not have";
 * that was not true when it was written, and
 * [ADR-044](../../../../../../../adrs/layer-5/ADR-044-scroll-position-is-a-declared-quantum.md)
 * withdrew it. This is the gap that withdrawal opened.
 *
 * The guest-side tests pin what crosses. These pin what the host does: a target moves the list, a
 * target for an item that does not exist yet **waits** rather than clamping to the end, reports are
 * item-granular rather than pixel-granular, and a replacement guest is told where the list is.
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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.decodePositional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val VERTICAL_LIST = DogwoodDictionary.VerticalList.value
private val TEXT = DogwoodDictionary.Text.value

private const val TARGET_INDEX = 3
private const val TARGET_SEQUENCE = 4
private const val OBSERVED = 5

private const val WINDOW_DP = 160
private const val ROW_DP = 40

/** One viewport report, as the guest would receive it. */
private data class Viewport(val first: Int, val last: Int, val scrolling: Boolean)

@OptIn(ExperimentalTestApi::class)
class LazyListMirrorTest {

  private class Reports {
    val all = mutableListOf<Viewport>()
    val sink = EventSink { _, tag, args ->
      if (tag == EventTag(1)) {
        all += Viewport(
          args[0].jsonPrimitive.intOrNull ?: -99,
          args[1].jsonPrimitive.intOrNull ?: -99,
          args[2].jsonPrimitive.content.toBoolean(),
        )
      }
    }
    val last: Viewport? get() = all.lastOrNull()
  }

  /**
   * A vertical list with [rows] fixed-height rows.
   *
   * Fixed height, for the reason `ScrollMirrorTest` fixes its rows: every assertion here is about
   * *which items are visible*, and a list whose row height came from the font metrics of whoever
   * ran the test would assert something different on each machine.
   */
  private fun tree(
    rows: Int,
    observed: Boolean = true,
    targetIndex: Int = 0,
    sequence: Int = 0,
  ): HostTree = HostTree().also { tree ->
    val header = buildString {
      append("[0,1,$VERTICAL_LIST]")
      append(",[1,1,$OBSERVED,$observed]")
      if (sequence > 0) append(",[1,1,$TARGET_INDEX,$targetIndex],[1,1,$TARGET_SEQUENCE,$sequence]")
      append(",[3,0,1,1,0]")
    }
    tree.apply(decodePositional("[1,[$header${rowsFrom(1, rows)}]]"))
  }

  /**
   * Rows [first] through `first + count - 1`, each exactly [ROW_DP] tall.
   *
   * Modifier kind 7 is the height modifier. Fixing the height matters: every assertion here is
   * about *which items are visible*, and rows sized by the font metrics of whoever ran the test
   * would put a different answer on each machine.
   */
  private fun rowsFrom(first: Int, count: Int): String =
    (first until first + count).joinToString("") { row ->
      val id = row + 1
      """,[0,$id,$TEXT],[1,$id,1,"Row $row"],[2,$id,[[7,$ROW_DP]]],[3,1,1,$id,${row - 1}]"""
    }

  private fun show(
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
  fun aListReportsTheRangeItActuallyLaidOut() = run {
    // The control. A window of four rows shows items 0 through 3; without a real measurement every
    // claim below would hold against a list that laid nothing out.
    show(tree(rows = 12)) { reports ->
      onNodeWithText("Row 1").assertIsDisplayed()
      assertEquals(0, reports.last?.first, "reports: ${reports.all}")
      assertEquals(3, reports.last?.last, "reports: ${reports.all}")
    }
  }

  @Test
  fun aListNobodyIsWatchingReportsNothing() = run {
    // Presence is a property because the host cannot see guest closures. Without it every list on
    // every screen would pay for a viewport observer nobody reads.
    show(tree(rows = 12, observed = false)) { reports ->
      onNodeWithText("Row 1").assertIsDisplayed()
      assertTrue(reports.all.isEmpty(), "reports: ${reports.all}")
    }
  }

  @Test
  fun aDeclaredTargetMovesTheList() = run {
    show(tree(rows = 12, targetIndex = 7, sequence = 1)) { reports ->
      waitForIdle()
      onNodeWithText("Row 8").assertIsDisplayed()
      assertEquals(7, reports.last?.first, "reports: ${reports.all}")
    }
  }

  @Test
  fun askingTwiceForTheSamePlaceMovesTheListTwice() = run {
    // The reason the target is a counter rather than a flag: a user who taps "back to top",
    // scrolls away, and taps again expects to go back.
    val tree = tree(rows = 12, targetIndex = 7, sequence = 1)
    show(tree) { reports ->
      waitForIdle()
      assertEquals(7, reports.last?.first)

      tree.apply(decodePositional("[2,[[1,1,$TARGET_INDEX,0],[1,1,$TARGET_SEQUENCE,2]]]"))
      waitForIdle()
      assertEquals(0, reports.last?.first, "reports: ${reports.all}")

      // The same index as the first request, with a new number on it.
      tree.apply(decodePositional("[3,[[1,1,$TARGET_INDEX,7],[1,1,$TARGET_SEQUENCE,3]]]"))
      waitForIdle()
      assertEquals(7, reports.last?.first, "reports: ${reports.all}")
    }
  }

  @Test
  fun aTargetForAnItemThatDoesNotExistYetWaitsRatherThanClampingToTheEnd() = run {
    // The finding a device produced and reasoning did not, now an assertion.
    //
    // A replacement guest re-runs its `LaunchedEffect`, so its content is being *fetched* while it
    // declares its restored target. At that moment the list holds a header and a loading row;
    // `scrollToItem(7)` clamps to the end and the position is silently lost. The target is held
    // until the list is long enough — so the list must still be near the top here, and must arrive
    // at item 7 once the items do.
    val tree = tree(rows = 2, targetIndex = 7, sequence = 1)
    show(tree) { reports ->
      waitForIdle()
      assertEquals(0, reports.last?.first, "a target past the end clamped instead of waiting: ${reports.all}")

      // The content arrives.
      // `rowsFrom` leads with a comma, because it is normally appended after the header.
      tree.apply(decodePositional("[2,[${rowsFrom(3, 10).removePrefix(",")}]]"))

      // `waitUntil`, not `waitForIdle`, and the difference is the whole point of this test. The
      // target is *held* -- it waits on a `snapshotFlow` for the list to grow, then scrolls, then
      // the viewport reports -- and that is several frames rather than one settled composition.
      // A single `waitForIdle` passed on a development machine and failed on a slower continuous
      // integration runner, which is the classic shape of a test that asserts a schedule instead
      // of an outcome.
      waitUntil("the held target never fired") { reports.last?.first == 7 }

      assertEquals(7, reports.last?.first, "the held target never fired: ${reports.all}")
      onNodeWithText("Row 8").assertIsDisplayed()
    }
  }

  @Test
  fun anUnchangedViewportCostsNoTraffic() = run {
    // The throttle, and the honest limit of the mirror: reports are item-granular, so a list that
    // has not crossed an item boundary produces nothing at all.
    val tree = tree(rows = 12)
    val reports = Reports()
    runComposeUiTest {
      var unrelated by mutableStateOf(0)
      setContent {
        Box(Modifier.size(WINDOW_DP.dp)) {
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

  @Test
  fun aReplacementGuestIsToldWhereTheListIsWithoutItHavingMoved() = run {
    // The same defect `ScrollMirrorTest` asserts for a scrolling container. This list survived a
    // code update on a device *before* the fix — but by accident, because clearing and rebuilding
    // the tree churns the visible range and happens to make the flow emit again. Depending on
    // churn is depending on a coincidence, and this is the assertion that stops it being one:
    // nothing here changes the tree between the two generations.
    val tree = tree(rows = 12, targetIndex = 7, sequence = 1)
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
      waitForIdle()

      val afterUpdate = reports.all.drop(beforeUpdate)
      assertTrue(
        afterUpdate.isNotEmpty(),
        "a replacement guest was told nothing; its last visible index would read -1. ${reports.all}",
      )
      assertEquals(7, afterUpdate.first().first)
    }
  }
}

/*
 * Project Dogwood -- the sixth holder shape, over a discrete quantity.
 *
 * A pager is `ScrollMirror` with the throttling removed: a page index is already the granularity
 * anyone cares about, so there is no quantum and the report fires when the page changes. What is
 * worth asserting is what a guest depends on and cannot see — that only the current page is
 * composed (or it is not a pager, it is a row that snaps), that the host counts the pages because
 * the guest cannot, that a *sequence* drives it, and that `byUser` separates the user's swipe from
 * the guest's own request landing.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.protocol.decodePositional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val PAGER = DogwoodDictionary.Pager.value
private val TEXT = DogwoodDictionary.Text.value
private const val WAIT = 10_000L

@OptIn(ExperimentalTestApi::class)
class PagerMirrorTest {

  private class Reports {
    val all = mutableListOf<Triple<Int, Int, Boolean>>()
    val sink = EventSink { _, _, args ->
      all += Triple(
        args[0].toString().toIntOrNull() ?: -1,
        args[1].toString().toIntOrNull() ?: -1,
        args[2].toString() == "true",
      )
    }
  }

  /** A pager of three pages. Property tags follow the holder's declaration order. */
  private fun tree(targetPage: Int, sequence: Int, watching: Boolean = true) = HostTree().also {
    it.apply(
      decodePositional(
        """[1,[[0,1,$PAGER],[1,1,1,$targetPage],[1,1,2,$sequence],[1,1,3,false],[1,1,4,$watching],""" +
          """[0,2,$TEXT],[1,2,1,"PAGE-ONE"],[3,1,1,2,0],""" +
          """[0,3,$TEXT],[1,3,1,"PAGE-TWO"],[3,1,1,3,1],""" +
          """[0,4,$TEXT],[1,4,1,"PAGE-THREE"],[3,1,1,4,2],""" +
          """[3,0,1,1,0]]]""",
      ),
    )
  }

  private fun rendered(
    tree: HostTree,
    reports: Reports = Reports(),
    body: androidx.compose.ui.test.ComposeUiTest.(Reports) -> Unit,
  ) = runComposeUiTest {
    setContent {
      Box(Modifier.size(300.dp)) { DogwoodTree(tree, reports.sink, skew = tree.skew) }
    }
    body(reports)
  }

  @Test
  fun onlyTheCurrentPageIsComposed() = run {
    // The property that makes this a pager rather than a snapping row, and the reason the binding
    // is hand-written: a generated slot would have composed all three at once.
    val tree = tree(targetPage = 0, sequence = 0)
    rendered(tree) {
      waitForIdle()
      onNodeWithText("PAGE-ONE").assertExists()
      assertEquals(
        0,
        onAllNodesWithText("PAGE-THREE").fetchSemanticsNodes().size,
        "a page two swipes away was composed",
      )
      assertTrue(tree.skew.isEmpty, "rendering a pager reported skew: ${tree.skew}")
    }
  }

  @Test
  fun theHostCountsThePagesAndSaysHowMany() = run {
    // Reported rather than declared: a guest that assumed its own count would be wrong the moment
    // a page sat behind an `if`, and it is the host that lays them out.
    val tree = tree(targetPage = 0, sequence = 0)
    rendered(tree) { reports ->
      waitUntil("no report arrived", timeoutMillis = WAIT) { reports.all.isNotEmpty() }
      assertEquals(3, reports.all.last().second, "reports: ${reports.all}")
    }
  }

  @Test
  fun aDeclaredTargetMovesToThatPage() = run {
    val tree = tree(targetPage = 2, sequence = 1)
    rendered(tree) {
      waitUntil("the pager never moved", timeoutMillis = WAIT) {
        onAllNodesWithText("PAGE-THREE").fetchSemanticsNodes().isNotEmpty()
      }
      onNodeWithText("PAGE-THREE").assertExists()
    }
  }

  @Test
  fun arrivingWhereTheGuestAskedIsNotTheUsersDoing() = run {
    // The `byUser` half, in the case a guest most needs right: its own request landing must not
    // read as a swipe, or "they skipped the tour" fires on every programmatic move.
    val tree = tree(targetPage = 2, sequence = 1)
    rendered(tree) { reports ->
      waitUntil("never settled on the target", timeoutMillis = WAIT) {
        reports.all.any { it.first == 2 }
      }
      assertEquals(false, reports.all.last { it.first == 2 }.third, "reports: ${reports.all}")
    }
  }

  @Test
  fun aTargetPastTheEndClampsRatherThanThrowing() = run {
    // Skew, in the shape a pager meets it: a payload built against more pages than this client
    // composed. Clamping keeps the screen where throwing inside composition would take it down on
    // every client at once — ADR-035's rule, applied to a page index.
    val tree = tree(targetPage = 9, sequence = 1)
    rendered(tree) {
      waitUntil("the pager never settled", timeoutMillis = WAIT) {
        onAllNodesWithText("PAGE-THREE").fetchSemanticsNodes().isNotEmpty()
      }
      onNodeWithText("PAGE-THREE").assertExists()
    }
  }

  @Test
  fun anUnwatchedPagerReportsNothing() = run {
    val tree = tree(targetPage = 0, sequence = 0, watching = false)
    rendered(tree) { reports ->
      waitForIdle()
      onNodeWithText("PAGE-ONE").assertExists()
      assertEquals(emptyList(), reports.all, "an unwatched pager reported: ${reports.all}")
    }
  }
}

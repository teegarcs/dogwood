/*
 * Project Dogwood -- the fifth holder shape, and the first whose report keeps arriving.
 *
 * A sheet is scroll's shape (a declared target, a continuous report) with snackbar's meaning (the
 * report is a decision somebody made). The two things worth asserting are the ones a guest depends
 * on and cannot see: that a *sequence* drives the sheet rather than a value — so asking twice for
 * the same position is two requests — and that `byUser` distinguishes the user moving the sheet
 * from the guest's own request landing, which is the field a guest needs to implement "remember
 * that they dismissed it" and cannot derive from position alone.
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
import dev.dogwood.protocol.widgetTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val SHEET = widgetTag(1, 18).value
private val TEXT = DogwoodDictionary.Text.value
private const val WAIT = 10_000L

@OptIn(ExperimentalTestApi::class)
class SheetMirrorTest {

  /** Every report the guest would have received, in order. */
  private class Reports {
    val all = mutableListOf<Pair<String, Boolean>>()
    val sink = EventSink { _, _, args ->
      all += (args[0].toString().trim('"')) to (args[1].toString() == "true")
    }
  }

  /**
   * A sheet carrying one `Text`. Property tags follow the shape's declaration order: 1 target,
   * 2 sequence, 3 watching, 4 skipPartiallyExpanded.
   */
  private fun tree(target: String, sequence: Int, watching: Boolean = true) = HostTree().also {
    it.apply(
      decodePositional(
        """[1,[[0,1,$SHEET],[1,1,1,"$target"],[1,1,2,$sequence],[1,1,3,$watching],""" +
          """[1,1,4,false],[0,2,$TEXT],[1,2,1,"INSIDE-THE-SHEET"],[3,1,1,2,0],[3,0,1,1,0]]]""",
      ),
    )
  }

  private fun rendered(
    tree: HostTree,
    reports: Reports = Reports(),
    body: androidx.compose.ui.test.ComposeUiTest.(Reports) -> Unit,
  ) = runComposeUiTest {
    setContent {
      Box(Modifier.size(400.dp)) { DogwoodTree(tree, reports.sink, skew = tree.skew) }
    }
    body(reports)
  }

  @Test
  fun aSheetNobodyAskedForIsNotOnScreen() = run {
    // Sequence zero means nothing has ever been requested — the same "a holder exists, a request
    // was not made" distinction every shape in this table draws. The properties are on the wire.
    val tree = tree(target = "expanded", sequence = 0)
    rendered(tree) {
      waitForIdle()
      assertEquals(0, onAllNodesWithText("INSIDE-THE-SHEET").fetchSemanticsNodes().size)
    }
  }

  @Test
  fun aDeclaredTargetOpensTheSheetWithItsContent() = run {
    val tree = tree(target = "expanded", sequence = 1)
    rendered(tree) {
      waitUntil("the sheet never opened", timeoutMillis = WAIT) {
        onAllNodesWithText("INSIDE-THE-SHEET").fetchSemanticsNodes().isNotEmpty()
      }
      onNodeWithText("INSIDE-THE-SHEET").assertExists()
      assertTrue(tree.skew.isEmpty, "opening a sheet reported skew: ${tree.skew}")
    }
  }

  @Test
  fun theHostReportsWhereTheSheetSettledAndSaysItWasNotTheUser() = run {
    // The `byUser` half, in the case a guest most needs to get right: the sheet arriving where the
    // guest asked is the guest's own request landing, and must NOT read as the user moving it, or
    // "remember that they dismissed it" fires on every programmatic open.
    val tree = tree(target = "expanded", sequence = 1)
    rendered(tree) { reports ->
      waitUntil("no report arrived", timeoutMillis = WAIT) { reports.all.isNotEmpty() }
      val settled = reports.all.last()
      assertEquals("expanded", settled.first, "reports: ${reports.all}")
      assertEquals(false, settled.second, "the guest's own request read as a user action")
    }
  }

  @Test
  fun anUnwatchedSheetReportsNothing() = run {
    // Presence is a property because the host cannot see guest closures. Without this every sheet
    // on every screen would pay for an observer nobody reads.
    val tree = tree(target = "expanded", sequence = 1, watching = false)
    rendered(tree) { reports ->
      waitUntil("the sheet never opened", timeoutMillis = WAIT) {
        onAllNodesWithText("INSIDE-THE-SHEET").fetchSemanticsNodes().isNotEmpty()
      }
      assertEquals(emptyList(), reports.all, "an unwatched sheet reported: ${reports.all}")
    }
  }

  @Test
  fun askingAgainAfterTheUserClosedItReopensTheSheet() = run {
    // The sequence rule, and the case that makes it necessary rather than tidy: the guest asks for
    // `expanded` twice with the user closing the sheet in between. A value-keyed effect sees the
    // same string the second time and does nothing, leaving a control that works once.
    val tree = tree(target = "expanded", sequence = 1)
    rendered(tree) {
      waitUntil("the sheet never opened", timeoutMillis = WAIT) {
        onAllNodesWithText("INSIDE-THE-SHEET").fetchSemanticsNodes().isNotEmpty()
      }
      tree.apply(decodePositional("""[2,[[1,1,1,"hidden"],[1,1,2,2]]]"""))
      waitUntil("the sheet never closed", timeoutMillis = WAIT) {
        onAllNodesWithText("INSIDE-THE-SHEET").fetchSemanticsNodes().isEmpty()
      }
      tree.apply(decodePositional("""[3,[[1,1,1,"expanded"],[1,1,2,3]]]"""))
      waitUntil("the second request did nothing", timeoutMillis = WAIT) {
        onAllNodesWithText("INSIDE-THE-SHEET").fetchSemanticsNodes().isNotEmpty()
      }
      onNodeWithText("INSIDE-THE-SHEET").assertExists()
    }
  }
}

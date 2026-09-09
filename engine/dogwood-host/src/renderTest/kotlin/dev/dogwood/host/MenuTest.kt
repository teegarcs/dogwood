/*
 * Project Dogwood -- a menu, and the second case of "openness is the guest's own state".
 *
 * What needs asserting is what a guest depends on and cannot see: that the **anchor** stays on
 * screen while the menu comes and goes (it is the control that opened it, and a menu that replaced
 * its own button would be a menu nobody could reopen), that closing **removes** the items rather
 * than covering them, and that an item's tap crosses as its event.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.protocol.decodePositional
import dev.dogwood.protocol.widgetTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val MENU = widgetTag(1, 19).value
private val MENU_ITEM = widgetTag(1, 20).value
private val TEXT = DogwoodDictionary.Text.value

@OptIn(ExperimentalTestApi::class)
class MenuTest {

  /**
   * A menu whose anchor is a `Text` and whose content is one `MenuItem`.
   *
   * Slot 1 is the anchor and slot 2 the content, in surface declaration order — which is also the
   * order the dictionary assigns, and the reason the surface may only ever be appended to.
   */
  private fun tree(expanded: Boolean) = HostTree().also {
    it.apply(
      decodePositional(
        """[1,[[0,1,$MENU],[1,1,1,$expanded],""" +
          """[0,2,$TEXT],[1,2,1,"THE-ANCHOR"],[3,1,1,2,0],""" +
          """[0,3,$MENU_ITEM],[1,3,1,"THE-ITEM"],[3,1,2,3,0],""" +
          """[3,0,1,1,0]]]""",
      ),
    )
  }

  private class Taps {
    val ids = mutableListOf<Int>()
    val sink = EventSink { node, _, _ -> ids += node.id.value }
  }

  private fun rendered(
    tree: HostTree,
    taps: Taps = Taps(),
    body: androidx.compose.ui.test.ComposeUiTest.(Taps) -> Unit,
  ) = runComposeUiTest {
    setContent {
      Box(Modifier.size(400.dp)) { DogwoodTree(tree, taps.sink, skew = tree.skew) }
    }
    waitForIdle()
    body(taps)
  }

  @Test
  fun anOpenMenuShowsItsItemsBesideItsAnchor() = run {
    val tree = tree(expanded = true)
    rendered(tree) {
      onNodeWithText("THE-ANCHOR").assertExists()
      onNodeWithText("THE-ITEM").assertExists()
      assertTrue(tree.skew.isEmpty, "rendering a menu reported skew: ${tree.skew}")
    }
  }

  @Test
  fun aClosedMenuKeepsItsAnchorAndDropsItsItems() = run {
    // The pair that matters. The anchor is the control the user reopens the menu with, so it must
    // survive; the items must be **absent** rather than hidden, or a closed menu keeps its
    // contents' `remember` alive and their effects running.
    val tree = tree(expanded = false)
    rendered(tree) {
      onNodeWithText("THE-ANCHOR").assertExists()
      assertEquals(
        0,
        onAllNodesWithText("THE-ITEM").fetchSemanticsNodes().size,
        "a closed menu left its items composed",
      )
    }
  }

  @Test
  fun tappingAnItemCrossesAsThatItemsEvent() = run {
    val tree = tree(expanded = true)
    rendered(tree) { taps ->
      onNodeWithText("THE-ITEM").performClick()
      waitUntil("the tap never crossed", timeoutMillis = 10_000) { taps.ids.isNotEmpty() }
      // Node 3 is the item, not the menu: an event attributed to the container would make two
      // items indistinguishable, which is the failure a guest could not diagnose from its side.
      assertEquals(listOf(3), taps.ids)
    }
  }
}

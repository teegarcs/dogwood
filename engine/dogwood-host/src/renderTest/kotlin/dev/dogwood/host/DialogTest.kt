/*
 * Project Dogwood -- the first component whose presence is a host concern.
 *
 * A dialog is a real platform window, so what needs asserting is not "the binding ran" but the two
 * things a guest depends on and cannot see: that hiding **removes** the content rather than
 * covering it, and that the platform's dismissal reaches the guest as an event. The first is what
 * keeps a closed dialog from leaving its `remember` and its effects alive behind the screen.
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

private val DIALOG = widgetTag(1, 17).value
private val TEXT = DogwoodDictionary.Text.value

@OptIn(ExperimentalTestApi::class)
class DialogTest {

  /** A dialog carrying one `Text`, open or closed. Property 1 is `visible`; slot 1 is the content. */
  private fun tree(visible: Boolean) = HostTree().also {
    it.apply(
      decodePositional(
        """[1,[[0,1,$DIALOG],[1,1,1,$visible],[0,2,$TEXT],[1,2,1,"INSIDE-THE-DIALOG"],""" +
          """[3,1,1,2,0],[3,0,1,1,0]]]""",
      ),
    )
  }

  /**
   * Renders and runs [assertions] **with the test's own receiver**, so a node query inside it is
   * the same query the harness would make — and the block's result is returned from each test, for
   * the reason ADR-054 gives: on Kotlin/WebAssembly a discarded one composes nothing at all.
   */
  private fun rendered(
    tree: HostTree,
    assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
  ) = runComposeUiTest {
    setContent {
      Box(Modifier.size(400.dp)) { DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew) }
    }
    waitForIdle()
    assertions()
  }

  @Test
  fun anOpenDialogShowsItsContent() = run {
    val tree = tree(visible = true)
    rendered(tree) {
      onNodeWithText("INSIDE-THE-DIALOG").assertExists()
      assertTrue(tree.skew.isEmpty, "rendering a dialog reported skew: ${tree.skew}")
    }
  }

  @Test
  fun aClosedDialogComposesNothingAtAll() = run {
    // Not "is not displayed": **absent**. A hidden dialog that stayed composed would keep its
    // content's `remember` alive and its effects running, so a guest that closed one would leave
    // work behind the screen it moved on from.
    val tree = tree(visible = false)
    rendered(tree) {
      assertEquals(
        0,
        onAllNodesWithText("INSIDE-THE-DIALOG").fetchSemanticsNodes().size,
        "a closed dialog left its content composed",
      )
    }
  }

  @Test
  fun theContentIsThereToBeRemovedInTheFirstPlace() = run {
    // The control for the test above. Without it, "the content is absent" is also satisfied by a
    // binding that never composes its slot at all -- which would pass while the component was
    // entirely broken.
    val tree = tree(visible = true)
    rendered(tree) {
      onNodeWithText("INSIDE-THE-DIALOG").assertExists()
    }
  }
}

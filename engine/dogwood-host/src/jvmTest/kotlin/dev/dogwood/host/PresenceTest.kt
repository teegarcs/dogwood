/*
 * Project Dogwood -- enter and exit.
 *
 * The exit half is the reason this is a container rather than something the applier does. A node
 * animating away is a node the guest still owns; `onExited` is how it learns removal is safe. These
 * tests are about that handshake, and about the one distinction that makes it usable: an exit that
 * is interrupted is not an exit.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.dogwood.protocol.decodePositional

private val TEXT_TAG = DogwoodDictionary.Text.value
private val PRESENCE = dev.dogwood.protocol.widgetTag(1, 14).value

/** Presence(1) wrapping Text(2), with the guest's visibility as property 1. */
private fun presenceTree(visible: Boolean, exit: String = "fade"): HostTree = HostTree().also {
  it.apply(
    decodePositional(
      """[1,[[0,1,$PRESENCE],[1,1,1,$visible],[1,1,3,"$exit"],[3,0,1,1,0],""" +
        """[0,2,$TEXT_TAG],[1,2,1,"content"],[3,1,1,2,0]]]""",
    ),
  )
}

private fun HostTree.setVisible(visible: Boolean, sequence: Int) {
  apply(decodePositional("[$sequence,[[1,1,1,$visible]]]"))
  Snapshot.sendApplyNotifications()
}

class PresenceTest {

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun contentIsPresentWhenTheGuestSaysItIs() {
    val tree = presenceTree(visible = true)
    runComposeUiTest {
      setContent { Box(Modifier.size(300.dp, 300.dp)) { DogwoodTree(tree, EventSink { _, _, _ -> }) } }
      onNodeWithText("content").assertIsDisplayed()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun anExitReportsOnceWhenItHasFinishedAndNotBefore() {
    // The handshake: the guest keeps the node composed until this arrives, then removes it.
    val events = mutableListOf<Int>()
    val tree = presenceTree(visible = true)
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        Box(Modifier.size(300.dp, 300.dp)) {
          DogwoodTree(tree, EventSink { _, tag, _ -> events += tag.value })
        }
      }
      mainClock.advanceTimeBy(50)
      events.clear()

      tree.setVisible(false, sequence = 2)
      mainClock.advanceTimeBy(30)
      assertEquals(emptyList(), events, "mid-exit is not exited")

      mainClock.advanceTimeBy(1_000)
      assertEquals(listOf(1), events, "the exit must report exactly once, on its event tag")
      assertEquals(
        0,
        onAllNodesWithText("content").fetchSemanticsNodes().size,
        "and the content must be gone",
      )
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun anInterruptedExitReportsNothing() {
    // The distinction that makes the event usable. A guest that treated an interrupted exit as an
    // exit would remove a node the user can still see.
    val events = mutableListOf<Int>()
    val tree = presenceTree(visible = true)
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        Box(Modifier.size(300.dp, 300.dp)) {
          DogwoodTree(tree, EventSink { _, tag, _ -> events += tag.value })
        }
      }
      mainClock.advanceTimeBy(50)
      events.clear()

      tree.setVisible(false, sequence = 2)
      mainClock.advanceTimeBy(40)
      tree.setVisible(true, sequence = 3)
      mainClock.advanceTimeBy(1_000)

      assertEquals(emptyList(), events, "an exit that never completed must not be reported")
      onNodeWithText("content").assertIsDisplayed()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun contentAbsentFromTheStartNeitherAppearsNorReports() {
    // A guest that starts something hidden has not exited anything.
    val events = mutableListOf<Int>()
    val tree = presenceTree(visible = false)
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        Box(Modifier.size(300.dp, 300.dp)) {
          DogwoodTree(tree, EventSink { _, tag, _ -> events += tag.value })
        }
      }
      mainClock.advanceTimeBy(1_000)
      assertEquals(0, onAllNodesWithText("content").fetchSemanticsNodes().size)
      assertEquals(emptyList(), events, "never having been visible is not an exit")
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun anUnknownTransitionNameDegradesToAFadeAndIsReported() {
    // A payload built against a newer design system gets motion that is slightly wrong rather than
    // a screen that throws — the same rule every other named thing follows.
    val skew = SkewReport()
    val tree = presenceTree(visible = true, exit = "dissolveIntoStardust")
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        Box(Modifier.size(300.dp, 300.dp)) {
          DogwoodTree(tree, EventSink { _, _, _ -> }, skew = skew)
        }
      }
      mainClock.advanceTimeBy(50)
      onNodeWithText("content").assertIsDisplayed()

      tree.setVisible(false, sequence = 2)
      mainClock.advanceTimeBy(1_000)
      assertEquals(0, onAllNodesWithText("content").fetchSemanticsNodes().size, "it still exits")
    }
    assertEquals(setOf("dissolveIntoStardust"), skew.unknownTransitions)
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun combinedTransitionPartsAreAccepted() {
    val skew = SkewReport()
    val tree = presenceTree(visible = true, exit = "fade+shrinkVertically")
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        Box(Modifier.size(300.dp, 300.dp)) {
          DogwoodTree(tree, EventSink { _, _, _ -> }, skew = skew)
        }
      }
      mainClock.advanceTimeBy(50)
      tree.setVisible(false, sequence = 2)
      mainClock.advanceTimeBy(1_000)
      assertEquals(0, onAllNodesWithText("content").fetchSemanticsNodes().size)
    }
    assertTrue(skew.unknownTransitions.isEmpty(), "both parts are known: ${skew.unknownTransitions}")
  }
}

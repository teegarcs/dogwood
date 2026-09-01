/*
 * Project Dogwood -- animation, host side, on a controlled clock.
 *
 * The guest tests prove one crossing per declared target. These prove the other half: that
 * something actually moves between those crossings, that it lands where it was told, and that
 * completion means arrival rather than "the target changed".
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.protocol.EventTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TEXT_TAG = DogwoodDictionary.Text.value

/** `[modifierTag, [animatedNumber, target, spec, notify]]`; HEIGHT is local tag 7 in segment 0. */
private fun heightTree(target: Double, notify: Boolean = true, sequence: Int = 1): String =
  """[$sequence,[[2,1,[[7,[12,$target,[1,300,"linear",0],$notify]]]]]]"""

private fun initialTree(): HostTree = HostTree().also {
  it.apply(
    decodePositional(
      """[1,[[0,1,$TEXT_TAG],[1,1,1,"x"],[2,1,[[7,[12,20.0,[1,300,"linear",0],true]]]],[3,0,1,1,0]]]""",
    ),
  )
}

class AnimatedModifierHostTest {

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aChangedTargetAnimatesRatherThanJumping() {
    val tree = initialTree()
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        Box(Modifier.size(300.dp, 300.dp)) {
          DogwoodTree(tree, EventSink { _, _, _ -> }, Modifier.size(300.dp, 300.dp))
        }
      }
      mainClock.advanceTimeBy(50)
      // First composition starts *at* the target: an animated value with nothing to animate from
      // must not fade in from zero on every screen open.
      assertEquals(20, onNodeWithText("x").fetchSemanticsNode().size.height)

      tree.apply(decodePositional(heightTree(120.0, sequence = 2)))
      Snapshot.sendApplyNotifications()
      mainClock.advanceTimeBy(150)

      val midway = onNodeWithText("x").fetchSemanticsNode().size.height
      assertTrue(
        midway in 21..119,
        "the value must be in flight halfway through a 300ms linear tween; got $midway",
      )

      mainClock.advanceTimeBy(300)
      assertEquals(120, onNodeWithText("x").fetchSemanticsNode().size.height, "it must arrive")
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun completionFiresOnceOnArrival() {
    val tree = initialTree()
    val completions = mutableListOf<Int>()
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        Box(Modifier.size(300.dp, 300.dp)) {
          DogwoodTree(
            tree,
            EventSink { _, tag, _ -> completions += tag.value },
            Modifier.size(300.dp, 300.dp),
          )
        }
      }
      mainClock.advanceTimeBy(50)
      completions.clear()

      tree.apply(decodePositional(heightTree(120.0, sequence = 2)))
      Snapshot.sendApplyNotifications()
      mainClock.advanceTimeBy(100)
      assertEquals(emptyList(), completions, "nothing has arrived yet")

      mainClock.advanceTimeBy(400)
      // Event tag 1000 + 0: the first element of the chain. Derived from the position, which is
      // the identifier both sides already agree on.
      assertEquals(listOf(1000), completions)
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aRetargetIsNotACompletion() {
    // The distinction matters: a guest that treated a retarget as an arrival would advance a
    // wizard, dismiss a dialogue, or fire analytics for something that never finished.
    val tree = initialTree()
    val completions = mutableListOf<Int>()
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        Box(Modifier.size(300.dp, 300.dp)) {
          DogwoodTree(
            tree,
            EventSink { _, tag, _ -> completions += tag.value },
            Modifier.size(300.dp, 300.dp),
          )
        }
      }
      mainClock.advanceTimeBy(50)
      completions.clear()

      tree.apply(decodePositional(heightTree(120.0, sequence = 2)))
      Snapshot.sendApplyNotifications()
      mainClock.advanceTimeBy(100)

      // Interrupted halfway.
      tree.apply(decodePositional(heightTree(40.0, sequence = 3)))
      Snapshot.sendApplyNotifications()
      mainClock.advanceTimeBy(400)

      assertEquals(listOf(1000), completions, "exactly one arrival, at the second target")
      assertEquals(40, onNodeWithText("x").fetchSemanticsNode().size.height)
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aGuestThatAsksForNoCompletionGetsNoEvents() {
    val tree = initialTree()
    val completions = mutableListOf<Int>()
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        Box(Modifier.size(300.dp, 300.dp)) {
          DogwoodTree(
            tree,
            EventSink { _, tag, _ -> completions += tag.value },
            Modifier.size(300.dp, 300.dp),
          )
        }
      }
      mainClock.advanceTimeBy(50)
      completions.clear()

      tree.apply(decodePositional(heightTree(120.0, notify = false, sequence = 2)))
      Snapshot.sendApplyNotifications()
      mainClock.advanceTimeBy(500)

      assertEquals(emptyList(), completions)
      assertEquals(120, onNodeWithText("x").fetchSemanticsNode().size.height, "it still animates")
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aPlainNumberStillWorksWhereAnimationIsNotAsked() {
    // Every existing modifier argument is a plain number, and this path must stay free.
    val tree = HostTree().also {
      it.apply(
        decodePositional("""[1,[[0,1,$TEXT_TAG],[1,1,1,"x"],[2,1,[[7,64]]],[3,0,1,1,0]]]"""),
      )
    }
    runComposeUiTest {
      setContent {
        Box(Modifier.size(300.dp, 300.dp)) {
          DogwoodTree(tree, EventSink { _, _, _ -> }, Modifier.size(300.dp, 300.dp))
        }
      }
      assertEquals(64, onNodeWithText("x").fetchSemanticsNode().size.height)
    }
  }
}

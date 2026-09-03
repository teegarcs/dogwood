/*
 * Project Dogwood -- node identity, in a real host composition.
 *
 * specs/layer-5-host.md says wrapping every child in `key(node.id)` is "a generator requirement
 * with a test, not a note". Until now the test was on the guest side: it proved the *right change*
 * crossed, that a reorder produces a `ChildMove` rather than a rewrite. What it could not prove is
 * the half the requirement is actually about -- that the host's composition keeps the state
 * attached to a node when that node moves.
 *
 * These run a real Compose composition on the Java Virtual Machine, which is what makes the
 * difference assertable at all. The negative control is the important one: it renders the same
 * tree *without* `key` and shows the state being lost, so this passes because of the mechanism
 * rather than in spite of its absence.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.dogwood.protocol.decodePositional

private val TEXT = DogwoodDictionary.Text.value
private val SPACER = DogwoodDictionary.Spacer.value
private val LIST = DogwoodDictionary.VerticalList.value

private fun batch(sequence: Int, changes: List<String>): String =
  "[$sequence,[${changes.joinToString(",")}]]"

/**
 * Root(0) content slot holds a spacer at index 0 and a scrolling list at index 1.
 *
 * The spacer exists to give the reorder something to swap with. Its tag differs from the list's,
 * which is what makes the negative control fail loudly: without a key, position 0's composition
 * group is reused for a different widget entirely.
 */
private fun scrollingTree(rows: Int = 40): HostTree {
  val changes = mutableListOf(
    "[0,1,$SPACER]",
    "[3,0,1,1,0]",
    "[0,2,$LIST]",
    "[1,2,5,true]",
    "[3,0,1,2,1]",
  )
  repeat(rows) { index ->
    val id = 100 + index
    changes += "[0,$id,$TEXT]"
    changes += "[1,$id,1,\"row $index\"]"
    changes += "[3,2,1,$id,$index]"
  }
  return HostTree().also { it.apply(decodePositional(batch(1, changes))) }
}

/**
 * Brings the second child to the front.
 *
 * `[5, parent, slot, from, to, count]`, and the `to` index is in the list's *pre-removal* space --
 * Compose's `AbstractApplier.move` convention, which both sides of the protocol implement. That
 * makes `move(0, 1, 1)` a no-op rather than a swap, which is worth knowing: an earlier draft of
 * this test used it and every assertion still passed, because nothing had moved.
 *
 * The apply notification is explicit. In the running host it is not: `HostTree.apply` is called
 * from the user-interface dispatcher, where the platform's global snapshot manager sends
 * notifications for it. A headless test composition has no such manager, and without this the
 * mutation lands in the global snapshot and the composition never hears about it -- which looks
 * exactly like the test passing.
 */
private fun HostTree.swapChildren() {
  apply(decodePositional(batch(2, listOf("[5,0,1,1,0,1]"))))
  Snapshot.sendApplyNotifications()
}

private val noEvents = EventSink { _, _, _ -> }

class NodeIdentityHostTest {

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun scrollPositionSurvivesAReorderOfItsSiblings() {
    val tree = scrollingTree()
    runComposeUiTest {
      setContent {
        Box(Modifier.size(240.dp, 320.dp)) {
          DogwoodTree(tree, noEvents, Modifier.size(240.dp, 320.dp))
        }
      }

      onNode(hasScrollAction()).performScrollToIndex(30)
      onNodeWithText("row 30").assertIsDisplayed()

      tree.swapChildren()
      waitForIdle()

      // The list did not move within its own slot; its *sibling* did. Without `key(node.id)`
      // Compose matches children positionally, so position 0's group -- previously a spacer --
      // would be reused for the list, every `remember` inside it would be fresh, and the scroll
      // position would be gone.
      onNodeWithText("row 30").assertIsDisplayed()
      // A lazy list does not compose what is off screen, so an item above the viewport has no
      // node at all -- which is a stronger statement than "not displayed".
      assertEquals(0, onAllNodesWithText("row 0").fetchSemanticsNodes().size)
    }
  }

  /**
   * Two children with the *same* widget tag, so nothing but identity can explain the result.
   *
   * The scroll test above shows the consequence; this shows the mechanism. Each rendered child
   * records which composition group it landed in, and the two children are then swapped. With
   * `key(node.id)` a child keeps its group when it moves; without one, Compose matches
   * positionally and the two children trade groups -- taking every `remember` in them along.
   */
  @Composable
  private fun Tracked(child: WidgetView, groups: MutableMap<Int, Int>, next: () -> Int) {
    val group = androidx.compose.runtime.remember { next() }
    groups[child.id.value] = group
    RenderNode(child, LayoutScope(), noEvents)
  }

  /**
   * Two renderers rather than one with a flag, and the reason is the finding itself.
   *
   * An earlier version wrote `if (keyed) key(id) { Tracked(...) } else Tracked(...)` inside the
   * loop, and the keyed case behaved exactly like the unkeyed one. `key` relocates a movable group
   * among its **immediate siblings**, and a per-item conditional puts each movable group alone
   * inside its own replace group, where there is nothing to match against. The mechanism is
   * silently defeated and the rendering still looks right.
   *
   * That is a live hazard for the generator, which emits this loop: a `key` wrapped in a
   * per-child conditional is indistinguishable from no key at all.
   */
  @Composable
  private fun KeyedChildren(tree: HostTree, groups: MutableMap<Int, Int>, next: () -> Int) {
    Column {
      for (child in tree.root.children(1)) {
        androidx.compose.runtime.key(child.id.value) { Tracked(child, groups, next) }
      }
    }
  }

  @Composable
  private fun UnkeyedChildren(tree: HostTree, groups: MutableMap<Int, Int>, next: () -> Int) {
    Column {
      for (child in tree.root.children(1)) {
        Tracked(child, groups, next)
      }
    }
  }

  private fun twoTexts(): HostTree {
    val changes = listOf(
      "[0,1,$TEXT]", "[1,1,1,\"first\"]", "[3,0,1,1,0]",
      "[0,2,$TEXT]", "[1,2,1,\"second\"]", "[3,0,1,2,1]",
    )
    return HostTree().also { it.apply(decodePositional(batch(1, changes))) }
  }

  @OptIn(ExperimentalTestApi::class)
  private fun groupsAfterSwap(keyed: Boolean): Pair<Map<Int, Int>, Map<Int, Int>> {
    val tree = twoTexts()
    val groups = mutableMapOf<Int, Int>()
    lateinit var before: Map<Int, Int>
    lateinit var after: Map<Int, Int>
    runComposeUiTest {
      setContent {
        var counter = 0
        val next = { ++counter }
        androidx.compose.runtime.CompositionLocalProvider(
          LocalExpressionEvaluator provides ExpressionEvaluator(),
        ) {
          if (keyed) {
            KeyedChildren(tree, groups, next)
          } else {
            UnkeyedChildren(tree, groups, next)
          }
        }
      }
      waitForIdle()
      before = groups.toMap()
      tree.swapChildren()
      waitForIdle()
      after = groups.toMap()
    }
    return before to after
  }

  @Test
  fun withKeysAChildKeepsItsCompositionGroupWhenItMoves() {
    val (before, after) = groupsAfterSwap(keyed = true)
    assertEquals(2, before.size, "both children must have rendered")
    assertEquals(
      before,
      after,
      "a keyed child must carry its group -- and therefore every `remember` inside it -- across " +
        "the move. Host-side scroll position, focus, animation state and the input method " +
        "connection all live in those `remember`s",
    )
  }

  @Test
  fun negativeControlWithoutKeysTheTwoChildrenTradeGroups() {
    // The bug the requirement exists to prevent, reproduced deliberately. Without this the test
    // above would pass whether or not `key` was doing anything.
    val (before, after) = groupsAfterSwap(keyed = false)
    assertEquals(2, before.size)
    assertTrue(
      before != after,
      "positional identity must be observable: the children were expected to trade groups, but " +
        "both mappings came out as $before",
    )
    assertEquals(before[1], after[2], "the group that held child 1 now holds child 2")
    assertEquals(before[2], after[1], "and the other way round")
  }
}

/**
 * Modifier order, which [ADR-009](../../../../../adrs/layer-5/ADR-009-modifier-subsystem.md) said
 * was asserted only as protocol.
 *
 * The protocol half -- a chain crosses in order with its arguments intact -- was already tested on
 * the guest. The claim it could not reach was that the order *matters*, which is the reason the
 * chain is replayed exactly rather than sorted or deduplicated. Now that a composition runs in a
 * unit test, it can.
 */
class ModifierOrderHostTest {

  private fun sizedTree(chain: String): HostTree {
    val changes = listOf(
      "[0,1,$TEXT]",
      "[1,1,1,\"x\"]",
      "[2,1,$chain]",
      "[3,0,1,1,0]",
    )
    return HostTree().also { it.apply(decodePositional(batch(1, changes))) }
  }

  @OptIn(ExperimentalTestApi::class)
  private fun measuredWidth(chain: String): Int {
    var width = -1
    runComposeUiTest {
      setContent { DogwoodTree(sizedTree(chain), noEvents) }
      width = onNodeWithText("x").fetchSemanticsNode().size.width
    }
    return width
  }

  @Test
  fun paddingBeforeSizeIsNotTheSameAsSizeBeforePadding() {
    // Modifiers apply outside in, and what is measured here is the innermost node -- the text's
    // own layout node, inside the whole chain.
    //
    //   padding(8).size(48): the padding is outermost and the size is inside it, so the content
    //     gets its full 48.
    //   size(48).padding(8): the size is outermost and the padding is inside it, so the padding
    //     eats sixteen of those 48 and the content gets 32.
    //
    // At the test density of one, that is literally 48 against 32.
    val paddingFirst = measuredWidth("[[1,8],[4,48]]")
    val sizeFirst = measuredWidth("[[4,48],[1,8]]")

    assertTrue(paddingFirst > 0 && sizeFirst > 0, "both chains must have measured")
    assertTrue(
      paddingFirst > sizeFirst,
      "order is load-bearing: got $paddingFirst for padding-then-size and $sizeFirst for " +
        "size-then-padding. Equal values would mean the chain was being sorted, merged, or " +
        "deduplicated somewhere between the guest and the host",
    )
  }

  @Test
  fun theSameChainTwiceMeasuresTheSame() {
    assertEquals(measuredWidth("[[1,8],[4,48]]"), measuredWidth("[[1,8],[4,48]]"))
  }
}

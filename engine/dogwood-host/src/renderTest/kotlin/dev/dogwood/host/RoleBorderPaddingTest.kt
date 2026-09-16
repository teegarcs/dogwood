/*
 * Project Dogwood -- the three modifiers ADR-069 §4 left as "one more tag when a component needs
 * them": a clickable with a semantics role, a border with a shape, and per-side padding whose sides
 * are animated targets.
 *
 * Each is graded on its observable consequence rather than on the branch having run: the role is
 * read back out of the semantics tree, the shaped border is a node that laid out at its size, and
 * the animated padding is where the child ended up once the clock settled.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.protocol.ModifierTags
import dev.dogwood.protocol.decodePositional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val BOX = DogwoodDictionary.Box.value
private const val FRAME = 200

@OptIn(ExperimentalTestApi::class)
class RoleBorderPaddingTest {

  private fun tree(vararg changes: String): HostTree = HostTree().also {
    it.apply(decodePositional("[1,[${changes.joinToString(",")}]]"))
  }

  private fun rendered(
    tree: HostTree,
    events: EventSink = EventSink { _, _, _ -> },
    assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
  ) = runComposeUiTest {
    setContent {
      Box(Modifier.size(FRAME.dp)) { DogwoodTree(tree, events, skew = tree.skew) }
    }
    waitForIdle()
    assertions()
  }

  @Test
  fun aRoleReachesTheSemanticsTreeAndTheTapStillRoutesByIndex() = run {
    val taps = mutableListOf<Int>()
    // Chain: [testTag, size, clickable-with-role] -- the clickable is element two.
    val tree = tree(
      "[0,1,$BOX]",
      """[2,1,[[${ModifierTags.TEST_TAG},"toggle"],[4,40],[${ModifierTags.CLICKABLE_ROLE},[true,"switch"]]]]""",
      "[3,0,1,1,0]",
    )
    rendered(tree, EventSink { _, tag, _ -> taps += tag.value }) {
      onNodeWithTag("toggle").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
      onNodeWithTag("toggle").performClick()
      assertEquals(listOf(1000 + 2), taps, "the role changed nothing about where the tap goes")
    }
  }

  @Test
  fun anUnknownRoleIsNoRoleAndIsReported() = run {
    val tree = tree(
      "[0,1,$BOX]",
      """[2,1,[[${ModifierTags.TEST_TAG},"toggle"],[4,40],[${ModifierTags.CLICKABLE_ROLE},[true,"lever"]]]]""",
      "[3,0,1,1,0]",
    )
    rendered(tree) {
      // Still tappable, still a node; just not called anything a screen reader would say wrongly.
      onNodeWithTag("toggle").assertWidthIsEqualTo(40.dp)
      assertTrue("role:lever" in tree.skew.unknownNames, tree.skew.unknownNames.toString())
    }
  }

  @Test
  fun aShapedBorderLaysOutAtItsSize() = run {
    // A 2dp border, an opaque literal colour, an 8dp rounded-corner shape.
    val tree = tree(
      "[0,1,$BOX]",
      """[2,1,[[${ModifierTags.TEST_TAG},"pill"],[4,40],[${ModifierTags.BORDER_SHAPE},[2,[3,4278190080],[1,8]]]]]""",
      "[3,0,1,1,0]",
    )
    rendered(tree) {
      onNodeWithTag("pill").assertWidthIsEqualTo(40.dp)
      assertTrue(tree.skew.isEmpty, "nothing about a shaped border is skew: ${tree.skew}")
    }
  }

  @Test
  fun animatedPerSidePaddingSettlesWhereItWasAsked() = run {
    // Padding first, then the tag, then the size: the tagged node sits inside the padded area, so
    // its position in the root IS the padding once the animation has landed. Start animates to
    // 24dp over a tween; top is a plain 8; end and bottom are unset.
    val tree = tree(
      "[0,1,$BOX]",
      """[2,1,[[${ModifierTags.PADDING_SIDES_ANIMATED},[[12,24,[1,100,"linear",0],false],8,0,0]],[${ModifierTags.TEST_TAG},"inner"],[4,10]]]""",
      "[3,0,1,1,0]",
    )
    rendered(tree) {
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      onNodeWithTag("inner").assertLeftPositionInRootIsEqualTo(24.dp)
      onNodeWithTag("inner").assertTopPositionInRootIsEqualTo(8.dp)
    }
  }
}

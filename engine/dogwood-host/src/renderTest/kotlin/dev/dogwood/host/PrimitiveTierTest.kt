/*
 * Project Dogwood -- the primitive tier's second growth, rendered.
 *
 * The guest tests prove the bytes; these prove the bytes *lay out*. Each tree is hand-written in
 * the positional grammar so the test reads the host exactly as a payload from any guest would,
 * and each assertion is a measured position or size in density-independent pixels -- the one
 * thing "the binding ran" cannot tell you. `testTag` is how a node is found, so the first
 * modifier proves itself by making every other assertion possible.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.protocol.ModifierTags
import dev.dogwood.protocol.decodePositional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TEXT = DogwoodDictionary.Text.value
private val BOX = DogwoodDictionary.Box.value
private val ROW = DogwoodDictionary.Row.value
private val COLUMN = DogwoodDictionary.Column.value

/** The root slot is 100dp square, so every position below is a fraction of a known frame. */
private const val FRAME = 100

@OptIn(ExperimentalTestApi::class)
class PrimitiveTierTest {

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
  fun aClickableNodeReportsATapOnTheTagDerivedFromItsElementIndex() = run {
    val taps = mutableListOf<Int>()
    // Chain: [testTag, size, clickable] -- the clickable is element two.
    val tree = tree(
      "[0,1,$BOX]",
      """[2,1,[[${ModifierTags.TEST_TAG},"target"],[4,40],[${ModifierTags.CLICKABLE},true]]]""",
      "[3,0,1,1,0]",
    )
    rendered(tree, EventSink { _, tag, _ -> taps += tag.value }) {
      onNodeWithTag("target").performClick()
      assertEquals(listOf(1000 + 2), taps, "one tap, on the element's own tag")
    }
  }

  @Test
  fun aDisabledClickableSwallowsTheTap() = run {
    val taps = mutableListOf<Int>()
    val tree = tree(
      "[0,1,$BOX]",
      """[2,1,[[${ModifierTags.TEST_TAG},"target"],[4,40],[${ModifierTags.CLICKABLE},false]]]""",
      "[3,0,1,1,0]",
    )
    rendered(tree, EventSink { _, tag, _ -> taps += tag.value }) {
      onNodeWithTag("target").performClick()
      assertEquals(emptyList(), taps)
    }
  }

  @Test
  fun perSidePaddingAndFillMaxSizeLayOutAsAsked() = run {
    // padding(10,20,30,40) then testTag then fillMaxSize: the tagged node is what is left of the
    // frame after the padding -- 60 wide, 40 tall -- and it sits at (10, 20).
    val tree = tree(
      "[0,1,$BOX]",
      """[2,1,[[${ModifierTags.PADDING_SIDES},[10,20,30,40]],[${ModifierTags.TEST_TAG},"inner"],[${ModifierTags.FILL_MAX_SIZE},1.0]]]""",
      "[3,0,1,1,0]",
    )
    rendered(tree) {
      onNodeWithTag("inner").assertWidthIsEqualTo(60.dp).assertHeightIsEqualTo(40.dp)
      onNodeWithTag("inner").assertLeftPositionInRootIsEqualTo(10.dp).assertTopPositionInRootIsEqualTo(20.dp)
      assertTrue(tree.skew.isEmpty, "a well-formed chain reported skew: ${tree.skew}")
    }
  }

  @Test
  fun offsetMovesTheNodeWithoutChangingItsSize() = run {
    val tree = tree(
      "[0,1,$BOX]",
      """[2,1,[[${ModifierTags.OFFSET},[15,25]],[${ModifierTags.TEST_TAG},"moved"],[4,10]]]""",
      "[3,0,1,1,0]",
    )
    rendered(tree) {
      onNodeWithTag("moved")
        .assertLeftPositionInRootIsEqualTo(15.dp)
        .assertTopPositionInRootIsEqualTo(25.dp)
        .assertWidthIsEqualTo(10.dp)
    }
  }

  @Test
  fun aRowSpacesItsChildrenByTheDeclaredGap() = run {
    // Two 20dp children, spacedBy 10: the second starts at 30.
    val tree = tree(
      "[0,1,$ROW]", """[1,1,2,"spacedBy:10"]""", "[3,0,1,1,0]",
      "[0,2,$BOX]", "[2,2,[[4,20]]]", "[3,1,1,2,0]",
      "[0,3,$BOX]", """[2,3,[[${ModifierTags.TEST_TAG},"second"],[4,20]]]""", "[3,1,1,3,1]",
    )
    rendered(tree) {
      onNodeWithTag("second").assertLeftPositionInRootIsEqualTo(30.dp)
    }
  }

  @Test
  fun aRowWithSpaceBetweenPushesTheLastChildToTheEnd() = run {
    // The row fills the 100dp frame; a 20dp second child ends up at 80.
    val tree = tree(
      "[0,1,$ROW]", """[1,1,2,"spaceBetween"]""", "[2,1,[[2,1.0]]]", "[3,0,1,1,0]",
      "[0,2,$BOX]", "[2,2,[[4,20]]]", "[3,1,1,2,0]",
      "[0,3,$BOX]", """[2,3,[[${ModifierTags.TEST_TAG},"last"],[4,20]]]""", "[3,1,1,3,1]",
    )
    rendered(tree) {
      onNodeWithTag("last").assertLeftPositionInRootIsEqualTo(80.dp)
    }
  }

  @Test
  fun aColumnAlignsItsChildrenToTheEndWhenAsked() = run {
    // Ordinal 2 is `End` in the guest's `HorizontalAlignment`; the column fills the frame.
    val tree = tree(
      "[0,1,$COLUMN]", "[1,1,2,2]", "[2,1,[[2,1.0]]]", "[3,0,1,1,0]",
      "[0,2,$BOX]", """[2,2,[[${ModifierTags.TEST_TAG},"child"],[4,20]]]""", "[3,1,1,2,0]",
    )
    rendered(tree) {
      onNodeWithTag("child").assertLeftPositionInRootIsEqualTo(80.dp)
    }
  }

  @Test
  fun aBoxCentresItsContentWhenAsked() = run {
    // Ordinal 4 is `Center` in the guest's `BoxAlignment`.
    val tree = tree(
      "[0,1,$BOX]", "[1,1,1,4]", "[2,1,[[${ModifierTags.FILL_MAX_SIZE},1.0]]]", "[3,0,1,1,0]",
      "[0,2,$BOX]", """[2,2,[[${ModifierTags.TEST_TAG},"child"],[4,20]]]""", "[3,1,1,2,0]",
    )
    rendered(tree) {
      onNodeWithTag("child").assertLeftPositionInRootIsEqualTo(40.dp).assertTopPositionInRootIsEqualTo(40.dp)
    }
  }

  @Test
  fun aColumnThatSaysNothingLaysOutAsItAlwaysDid() = run {
    // The control for the two above: no tags, children packed at the start.
    val tree = tree(
      "[0,1,$COLUMN]", "[2,1,[[2,1.0]]]", "[3,0,1,1,0]",
      "[0,2,$BOX]", """[2,2,[[${ModifierTags.TEST_TAG},"child"],[4,20]]]""", "[3,1,1,2,0]",
    )
    rendered(tree) {
      onNodeWithTag("child").assertLeftPositionInRootIsEqualTo(0.dp).assertTopPositionInRootIsEqualTo(0.dp)
      assertTrue(tree.skew.isEmpty)
    }
  }

  @Test
  fun contentDescriptionReachesTheSemanticsTree() = run {
    val tree = tree(
      "[0,1,$BOX]",
      """[2,1,[[${ModifierTags.TEST_TAG},"named"],[${ModifierTags.CONTENT_DESCRIPTION},"a named box"],[4,20]]]""",
      "[3,0,1,1,0]",
    )
    rendered(tree) {
      onNodeWithTag("named").assertContentDescriptionEquals("a named box")
    }
  }

  @Test
  fun borderShadowAspectRatioAndBoundsRenderWithoutSkew() = run {
    // Nothing to measure that Compose does not already guarantee; what matters is that a chain of
    // every remaining new tag builds, draws, and reports nothing -- and that widthIn's bounds bite.
    val tree = tree(
      "[0,1,$BOX]",
      """[2,1,[[${ModifierTags.TEST_TAG},"decorated"],[${ModifierTags.BORDER},[2,[4,"primary"]]],[${ModifierTags.SHADOW},4],""" +
        """[${ModifierTags.WIDTH_IN},[-1,30]],[${ModifierTags.HEIGHT_IN},[35,-1]],[${ModifierTags.DEFAULT_MIN_SIZE},[10,10]],""" +
        """[${ModifierTags.WRAP_CONTENT_WIDTH},true],[${ModifierTags.WRAP_CONTENT_HEIGHT},true],[${ModifierTags.FILL_MAX_SIZE},1.0]]]""",
      "[3,0,1,1,0]",
    )
    rendered(tree) {
      onNodeWithTag("decorated").assertWidthIsEqualTo(30.dp)
      onNodeWithTag("decorated").assertHeightIsEqualTo(100.dp)
      assertTrue(tree.skew.isEmpty, "a well-formed chain reported skew: ${tree.skew}")
    }
  }

  @Test
  fun aspectRatioIsClampedRatherThanThrown() = run {
    // Zero throws inside layout in Compose; here it is a reported clamp and a screen that stays up.
    val tree = tree(
      "[0,1,$BOX]",
      """[2,1,[[${ModifierTags.TEST_TAG},"ratio"],[${ModifierTags.ASPECT_RATIO},0.0]]]""",
      "[3,0,1,1,0]",
    )
    rendered(tree) {
      onNodeWithTag("ratio").assertExists()
      assertTrue(tree.skew.clampedValues.any { it.startsWith("aspectRatio=") }, "${tree.skew.clampedValues}")
    }
  }

  @Test
  fun textOverridesRenderAndAnUnknownNameIsReported() = run {
    val tree = tree(
      "[0,1,$TEXT]", """[1,1,1,"styled"]""", """[1,1,6,"bold"]""", """[1,1,7,"center"]""",
      """[1,1,8,"clip"]""", "[1,1,9,18]", """[1,1,10,"underline"]""", "[1,1,11,24]",
      "[3,0,1,1,0]",
      "[0,2,$TEXT]", """[1,2,1,"odd"]""", """[1,2,6,"heavy"]""", """[1,2,7,"middle"]""",
      "[3,0,1,2,1]",
    )
    rendered(tree) {
      onNodeWithText("styled").assertExists()
      onNodeWithText("odd").assertExists()
      assertEquals(
        setOf("fontWeight:heavy", "textAlign:middle"),
        tree.skew.unknownTextStyles,
        "an unknown name degrades and is named in the report",
      )
    }
  }
}

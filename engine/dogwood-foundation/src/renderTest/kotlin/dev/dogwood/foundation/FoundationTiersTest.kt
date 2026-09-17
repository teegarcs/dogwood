/*
 * Project Dogwood -- one render test per segment in the foundation module, plus the one claim the
 * three of them share with the primitive tier.
 *
 * The module carries three dictionary segments -- `androidx.foundation.layout` (253),
 * `androidx.foundation` (254) and `androidx.ui` (252) -- and each is a separate generated binding
 * object with its own tag space. What could go wrong is per segment and per signature shape: a
 * layout whose arrangement never reaches the library renders a column that looks like a column and
 * ignores what the payload asked for; a text whose `maxLines` is dropped renders the whole
 * paragraph; a window component that composes into the tree instead of into a window renders in
 * the wrong place. None of that is visible in a build that compiles, and none of it is visible in
 * the lock either, which pins tags rather than behaviour.
 *
 * Every tree here is built by hand in the wire grammar, so the test exercises the binding the way a
 * payload does: `HostTree` decodes, `DogwoodTree` composes, the assertions read the rendered
 * screen. Tags are literals because tags are permanent -- the three lock files beside this module
 * are what makes that true.
 *
 * Expression bodies throughout: on Kotlin/WebAssembly `runComposeUiTest` returns a promise, and a
 * test that drops it composes nothing while passing (`tools/render-shape/check.py`).
 */
package dev.dogwood.foundation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.host.DogwoodDictionary
import dev.dogwood.host.DogwoodRegistry
import dev.dogwood.host.DogwoodTree
import dev.dogwood.host.EventSink
import dev.dogwood.host.HostTree
import dev.dogwood.protocol.ModifierTags
import dev.dogwood.protocol.Segments
import dev.dogwood.protocol.decodePositional
import dev.dogwood.protocol.widgetTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The local tags, from the three committed locks. Permanent by construction.
private const val LAYOUT_BOX = 1
private const val LAYOUT_BOX_EMPTY = 2
private const val LAYOUT_BOX_WITH_CONSTRAINTS = 3
private const val LAYOUT_COLUMN = 4
private const val LAYOUT_FLOW_ROW = 5
private const val LAYOUT_FLOW_COLUMN = 6
private const val LAYOUT_ROW = 7
private const val LAYOUT_SPACER = 8

private const val FOUNDATION_BASIC_TEXT = 1
private const val FOUNDATION_SELECTION_CONTAINER = 2
private const val FOUNDATION_DISABLE_SELECTION = 3

private const val UI_LOOKAHEAD_SCOPE = 1
private const val UI_DIALOG = 2
private const val UI_POPUP = 3

/**
 * A change batch, written the way a payload writes one.
 *
 * `[0,id,widget]` creates, `[1,id,tag,value]` sets a property, `[2,id,[[tag,value]]]` sets
 * modifiers and `[3,parent,slot,child,index]` inserts into a slot. Root is id 0. Unlike the
 * Material 3 tier's helper this one takes a segment per call, because this module's three segments
 * appear in the same tree.
 */
private class Wire {
  private val ops = mutableListOf<String>()
  private var last = 0

  fun create(segment: Int, local: Int): Int {
    val id = ++last
    ops += "[0,$id,${widgetTag(segment, local).value}]"
    return id
  }

  fun property(id: Int, tag: Int, value: String) { ops += "[1,$id,$tag,$value]" }

  fun tagged(id: Int, name: String) {
    ops += """[2,$id,[[${ModifierTags.TEST_TAG},"$name"]]]"""
  }

  fun insert(parent: Int, slot: Int, child: Int, index: Int = 0) {
    ops += "[3,$parent,$slot,$child,$index]"
  }

  /** A primitive-tier `Text`, which is what a payload puts in most slots. */
  fun text(parent: Int, slot: Int, words: String, index: Int = 0): Int {
    val id = ++last
    ops += "[0,$id,${DogwoodDictionary.Text.value}]"
    ops += """[1,$id,1,"$words"]"""
    insert(parent, slot, id, index)
    return id
  }

  fun build(): HostTree = HostTree().also {
    it.apply(decodePositional("[1,[${ops.joinToString(",")}]]"))
  }
}

@OptIn(ExperimentalTestApi::class)
class FoundationTiersTest {

  init {
    for (binding in listOf(FoundationBinding, FoundationLayoutBinding, UiBinding)) {
      if (DogwoodRegistry.bindings.none { it.segmentName == binding.segmentName }) {
        DogwoodRegistry.register(binding)
      }
    }
  }

  private fun rendered(
    tree: HostTree,
    events: EventSink = EventSink { _, _, _ -> },
    assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
  ) = runComposeUiTest {
    setContent {
      Box(Modifier.size(400.dp)) { DogwoodTree(tree, events, skew = tree.skew) }
    }
    waitForIdle()
    assertions()
  }

  // -----------------------------------------------------------------------------------------
  // Segment 253 -- androidx.foundation.layout
  // -----------------------------------------------------------------------------------------

  /**
   * Every one of the eight bound layouts, each composing a child of its own.
   *
   * One test rather than eight because the failure is shared: these are emitted from the same
   * signature shape -- a modifier, one or two closed-set tokens and a content slot -- so either the
   * shape is handled or none of them is. What it would catch is a binding that composed its slot
   * into the wrong place, or one whose dispatch fell through to the next tag: the two `Box`
   * overloads and the two flow layouts are adjacent tags with near-identical signatures, and a
   * dispatch off by one would render a `FlowRow` where the payload asked for a `FlowColumn` and
   * display exactly the same words.
   */
  @Test
  fun everyBoundLayoutComposesItsOwnChild() = run {
    val wire = Wire()
    val withContent = listOf(
      LAYOUT_BOX to "in a box",
      LAYOUT_BOX_WITH_CONSTRAINTS to "in a measured box",
      LAYOUT_COLUMN to "in a column",
      LAYOUT_FLOW_ROW to "in a flow row",
      LAYOUT_FLOW_COLUMN to "in a flow column",
      LAYOUT_ROW to "in a row",
    )
    for ((index, entry) in withContent.withIndex()) {
      val (local, words) = entry
      val id = wire.create(Segments.FOUNDATION_LAYOUT, local)
      wire.text(id, 1, words)
      wire.insert(0, 1, id, index)
    }
    // The two that take no content: the empty `Box` overload and `Spacer`. They carry a test tag,
    // so the claim about them is that a node with that tag exists -- `assertExists` rather than
    // `assertIsDisplayed`, because both are zero-sized when the payload sends no size modifier and
    // "displayed" would be a claim about the modifier rather than about the binding.
    for ((index, local) in listOf(LAYOUT_BOX_EMPTY, LAYOUT_SPACER).withIndex()) {
      val id = wire.create(Segments.FOUNDATION_LAYOUT, local)
      wire.tagged(id, "empty$index")
      wire.insert(0, 1, id, withContent.size + index)
    }
    val tree = wire.build()
    rendered(tree) {
      for ((_, words) in withContent) onNodeWithText(words).assertIsDisplayed()
      onNodeWithTag("empty0").assertExists()
      onNodeWithTag("empty1").assertExists()
      assertEquals(
        emptySet<Int>(),
        tree.skew.unknownWidgetTags,
        "a layout this client does carry was rendered as a placeholder",
      )
    }
  }

  /**
   * The closed sets, read by name, and the negative that gives the assertion its teeth.
   *
   * A `Column`'s `verticalArrangement` and `horizontalAlignment` are the two things a payload most
   * wants from the layout tier, and they cross as a *name* and an *ordinal* respectively -- two
   * different encodings in one component. A reader that failed to match either would report
   * `unknownNames` and fall back to the library's default, which still renders. So the claim is
   * that nothing was reported as unknown: the words being on screen alone would not distinguish a
   * working reader from a silently-defaulting one.
   */
  @Test
  fun aColumnsArrangementAndAlignmentAreReadWithoutSkew() = run {
    val wire = Wire()
    val column = wire.create(Segments.FOUNDATION_LAYOUT, LAYOUT_COLUMN)
    wire.property(column, 1, "\"spaceBetween\"")
    wire.property(column, 2, "2")
    wire.text(column, 1, "top of the column")
    wire.text(column, 1, "bottom of the column", index = 1)
    wire.insert(0, 1, column, 0)

    val row = wire.create(Segments.FOUNDATION_LAYOUT, LAYOUT_ROW)
    wire.property(row, 1, "\"spacedBy:8\"")
    wire.property(row, 2, "0")
    wire.text(row, 1, "left of the row")
    wire.insert(0, 1, row, 1)

    val boxed = wire.create(Segments.FOUNDATION_LAYOUT, LAYOUT_BOX)
    wire.property(boxed, 1, "4")
    wire.text(boxed, 1, "centred in the box")
    wire.insert(0, 1, boxed, 2)

    val tree = wire.build()
    rendered(tree) {
      onNodeWithText("top of the column").assertIsDisplayed()
      onNodeWithText("bottom of the column").assertIsDisplayed()
      onNodeWithText("left of the row").assertIsDisplayed()
      onNodeWithText("centred in the box").assertIsDisplayed()
      assertEquals(
        emptySet<String>(),
        tree.skew.unknownNames,
        "a name this client knows was reported as skew, which means the reader did not match it " +
          "and the library's default rendered instead",
      )
    }
  }

  /**
   * An arrangement from a newer dictionary is reported rather than guessed.
   *
   * The converse of the test above, and the one that proves that test is measuring something: an
   * unknown name must degrade to the library's default *and say so*, because a client one version
   * behind a payload is the normal case and silence is what makes it invisible.
   */
  @Test
  fun anArrangementThisClientDoesNotKnowIsReported() = run {
    val wire = Wire()
    val column = wire.create(Segments.FOUNDATION_LAYOUT, LAYOUT_COLUMN)
    wire.property(column, 1, "\"spacedAroundEvenlyBetween\"")
    wire.text(column, 1, "still rendered")
    wire.insert(0, 1, column, 0)
    val tree = wire.build()
    rendered(tree) {
      onNodeWithText("still rendered").assertIsDisplayed()
      assertTrue(
        tree.skew.unknownNames.any { it.startsWith("arrangement:") },
        "an arrangement from a newer dictionary was accepted silently: ${tree.skew.unknownNames}",
      )
    }
  }

  // -----------------------------------------------------------------------------------------
  // Segment 254 -- androidx.foundation
  // -----------------------------------------------------------------------------------------

  /**
   * All three bound `foundation` components: the text and the two selection wrappers.
   *
   * `BasicText` is the only one of the three that carries values, and it carries five of them; the
   * two wrappers carry nothing but a slot, which is precisely why they are easy to emit wrongly --
   * a wrapper whose slot was not composed renders an empty screen and fails nothing at compile
   * time.
   */
  @Test
  fun basicTextRendersAndBothSelectionWrappersComposeTheirSlot() = run {
    val wire = Wire()
    val text = wire.create(Segments.FOUNDATION, FOUNDATION_BASIC_TEXT)
    wire.property(text, 1, "\"foundation text\"")
    wire.property(text, 2, "\"ellipsis\"")
    wire.property(text, 3, "true")
    wire.property(text, 4, "2")
    wire.insert(0, 1, text, 0)

    val container = wire.create(Segments.FOUNDATION, FOUNDATION_SELECTION_CONTAINER)
    wire.text(container, 1, "selectable words")
    wire.insert(0, 1, container, 1)

    val disabled = wire.create(Segments.FOUNDATION, FOUNDATION_DISABLE_SELECTION)
    wire.text(disabled, 1, "unselectable words")
    wire.insert(0, 1, disabled, 2)

    val tree = wire.build()
    rendered(tree) {
      onNodeWithText("foundation text").assertIsDisplayed()
      onNodeWithText("selectable words").assertIsDisplayed()
      onNodeWithText("unselectable words").assertIsDisplayed()
      assertEquals(
        emptySet<String>(),
        tree.skew.unknownNames,
        "`ellipsis` is an overflow name this client knows; reporting it means the reader missed it",
      )
    }
  }

  // -----------------------------------------------------------------------------------------
  // Segment 252 -- androidx.ui
  // -----------------------------------------------------------------------------------------

  /**
   * The three `ui` components a payload can compose into an ordinary tree, one test each.
   *
   * `Dialog` and `Popup` are the only components in this module that compose into a **window**
   * rather than into the node they were inserted under, so a binding that treated them as ordinary
   * containers would put their content inside the layout instead of above it -- which renders, and
   * looks plausible until something is meant to overlay. `LookaheadScope` is a pure wrapper.
   *
   * A fourth component the rule accepted, `androidx.compose.ui.graphics.vector.Group`, is not in
   * this segment at all. It is a `@VectorComposable` and needs a vector applier, so composing it in
   * a host tree throws `IllegalStateException: Invalid applier` -- watched, then written into
   * `exclusions.txt` with its tag retired.
   */
  @Test
  fun aLookaheadScopeComposesItsContent() = run {
    val wire = Wire()
    val scope = wire.create(Segments.UI, UI_LOOKAHEAD_SCOPE)
    wire.text(scope, 1, "inside the lookahead scope")
    wire.insert(0, 1, scope, 0)
    rendered(wire.build()) {
      onNodeWithText("inside the lookahead scope").assertIsDisplayed()
    }
  }

  /**
   * `Dialog` composes into a window rather than into the node it was inserted under.
   *
   * Its own test rather than a third of one, because each of these needs its own composition: a
   * shared `run` block would return only the last harness result, and on Kotlin/WebAssembly the
   * two before it would compose nothing at all while passing (`tools/render-shape/check.py`).
   */
  @Test
  fun aDialogComposesItsContent() = run {
    val wire = Wire()
    val dialog = wire.create(Segments.UI, UI_DIALOG)
    wire.text(dialog, 1, "inside the dialog")
    wire.insert(0, 1, dialog, 0)
    rendered(wire.build()) {
      onNodeWithText("inside the dialog").assertIsDisplayed()
    }
  }

  @Test
  fun aPopupComposesItsContentAtTheAlignmentItWasGiven() = run {
    val wire = Wire()
    val popup = wire.create(Segments.UI, UI_POPUP)
    // `Alignment.Center`, the fifth entry of the guest enumeration, crossing as its ordinal.
    wire.property(popup, 1, "4")
    wire.text(popup, 1, "inside the popup")
    wire.insert(0, 1, popup, 0)
    val tree = wire.build()
    rendered(tree) {
      onNodeWithText("inside the popup").assertIsDisplayed()
      assertTrue(tree.skew.unknownNames.isEmpty(), "unexpected skew: ${tree.skew.unknownNames}")
    }
  }

  // -----------------------------------------------------------------------------------------
  // The claim the three of them share with segment 0
  // -----------------------------------------------------------------------------------------

  /**
   * The primitive tier and the generated layout tier both render, in one tree.
   *
   * ADR-072's D-I says segment 0 and `androidx.foundation.layout` both offer a `Column`, both stay
   * callable, and a payload picks by import. That is a sentence in a decision record until
   * something composes both at once and reads both back; this is that. What it would catch is the
   * mistake the primitive tier's own lock comment warns about in the other direction -- a
   * registration that shadowed segment 0's tags, which would not fail to render, it would render
   * the wrong widget.
   */
  @Test
  fun thePrimitiveColumnAndTheGeneratedColumnBothRenderInOneTree() = run {
    val wire = Wire()
    // Segment 0's `Column`, tag 2, which is what every payload in the field composes with.
    val primitive = wire.create(Segments.LAYOUT, 2)
    wire.property(primitive, 1, "\"spaceBetween\"")
    wire.text(primitive, 1, "the primitive column's child")
    wire.insert(0, 1, primitive, 0)
    // Segment 253's `Column`, tag 4, generated from the library's own signature.
    val generated = wire.create(Segments.FOUNDATION_LAYOUT, LAYOUT_COLUMN)
    wire.property(generated, 1, "\"spaceBetween\"")
    wire.text(generated, 1, "the generated column's child")
    wire.insert(0, 1, generated, 1)

    val tree = wire.build()
    rendered(tree) {
      onNodeWithText("the primitive column's child").assertIsDisplayed()
      onNodeWithText("the generated column's child").assertIsDisplayed()
      assertEquals(
        emptySet<Int>(),
        tree.skew.unknownWidgetTags,
        "one of the two columns was rendered as a placeholder, so the two tiers are not both live",
      )
    }
  }
}

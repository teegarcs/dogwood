/*
 * Project Dogwood -- one render test per bound Material 3 family.
 *
 * `Material3TierTest` beside this file pins the four properties of the *generator*: an event on a
 * derived tag, a component on the library's defaults, a slot composed inside, an affordance
 * withheld. This file asks a narrower question of every family the catalogue uses: **does this
 * particular generated binding do what its dictionary entry says?**
 *
 * The question is worth asking family by family because the generator emits each binding from a
 * signature, and the ways a signature can be mishandled are local to its shape: a component with
 * five slots can compose a child into the wrong one, a component whose event carries a `Float` can
 * drop the argument, a component whose optional event needs a presence flag can render the wrong
 * control. None of those is visible in a build that compiles.
 *
 * Every tree here is built by hand in the wire grammar, so the test exercises the binding the way
 * a payload does: `HostTree` decodes, `DogwoodTree` composes, the assertions read the rendered
 * screen. Tags are literals because tags are permanent -- `androidx.material3.lock.json` is what
 * makes that true, and a tag that moved would fail the lock long before it failed this.
 *
 * Expression bodies throughout: on Kotlin/WebAssembly `runComposeUiTest` returns a promise, and a
 * test that drops it composes nothing while passing (`tools/render-shape/check.py`).
 */
package dev.dogwood.material3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

// The local tags, from `androidx.material3.lock.json`. Permanent by construction.
private const val ALERT_DIALOG = 1
private const val BASIC_ALERT_DIALOG = 2
private const val BOTTOM_APP_BAR = 7
private const val BADGED_BOX = 9
private const val BADGE = 10
private const val BUTTON = 11
private const val ELEVATED_BUTTON = 12
private const val FILLED_TONAL_BUTTON = 13
private const val OUTLINED_BUTTON = 14
private const val TEXT_BUTTON = 15
private const val CARD = 16
private const val CLICKABLE_CARD = 17
private const val CHECKBOX = 22
private const val FILTER_CHIP = 25
private const val INPUT_CHIP = 27
private const val DATE_PICKER_DIALOG = 30
private const val HORIZONTAL_DIVIDER = 31
private const val EXPOSED_DROPDOWN_MENU_BOX = 34
private const val FAB = 35
private const val EXTENDED_FAB = 38
private const val FILLED_ICON_BUTTON = 40
private const val FILLED_ICON_TOGGLE_BUTTON = 41
private const val LABEL = 46
private const val LIST_ITEM = 47
private const val DROPDOWN_MENU_ITEM = 48
private const val MODAL_BOTTOM_SHEET = 49
private const val MODAL_NAVIGATION_DRAWER = 50
private const val MODAL_DRAWER_SHEET = 53
private const val NAVIGATION_RAIL = 56
private const val NAVIGATION_RAIL_ITEM = 57
private const val RADIO_BUTTON = 60
private const val SHORT_NAVIGATION_BAR = 63
private const val SHORT_NAVIGATION_BAR_ITEM = 64
private const val SLIDER = 65
private const val SNACKBAR = 67
private const val SWITCH = 68
private const val TAB = 69
private const val CONTENT_TAB = 71
private const val PRIMARY_TAB_ROW = 72
private const val M3_TEXT = 76
private const val TIME_PICKER_DIALOG = 77
private const val TIME_PICKER = 90
private const val TRI_STATE_CHECKBOX = 92
private const val TOP_SEARCH_BAR = 93
private const val STATE_SLIDER = 94
private const val STATE_RANGE_SLIDER = 95
private const val SNACKBAR_HOST = 96
private const val SWIPE_TO_DISMISS_BOX = 97

/**
 * How long a holder's report may take to arrive.
 *
 * Compose's default is one second, which is a development machine's second. Every wait here is on
 * an outcome that takes several frames -- a drawer animating open, a snackbar reaching the front of
 * a queue, a search bar finishing an expansion -- and a two-processor continuous-integration runner
 * under load does not have the same second. Generous rather than tuned: a wait that ends when the
 * outcome arrives costs nothing extra by being allowed to wait longer, and a test that fails for
 * want of a second teaches a team to rerun the build rather than to read it.
 */
private const val HOLDER_WAIT = 10_000L
private const val LINEAR_PROGRESS = 58
private const val CIRCULAR_PROGRESS = 59

/**
 * A change batch, written the way a payload writes one.
 *
 * `[0,id,widget]` creates, `[1,id,tag,value]` sets a property, `[2,id,[[tag,value]]]` sets
 * modifiers and `[3,parent,slot,child,index]` inserts into a slot. Root is id 0.
 */
private class Wire {
  private val ops = mutableListOf<String>()
  private var last = 0

  fun create(local: Int): Int {
    val id = ++last
    ops += "[0,$id,${widgetTag(Segments.MATERIAL3, local).value}]"
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
class Material3FamiliesTest {

  init {
    if (DogwoodRegistry.bindings.none { it.segmentName == Material3Binding.segmentName }) {
      DogwoodRegistry.register(Material3Binding)
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
  // Buttons
  // -----------------------------------------------------------------------------------------

  /**
   * All five button shapes, each with its own slot and its own event tag.
   *
   * One test rather than five because what could go wrong is shared: the generator emits these
   * from the same signature shape, so either the shape is handled or none of them is. What the
   * test would catch is a binding that routed every button's `onClick` to the first one's tag,
   * which is the kind of mistake a generator makes and a compiler does not see.
   */
  @Test
  fun everyButtonShapeComposesItsLabelAndSendsItsOwnClick() = run {
    val wire = Wire()
    val shapes = listOf(
      BUTTON to "filled",
      ELEVATED_BUTTON to "elevated",
      FILLED_TONAL_BUTTON to "tonal",
      OUTLINED_BUTTON to "outlined",
      TEXT_BUTTON to "text",
    )
    val ids = shapes.mapIndexed { index, (local, label) ->
      val id = wire.create(local)
      wire.tagged(id, label)
      wire.text(id, 1, label)
      wire.insert(0, 1, id, index)
      id to label
    }
    val pressed = mutableListOf<String>()
    val byId = ids.toMap()
    val tree = wire.build()
    rendered(tree, EventSink { node, tag, _ -> pressed += "${byId[node.id.value]}:${tag.value}" }) {
      for ((_, label) in ids) {
        onNodeWithText(label).assertIsDisplayed()
        onNodeWithTag(label).performClick()
      }
      assertEquals(
        listOf("filled:1", "elevated:1", "tonal:1", "outlined:1", "text:1"),
        pressed,
        "each button must send its own node's click on its own event tag",
      )
    }
  }

  @Test
  fun anIconButtonAndAnIconToggleButtonBothReportThroughTheirOwnEvent() = run {
    val wire = Wire()
    val plain = wire.create(FILLED_ICON_BUTTON)
    wire.tagged(plain, "plain")
    wire.text(plain, 1, "P")
    wire.insert(0, 1, plain, 0)

    val toggle = wire.create(FILLED_ICON_TOGGLE_BUTTON)
    wire.property(toggle, 1, "false")
    wire.tagged(toggle, "toggle")
    wire.text(toggle, 1, "T")
    wire.insert(0, 1, toggle, 1)

    val seen = mutableListOf<String>()
    rendered(
      wire.build(),
      EventSink { _, tag, args ->
        seen += "${tag.value}:${args.firstOrNull()?.jsonPrimitive?.booleanOrNull}"
      },
    ) {
      onNodeWithTag("plain").performClick()
      onNodeWithTag("toggle").performClick()
      // A plain click carries nothing; a toggle carries the state it is moving to. A binding that
      // sent the toggle's event with no argument would leave the payload unable to tell which way.
      assertEquals(listOf("1:null", "1:true"), seen)
    }
  }

  @Test
  fun bothFloatingActionButtonsComposeTheirSlotsAndClick() = run {
    val wire = Wire()
    val fab = wire.create(FAB)
    wire.tagged(fab, "fab")
    wire.text(fab, 1, "go")
    wire.insert(0, 1, fab, 0)
    val extended = wire.create(EXTENDED_FAB)
    wire.tagged(extended, "extended")
    wire.text(extended, 1, "go further")
    wire.insert(0, 1, extended, 1)
    var clicks = 0
    rendered(wire.build(), EventSink { _, _, _ -> clicks++ }) {
      onNodeWithText("go").assertIsDisplayed()
      onNodeWithText("go further").assertIsDisplayed()
      onNodeWithTag("fab").performClick()
      onNodeWithTag("extended").performClick()
      assertEquals(2, clicks)
    }
  }

  // -----------------------------------------------------------------------------------------
  // Selection
  // -----------------------------------------------------------------------------------------

  @Test
  fun aCheckboxAndARadioButtonCarryTheirStateAndReportAChange() = run {
    val wire = Wire()
    val box = wire.create(CHECKBOX)
    wire.property(box, 1, "false")
    // The presence flag beside the event. The host cannot see a guest closure, so absence of a
    // handler is a *property*: a checkbox with no handler is a different control from one with.
    wire.property(box, 2, "true")
    wire.tagged(box, "box")
    wire.insert(0, 1, box, 0)

    val radio = wire.create(RADIO_BUTTON)
    wire.property(radio, 1, "false")
    wire.property(radio, 2, "true")
    wire.tagged(radio, "radio")
    wire.insert(0, 1, radio, 1)

    val seen = mutableListOf<String>()
    rendered(
      wire.build(),
      EventSink { _, tag, args ->
        seen += "${tag.value}:${args.firstOrNull()?.jsonPrimitive?.booleanOrNull}"
      },
    ) {
      onNodeWithTag("box").performClick()
      onNodeWithTag("radio").performClick()
      assertEquals(listOf("1:true", "1:null"), seen, "the checkbox carries its new state; the radio carries nothing")
    }
  }

  /**
   * The slider, whose two overloads were one ambiguity until ADR-073.
   *
   * What this pins is the argument: `onValueChange` carries a `Float`, and a binding that dropped
   * it or rounded it to an `Int` would leave a payload unable to read a slider at all.
   */
  @Test
  fun aSliderReportsItsNewValueAsANumber() = run {
    val wire = Wire()
    val slider = wire.create(SLIDER)
    wire.property(slider, 1, "0.0")
    wire.property(slider, 3, "0")
    wire.tagged(slider, "slider")
    wire.insert(0, 1, slider, 0)
    val values = mutableListOf<Float?>()
    rendered(wire.build(), EventSink { _, _, args -> values += args.firstOrNull()?.jsonPrimitive?.floatOrNull }) {
      onNodeWithTag("slider").performClick()
      assertTrue(values.isNotEmpty(), "a click on the track must report a value")
      assertTrue(values.all { it != null }, "the value crossed as something other than a number: $values")
    }
  }

  @Test
  fun aChipCarriesItsSelectionAndComposesThreeSlotsInTheRightOrder() = run {
    val wire = Wire()
    val chip = wire.create(FILTER_CHIP)
    wire.property(chip, 1, "true")
    wire.tagged(chip, "chip")
    wire.text(chip, 1, "label text")
    wire.text(chip, 2, "leading")
    wire.text(chip, 3, "trailing")
    wire.insert(0, 1, chip, 0)

    val input = wire.create(INPUT_CHIP)
    wire.property(input, 1, "false")
    wire.text(input, 1, "input label")
    wire.text(input, 3, "avatar")
    wire.insert(0, 1, input, 1)

    var clicks = 0
    rendered(wire.build(), EventSink { _, _, _ -> clicks++ }) {
      // Three slots, three children, each in its own place -- a binding that composed them all
      // into `label` would still display all three, so each is asserted by its own text.
      onNodeWithText("label text").assertIsDisplayed()
      onNodeWithText("leading").assertIsDisplayed()
      onNodeWithText("trailing").assertIsDisplayed()
      onNodeWithText("input label").assertIsDisplayed()
      onNodeWithText("avatar").assertIsDisplayed()
      onNodeWithTag("chip").performClick()
      assertEquals(1, clicks)
    }
  }

  /**
   * The affordance rule on a selection control, which is the case that matters most.
   *
   * `selected` governs what the control claims to be, so a client that cannot read everything the
   * payload sent must not draw a chip that looks unselected when the payload said otherwise.
   */
  @Test
  fun aChipCarryingAnUnreadablePropertyIsWithheld() = run {
    val wire = Wire()
    val chip = wire.create(FILTER_CHIP)
    wire.property(chip, 1, "true")
    wire.property(chip, 99, "true")
    wire.text(chip, 1, "hidden")
    wire.insert(0, 1, chip, 0)
    val tree = wire.build()
    rendered(tree) {
      assertTrue(
        widgetTag(Segments.MATERIAL3, FILTER_CHIP).value in tree.skew.withheldWidgets,
        "a selection control with an unreadable property was drawn anyway",
      )
    }
  }

  // -----------------------------------------------------------------------------------------
  // Cards, lists and decorations
  // -----------------------------------------------------------------------------------------

  @Test
  fun bothCardOverloadsExistAndOnlyTheClickableOneReports() = run {
    val wire = Wire()
    val plain = wire.create(CARD)
    wire.text(plain, 1, "plain card")
    wire.insert(0, 1, plain, 0)
    val clickable = wire.create(CLICKABLE_CARD)
    wire.tagged(clickable, "clickable")
    wire.text(clickable, 1, "clickable card")
    wire.insert(0, 1, clickable, 1)
    var clicks = 0
    rendered(wire.build(), EventSink { _, _, _ -> clicks++ }) {
      onNodeWithText("plain card").assertIsDisplayed()
      onNodeWithText("clickable card").assertIsDisplayed()
      onNodeWithTag("clickable").performClick()
      assertEquals(1, clicks, "the clickable overload is a different component with its own tag")
    }
  }

  /**
   * Five slots, five children, five places.
   *
   * `ListItem` is the widest slot list in the tier, and a generated binding that shifted the slot
   * numbering by one would put the trailing content where the leading content belongs -- which
   * renders, looks plausible in a screenshot, and is wrong.
   */
  @Test
  fun aListItemComposesEveryOneOfItsFiveSlots() = run {
    val wire = Wire()
    val item = wire.create(LIST_ITEM)
    wire.text(item, 1, "headline")
    wire.text(item, 2, "overline")
    wire.text(item, 3, "supporting")
    wire.text(item, 4, "leading")
    wire.text(item, 5, "trailing")
    wire.insert(0, 1, item, 0)
    rendered(wire.build()) {
      for (slot in listOf("headline", "overline", "supporting", "leading", "trailing")) {
        onNodeWithText(slot).assertIsDisplayed()
      }
    }
  }

  @Test
  fun aBadgeInsideABadgedBoxAndALabelBothNest() = run {
    val wire = Wire()
    val box = wire.create(BADGED_BOX)
    val badge = wire.create(BADGE)
    wire.text(badge, 1, "9")
    wire.insert(box, 1, badge, 0)
    wire.text(box, 2, "inbox")
    wire.insert(0, 1, box, 0)

    val label = wire.create(LABEL)
    wire.text(label, 1, "the label")
    wire.text(label, 2, "the content")
    wire.insert(0, 1, label, 1)

    rendered(wire.build()) {
      onNodeWithText("9").assertIsDisplayed()
      onNodeWithText("inbox").assertIsDisplayed()
      onNodeWithText("the content").assertIsDisplayed()
    }
  }

  @Test
  fun dividersAndProgressIndicatorsRenderOnTheLibrarysDefaults() = run {
    val wire = Wire()
    for ((index, local) in listOf(HORIZONTAL_DIVIDER, LINEAR_PROGRESS, CIRCULAR_PROGRESS).withIndex()) {
      val id = wire.create(local)
      wire.tagged(id, "n$index")
      wire.insert(0, 1, id, index)
    }
    rendered(wire.build()) {
      // Nothing optional was sent, so everything about these came from the library's own default
      // expressions, evaluated in the host. Displayed is the whole claim.
      for (index in 0..2) onNodeWithTag("n$index").assertIsDisplayed()
    }
  }

  @Test
  fun material3TextReadsTheClosedSetsByName() = run {
    val wire = Wire()
    val id = wire.create(M3_TEXT)
    wire.property(id, 1, "\"styled words\"")
    wire.property(id, 4, "\"bold\"")
    wire.property(id, 7, "\"center\"")
    wire.property(id, 11, "2")
    wire.insert(0, 1, id, 0)
    val tree = wire.build()
    rendered(tree) {
      onNodeWithText("styled words").assertIsDisplayed()
      // A name this client knows is not skew. The point of the assertion is the negative: a
      // reader that failed to match would have reported `UNKNOWN_NAME` and rendered the default.
      assertTrue(tree.skew.unknownNames.isEmpty(), "known names were reported as skew: ${tree.skew.unknownNames}")
    }
  }

  @Test
  fun aNameThisClientDoesNotKnowIsReportedRatherThanGuessed() = run {
    val wire = Wire()
    val id = wire.create(M3_TEXT)
    wire.property(id, 1, "\"future words\"")
    wire.property(id, 4, "\"ultrablack\"")
    wire.insert(0, 1, id, 0)
    val tree = wire.build()
    rendered(tree) {
      onNodeWithText("future words").assertIsDisplayed()
      assertTrue(
        tree.skew.unknownNames.isNotEmpty(),
        "a font weight from a newer dictionary was accepted silently",
      )
    }
  }

  // -----------------------------------------------------------------------------------------
  // Bars, rails and tabs
  // -----------------------------------------------------------------------------------------

  @Test
  fun aBottomAppBarComposesItsActionsAndItsFloatingActionButton() = run {
    val wire = Wire()
    val bar = wire.create(BOTTOM_APP_BAR)
    wire.text(bar, 1, "bar action")
    val fab = wire.create(FAB)
    wire.text(fab, 1, "bar fab")
    wire.insert(bar, 2, fab, 0)
    wire.insert(0, 1, bar, 0)
    rendered(wire.build()) {
      onNodeWithText("bar action").assertIsDisplayed()
      onNodeWithText("bar fab").assertIsDisplayed()
    }
  }

  @Test
  fun navigationItemsCarrySelectionAndReportTheirOwnClick() = run {
    val wire = Wire()
    val bar = wire.create(SHORT_NAVIGATION_BAR)
    val first = wire.create(SHORT_NAVIGATION_BAR_ITEM)
    wire.property(first, 1, "true")
    wire.tagged(first, "first")
    wire.text(first, 1, "A")
    wire.text(first, 2, "Alpha")
    wire.insert(bar, 1, first, 0)
    val second = wire.create(SHORT_NAVIGATION_BAR_ITEM)
    wire.property(second, 1, "false")
    wire.tagged(second, "second")
    wire.text(second, 1, "B")
    wire.text(second, 2, "Beta")
    wire.insert(bar, 1, second, 1)
    wire.insert(0, 1, bar, 0)

    val clicked = mutableListOf<Int>()
    rendered(wire.build(), EventSink { node, _, _ -> clicked += node.id.value }) {
      onNodeWithText("Alpha").assertIsDisplayed()
      onNodeWithTag("second").performClick()
      assertEquals(listOf(second), clicked, "the second item's click must carry the second item's node")
    }
  }

  @Test
  fun aNavigationRailComposesItsHeaderAboveItsItems() = run {
    val wire = Wire()
    val rail = wire.create(NAVIGATION_RAIL)
    wire.text(rail, 1, "rail header")
    val item = wire.create(NAVIGATION_RAIL_ITEM)
    wire.property(item, 1, "true")
    wire.text(item, 1, "R")
    wire.text(item, 2, "Rail item")
    wire.insert(rail, 2, item, 0)
    wire.insert(0, 1, rail, 0)
    rendered(wire.build()) {
      onNodeWithText("rail header").assertIsDisplayed()
      onNodeWithText("Rail item").assertIsDisplayed()
    }
  }

  /**
   * A tab row, its tabs, and the content overload.
   *
   * `PrimaryTabRow` takes its tabs in a slot and its selection as a property, so a row whose
   * `selectedTabIndex` did not reach the library would render every tab unselected and still look
   * like a tab row. The two `Tab` overloads are separate components, and the content one composes
   * a whole subtree where the other takes text and icon.
   */
  @Test
  fun aTabRowSelectsAndBothTabOverloadsCompose() = run {
    val wire = Wire()
    val row = wire.create(PRIMARY_TAB_ROW)
    wire.property(row, 1, "1")
    val first = wire.create(TAB)
    wire.property(first, 1, "false")
    wire.tagged(first, "tab0")
    wire.text(first, 1, "First")
    wire.insert(row, 3, first, 0)
    val second = wire.create(CONTENT_TAB)
    wire.property(second, 1, "true")
    wire.tagged(second, "tab1")
    wire.text(second, 1, "Second, composed")
    wire.insert(row, 3, second, 1)
    wire.insert(0, 1, row, 0)

    var clicks = 0
    rendered(wire.build(), EventSink { _, _, _ -> clicks++ }) {
      onNodeWithText("First").assertIsDisplayed()
      onNodeWithText("Second, composed").assertIsDisplayed()
      onNodeWithTag("tab0").performClick()
      assertEquals(1, clicks)
    }
  }

  // -----------------------------------------------------------------------------------------
  // Dialogs, sheets, drawers and menus
  // -----------------------------------------------------------------------------------------

  /**
   * The dialog family, which is the one place a generated binding composes into a *window* rather
   * than into the tree it was inserted in.
   *
   * Five slots, and the confirm button has to be reachable and clickable: a dialog whose confirm
   * button composed but did not route its click is a dialog a user cannot leave.
   */
  @Test
  fun anAlertDialogComposesEverySlotAndItsConfirmButtonReports() = run {
    val wire = Wire()
    val dialog = wire.create(ALERT_DIALOG)
    val confirm = wire.create(BUTTON)
    wire.tagged(confirm, "confirm")
    wire.text(confirm, 1, "Confirm it")
    wire.insert(dialog, 1, confirm, 0)
    val dismiss = wire.create(TEXT_BUTTON)
    wire.text(dismiss, 1, "Keep it")
    wire.insert(dialog, 2, dismiss, 0)
    wire.text(dialog, 4, "A question")
    wire.text(dialog, 5, "Some detail")
    wire.insert(0, 1, dialog, 0)

    var confirmed = 0
    rendered(wire.build(), EventSink { node, _, _ -> if (node.id.value == confirm) confirmed++ }) {
      onNodeWithText("A question").assertIsDisplayed()
      onNodeWithText("Some detail").assertIsDisplayed()
      onNodeWithText("Keep it").assertIsDisplayed()
      onNodeWithTag("confirm").performClick()
      assertEquals(1, confirmed)
    }
  }

  @Test
  fun theOtherThreeDialogsComposeTheirOwnSlots() = run {
    val wire = Wire()
    val basic = wire.create(BASIC_ALERT_DIALOG)
    wire.text(basic, 1, "basic body")
    wire.insert(0, 1, basic, 0)
    rendered(wire.build()) { onNodeWithText("basic body").assertIsDisplayed() }

    val second = Wire()
    val date = second.create(DATE_PICKER_DIALOG)
    val dateConfirm = second.create(BUTTON)
    second.text(dateConfirm, 1, "Pick the date")
    second.insert(date, 1, dateConfirm, 0)
    second.text(date, 3, "date body")
    second.insert(0, 1, date, 0)
    rendered(second.build()) {
      onNodeWithText("Pick the date").assertIsDisplayed()
      onNodeWithText("date body").assertIsDisplayed()
    }

    val third = Wire()
    val time = third.create(TIME_PICKER_DIALOG)
    val timeConfirm = third.create(BUTTON)
    third.text(timeConfirm, 1, "Pick the time")
    third.insert(time, 1, timeConfirm, 0)
    third.text(time, 2, "time title")
    third.text(time, 5, "time body")
    third.insert(0, 1, time, 0)
    rendered(third.build()) {
      onNodeWithText("time title").assertIsDisplayed()
      onNodeWithText("Pick the time").assertIsDisplayed()
    }
  }

  @Test
  fun aModalBottomSheetComposesItsContentAndItsDragHandle() = run {
    val wire = Wire()
    val sheet = wire.create(MODAL_BOTTOM_SHEET)
    wire.text(sheet, 1, "handle stand-in")
    wire.text(sheet, 2, "sheet body")
    wire.insert(0, 1, sheet, 0)
    rendered(wire.build()) {
      onNodeWithText("sheet body").assertIsDisplayed()
    }
  }

  @Test
  fun aDrawerComposesItsSheetAndTheContentBesideIt() = run {
    val wire = Wire()
    val drawer = wire.create(MODAL_NAVIGATION_DRAWER)
    val sheet = wire.create(MODAL_DRAWER_SHEET)
    wire.text(sheet, 1, "drawer sheet")
    wire.insert(drawer, 1, sheet, 0)
    wire.text(drawer, 2, "behind the drawer")
    wire.insert(0, 1, drawer, 0)
    rendered(wire.build()) {
      // The content is what a user sees with the drawer closed, which is the state a payload that
      // sends no gesture starts in.
      onNodeWithText("behind the drawer").assertIsDisplayed()
    }
  }

  @Test
  fun aMenuBoxCarriesItsExpandedStateAndItsItemsReport() = run {
    val wire = Wire()
    val box = wire.create(EXPOSED_DROPDOWN_MENU_BOX)
    wire.property(box, 1, "true")
    val item = wire.create(DROPDOWN_MENU_ITEM)
    wire.tagged(item, "item")
    wire.text(item, 1, "Economy")
    wire.insert(box, 1, item, 0)
    wire.insert(0, 1, box, 0)
    var chosen = 0
    rendered(wire.build(), EventSink { _, _, _ -> chosen++ }) {
      onNodeWithText("Economy").assertIsDisplayed()
      onNodeWithTag("item").performClick()
      assertEquals(1, chosen)
    }
  }

  /**
   * A live-state holder, mirrored.
   *
   * The generated binding does not receive a `TimePickerState` — it *builds* one, from four
   * properties the guest wrote, and hands the library the real object (ADR-043, ADR-074). What this
   * pins is that the plumbing on the host side is wired to the right tags: a picker asked to show
   * 09:30 shows 09:30, which it can only do if `stateInitial` reached the mirror and the mirror
   * reached the library's state.
   *
   * The time crosses as `HH:MM` in twenty-four-hour clock whatever the dial displays, because a
   * client's locale must not be baked into the wire.
   */
  @Test
  fun aTimePickerIsBuiltFromTheGuestsMirroredState() = run {
    val wire = Wire()
    val picker = wire.create(TIME_PICKER)
    wire.property(picker, 1, "true")
    wire.property(picker, 2, "1")
    wire.property(picker, 3, "\"09:30\"")
    wire.property(picker, 4, "true")
    wire.tagged(picker, "picker")
    wire.insert(0, 1, picker, 0)
    val reported = mutableListOf<String>()
    rendered(
      wire.build(),
      EventSink { _, tag, args ->
        reported += "${tag.value}:" + args.joinToString(",") { it.jsonPrimitive.content }
      },
    ) {
      onNodeWithTag("picker").assertIsDisplayed()
      // The report carries the sequence it answers, so two requests in flight cannot be confused.
      assertTrue(
        reported.any { it.startsWith("1:1,09:30") },
        "the picker never reported the time it was asked for: $reported",
      )
    }
  }

  @Test
  fun aSnackbarComposesItsMessageItsActionAndItsDismissAction() = run {
    val wire = Wire()
    val bar = wire.create(SNACKBAR)
    val action = wire.create(TEXT_BUTTON)
    wire.tagged(action, "undo")
    wire.text(action, 1, "Undo")
    wire.insert(bar, 1, action, 0)
    val dismiss = wire.create(TEXT_BUTTON)
    wire.text(dismiss, 1, "Dismiss")
    wire.insert(bar, 2, dismiss, 0)
    wire.text(bar, 3, "Seat released")
    wire.insert(0, 1, bar, 0)
    var undone = 0
    rendered(wire.build(), EventSink { node, _, _ -> if (node.id.value == action) undone++ }) {
      onNodeWithText("Seat released").assertIsDisplayed()
      onNodeWithText("Dismiss").assertIsDisplayed()
      onNodeWithTag("undo").performClick()
      assertEquals(1, undone)
    }
  }

  // -----------------------------------------------------------------------------------------
  // Live-state holders
  //
  // A holder is not a property: the payload writes a target and a sequence, the host owns the real
  // object, and what comes back is an event (ADR-043). What a compile cannot see is whether the
  // two halves of that agree -- a mirror whose effect reads a field the stub never writes renders a
  // perfectly good widget that does nothing, which is the failure the whole shape table exists to
  // make loud. Each test below drives one holder across the wire and reads its report back.
  // -----------------------------------------------------------------------------------------------

  /**
   * The three answers a tri-state control can give, and a fourth a client has never heard of.
   *
   * `ToggleableState` is crossed as a **value**, not as a holder -- `TriStateCheckbox` takes it the
   * way `Checkbox` takes `checked`, and only the type's `State` suffix ever made it look otherwise.
   * So what this asks is a value question: does each name reach the control as the right one of
   * three, and does a name this client does not carry degrade to the empty box rather than to
   * whichever entry happens to sit at that index?
   */
  @Test
  fun aTriStateCheckboxDrawsEachAnswerAndDegradesOneItCannotRead() = run {
    val wire = Wire()
    val asked = listOf(
      "on" to ToggleableState.On,
      "off" to ToggleableState.Off,
      "indeterminate" to ToggleableState.Indeterminate,
      // A name from a dictionary version this client does not have. `Off` is the conservative
      // reading: a box showing less than the payload meant, never more.
      "mostly" to ToggleableState.Off,
    )
    asked.forEachIndexed { index, (name, _) ->
      val box = wire.create(TRI_STATE_CHECKBOX)
      wire.property(box, 1, """"$name"""")
      // The presence flag beside the event, as every optional callback carries.
      wire.property(box, 2, "true")
      wire.tagged(box, name)
      wire.insert(0, 1, box, index)
    }
    val clicked = mutableListOf<Int>()
    rendered(wire.build(), EventSink { _, tag, _ -> clicked += tag.value }) {
      for ((name, expected) in asked) {
        onNodeWithTag(name).assert(
          SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, expected),
          messagePrefixOnError = { "the checkbox asked for '$name'" },
        )
      }
      onNodeWithTag("indeterminate").performClick()
      assertEquals(listOf(1), clicked, "a tri-state checkbox sends its click on its own event tag")
    }
  }

  /**
   * A snackbar asked for over the wire, and the answer the guest is waiting on.
   *
   * The first report in this tier that is a **reply** rather than an observation: the guest's
   * `showSnackbar` is suspended, and what comes back decides whether a row is restored. It carries
   * the sequence it answers, so two requests in flight cannot be confused -- asserted here with a
   * sequence that is deliberately not 1.
   */
  @Test
  fun aSnackbarHostShowsTheGuestsMessageAndAnswersWithWhatTheUserDid() = run {
    val wire = Wire()
    val host = wire.create(SNACKBAR_HOST)
    wire.property(host, 1, """"Seat released"""")
    wire.property(host, 2, """"Undo"""")
    wire.property(host, 3, "7")
    wire.property(host, 4, "true")
    wire.insert(0, 1, host, 0)
    val answers = mutableListOf<String>()
    rendered(
      wire.build(),
      EventSink { _, tag, args ->
        answers += "${tag.value}:" + args.joinToString(",") { it.jsonPrimitive.content }
      },
    ) {
      waitUntil("the snackbar never appeared", HOLDER_WAIT) {
        onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty()
      }
      onNodeWithText("Seat released").assertIsDisplayed()
      onNodeWithText("Undo").performClick()
      waitUntil("the guest was never answered", HOLDER_WAIT) { answers.isNotEmpty() }
      assertEquals(listOf("1:7,true"), answers, "the reply carries the sequence it answers")
    }
  }

  /**
   * A row swept away by the payload rather than by a finger, and the direction reported back.
   *
   * The direction is the whole reason this holder reports a name: a row swiped one way and a row
   * swiped the other mean different things in every inbox ever built, and `byUser` is false here
   * because this dismissal was the guest's own request landing -- which is exactly the distinction
   * a guest cannot make from "the row is gone".
   */
  @Test
  fun aSwipeToDismissBoxActsOnTheGuestsTargetAndReportsTheDirection() = run {
    val wire = Wire()
    val box = wire.create(SWIPE_TO_DISMISS_BOX)
    wire.property(box, 1, """"startToEnd"""")
    wire.property(box, 2, "1")
    wire.property(box, 3, "true")
    wire.text(box, 1, "background")
    wire.text(box, 2, "row")
    wire.insert(0, 1, box, 0)
    val reported = mutableListOf<String>()
    rendered(
      wire.build(),
      EventSink { _, tag, args ->
        reported += "${tag.value}:" + args.joinToString(",") { it.jsonPrimitive.content }
      },
    ) {
      onNodeWithText("row").assertIsDisplayed()
      waitUntil("the row never went anywhere", HOLDER_WAIT) { reported.isNotEmpty() }
      assertEquals(
        listOf("1:startToEnd,false"),
        reported,
        "the guest's own dismissal must not come back as the user's",
      )
    }
  }

  /**
   * A navigation drawer opened by the payload, and where it lands reported back.
   *
   * This one is worth having for a reason the bound count does not show: `DrawerState` binds no new
   * component at all -- the two drawer-sheet overloads that take one have guest signatures identical
   * to the stateless ones already bound, so the overload dedupe drops them. What it does is turn
   * this drawer's `drawerState`, already on the widget but frozen at the library's default, into
   * something a payload can drive. Nothing but a render test says whether that worked.
   */
  @Test
  fun aNavigationDrawerOpensOnTheGuestsTargetAndReportsWhereItLands() = run {
    val wire = Wire()
    val drawer = wire.create(MODAL_NAVIGATION_DRAWER)
    wire.property(drawer, 3, """"open"""")
    wire.property(drawer, 4, "1")
    wire.property(drawer, 5, "true")
    wire.text(drawer, 1, "menu")
    wire.text(drawer, 2, "page")
    wire.insert(0, 1, drawer, 0)
    val reported = mutableListOf<String>()
    rendered(
      wire.build(),
      EventSink { _, tag, args ->
        reported += "${tag.value}:" + args.joinToString(",") { it.jsonPrimitive.content }
      },
    ) {
      onNodeWithText("page").assertIsDisplayed()
      waitUntil("the drawer never opened", HOLDER_WAIT) { reported.isNotEmpty() }
      assertEquals(
        listOf("1:open,false"),
        reported,
        "the drawer opened because the guest asked, which is not the user opening it",
      )
      onNodeWithText("menu").assertIsDisplayed()
    }
  }

  /**
   * A search bar expanded by the payload, and where it lands reported back.
   *
   * The holder carries whether the bar is open and deliberately nothing about what is typed in it:
   * a search field's text is a versioned round trip with its own protocol (ADR-019), and a second
   * copy riding on this holder would be a field that drops keystrokes under load.
   */
  @Test
  fun aSearchBarExpandsOnTheGuestsTargetAndReportsWhereItLands() = run {
    val wire = Wire()
    val bar = wire.create(TOP_SEARCH_BAR)
    wire.property(bar, 1, """"expanded"""")
    wire.property(bar, 2, "1")
    wire.property(bar, 3, "true")
    wire.text(bar, 1, "Search seats")
    wire.insert(0, 1, bar, 0)
    val reported = mutableListOf<String>()
    rendered(
      wire.build(),
      EventSink { _, tag, args ->
        reported += "${tag.value}:" + args.joinToString(",") { it.jsonPrimitive.content }
      },
    ) {
      onNodeWithText("Search seats").assertIsDisplayed()
      waitUntil("the search bar never expanded", HOLDER_WAIT) { reported.isNotEmpty() }
      assertEquals(listOf("1:expanded,false"), reported)
    }
  }

  /**
   * A slider that owns its own value, moved by the payload and reporting where the thumb ended up.
   *
   * Two things at once, and the second is the reason this test is worth more than the first. The
   * value round trip is ordinary. The `steps` beside it is **negative**, which is a number
   * `Slider(state)` answers with `require(state.steps >= 0)` -- an exception inside composition,
   * which takes the screen down on every client that received the payload at the same moment
   * (ADR-035). It is clamped, so the slider renders and the value still crosses.
   */
  @Test
  fun aStateDrivenSliderTakesTheGuestsValueAndSurvivesAHostileStepCount() = run {
    val wire = Wire()
    val slider = wire.create(STATE_SLIDER)
    wire.property(slider, 1, "0.75")
    wire.property(slider, 2, "1")
    wire.property(slider, 3, "true")
    // Negative, on purpose. See the note above.
    wire.property(slider, 4, "-4")
    wire.property(slider, 5, "0.0")
    wire.property(slider, 6, "1.0")
    wire.tagged(slider, "slider")
    wire.insert(0, 1, slider, 0)
    val reported = mutableListOf<String>()
    rendered(
      wire.build(),
      EventSink { _, tag, args ->
        reported += "${tag.value}:" + args.joinToString(",") { it.jsonPrimitive.content }
      },
    ) {
      onNodeWithTag("slider").assertIsDisplayed()
      waitUntil("the thumb never moved to the guest's value", HOLDER_WAIT) { reported.isNotEmpty() }
      assertEquals(listOf("1:0.75,false"), reported)
    }
  }

  /**
   * Both thumbs of a range slider, taken from the payload and reported back together.
   *
   * One request and one report, because a selection is one thing the user sees -- and the report
   * carries both numbers rather than one per thumb, so a guest never holds half a selection.
   */
  @Test
  fun aRangeSliderTakesBothThumbsFromTheGuestAndReportsThem() = run {
    val wire = Wire()
    val slider = wire.create(STATE_RANGE_SLIDER)
    wire.property(slider, 1, "0.25")
    wire.property(slider, 2, "0.75")
    wire.property(slider, 3, "1")
    wire.property(slider, 4, "true")
    wire.property(slider, 5, "0")
    wire.property(slider, 6, "0.0")
    wire.property(slider, 7, "1.0")
    wire.tagged(slider, "range")
    wire.insert(0, 1, slider, 0)
    val reported = mutableListOf<String>()
    rendered(
      wire.build(),
      EventSink { _, tag, args ->
        reported += "${tag.value}:" + args.joinToString(",") { it.jsonPrimitive.content }
      },
    ) {
      onNodeWithTag("range").assertIsDisplayed()
      waitUntil("the thumbs never moved to the guest's selection", HOLDER_WAIT) {
        reported.isNotEmpty()
      }
      assertEquals(listOf("1:0.25,0.75,false"), reported)
    }
  }
}

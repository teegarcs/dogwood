/*
 * Project Dogwood -- the one kind of skew that must not degrade quietly.
 *
 * Section 6 of the technical specification: parameters whose absence changes safety or affordance
 * -- `enabled`, `checked`, `readOnly`, `selected` -- must be marked in the dictionary so that a
 * widget carrying an unknown one is **replaced by a declared fallback rather than rendered wrong**.
 *
 * The asymmetry is the whole point, and it is worth stating plainly because every other rule in
 * this system pulls the other way. Everywhere else, a client that meets something it does not
 * understand keeps drawing: an unknown text style becomes body text, an unknown icon becomes the
 * fallback glyph, an unknown colour token becomes unspecified. That is right, because the cost is
 * appearance. Here the cost is a control that lies about what it will do -- a button a payload
 * disabled, drawn enabled, and tapped.
 *
 * The client cannot tell the two cases apart on the wire. A property tag it does not know comes
 * from a dictionary it has never seen, so it cannot ask "was that the one that disables this?"
 * The decision therefore lives at the widget: a widget that owns an affordance refuses to draw
 * when anything on it is unreadable, and a widget that owns none ignores the same skew.
 *
 * Requirement stated in 2026 and unimplemented until now: `safetyRelevant` appeared in the
 * specification as a required dictionary field and existed nowhere in the code, while the
 * generated binding did the opposite of what the rule asks -- `enabled = node.boolean(2, true)`
 * defaulted an absent affordance to *enabled*.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.dogwood.protocol.Segments
import dev.dogwood.protocol.widgetTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun batch(sequence: Int, vararg changes: String): String =
  "[$sequence,[${changes.joinToString(",")}]]"

private val BUTTON = widgetTag(Segments.DESIGN_SYSTEM, 1).value
private val BADGE = widgetTag(Segments.DESIGN_SYSTEM, 4).value

/** A property tag no dictionary this client carries has ever defined. */
private const val FROM_THE_FUTURE = 97

class AffordanceTest {

  /** One button, labelled, optionally carrying a property from a newer dictionary. */
  private fun buttonTree(withUnknownProperty: Boolean): HostTree = HostTree().also {
    val extra = if (withUnknownProperty) ",[1,1,$FROM_THE_FUTURE,false]" else ""
    it.apply(
      decodePositional(batch(1, "[0,1,$BUTTON]", "[1,1,1,\"Pay\"]$extra", "[3,0,1,1,0]")),
    )
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aButtonWithOnlyKnownPropertiesRenders() {
    // The control: without this, a test asserting the withheld case proves only that the fixture
    // never rendered anything.
    val tree = buttonTree(withUnknownProperty = false)
    runComposeUiTest {
      setContent { DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew) }
      onNodeWithText("Pay").assertIsDisplayed()
    }
    assertTrue(tree.skew.isEmpty, "nothing about this is skew")
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aButtonCarryingAnUnreadablePropertyIsWithheldRatherThanDrawn() {
    // The payload was built against a dictionary this client has never seen and is saying
    // something about this button that this client cannot read. One of the things it might be
    // saying is "this is disabled". Drawing it anyway offers the user an action the payload was
    // trying to withhold, so the control is not drawn at all.
    val tree = buttonTree(withUnknownProperty = true)
    runComposeUiTest {
      setContent { DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew) }
      assertEquals(
        0,
        onAllNodesWithText("Pay").fetchSemanticsNodes().size,
        "a control that cannot be read must not draw",
      )
    }
    assertEquals(setOf(BUTTON), tree.skew.withheldWidgets)
    assertFalse(tree.skew.isEmpty)
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun tappingWhereTheWithheldControlWouldHaveBeenSendsNothing() {
    // The assertion the requirement is actually about. "It is not displayed" can be satisfied by a
    // control drawn underneath something else and still tappable; what matters is that no event
    // reaches the guest, because the event is the thing that spends the user's money.
    //
    // So this taps a *position* rather than a node. Asking Compose for the button and finding it
    // absent would only prove the test could not find it -- and an earlier version of this test
    // did exactly that, clicking the surface's own semantics node, which dispatches to nothing and
    // passed just as happily with the guard removed.
    //
    // The position comes from the readable button, measured, so both halves tap the same pixel.
    val drawn = buttonTree(withUnknownProperty = false)
    val firedWhenDrawn = mutableListOf<Int>()
    var target = Offset.Zero
    runComposeUiTest {
      setContent {
        Box(Modifier.size(200.dp).testTag("surface")) {
          DogwoodTree(drawn, EventSink { _, tag, _ -> firedWhenDrawn += tag.value }, skew = drawn.skew)
        }
      }
      val bounds = onNodeWithText("Pay").fetchSemanticsNode().boundsInRoot
      target = bounds.center
      onNodeWithTag("surface").performTouchInput { click(target) }
    }
    // The negative control, and it is not optional: without it, the assertion below passes against
    // a harness that never delivered a touch at all.
    assertEquals(listOf(1), firedWhenDrawn, "the readable button must receive this exact tap")

    val withheld = buttonTree(withUnknownProperty = true)
    val fired = mutableListOf<Int>()
    runComposeUiTest {
      setContent {
        Box(Modifier.size(200.dp).testTag("surface")) {
          DogwoodTree(withheld, EventSink { _, tag, _ -> fired += tag.value }, skew = withheld.skew)
        }
      }
      onNodeWithTag("surface").performTouchInput { click(target) }
    }
    assertEquals(emptyList(), fired, "a withheld control must not be able to send an event")
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aWidgetThatOwnsNoAffordanceIgnoresTheSameSkew() {
    // The other half, and the reason the marking is per widget rather than blanket. A badge is
    // read, not operated. Withholding it over a property this client cannot read would turn
    // cosmetic skew into a hole in the screen -- a much larger regression than the skew itself.
    val tree = HostTree().also {
      it.apply(
        decodePositional(
          batch(1, "[0,1,$BADGE]", "[1,1,1,\"New\"]", "[1,1,$FROM_THE_FUTURE,true]", "[3,0,1,1,0]"),
        ),
      )
    }
    runComposeUiTest {
      setContent { DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew) }
      onNodeWithText("New").assertIsDisplayed()
    }
    assertTrue(tree.skew.withheldWidgets.isEmpty(), "a badge has no affordance to get wrong")
  }

  @Test
  fun theDictionaryRecordsWhichParametersGovernAffordance() {
    // The specification requires the marking to be *in the dictionary*, because the dictionary is
    // what a server compiles against. A rule enforced only in generated host code would leave the
    // build side unable to see it.
    val lock = java.io.File("../surface/dogwood.designsystem.lock.json")
    assertTrue(lock.exists(), "the lock is the published dictionary; looked in ${lock.absolutePath}")
    val components = Json.parseToJsonElement(lock.readText())
      .jsonObject.getValue("components").jsonArray
      .associate { entry ->
        val o = entry.jsonObject
        o.getValue("name").jsonPrimitive.content to
          (o["safetyRelevant"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet())
      }

    assertEquals(setOf("enabled"), components["PrimaryButton"])
    assertEquals(setOf("selected"), components["Chip"])
    assertEquals(setOf("enabled"), components["TextInput"])
    // And the deliberate omission, which is the half that keeps the rule proportionate: a badge
    // carries `selected` too, and is display-only, so withholding it would turn cosmetic skew into
    // a hole in the screen. The rule is what the parameter governs, not what it is called.
    assertEquals(emptySet(), components["Badge"])
  }
}

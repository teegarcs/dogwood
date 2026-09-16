/*
 * Project Dogwood -- the generated Material 3 tier, rendered.
 *
 * Nobody wrote these bindings; the generator read the library's sources and emitted them
 * (plans/generator-v2.md, M2). What these pin is therefore not "Switch works" -- Material 3 owns
 * that -- but that the generated glue around it does: an event on the derived tag reaches the
 * host's sink with its argument, a component the guest sent nothing optional for renders on the
 * library's own defaults, a slot's children are composed inside the component, and the affordance
 * guard withholds a control that arrived carrying a property this client cannot read.
 *
 * Every test is expression-bodied and returns the harness result, for the reason
 * `SkewReportingTest.rendered` in `dogwood-host` records: on Kotlin/WebAssembly a discarded result
 * is a test that passes without composing.
 */
package dev.dogwood.material3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import kotlinx.serialization.json.jsonPrimitive

private val SWITCH = widgetTag(Segments.MATERIAL3, 68).value
private val BUTTON = widgetTag(Segments.MATERIAL3, 11).value
private val CARD = widgetTag(Segments.MATERIAL3, 16).value
private val TEXT = DogwoodDictionary.Text.value

@OptIn(ExperimentalTestApi::class)
class Material3TierTest {

  init {
    // Once per process. The registry refuses a second registration, and the test runner keeps
    // one process across classes.
    if (DogwoodRegistry.bindings.none { it.segmentName == Material3Binding.segmentName }) {
      DogwoodRegistry.register(Material3Binding)
    }
  }

  private fun tree(vararg changes: String): HostTree = HostTree().also {
    it.apply(decodePositional("[1,[${changes.joinToString(",")}]]"))
  }

  private fun rendered(
    tree: HostTree,
    events: EventSink = EventSink { _, _, _ -> },
    assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
  ) = runComposeUiTest {
    setContent {
      Box(Modifier.size(300.dp)) { DogwoodTree(tree, events, skew = tree.skew) }
    }
    waitForIdle()
    assertions()
  }

  @Test
  fun aSwitchToggledThroughTheScreenSendsTheEventWithItsArgument() = run {
    val received = mutableListOf<String>()
    // checked=false on tag 1, a handler present on tag 2, and a test tag to find it by.
    val tree = tree(
      "[0,1,$SWITCH]", "[1,1,1,false]", "[1,1,2,true]",
      """[2,1,[[${ModifierTags.TEST_TAG},"sw"]]]""",
      "[3,0,1,1,0]",
    )
    val sink = EventSink { _, tag, args ->
      received.add("${tag.value}:${args.firstOrNull()?.jsonPrimitive?.booleanOrNull}")
    }
    rendered(tree, sink) {
      onNodeWithTag("sw").performClick()
      assertEquals(listOf("1:true"), received, "one event on the switch's tag, carrying the new state")
    }
  }

  @Test
  fun aButtonWithNothingOptionalRendersOnTheLibrarysOwnDefaults() = run {
    // No properties at all: shape, colours, elevation, padding are all the host's -- which is
    // the sentinel rule and the reason the host binding carries the library's default text.
    val tree = tree(
      "[0,1,$BUTTON]", "[3,0,1,1,0]",
      "[0,2,$TEXT]", """[1,2,1,"press"]""", "[3,1,1,2,0]",
    )
    rendered(tree) {
      onNodeWithText("press").assertIsDisplayed()
    }
  }

  @Test
  fun aCardComposesItsSlotChildrenInside() = run {
    val tree = tree(
      "[0,1,$CARD]", "[3,0,1,1,0]",
      "[0,2,$TEXT]", """[1,2,1,"inside"]""", "[3,1,1,2,0]",
    )
    rendered(tree) {
      onNodeWithText("inside").assertIsDisplayed()
    }
  }

  @Test
  fun aSwitchCarryingAPropertyThisClientCannotReadIsWithheld() = run {
    // Tag 99 is from a dictionary this client has never seen. `checked` and `enabled` are
    // affordances, so the rule is to draw nothing the user could act on, and to say so.
    val tree = tree(
      "[0,1,$SWITCH]", "[1,1,1,true]", "[1,1,99,true]",
      """[2,1,[[${ModifierTags.TEST_TAG},"sw"]]]""",
      "[3,0,1,1,0]",
    )
    rendered(tree) {
      assertTrue(SWITCH in tree.skew.withheldWidgets, "the switch was drawn despite an unreadable property")
    }
  }
}

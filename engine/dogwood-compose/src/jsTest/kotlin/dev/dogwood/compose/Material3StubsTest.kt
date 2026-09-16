/*
 * Project Dogwood -- two generated Material 3 stubs, on the wire.
 *
 * The stubs are generated from the library's own sources (plans/generator-v2.md), so what these
 * pin is the *shape* the generator gives every one of them: settable values on their dictionary
 * tags, an optional event's presence as a property beside it, absence sending nothing at all, and
 * a slot recorded under its children tag. If these hold for `Switch` and `Button` they hold for the
 * other seventy-eight, because the same emitter wrote them.
 */
package dev.dogwood.compose

import dev.dogwood.compose.material3.Button
import dev.dogwood.compose.material3.Switch
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.Segments
import dev.dogwood.protocol.WidgetTag
import dev.dogwood.protocol.widgetTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private val SWITCH = widgetTag(Segments.MATERIAL3, 68)
private val BUTTON = widgetTag(Segments.MATERIAL3, 11)

private fun RecordingHost.nodeOf(widget: WidgetTag): dev.dogwood.protocol.Id =
  decoded().flatMap { it.g }.filterIsInstance<Create>().first { it.w == widget }.i

private fun RecordingHost.properties(widget: WidgetTag): Map<Int, JsonElement> {
  val id = nodeOf(widget)
  return decoded().flatMap { it.g }.filterIsInstance<PropertySet>().filter { it.i == id }.associate { it.p.value to it.v }
}

class Material3StubsTest {

  @Test
  fun aSwitchCrossesItsValuesOnTheirTagsAndItsHandlerAsPresence() {
    val (host, _) = compose {
      Switch(checked = true, onCheckedChange = { }, enabled = false)
    }
    val properties = host.properties(SWITCH)
    assertEquals(true, properties[1]?.jsonPrimitive?.booleanOrNull, "checked is tag 1")
    // The handler never crosses; that it exists does, because `Switch(onCheckedChange = null)` is
    // a control the host must draw as not interactive.
    assertEquals(true, properties[2]?.jsonPrimitive?.booleanOrNull, "handler presence is tag 2")
    assertEquals(false, properties[3]?.jsonPrimitive?.booleanOrNull, "enabled is tag 3")
  }

  @Test
  fun anEventCarriesItsArgumentBackToTheHandler() {
    var received: Boolean? = null
    val (host, composition) = compose {
      Switch(checked = false, onCheckedChange = { received = it })
    }
    composition.sendEvent(Event(i = host.nodeOf(SWITCH), e = EventTag(1), q = composition.lastSentSequence, a = listOf(JsonPrimitive(true))))
    assertEquals(true, received)
  }

  @Test
  fun absenceSendsNothingAndAButtonRecordsItsSlot() {
    val (host, _) = compose {
      Button(onClick = { }) { Text("press") }
    }
    val properties = host.properties(BUTTON)
    // `enabled`, `shape` and `contentPadding` were not given: nothing crosses, and the host passes
    // the library's own defaults. A required event has no presence flag either.
    assertTrue(properties.isEmpty(), "a bare Button sent properties: $properties")
    // The slot is recorded: the Text is a child of the Button under children tag 1.
    val button = host.nodeOf(BUTTON)
    val attached = host.decoded().flatMap { it.g }.filterIsInstance<dev.dogwood.protocol.ChildAdd>()
      .any { it.i == button && it.s.value == 1 }
    assertTrue(attached, "the content slot was not recorded under the button")
  }
}

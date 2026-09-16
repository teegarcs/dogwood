/*
 * Project Dogwood -- the guest half of four small additions: a clickable with a role, a border
 * with a shape, per-side padding as animated targets, and a snackbar the guest can take back.
 *
 * What these pin is the wire, and one routing rule: a role crosses as its *name* on its own tag so
 * a host that predates roles keeps reading the old form; a shaped border carries three parts; the
 * four padding sides ride one element and exactly one of them asks for a completion; and dismissing
 * a snackbar bumps a counter the host reads while the caller resumes at once.
 */
package dev.dogwood.compose

import androidx.compose.runtime.rememberCoroutineScope
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.ModifierElem
import dev.dogwood.protocol.ModifierSet
import dev.dogwood.protocol.ModifierTags
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.WidgetTag
import dev.dogwood.protocol.widgetTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private fun RecordingHost.chain(): List<ModifierElem> =
  decoded().flatMap { it.g }.filterIsInstance<ModifierSet>().single().e

private fun RecordingHost.nodeOf(widget: WidgetTag) =
  decoded().flatMap { it.g }.filterIsInstance<Create>().first { it.w == widget }.i

private fun RecordingHost.propertiesOf(widget: WidgetTag): List<Pair<Int, kotlinx.serialization.json.JsonElement>> {
  val id = nodeOf(widget)
  return decoded().flatMap { it.g }.filterIsInstance<PropertySet>().filter { it.i == id }.map { it.p.value to it.v }
}

private val SNACKBAR_AREA = widgetTag(1, 16)

class RoleBorderPaddingDismissTest {

  @Test
  fun aClickableWithARoleCrossesOnItsOwnTagAndStillRoutesByIndex() {
    var taps = 0
    val (host, composition) = compose {
      Box(modifier = Modifier.padding(4).clickable(role = Role.Switch) { taps += 1 })
    }
    val chain = host.chain()
    assertEquals(ModifierTags.CLICKABLE_ROLE, chain[1].t.local)
    val parts = chain[1].v as JsonArray
    assertEquals(true, parts[0].jsonPrimitive.booleanOrNull)
    assertEquals("switch", parts[1].jsonPrimitive.content, "the role crosses as its name, not its ordinal")

    val node = host.nodeOf(Tags.Box)
    composition.sendEvent(Event(i = node, e = EventTag(ELEMENT_EVENT_BASE + 1), q = composition.lastSentSequence))
    assertEquals(1, taps, "the handler sits in the same per-element slot the roleless form uses")
  }

  @Test
  fun aClickableWithoutARoleKeepsTheOldWireForm() {
    // The whole reason roles got a tag of their own: a payload that names none must keep working
    // on a host built before roles existed.
    val (host, _) = compose { Box(modifier = Modifier.clickable { }) }
    assertEquals(ModifierTags.CLICKABLE, host.chain().single().t.local)
  }

  @Test
  fun aShapedBorderCarriesWidthColourAndShape() {
    val (host, _) = compose {
      Box(modifier = Modifier.border(2, Color.token("primary"), Shape.roundedCorner(8)))
    }
    val element = host.chain().single()
    assertEquals(ModifierTags.BORDER_SHAPE, element.t.local)
    val parts = element.v as JsonArray
    assertEquals(3, parts.size)
    assertEquals(2, parts[0].jsonPrimitive.intOrNull)
    assertTrue(parts[1] is JsonArray, "the colour is a recipe")
    assertTrue(parts[2] is JsonArray, "the shape is a recipe")
  }

  @Test
  fun animatedPerSidePaddingRidesOneElementAndOneSideAsksForCompletion() {
    var finished = 0
    val (host, composition) = compose {
      Box(
        modifier = Modifier.padding(
          start = animateDp(24, onFinished = { finished += 1 }),
          top = animateDp(8, onFinished = { finished += 1 }),
        ),
      )
    }
    val element = host.chain().single()
    assertEquals(ModifierTags.PADDING_SIDES_ANIMATED, element.t.local)
    val sides = element.v as JsonArray
    assertEquals(4, sides.size, "four sides, always, so the host reads by position")
    val start = sides[0] as JsonArray
    val top = sides[1] as JsonArray
    assertEquals(24, start[1].jsonPrimitive.intOrNull)
    assertEquals(true, start.last().jsonPrimitive.booleanOrNull, "the first side with a callback notifies")
    assertEquals(false, top.last().jsonPrimitive.booleanOrNull, "the second does not; one element, one completion")
    assertEquals(0, sides[2].jsonPrimitive.intOrNull, "an unset side is zero")

    // The completion arrives on the element's own tag, once, and reaches the guest once.
    val node = host.nodeOf(Tags.Box)
    composition.sendEvent(Event(i = node, e = EventTag(ELEMENT_EVENT_BASE + 0), q = composition.lastSentSequence))
    assertEquals(1, finished)
  }

  @Test
  fun dismissResumesTheCallerAndBumpsTheCounterTheHostReads() {
    var result: SnackbarResult? = null
    var holder: SnackbarHostState? = null
    val (host, composition) = compose {
      val state = rememberSnackbarHostState()
      val scope = rememberCoroutineScope()
      holder = state
      SnackbarArea(snackbars = state) {}
      scope.launch { result = state.showSnackbar("Deleted") }
    }
    val before = host.propertiesOf(SNACKBAR_AREA).filter { it.first == 5 }.map { it.second.jsonPrimitive.intOrNull }
    assertEquals(listOf(0), before.distinct(), "nothing dismissed yet")

    holder!!.dismiss()
    assertEquals(SnackbarResult.DISMISSED, result, "the caller resumes at once; the host's answer is not waited for")
    // A state change crosses on the next frame, as every property does; the test drives the clock.
    composition.frame(0L)
    val after = host.propertiesOf(SNACKBAR_AREA).filter { it.first == 5 }.map { it.second.jsonPrimitive.intOrNull }
    assertEquals(1, after.last(), "the counter crossed as property five of the area")

    // A second dismiss with nothing outstanding is nothing: no request, no counter movement.
    holder!!.dismiss()
    composition.frame(16L)
    val again = host.propertiesOf(SNACKBAR_AREA).filter { it.first == 5 }.map { it.second.jsonPrimitive.intOrNull }
    assertEquals(after.size, again.size)
  }
}

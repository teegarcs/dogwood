/*
 * Project Dogwood -- the primitive tier's second growth, as traffic.
 *
 * Every assertion is on what crossed, decoded by the host's own decoder, because the claim is a
 * wire claim: each new modifier is one element with its tag and its argument, each new property
 * is one tag that is absent until set, and `clickable` puts nothing new on the wire at all -- its
 * handler lives in the per-element slot animation completions already use.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.ModifierElem
import dev.dogwood.protocol.ModifierSet
import dev.dogwood.protocol.ModifierTags
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.WidgetTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private fun RecordingHost.chain(): List<ModifierElem> =
  decoded().flatMap { it.g }.filterIsInstance<ModifierSet>().single().e

private fun RecordingHost.properties(widget: WidgetTag): Map<Int, kotlinx.serialization.json.JsonElement> {
  val id = decoded().flatMap { it.g }.filterIsInstance<Create>().first { it.w == widget }.i
  return decoded().flatMap { it.g }.filterIsInstance<PropertySet>().filter { it.i == id }
    .associate { it.p.value to it.v }
}

private fun RecordingHost.nodeOf(widget: WidgetTag) =
  decoded().flatMap { it.g }.filterIsInstance<Create>().first { it.w == widget }.i

private fun ModifierElem.ints(): List<Int> = (v as JsonArray).map { it.jsonPrimitive.intOrNull ?: -99 }

class PrimitiveTierModifierTest {

  @Test
  fun everyNewModifierCrossesWithItsTagAndArgument() {
    val (host, _) = compose {
      Box(
        modifier = Modifier
          .border(2, Color.token("primary"))
          .offset(xDp = 4, yDp = -6)
          .fillMaxHeight(0.5f)
          .fillMaxSize(0.25f)
          .padding(start = 1, top = 2, end = 3, bottom = 4)
          .padding(horizontal = 7, vertical = 9)
          .shadow(3)
          .aspectRatio(1.5f)
          .contentDescription("a box")
          .testTag("the-box")
          .wrapContentWidth()
          .wrapContentHeight()
          .defaultMinSize(minWidthDp = 48)
          .widthIn(minDp = 10, maxDp = 20)
          .heightIn(maxDp = 30),
      )
    }
    val chain = host.chain()
    val tags = chain.map { it.t.local }
    assertEquals(
      listOf(
        ModifierTags.BORDER, ModifierTags.OFFSET, ModifierTags.FILL_MAX_HEIGHT, ModifierTags.FILL_MAX_SIZE,
        ModifierTags.PADDING_SIDES, ModifierTags.PADDING_SIDES, ModifierTags.SHADOW, ModifierTags.ASPECT_RATIO,
        ModifierTags.CONTENT_DESCRIPTION, ModifierTags.TEST_TAG, ModifierTags.WRAP_CONTENT_WIDTH,
        ModifierTags.WRAP_CONTENT_HEIGHT, ModifierTags.DEFAULT_MIN_SIZE, ModifierTags.WIDTH_IN, ModifierTags.HEIGHT_IN,
      ),
      tags,
      "the chain must cross in order, one element per call",
    )
    // The border carries its width and a colour *recipe* -- the token factory, not a resolved colour.
    val border = chain[0].v.jsonArray
    assertEquals(2, border[0].jsonPrimitive.intOrNull)
    assertEquals(4, border[1].jsonArray[0].jsonPrimitive.intOrNull, "colour token factory")
    assertEquals("primary", border[1].jsonArray[1].jsonPrimitive.content)
    assertEquals(listOf(4, -6), chain[1].ints())
    assertEquals(0.5f, chain[2].v.jsonPrimitive.floatOrNull)
    assertEquals(0.25f, chain[3].v.jsonPrimitive.floatOrNull)
    assertEquals(listOf(1, 2, 3, 4), chain[4].ints())
    // The symmetric spelling crosses as the four-sided form; the host has one reader.
    assertEquals(listOf(7, 9, 7, 9), chain[5].ints())
    assertEquals(3, chain[6].v.jsonPrimitive.intOrNull)
    assertEquals(1.5f, chain[7].v.jsonPrimitive.floatOrNull)
    assertEquals("a box", chain[8].v.jsonPrimitive.content)
    assertEquals("the-box", chain[9].v.jsonPrimitive.content)
    // -1 is "unspecified", so a one-sided call still crosses two numbers.
    assertEquals(listOf(48, -1), chain[12].ints())
    assertEquals(listOf(10, 20), chain[13].ints())
    assertEquals(listOf(-1, 30), chain[14].ints())
  }

  @Test
  fun clickableCrossesOnlyItsEnabledFlagAndRoutesTheTapByElementIndex() {
    var taps = 0
    val (host, composition) = compose {
      Box(modifier = Modifier.padding(4).clickable { taps += 1 })
    }
    val chain = host.chain()
    assertEquals(ModifierTags.CLICKABLE, chain[1].t.local)
    assertEquals(true, chain[1].v.jsonPrimitive.booleanOrNull, "the element's value is `enabled`")

    val node = host.nodeOf(Tags.Box)
    // Element one is the clickable; element zero is the padding. Same derivation as a completion.
    composition.sendEvent(Event(i = node, e = EventTag(ELEMENT_EVENT_BASE + 1), q = composition.lastSentSequence))
    assertEquals(1, taps)
    composition.sendEvent(Event(i = node, e = EventTag(ELEMENT_EVENT_BASE + 0), q = composition.lastSentSequence))
    assertEquals(1, taps, "the padding element has no handler")
  }

  @Test
  fun aDisabledClickableSaysSoOnTheWireAndStillOccupiesItsElement() {
    val (host, _) = compose {
      Box(modifier = Modifier.clickable(enabled = false) {})
    }
    assertEquals(false, host.chain().single().v.jsonPrimitive.booleanOrNull)
  }

  @Test
  fun aClickHandlerIsReboundEveryRecompositionRatherThanCaptured() {
    // The stale-closure defect ADR-016 found, on the newest element that can carry a closure.
    var counter by mutableStateOf(0)
    var observed = -1
    val (host, composition) = compose {
      Text("x", modifier = Modifier.clickable { observed = counter })
    }
    val node = host.nodeOf(Tags.Text)
    counter = 7
    composition.frame(0L)
    composition.sendEvent(Event(i = node, e = EventTag(ELEMENT_EVENT_BASE + 0), q = composition.lastSentSequence))
    assertEquals(7, observed, "the tap fired into a stale closure")
  }

  @Test
  fun changingTheHandlerAloneDoesNotReCrossTheChain() {
    // Handlers are outside the chain's equality on purpose: a fresh lambda per recomposition must
    // not look like a changed modifier, or every screen would re-send every chain every frame.
    var counter by mutableStateOf(0)
    val (host, composition) = compose {
      Text("$counter", modifier = Modifier.clickable { counter += 1 })
    }
    val before = host.decoded().flatMap { it.g }.filterIsInstance<ModifierSet>().size
    counter = 1
    composition.frame(0L)
    val after = host.decoded().flatMap { it.g }.filterIsInstance<ModifierSet>().size
    assertEquals(before, after, "the chain crossed again although only the text changed")
  }
}

class PrimitiveTierPropertyTest {

  @Test
  fun arrangementAndAlignmentCrossOnColumnRowAndBox() {
    val (host, _) = compose {
      Column(verticalArrangement = Arrangement.spacedBy(8), horizontalAlignment = HorizontalAlignment.End) {}
      Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = VerticalAlignment.Top) {}
      Box(contentAlignment = BoxAlignment.Center)
    }
    val column = host.properties(Tags.Column)
    assertEquals("spacedBy:8", column[1]?.jsonPrimitive?.content)
    assertEquals(HorizontalAlignment.End.ordinal, column[2]?.jsonPrimitive?.intOrNull)
    val row = host.properties(Tags.Row)
    assertEquals(false, row[1]?.jsonPrimitive?.booleanOrNull, "P1 is still the row's own clickable flag")
    assertEquals("spaceBetween", row[2]?.jsonPrimitive?.content)
    assertEquals(VerticalAlignment.Top.ordinal, row[3]?.jsonPrimitive?.intOrNull)
    val box = host.properties(Tags.Box)
    assertEquals(BoxAlignment.Center.ordinal, box[1]?.jsonPrimitive?.intOrNull)
  }

  @Test
  fun absenceSendsNothing() {
    // The sentinel rule. A host one layout version behind never reads these tags; a host at this
    // version reads absence as its own default. Either way a `Column {}` is one Create and no
    // properties, exactly as it was before the tags existed.
    val (host, _) = compose {
      Column {}
      Box {}
      Text("plain")
    }
    assertTrue(host.properties(Tags.Column).isEmpty(), "a bare column sent properties: ${host.properties(Tags.Column)}")
    assertTrue(host.properties(Tags.Box).isEmpty())
    assertEquals(setOf(1), host.properties(Tags.Text).keys, "a plain text sends its text and nothing else")
  }

  @Test
  fun textOverridesCrossAsNamesOnTheirOwnTags() {
    val (host, _) = compose {
      Text(
        "styled",
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        overflow = TextOverflow.Clip,
        sizeSp = 18,
        textDecoration = TextDecoration.Underline,
        lineHeightSp = 24,
      )
    }
    val text = host.properties(Tags.Text)
    assertEquals("bold", text[6]?.jsonPrimitive?.content)
    assertEquals("center", text[7]?.jsonPrimitive?.content)
    assertEquals("clip", text[8]?.jsonPrimitive?.content)
    assertEquals(18, text[9]?.jsonPrimitive?.intOrNull)
    assertEquals("underline", text[10]?.jsonPrimitive?.content)
    assertEquals(24, text[11]?.jsonPrimitive?.intOrNull)
    assertNull(text[5], "no colour was asked for")
  }

  @Test
  fun theRecipeOverloadCarriesTheSameOverrides() {
    // Two overloads, one set of tags: a formatted price asking for bold must be bold too.
    val (host, _) = compose {
      Text(TextValue("literal"), fontWeight = FontWeight.of(600), color = Color.token("primary"))
    }
    val text = host.properties(Tags.Text)
    assertEquals("600", text[6]?.jsonPrimitive?.content)
    assertEquals(4, text[5]?.jsonArray?.get(0)?.jsonPrimitive?.intOrNull, "the colour token factory")
  }
}

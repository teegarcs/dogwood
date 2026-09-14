/*
 * Project Dogwood -- reading an enumeration a surface declared.
 *
 * The generated binding calls `node.enum(...)` with the entries this client was built with, and the
 * case that matters is the name that is not among them: a payload built against a newer surface
 * sends an entry this client has never heard of. The reader must degrade to the declared default
 * and say so in the skew report, never throw -- a throw lands inside composition on every client
 * that received the payload, at once.
 */
package dev.dogwood.host

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.ModifierElem
import dev.dogwood.protocol.WidgetTag
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private enum class Tone { Neutral, Positive, Negative }

private class Node(private val properties: Map<Int, JsonElement>) : WidgetView {
  override val id: Id = Id(1)
  override val tag: WidgetTag = WidgetTag(1)
  override val modifiers: List<ModifierElem> = emptyList()
  override fun property(tag: Int): JsonElement? = properties[tag]
  override fun propertyTags(): Set<Int> = properties.keys
  override fun children(slot: Int): List<WidgetView> = emptyList()
}

@OptIn(ExperimentalTestApi::class)
class EnumReaderTest {

  private fun read(node: WidgetView, report: SkewReport, block: @androidx.compose.runtime.Composable () -> Unit) =
    runComposeUiTest {
      setContent { CompositionLocalProvider(LocalSkewReport provides report) { block() } }
    }

  @Test
  fun aKnownNameResolves() {
    val report = SkewReport()
    var value: Tone? = null
    read(Node(mapOf(1 to JsonPrimitive("Positive"))), report) {
      value = Node(mapOf(1 to JsonPrimitive("Positive"))).enum(1, Tone.entries, Tone.Neutral, what = "Tag.tone")
    }
    assertEquals(Tone.Positive, value)
    assertTrue(report.unknownEnumValues.isEmpty())
  }

  @Test
  fun anUnknownNameReadsAsTheDefaultAndIsReported() {
    val report = SkewReport()
    var value: Tone? = null
    read(Node(mapOf(1 to JsonPrimitive("Warning"))), report) {
      value = Node(mapOf(1 to JsonPrimitive("Warning"))).enum(1, Tone.entries, Tone.Neutral, what = "Tag.tone")
    }
    assertEquals(Tone.Neutral, value, "a newer payload's entry degrades to the declared default")
    assertEquals(setOf("Tag.tone=Warning"), report.unknownEnumValues)
    // And it reaches the drain under its own kind, so a dashboard can count it.
    assertTrue(report.entries().contains(SkewEntry(SkewKind.UNKNOWN_ENUM_VALUE, "Tag.tone=Warning")))
  }

  @Test
  fun absenceIsTheSentinelNotSkew() {
    val report = SkewReport()
    var required: Tone? = null
    var optional: Tone? = Tone.Negative
    read(Node(emptyMap()), report) {
      required = Node(emptyMap()).enum(1, Tone.entries, Tone.Neutral, what = "Tag.tone")
      optional = Node(emptyMap()).enumOrNull(1, Tone.entries, what = "Tag.accent")
    }
    assertEquals(Tone.Neutral, required)
    assertNull(optional)
    assertTrue(report.unknownEnumValues.isEmpty(), "nothing was sent, so nothing was misunderstood")
  }
}

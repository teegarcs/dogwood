/*
 * Project Dogwood -- reading an enumeration a surface declared, on every target that renders.
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

  /**
   * Composes [block] under [report] and **returns** the harness result, so the composition actually
   * happens on Kotlin/WebAssembly -- see `SkewReportingTest.rendered` for why a discarded result
   * there is a test that passes without composing. This file lived in `jvmTest` for a day, which
   * meant the enumeration readers were tested on one of the three targets they ship to.
   */
  private fun read(report: SkewReport, block: @androidx.compose.runtime.Composable () -> Unit) =
    runComposeUiTest {
      setContent { CompositionLocalProvider(LocalSkewReport provides report) { block() } }
      waitForIdle()
    }

  // Expression-bodied, so each test's return type is the harness result and nothing else.
  @Test
  fun aKnownNameResolves() = SkewReport().let { report ->
    val node = Node(mapOf(1 to JsonPrimitive("Positive")))
    read(report) {
      assertEquals(Tone.Positive, node.enum(1, Tone.entries, Tone.Neutral, what = "Tag.tone"))
      assertTrue(report.unknownEnumValues.isEmpty())
    }
  }

  @Test
  fun anUnknownNameReadsAsTheDefaultAndIsReported() = SkewReport().let { report ->
    val node = Node(mapOf(1 to JsonPrimitive("Warning")))
    read(report) {
      val value = node.enum(1, Tone.entries, Tone.Neutral, what = "Tag.tone")
      assertEquals(Tone.Neutral, value, "a newer payload's entry degrades to the declared default")
      assertEquals(setOf("Tag.tone=Warning"), report.unknownEnumValues)
      // And it reaches the drain under its own kind, so a dashboard can count it.
      assertTrue(report.entries().contains(SkewEntry(SkewKind.UNKNOWN_ENUM_VALUE, "Tag.tone=Warning")))
    }
  }

  @Test
  fun absenceIsTheSentinelNotSkew() = SkewReport().let { report ->
    val node = Node(emptyMap())
    read(report) {
      assertEquals(Tone.Neutral, node.enum(1, Tone.entries, Tone.Neutral, what = "Tag.tone"))
      assertNull(node.enumOrNull(1, Tone.entries, what = "Tag.accent"))
      assertTrue(report.unknownEnumValues.isEmpty(), "nothing was sent, so nothing was misunderstood")
    }
  }
}

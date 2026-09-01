/*
 * Project Dogwood -- the generator, tested against signatures shaped like the real audit.
 *
 * The fixture is deliberately a mix: components that bind, and components that fail each of the
 * five ways the Backpack audit found. A generator tested only on things that work is a generator
 * whose rejection path has never run.
 */
package dev.dogwood.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SURFACE = """
package acme.design

import androidx.compose.runtime.Composable

@Composable
fun AcmeDivider(modifier: Modifier = Modifier.Empty) {}

@Composable
fun AcmeBadge(text: String, selected: Boolean = false, modifier: Modifier = Modifier.Empty) {}

@Composable
fun AcmeButton(
  text: String,
  modifier: Modifier = Modifier.Empty,
  enabled: Boolean = true,
  style: String? = LocalDogwoodTheme.current.buttonStyle,
  onClick: () -> Unit,
) {}

@Composable
fun AcmeCard(modifier: Modifier = Modifier.Empty, content: @Composable () -> Unit) {}

@Composable
fun AcmeRipple(
  text: String,
  interactionSource: MutableInteractionSource,
  onClick: () -> Unit,
) {}

@Composable
fun AcmeIcon(icon: Painter, contentDescription: String) {}

@Composable
fun AcmeRating(
  rating: Float,
  contentDescription: (Float, Int) -> String,
) {}

@Composable
fun AcmeCarousel(state: CarouselState, content: @Composable (Int) -> Unit) {}

@Composable
private fun AcmeInternalHelper(text: String) {}
"""

class GeneratorTest {

  private val components = SurfaceParser().parse(SURFACE)

  @Test
  fun findsOnlyPublicComposables() {
    assertEquals(
      listOf("AcmeDivider", "AcmeBadge", "AcmeButton", "AcmeCard", "AcmeRipple", "AcmeIcon", "AcmeRating", "AcmeCarousel"),
      components.map { it.name },
      "private helpers are not surface",
    )
  }

  @Test
  fun classifiesEachParameterKind() {
    val button = components.single { it.name == "AcmeButton" }
    assertEquals(ParameterKind.VALUE, button.parameters.single { it.name == "text" }.kind)
    assertEquals(ParameterKind.MODIFIER, button.parameters.single { it.name == "modifier" }.kind)
    assertEquals(ParameterKind.EVENT, button.parameters.single { it.name == "onClick" }.kind)
    assertEquals(ParameterKind.SLOT, components.single { it.name == "AcmeCard" }.parameters.single { it.name == "content" }.kind)
  }

  @Test
  fun rejectsTheFiveFailureClassesTheAuditFound() {
    fun rejection(component: String, parameter: String) = components
      .single { it.name == component }.parameters.single { it.name == parameter }

    assertTrue(rejection("AcmeRipple", "interactionSource").rejection!!.contains("live-state"))
    assertTrue(rejection("AcmeIcon", "icon").rejection!!.contains("asset-gated"))
    assertTrue(
      rejection("AcmeRating", "contentDescription").rejection!!.contains("host-invoked"),
      "a lambda returning a value cannot cross; the host cannot block on the guest mid-frame",
    )
    assertTrue(rejection("AcmeCarousel", "state").rejection!!.contains("live-state"))
    assertEquals(
      listOf("AcmeDivider", "AcmeBadge", "AcmeButton", "AcmeCard"),
      components.filter { it.isBindable }.map { it.name },
    )
  }

  @Test
  fun aHostResolvedDefaultIsRecognised() {
    val style = components.single { it.name == "AcmeButton" }.parameters.single { it.name == "style" }
    assertTrue(
      style.defaultIsHostResolved,
      "a default reading a composition local has no value until it is composed on the host",
    )
    val enabled = components.single { it.name == "AcmeButton" }.parameters.single { it.name == "enabled" }
    assertTrue(!enabled.defaultIsHostResolved, "a literal default the guest can evaluate is not host-resolved")
  }

  @Test
  fun assignsStableTagsInDeclarationOrder() {
    val dictionary = buildDictionary("acme.design", segmentId = 1, version = 1, components = components)
    assertEquals(1, dictionary.components.first { it.name == "AcmeDivider" }.localTag)
    assertEquals(3, dictionary.components.first { it.name == "AcmeButton" }.localTag)
    val button = dictionary.components.first { it.name == "AcmeButton" }
    assertEquals(mapOf("text" to 1, "enabled" to 2, "style" to 3), button.properties)
    assertEquals(mapOf("onClick" to 1), button.events)
    assertEquals(mapOf("content" to 1), dictionary.components.first { it.name == "AcmeCard" }.slots)
  }

  @Test
  fun theDictionaryRecordsWhatItDeclinedToBind() {
    val dictionary = buildDictionary("acme.design", 1, 1, components)
    val rejected = dictionary.components.first { it.name == "AcmeRipple" }.rejected
    assertTrue("interactionSource" in rejected)
  }

  @Test
  fun emitsGuestStubsThatSendNothingForAHostResolvedDefault() {
    val dictionary = buildDictionary("acme.design", 1, 1, components)
    val stubs = emitGuestStubs("acme.generated", dictionary, components)

    assertTrue(stubs.contains("fun AcmeButton("), "a bindable component is emitted")
    assertTrue(!stubs.contains("fun AcmeIcon("), "a rejected component is not emitted")
    // Absence is the sentinel: the host resolves its own default.
    assertTrue(
      stubs.contains("set(style) { if (it != null)"),
      "a host-resolved default must be guarded so absence reaches the wire:\n$stubs",
    )
    assertTrue(
      stubs.contains("set(text) { recording.recorder.property"),
      "a required value is sent unconditionally",
    )
    assertTrue(stubs.contains("widgetTag(1, 3)"), "the stub carries its segment-encoded tag")
  }
}

/**
 * The second half of the Phase 3 gate: the generator round-trips an upstream change without hand
 * edits.
 *
 * A Compose version bump is not reproducible in a test, but the property that matters under one
 * is: when the surface gains a component and an optional parameter, every tag that already
 * existed must keep its number. If tags moved, a client one dictionary version behind would
 * silently render the wrong widget — which is worse than failing to render it.
 */
class RoundTripTest {

  private val before = """
    package acme.design
    import androidx.compose.runtime.Composable

    @Composable fun AcmeDivider(modifier: Modifier = Modifier.Empty) {}
    @Composable fun AcmeBadge(text: String, modifier: Modifier = Modifier.Empty) {}
    @Composable fun AcmeChip(text: String, onSelectedChange: (Boolean) -> Unit) {}
  """

  /** Upstream added a component at the end, and an optional parameter to an existing one. */
  private val after = """
    package acme.design
    import androidx.compose.runtime.Composable

    @Composable fun AcmeDivider(modifier: Modifier = Modifier.Empty) {}
    @Composable fun AcmeBadge(text: String, modifier: Modifier = Modifier.Empty, subtitle: String? = null) {}
    @Composable fun AcmeChip(text: String, onSelectedChange: (Boolean) -> Unit) {}
    @Composable fun AcmeSpinner(modifier: Modifier = Modifier.Empty) {}
  """

  @Test
  fun existingTagsSurviveAnUpstreamChange() {
    val parser = SurfaceParser()
    val v1 = buildDictionary("acme", 1, 1, parser.parse(before, "Before.kt"))
    val v2 = buildDictionary("acme", 1, 2, parser.parse(after, "After.kt"))

    for (entry in v1.components) {
      val updated = v2.components.single { it.name == entry.name }
      assertEquals(entry.localTag, updated.localTag, "${entry.name} changed widget tag")
      for ((property, tag) in entry.properties) {
        assertEquals(tag, updated.properties[property], "${entry.name}.$property changed property tag")
      }
      for ((event, tag) in entry.events) {
        assertEquals(tag, updated.events[event], "${entry.name}.$event changed event tag")
      }
    }
  }

  @Test
  fun additionsAppend() {
    val parser = SurfaceParser()
    val v2 = buildDictionary("acme", 1, 2, parser.parse(after, "After.kt"))
    assertEquals(4, v2.components.single { it.name == "AcmeSpinner" }.localTag, "a new component appends")
    assertEquals(2, v2.components.single { it.name == "AcmeBadge" }.properties["subtitle"], "a new optional parameter appends")
  }

  @Test
  fun regeneratingUnchangedInputProducesIdenticalOutput() {
    val parser = SurfaceParser()
    val first = buildDictionary("acme", 1, 1, parser.parse(before, "Before.kt")).encode()
    val second = buildDictionary("acme", 1, 1, parser.parse(before, "Before.kt")).encode()
    assertEquals(first, second, "the generator must be deterministic or its output cannot be diffed")
  }
}

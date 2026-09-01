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
fun AcmeDivider(modifier: DogwoodModifier = DogwoodModifier.Empty) {}

@Composable
fun AcmeBadge(text: String, selected: Boolean = false, modifier: DogwoodModifier = DogwoodModifier.Empty) {}

@Composable
fun AcmeButton(
  text: String,
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  enabled: Boolean = true,
  style: String? = LocalDogwoodTheme.current.buttonStyle,
  onClick: () -> Unit,
) {}

@Composable
fun AcmeCard(modifier: DogwoodModifier = DogwoodModifier.Empty, content: @Composable () -> Unit) {}

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

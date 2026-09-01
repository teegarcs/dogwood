/*
 * Project Dogwood -- host-resolved values reaching a design system's own components.
 *
 * The rule these pin: **anything that depends on the device crosses as a recipe, not a result.**
 * Before this, the rule held at three hand-wired spots and stopped at the edge of the generated
 * surface, so a product's own `Price` could only ever receive a string somebody had already
 * formatted — deciding the currency symbol, the separators and the decimal places on behalf of
 * every device the payload would reach.
 */
package dev.dogwood.compose

import dev.dogwood.protocol.PropertySet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private fun RecordingHost.property(tag: Int) =
  decoded().flatMap { it.g }.filterIsInstance<PropertySet>().last { it.p.value == tag }

class GeneratedComponentTest {

  @Test
  fun aRecipeReachesAGeneratedComponentsTextParameter() {
    // The whole point of the type. `Price` is the component that exists to display money, and it
    // is the one place correctly formatted money could not go.
    val (host, _) = compose { Price(price = Formats.currency(61_200, "USD")) }
    val value = host.property(1).v
    assertTrue(value is JsonArray, "money must cross as a recipe, not a finished string; got $value")
    assertEquals(6, value.jsonArray[0].jsonPrimitive.intOrNull, "the currency factory")
    assertEquals(61_200, value.jsonArray[1].jsonPrimitive.intOrNull)
    assertEquals("USD", value.jsonArray[2].jsonPrimitive.content)
  }

  @Test
  fun aLiteralStillCrossesAsAPlainString() {
    // One property tag carries either form. A literal must not grow a wrapper.
    val (host, _) = compose { Price(price = TextValue("From $612")) }
    assertEquals(JsonPrimitive("From $612"), host.property(1).v)
  }

  @Test
  fun theConvenienceOverloadKeepsLiteralCallSitesUnchanged() {
    // Widening the parameter's type must not break the common case, which is a literal.
    val (host, _) = compose { Price(price = "From $612") }
    assertEquals(JsonPrimitive("From $612"), host.property(1).v)
  }

  @Test
  fun aLiteralAndARecipeMixOnOneComponent() {
    val (host, _) = compose {
      Price(
        price = Formats.currency(61_200, "USD"),
        trailingText = TextValue("return"),
      )
    }
    assertTrue(host.property(1).v is JsonArray, "the price is a recipe")
    assertEquals(JsonPrimitive("return"), host.property(4).v, "the suffix is a literal")
  }

  @Test
  fun anUnsetOptionalTextSendsNothing() {
    // Absence stays the host-default sentinel; the new type must not start sending explicit nulls.
    val (host, _) = compose { Price(price = "x") }
    val tags = host.decoded().flatMap { it.g }.filterIsInstance<PropertySet>().map { it.p.value }
    assertTrue(2 !in tags && 3 !in tags && 4 !in tags, "unset optionals crossed: $tags")
  }

  @Test
  fun aColourParameterCrossesAsARecipeNotATokenName() {
    // `Icon.tint` used to be a `String` that happened to hold a token name — host-resolved in
    // fact, invisible to the type system, and therefore uncheckable.
    val (host, _) = compose { Icon(name = "flight", tint = Color.token("primary")) }
    val value = host.property(4).v
    assertTrue(value is JsonArray, "a colour is always a recipe; got $value")
    assertEquals(4, value.jsonArray[0].jsonPrimitive.intOrNull, "the colour-token factory")
    assertEquals("primary", value.jsonArray[1].jsonPrimitive.content)
  }

  @Test
  fun aLiteralColourIsStillARecipeAndSaysSo() {
    // `Color(0xFF…)` is the opt-out from theming, not an escape from the encoding.
    val (host, _) = compose { Icon(name = "flight", tint = Color(0xFF0770E3)) }
    val value = host.property(4).v as JsonArray
    assertEquals(3, value.jsonArray[0].jsonPrimitive.intOrNull, "the literal-colour factory")
  }

  @Test
  fun anUnsetColourSendsNothingSoTheHostChooses() {
    val (host, _) = compose { Icon(name = "flight") }
    val tags = host.decoded().flatMap { it.g }.filterIsInstance<PropertySet>().map { it.p.value }
    assertTrue(4 !in tags, "an unset tint must let the host pick its own")
  }
}

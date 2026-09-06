/*
 * Project Dogwood -- the resources subsystem, guest side.
 *
 * Two halves, split by what the sandbox can actually do. Words it can hold: a payload carries its
 * own string table, so changing copy needs no store release. Numbers it cannot render: the pinned
 * QuickJS ships no ECMA-402 `Intl`, so money, dates and decimal separators cross as recipes and
 * the host renders them.
 */
/*
 * Reads the wire form of a host-resolved value directly, which is the generated stubs' seam. A test
 * asserting what crosses is the one other legitimate caller. See `GeneratedApi.kt`.
 */
@file:OptIn(dev.dogwood.compose.DogwoodGeneratedApi::class)

package dev.dogwood.compose

import androidx.compose.runtime.Composable
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.PropertySet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private fun RecordingHost.properties(tag: Int) =
  decoded().flatMap { it.g }.filterIsInstance<PropertySet>().filter { it.p.value == tag }

class FormatRecipeTest {

  @Test
  fun formattedTextCrossesAsARecipeAndNotAsAString() {
    // The number crosses, not the rendered string. That is what lets a device change locale and
    // re-render correctly with no traffic and no guest recomposition at all.
    val (host, _) = compose { Text(Formats.currency(61_200, "USD")) }
    val recipe = host.properties(4).single().v
    assertTrue(recipe is JsonArray, "a recipe is a positional array; got $recipe")
    assertEquals(6, recipe.jsonArray[0].jsonPrimitive.intOrNull, "the currency factory")
    assertEquals(61_200, recipe.jsonArray[1].jsonPrimitive.intOrNull)
    assertEquals("USD", recipe.jsonArray[2].jsonPrimitive.content)
    assertTrue(host.properties(1).isEmpty(), "the literal text property must not also be sent")
  }

  @Test
  fun everyFormatKindHasItsOwnFactory() {
    // A closed, versioned set. Two kinds sharing a factory identifier would render one of them as
    // the other on a client that only implemented the first.
    val factories = listOf(
      Formats.number(1.0),
      Formats.currency(1, "USD"),
      Formats.percent(0.5),
      Formats.date(0),
      Formats.time(0),
      Formats.dateTime(0),
      Formats.relativeTime(0, 0),
    ).map { (it.json as JsonArray)[0].jsonPrimitive.intOrNull }
    assertEquals(factories.size, factories.toSet().size, "duplicate factory identifiers: $factories")
    assertEquals(listOf(5, 6, 7, 8, 9, 10, 11), factories)
  }

  @Test
  fun aTextStyleCrossesAsAName() {
    // A token, not a `TextStyle`: the guest cannot construct one, and a literal could not follow
    // the host's typography -- including its font family.
    val (host, _) = compose { Text("hello", style = "titleLarge") }
    assertEquals(JsonPrimitive("titleLarge"), host.properties(3).single().v)
  }

  @Test
  fun anUnsetStyleSendsNothingAtAll() {
    // Absence IS the host-default sentinel; sending "bodyLarge" explicitly would take the
    // decision away from the host that is supposed to make it.
    val (host, _) = compose { Text("hello") }
    assertTrue(host.properties(3).isEmpty())
  }
}

class StringTableTest {

  private val table = StringTable(
    mapOf(
      "en" to mapOf("hello" to "Hello", "onlyEnglish" to "Only English"),
      "ja" to mapOf("hello" to "こんにちは"),
    ),
  )

  @Composable
  private fun Greeting() {
    Text(strings("hello"))
  }

  private fun rendered(host: RecordingHost) =
    host.decoded().flatMap { it.g }.filterIsInstance<PropertySet>()
      .filter { it.p.value == 1 }
      .map { it.v.toString().trim('"') }

  @Test
  fun theDeviceLanguageSelectsTheTranslation() {
    val (host, _) = compose(configuration = HostEnvironment(locale = "ja-JP")) {
      androidx.compose.runtime.CompositionLocalProvider(LocalStringTable provides table) {
        Greeting()
      }
    }
    assertTrue("こんにちは" in rendered(host), rendered(host).toString())
  }

  @Test
  fun regionIsIgnoredBecauseTranslationsAreByLanguage() {
    // `en-GB` and `en-US` share a translation; they differ in *formatting*, which is the host's
    // job, not the table's.
    val (host, _) = compose(configuration = HostEnvironment(locale = "en-GB")) {
      androidx.compose.runtime.CompositionLocalProvider(LocalStringTable provides table) {
        Greeting()
      }
    }
    assertTrue("Hello" in rendered(host))
  }

  @Test
  fun anUntranslatedKeyFallsBackToTheFallbackLanguage() {
    // A screen that rendered nothing because nobody translated it is worse than one in English.
    assertEquals("Only English", table.get("ja", "onlyEnglish"))
  }

  @Test
  fun aMissingKeyRendersItsOwnName() {
    // Visible in a screenshot rather than a gap somebody has to notice.
    assertEquals("checkout.title", table.get("en", "checkout.title"))
  }

  @Test
  fun anExperienceWithNoTableRendersItsKeys() {
    val (host, _) = compose { Greeting() }
    assertTrue("hello" in rendered(host))
  }
}

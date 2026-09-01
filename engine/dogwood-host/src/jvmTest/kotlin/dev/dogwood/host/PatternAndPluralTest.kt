/*
 * Project Dogwood -- formatting the payload can configure, and plurals it cannot.
 *
 * Two halves of the same line. A number *pattern* is the payload's to ship, because it is a
 * presentation choice and changing it should not need a host release. A plural *category* is the
 * host's, because it is locale data the sandbox does not have — while the words remain the
 * payload's, like every other word it shows.
 */
package dev.dogwood.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

private fun expr(text: String) = Json.parseToJsonElement(text)

class GuestSuppliedPatternTest {

  @Test
  fun oneGuestPatternRendersWithEachDevicesSymbols() {
    // The whole point of "configurable over the wire": the payload decides the shape, the device
    // decides the separators, and one set of wire bytes is correct in both places.
    val e = ExpressionEvaluator()
    val recipe = """[5,1234.56,null,"#,##0.0"]"""
    assertEquals("1,234.6", e.text(expr(recipe), "en-US", "UTC"))
    assertEquals("1.234,6", e.text(expr(recipe), "de-DE", "UTC"))
  }

  @Test
  fun aPatternChangesTheShapeAndNotJustTheSymbols() {
    val e = ExpressionEvaluator()
    val plain = e.text(expr("[5,1234.5,null,null]"), "en-US", "UTC")
    val padded = e.text(expr("""[5,1234.5,null,"00000.000"]"""), "en-US", "UTC")
    assertNotEquals(plain, padded)
    assertEquals("01234.500", padded)
  }

  @Test
  fun aMalformedPatternDegradesToTheLocalesOwnFormAndIsReported() {
    // A payload can be replaced over the air, so a pattern is untrusted input. The price still
    // renders; the payload's mistake becomes telemetry rather than a blank nobody can explain.
    val skew = SkewReport()
    val rendered = ExpressionEvaluator(skew).text(expr("""[5,1234.5,null,"###,#0#{{"]"""), "en-US", "UTC")
    assertTrue(rendered.startsWith("1,234"), rendered)
    assertEquals(setOf("###,#0#{{"), skew.rejectedNumberPatterns)
  }

  @Test
  fun noPatternBehavesExactlyAsBefore() {
    // The parameter is additive; every existing recipe must render unchanged.
    val e = ExpressionEvaluator()
    assertEquals("1,234.5", e.text(expr("[5,1234.5,null]"), "en-US", "UTC"))
  }
}

class PluralTest {

  private val nights = mapOf("one" to "# night", "other" to "# nights")

  private fun recipe(count: Int, templates: Map<String, String> = nights): String {
    val body = templates.entries.joinToString(",") { """"${it.key}":"${it.value}"""" }
    return """[16,$count,{$body}]"""
  }

  @Test
  fun theCategoryComesFromTheHostAndTheWordsFromThePayload() {
    val e = ExpressionEvaluator()
    assertEquals("1 night", e.text(expr(recipe(1)), "en-US", "UTC"))
    assertEquals("3 nights", e.text(expr(recipe(3)), "en-US", "UTC"))
    assertEquals("0 nights", e.text(expr(recipe(0)), "en-US", "UTC"))
  }

  @Test
  fun theCountIsFormattedForTheLocaleTooNotJustInterpolated() {
    // `#` is the *formatted* count, so a four-figure one is grouped the way the device groups.
    val e = ExpressionEvaluator()
    assertEquals("1,200 nights", e.text(expr(recipe(1200)), "en-US", "UTC"))
    assertEquals("1.200 nights", e.text(expr(recipe(1200)), "de-DE", "UTC"))
  }

  @Test
  fun anUntranslatedCategoryFallsBackToOther() {
    // A language using a category the payload did not translate must still render a sentence.
    val e = ExpressionEvaluator()
    assertEquals("2 nights", e.text(expr(recipe(2, mapOf("other" to "# nights"))), "en-US", "UTC"))
  }

  @Test
  fun aPayloadThatTranslatedNothingStillRendersTheCountAndIsReported() {
    val skew = SkewReport()
    val rendered = ExpressionEvaluator(skew).text(expr(recipe(3, emptyMap())), "en-US", "UTC")
    assertEquals("3", rendered)
    assertTrue(skew.untranslatedPlurals.isNotEmpty(), "the gap must be recorded")
  }

  @Test
  fun aTemplateWithoutAPlaceholderIsRenderedAsWritten() {
    // Not every plural mentions its count: "no nights available" is a legitimate `zero` form.
    val e = ExpressionEvaluator()
    assertEquals(
      "no nights available",
      e.text(expr(recipe(0, mapOf("other" to "no nights available"))), "en-US", "UTC"),
    )
  }
}

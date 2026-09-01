/*
 * Project Dogwood -- the resources subsystem, host side.
 *
 * Three named things a guest can send that only the host can resolve: a formatting recipe, a text
 * style name, and an icon name. All three follow the same rule the colour tokens established --
 * the host owns the meaning, an unknown name degrades to something usable, and the name is
 * recorded so a team learns that payloads are ahead of devices.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

private fun expr(text: String) = Json.parseToJsonElement(text)
private val TEXT_TAG = DogwoodDictionary.Text.value

class FormattingTest {

  private fun evaluator() = ExpressionEvaluator()

  @Test
  fun currencyFractionDigitsComeFromTheCurrencyNotTheGuest() {
    // The reason the wire carries minor units and a code rather than a decimal amount: the number
    // of decimal places is a property of the currency, and the host is the side that knows it.
    // A guest that sent "612.00" would already have decided this, wrongly, for half the world.
    val e = evaluator()
    assertEquals("$612.00", e.text(expr("""[6,61200,"USD"]"""), "en-US", "UTC"))
    assertEquals("¥61,200", e.text(expr("""[6,61200,"JPY"]"""), "en-US", "UTC"))
  }

  @Test
  fun theSameRecipeRendersDifferentlyInDifferentLocales() {
    val e = evaluator()
    val english = e.text(expr("[5,1234567.891,null]"), "en-US", "UTC")
    val german = e.text(expr("[5,1234567.891,null]"), "de-DE", "UTC")
    assertNotEquals(english, german, "grouping and decimal separators must follow the locale")
    assertTrue(english.startsWith("1,234,567"), english)
    assertTrue(german.startsWith("1.234.567"), german)
  }

  @Test
  fun theMemoIsKeyedOnTheLocaleAndNotJustTheRecipe() {
    // A memo keyed on the recipe alone would keep showing prices in the locale the screen opened
    // in. Nobody would report that as a formatting bug.
    val e = evaluator()
    assertEquals("$612.00", e.text(expr("""[6,61200,"USD"]"""), "en-US", "UTC"))
    val german = e.text(expr("""[6,61200,"USD"]"""), "de-DE", "UTC")
    assertNotEquals("$612.00", german)
    assertEquals("$612.00", e.text(expr("""[6,61200,"USD"]"""), "en-US", "UTC"))
  }

  @Test
  fun anUnknownCurrencyDegradesToTheNumberAndTheCode() {
    // A payload may know about a currency this client's runtime does not. Showing the amount and
    // the code is worse than a correct symbol and much better than nothing.
    val rendered = evaluator().text(expr("""[6,61200,"XYZ"]"""), "en-US", "UTC")
    assertTrue(rendered.contains("XYZ"), rendered)
    assertTrue(rendered.contains("61,200"), rendered)
  }

  @Test
  fun anUnknownTextFactoryDegradesAndIsReported() {
    val skew = SkewReport()
    val rendered = ExpressionEvaluator(skew).text(expr("[97,1]"), "en-US", "UTC", fallback = "—")
    assertEquals("—", rendered)
    assertTrue(97 in skew.unknownExpressionFactories)
  }

  @Test
  fun datesAndTimesFollowTheTimeZone() {
    val e = evaluator()
    val utc = e.text(expr("[9,0]"), "en-US", "UTC")
    val tokyo = e.text(expr("[9,0]"), "en-US", "Asia/Tokyo")
    assertNotEquals(utc, tokyo, "the same instant is a different clock time in a different zone")
  }

  @Test
  fun relativeTimePicksTheLargestWholeUnit() {
    val e = evaluator()
    val threeDaysAgo = e.text(expr("[11,0,${3L * 86_400_000L}]"), "en-US", "UTC")
    assertEquals("3 days ago", threeDaysAgo)
    val inTwoHours = e.text(expr("[11,${2L * 3_600_000L},0]"), "en-US", "UTC")
    assertEquals("in 2 hours", inTwoHours)
    assertEquals("1 minute ago", e.text(expr("[11,0,60000]"), "en-US", "UTC"))
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aFormattedTextNodeRendersTheRecipeRatherThanARawValue() {
    // End to end through the real binding: the property carries `[6, 61200, "USD"]` and the
    // screen shows money.
    val tree = HostTree().also {
      it.apply(
        decodePositional("""[1,[[0,1,$TEXT_TAG],[1,1,4,[6,61200,"USD"]],[3,0,1,1,0]]]"""),
      )
    }
    runComposeUiTest {
      setContent {
        androidx.compose.runtime.CompositionLocalProvider(
          LocalFormatContext provides FormatContext("en-US", "UTC"),
        ) {
          Box(Modifier.size(300.dp, 200.dp)) {
            DogwoodTree(tree, EventSink { _, _, _ -> })
          }
        }
      }
      onNodeWithText("$612.00").assertIsDisplayed()
    }
  }
}

class NamedResourceTest {

  @Test
  fun everyTypographyTokenResolves() {
    val typography = Typography(
      displayLarge = androidx.compose.ui.text.TextStyle.Default,
      titleLarge = androidx.compose.ui.text.TextStyle.Default,
      titleMedium = androidx.compose.ui.text.TextStyle.Default,
      titleSmall = androidx.compose.ui.text.TextStyle.Default,
      bodyLarge = androidx.compose.ui.text.TextStyle.Default,
      bodyMedium = androidx.compose.ui.text.TextStyle.Default,
      bodySmall = androidx.compose.ui.text.TextStyle.Default,
      labelLarge = androidx.compose.ui.text.TextStyle.Default,
      labelMedium = androidx.compose.ui.text.TextStyle.Default,
      labelSmall = androidx.compose.ui.text.TextStyle.Default,
    )
    val names = listOf(
      "displayLarge", "titleLarge", "titleMedium", "titleSmall",
      "bodyLarge", "bodyMedium", "bodySmall",
      "labelLarge", "labelMedium", "labelSmall",
    )
    for (name in names) assertTrue(typography.token(name) != null, "missing '$name'")
    assertTrue(typography.token("gigantic") == null, "an unknown name must not resolve")
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun anUnknownTextStyleFallsBackToBodyAndIsReported() {
    val skew = SkewReport()
    val tree = HostTree().also {
      it.apply(
        decodePositional("""[1,[[0,1,$TEXT_TAG],[1,1,1,"hello"],[1,1,3,"gigantic"],[3,0,1,1,0]]]"""),
      )
    }
    runComposeUiTest {
      setContent { DogwoodTree(tree, EventSink { _, _, _ -> }, skew = skew) }
      // Still rendered: skew degrades, it does not blank the screen.
      onNodeWithText("hello").assertIsDisplayed()
    }
    assertEquals(setOf("gigantic"), skew.unknownTextStyles)
  }

  @Test
  fun theIconSetResolvesNamesAndHasAFallback() {
    val icons = IconSet.Default
    assertTrue("flight" in icons.names)
    assertTrue(icons["flight"] != null)
    assertTrue(icons["definitelyNotAnIcon"] == null)
    // Not null and not a throw: an unknown icon draws something rather than leaving a hole.
    assertNotEquals(icons.fallback, icons["flight"])
  }

  @Test
  fun aSkewReportKnowsWhetherItIsEmpty() {
    val skew = SkewReport()
    assertTrue(skew.isEmpty)
    skew.unknownIcons += "compass"
    assertTrue(!skew.isEmpty)
    assertTrue(skew.toString().contains("compass"))
  }
}

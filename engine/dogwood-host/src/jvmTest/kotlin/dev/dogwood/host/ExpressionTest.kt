/*
 * Project Dogwood -- the deferred-expression evaluator.
 *
 * A recipe the host evaluates is guest-supplied data driving host object construction, which
 * makes two of its properties safety properties rather than niceties: it must not grow without
 * bound, and it must not crash on a recipe it does not recognise.
 */
package dev.dogwood.host

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

private fun expr(text: String) = Json.parseToJsonElement(text)

class ExpressionTest {

  @Test
  fun buildsShapesAndColoursFromRecipes() {
    val evaluator = ExpressionEvaluator()
    assertEquals(RoundedCornerShape(8.dp), evaluator.shape(expr("[1,8]")))
    assertEquals(CircleShape, evaluator.shape(expr("[2]")))
    assertEquals(Palette.Light.primary, evaluator.color(expr("""[4,"primary"]"""), Palette.Light))
  }

  @Test
  fun memoizesSoAShapeIsNotRebuiltPerFrame() {
    val evaluator = ExpressionEvaluator()
    val first = evaluator.shape(expr("[1,12]"))
    val second = evaluator.shape(expr("[1,12]"))
    assertSame(first, second, "an identical recipe must not allocate twice; this runs per frame")
  }

  @Test
  fun theCacheIsBounded() {
    val evaluator = ExpressionEvaluator()
    // A guest that emits a distinct recipe per frame would otherwise grow host memory without
    // limit, which is a guest-controlled leak.
    repeat(3000) { evaluator.shape(expr("[1,$it]")) }
    // Still serving correct results after the bound was hit.
    assertEquals(RoundedCornerShape(7.dp), evaluator.shape(expr("[1,7]")))
  }

  @Test
  fun anUnknownFactoryFallsBackAndIsReported() {
    val evaluator = ExpressionEvaluator()
    // A guest built against a newer dictionary. It must degrade, exactly as an unknown widget
    // tag becomes a placeholder rather than a crash.
    val shape = evaluator.shape(expr("[99,1]"), fallback = CircleShape)
    assertSame(CircleShape, shape)
    assertEquals(Color.Unspecified, evaluator.color(expr("[98]"), Palette.Light))
    assertTrue(99 in evaluator.unknownFactories && 98 in evaluator.unknownFactories)
  }

  @Test
  fun anUnknownColourTokenFallsBack() {
    val evaluator = ExpressionEvaluator()
    // Known factory, unknown token: the same skew, one level down.
    assertEquals(Color.Unspecified, evaluator.color(expr("""[4,"chartreuse"]"""), Palette.Light))
  }

  @Test
  fun aTokenResolvesAgainstThePaletteInForce() {
    val evaluator = ExpressionEvaluator()
    // The same recipe, twice, either side of a theme switch. If the memo were keyed on the
    // recipe alone the screen would stay in the old theme with nothing to show why.
    assertEquals(Palette.Light.primary, evaluator.color(expr("""[4,"primary"]"""), Palette.Light))
    assertEquals(Palette.Dark.primary, evaluator.color(expr("""[4,"primary"]"""), Palette.Dark))
    assertEquals(Palette.Light.primary, evaluator.color(expr("""[4,"primary"]"""), Palette.Light))
  }

  @Test
  fun aLiteralColourDoesNotFollowTheTheme() {
    val evaluator = ExpressionEvaluator()
    // Deliberate: a guest that sends an ARGB literal has opted out of theming, and it gets what
    // it asked for. This is the reason a token is the recommended form for anything themed.
    val light = evaluator.color(expr("[3,4278190335]"), Palette.Light)
    val dark = evaluator.color(expr("[3,4278190335]"), Palette.Dark)
    assertEquals(light, dark)
  }

  @Test
  fun everyTokenNameResolvesInBothPalettes() {
    // A palette that carries a name in light but not in dark would render one theme correctly
    // and fall back to Color.Unspecified -- invisible text -- in the other.
    val names = listOf(
      "ink", "inkSecondary", "canvas", "canvasContrast", "primary", "primaryContainer",
      "onPrimary", "line", "success", "successContainer", "warning", "warningContainer", "star",
    )
    for (name in names) {
      assertTrue(Palette.Light.token(name) != null, "light palette is missing '$name'")
      assertTrue(Palette.Dark.token(name) != null, "dark palette is missing '$name'")
    }
  }
}

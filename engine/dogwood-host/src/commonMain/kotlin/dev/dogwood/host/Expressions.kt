/*
 * Project Dogwood -- the deferred-expression evaluator, host side.
 *
 * A deferred expression is a recipe, not a value: `[factory, arg, ...]`. The host owns every
 * factory, which is the point — `Shape` and `Color` have no serializable form and Compose's own
 * implementations are `internal`, so the guest could not send one even if it wanted to.
 *
 * Two properties this must have, and does:
 *
 *   - **Bounded.** Evaluated results are memoized, because a shape rebuilt per frame per node is
 *     allocation the frame budget cannot afford. The cache has a hard cap; an unbounded cache
 *     keyed on guest-supplied data is a guest-controlled memory leak.
 *   - **Closed.** An unknown factory renders as a documented fallback rather than throwing. A
 *     guest built against a newer dictionary must degrade, not crash the host — the same rule
 *     that makes an unknown widget tag a placeholder.
 */
package dev.dogwood.host

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val ROUNDED_CORNER = 1
private const val CIRCLE = 2
private const val COLOR_ARGB = 3
private const val COLOR_TOKEN = 4

/**
 * How many evaluated expressions to retain.
 *
 * A screen's distinct shapes and colours number in the tens; a thousand is generous headroom and
 * still a hard ceiling. When it is reached the cache is cleared rather than evicted one at a
 * time: this is a memo, not a working set, and rebuilding a shape is cheap.
 */
private const val CACHE_LIMIT = 1000

class ExpressionEvaluator {
  private val shapes = HashMap<JsonElement, Shape>()
  private val colors = HashMap<JsonElement, Color>()

  /**
   * Which palette the colour cache was filled against.
   *
   * A token recipe evaluates to a different colour in dark mode, so the memo is only valid while
   * the palette is unchanged. Identity comparison, and a clear rather than a rebuild: a theme
   * switch is rare, and a stale cache here would repaint the screen in the wrong theme with no
   * other symptom.
   */
  private var cachedFor: Palette? = null

  /** Factory identifiers this client does not know. Telemetry, and the skew signal. */
  val unknownFactories = mutableSetOf<Int>()

  fun shape(expression: JsonElement, fallback: Shape = RoundedCornerShape(0.dp)): Shape {
    shapes[expression]?.let { return it }
    if (shapes.size >= CACHE_LIMIT) shapes.clear()
    val args = expression.jsonArray
    val built = when (val factory = args[0].jsonPrimitive.intOrNull) {
      ROUNDED_CORNER -> RoundedCornerShape((args[1].jsonPrimitive.intOrNull ?: 0).dp)
      CIRCLE -> CircleShape
      else -> {
        if (factory != null) unknownFactories += factory
        fallback
      }
    }
    shapes[expression] = built
    return built
  }

  /**
   * @param palette the palette in force, which token recipes resolve against. Passed rather than
   *   held, because it changes while the evaluator does not: the evaluator's lifetime is the
   *   guest's, and dark mode can flip several times inside one.
   */
  fun color(
    expression: JsonElement,
    palette: Palette,
    fallback: Color = Color.Unspecified,
  ): Color {
    if (cachedFor !== palette) {
      colors.clear()
      cachedFor = palette
    }
    colors[expression]?.let { return it }
    if (colors.size >= CACHE_LIMIT) colors.clear()
    val args = expression.jsonArray
    val built = when (val factory = args[0].jsonPrimitive.intOrNull) {
      // A literal colour, which the guest may only send for something genuinely unthemed. It
      // does not follow dark mode, and that is the point of preferring a token.
      COLOR_ARGB -> Color((args[1].jsonPrimitive.longOrNull ?: 0L).toULong() shl 32)
      COLOR_TOKEN -> palette.token(args[1].jsonPrimitive.content) ?: fallback
      else -> {
        if (factory != null) unknownFactories += factory
        fallback
      }
    }
    colors[expression] = built
    return built
  }
}

/**
 * One evaluator per experience.
 *
 * Not global: the cache holds host objects built from guest-supplied recipes, so its lifetime is
 * the guest's. A global one would outlive the code that filled it.
 */
val LocalExpressionEvaluator = androidx.compose.runtime.staticCompositionLocalOf { ExpressionEvaluator() }

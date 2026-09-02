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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val ROUNDED_CORNER = 1
private const val CIRCLE = 2
private const val COLOR_ARGB = 3
private const val COLOR_TOKEN = 4
private const val TEXT_NUMBER = 5
private const val TEXT_CURRENCY = 6
private const val TEXT_PERCENT = 7
private const val TEXT_DATE = 8
private const val TEXT_TIME = 9
private const val TEXT_DATE_TIME = 10
private const val TEXT_RELATIVE_TIME = 11
private const val TEXT_PLURAL = 16

/**
 * How many evaluated expressions to retain.
 *
 * A screen's distinct shapes and colours number in the tens; a thousand is generous headroom and
 * still a hard ceiling. When it is reached the cache is cleared rather than evicted one at a
 * time: this is a memo, not a working set, and rebuilding a shape is cheap.
 */
private const val CACHE_LIMIT = 1000

class ExpressionEvaluator(
  /** Where unrecognised recipes and token names are recorded. See `Skew.kt`. */
  private val skew: SkewReport = SkewReport(),
) {
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

  private val texts = HashMap<JsonElement, String>()
  private var textLocale: String? = null
  private var textZone: String? = null

  /** Factory identifiers this client does not know. Telemetry, and the skew signal. */
  val unknownFactories: MutableSet<Int> get() = skew.unknownExpressionFactories

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
      COLOR_TOKEN -> {
        val name = args[1].jsonPrimitive.content
        palette.token(name) ?: run {
          skew.unknownColorTokens += name
          fallback
        }
      }
      else -> {
        if (factory != null) unknownFactories += factory
        fallback
      }
    }
    colors[expression] = built
    return built
  }

  /**
   * Renders a text recipe, in the locale the host is actually in.
   *
   * The memo is keyed on the recipe **and** the locale, because the same recipe produces a
   * different string in a different locale -- and it is cleared wholesale when the locale moves,
   * for the same reason the colour memo is cleared when the palette does. A locale change is rare
   * and a stale price is wrong in a way nobody would report as a formatting bug.
   *
   * @param fallback what an unknown factory renders as. Never a throw: a payload built against a
   *   newer dictionary must degrade.
   */
  fun text(
    expression: JsonElement,
    locale: String,
    timeZoneId: String,
    fallback: String = "",
  ): String {
    if (textLocale != locale || textZone != timeZoneId) {
      texts.clear()
      textLocale = locale
      textZone = timeZoneId
    }
    texts[expression]?.let { return it }
    if (texts.size >= CACHE_LIMIT) texts.clear()
    val args = expression.jsonArray
    fun arg(index: Int) = args.getOrNull(index)?.jsonPrimitive
    val built = when (val factory = arg(0)?.intOrNull) {
      TEXT_NUMBER -> {
        val formatted = formatNumber(
          arg(1)?.doubleOrNull ?: 0.0,
          locale,
          arg(2)?.takeIf { it !is JsonNull }?.intOrNull,
          arg(3)?.takeIf { it !is JsonNull }?.content,
        )
        // A guest-supplied pattern the platform would not accept. The value still renders, in the
        // locale's own form, and the payload's mistake surfaces as telemetry rather than as a
        // blank price nobody can explain.
        if (formatted.patternRejected) skew.rejectedNumberPatterns += arg(3)?.content.orEmpty()
        formatted.text
      }
      TEXT_CURRENCY -> formatCurrency(
        arg(1)?.longOrNull ?: 0L,
        arg(2)?.content.orEmpty(),
        locale,
      )
      TEXT_PERCENT -> formatPercent(
        arg(1)?.doubleOrNull ?: 0.0,
        locale,
        arg(2)?.takeIf { it !is JsonNull }?.intOrNull,
      )
      TEXT_DATE -> formatDateTime(arg(1)?.longOrNull ?: 0L, locale, timeZoneId, 0)
      TEXT_TIME -> formatDateTime(arg(1)?.longOrNull ?: 0L, locale, timeZoneId, 1)
      TEXT_DATE_TIME -> formatDateTime(arg(1)?.longOrNull ?: 0L, locale, timeZoneId, 2)
      TEXT_RELATIVE_TIME -> formatRelativeTime(
        arg(1)?.longOrNull ?: 0L,
        arg(2)?.longOrNull ?: 0L,
        locale,
      )
      // The words are the payload's; only the category is the host's, because only the category
      // needs locale data. `#` stands for the count, formatted for this locale.
      TEXT_PLURAL -> {
        val count = arg(1)?.intOrNull ?: 0
        val templates = args.getOrNull(2) as? kotlinx.serialization.json.JsonObject
        val category = pluralCategory(count, locale)
        val template = templates?.get(category)?.jsonPrimitive?.content
          ?: templates?.get("other")?.jsonPrimitive?.content
        if (template == null) {
          // A payload that translated no category at all. The count alone is more useful than
          // nothing, and the gap is recorded.
          skew.untranslatedPlurals += category
          formatNumber(count.toDouble(), locale, 0).text
        } else {
          template.replace("#", formatNumber(count.toDouble(), locale, 0).text)
        }
      }
      else -> {
        if (factory != null) unknownFactories += factory
        fallback
      }
    }
    texts[expression] = built
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

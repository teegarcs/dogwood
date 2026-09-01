/*
 * Project Dogwood -- host-resolved values, guest side.
 *
 * Some parameters are not values. `clip(RoundedCornerShape(8.dp))` does not pass a number; it
 * passes an object the host must construct. `background(Color(0xFF0770E3))` is the same. The
 * guest cannot construct either -- `Shape` and `Color` are host types with no serializable form,
 * and Compose's own implementations are `internal`.
 *
 * A deferred expression is therefore not a value but a **recipe**: a factory identifier and its
 * arguments, which the host evaluates. Roadmap.md requires this grammar to be settled jointly
 * with the modifier tag space, because modifier arguments are the first place it is needed and
 * Phase 3's generator consumes the settled pair.
 *
 * The wire form is a positional array, matching ADR-007: `[factory, arg, arg, ...]`.
 */
package dev.dogwood.compose

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Factory identifiers. A closed, versioned set; the host refuses anything it does not know. */
internal object ExpressionFactories {
  const val ROUNDED_CORNER = 1
  const val CIRCLE = 2
  const val COLOR_ARGB = 3
  const val COLOR_TOKEN = 4

  // Text the guest cannot produce. The pinned QuickJS ships no ECMA-402 `Intl`, so a guest has no
  // locale-aware number, currency or date formatting at all -- not a slow one, none.
  const val TEXT_NUMBER = 5
  const val TEXT_CURRENCY = 6
  const val TEXT_PERCENT = 7
  const val TEXT_DATE = 8
  const val TEXT_TIME = 9
  const val TEXT_DATE_TIME = 10
  const val TEXT_RELATIVE_TIME = 11
}

/**
 * The wire form of a host-resolved value: a factory and its arguments.
 *
 * `internal`, and that is the point of this revision. Guest code never names a recipe; it names a
 * [Shape], a [Color] or a piece of text, and those types happen to carry one. A public "expression"
 * type invited guest logic that branched on something only the host can compute.
 */
internal class Recipe(
  private val factory: Int,
  private val args: List<JsonElement>,
) {
  fun toJson(): JsonElement = JsonArray(listOf(JsonPrimitive(factory)) + args)

  override fun equals(other: Any?): Boolean =
    other is Recipe && other.factory == factory && other.args == args

  override fun hashCode(): Int = 31 * factory + args.hashCode()
}

/**
 * A shape the host builds.
 *
 * Compose's name, because it is Compose's concept. The guest cannot construct a real `Shape` --
 * Compose's own implementations are `internal` and have no serializable form -- so this names one
 * and the host builds it.
 */
class Shape internal constructor(private val recipe: Recipe) {
  /** The wire form. Every host-resolved type exposes this, so the generator has one rule. */
  internal val json: JsonElement get() = recipe.toJson()

  override fun equals(other: Any?): Boolean = other is Shape && other.recipe == recipe
  override fun hashCode(): Int = recipe.hashCode()

  companion object {
    fun roundedCorner(dp: Int): Shape =
      Shape(Recipe(ExpressionFactories.ROUNDED_CORNER, listOf(JsonPrimitive(dp))))

    val Circle: Shape = Shape(Recipe(ExpressionFactories.CIRCLE, emptyList()))
  }
}

/**
 * A colour the host resolves.
 *
 * Always a recipe, never a value, and the type says so: a colour depends on the environment it is
 * drawn in. [token] is the form to reach for -- it follows the host's palette, including dark mode,
 * which a literal cannot. `Color(0xFF0770E3)` is the deliberate opt-out, spelled exactly as it is
 * in Compose so that a literal reads the same in either world.
 */
class Color internal constructor(private val recipe: Recipe) {
  /** The wire form. Every host-resolved type exposes this, so the generator has one rule. */
  internal val json: JsonElement get() = recipe.toJson()

  override fun equals(other: Any?): Boolean = other is Color && other.recipe == recipe
  override fun hashCode(): Int = recipe.hashCode()

  companion object {
    /**
     * A named colour from the host's design system.
     *
     * The guest names an intent and the host resolves it in context. This is the whole
     * host-resolved rule in one call: the same name is a different colour in dark mode, and the
     * change costs no traffic.
     */
    fun token(name: String): Color =
      Color(Recipe(ExpressionFactories.COLOR_TOKEN, listOf(JsonPrimitive(name))))
  }
}

/**
 * A literal alpha-red-green-blue colour. Does not follow the theme; that is what choosing it means.
 *
 * Spelled exactly as Compose spells it, and a top-level function for the same reason Compose's is.
 */
fun Color(argb: Long): Color = Color(Recipe(ExpressionFactories.COLOR_ARGB, listOf(JsonPrimitive(argb))))

/**
 * Text only the host can produce.
 *
 * This is the deferred-expression grammar answering a problem the guest genuinely cannot solve
 * rather than one it merely should not: the pinned QuickJS (2021-03-27, via Zipline 1.27.0) ships
 * **no ECMA-402 `Intl`**, so there is no locale-aware number, currency, date or relative-time
 * formatting inside the sandbox at any price.
 *
 * A round-trip service was the obvious alternative and is the wrong one. Formatting is needed
 * *during composition*, once per value: six prices on a screen would be six suspending crossings
 * before anything could be drawn, and a guest that rendered placeholders while it waited would be
 * worse than one that could not format at all. A recipe costs nothing extra -- it rides the
 * property that was already crossing -- and the host formats at the moment it draws, in the locale
 * it is actually in.
 *
 * The consequence worth knowing: the *number* crosses, not the rendered string. A device that
 * switches from `en-US` to `ja-JP` re-renders these correctly with no traffic and no
 * recomposition of the guest at all.
 */
object Formats {
  private fun of(factory: Int, vararg args: JsonElement): TextValue =
    TextValue.recipe(Recipe(factory, args.toList()))

  /** @param maximumFractionDigits null lets the host's locale decide. */
  fun number(value: Double, maximumFractionDigits: Int? = null): TextValue =
    of(
      ExpressionFactories.TEXT_NUMBER,
      JsonPrimitive(value),
      maximumFractionDigits?.let(::JsonPrimitive) ?: JsonNull,
    )

  /**
   * @param minorUnits the amount in the currency's smallest unit -- cents, yen, fils. Integer on
   *   purpose: a price is not a floating-point quantity, and the host knows how many decimal
   *   places [currencyCode] actually has, which the guest does not.
   * @param currencyCode an ISO 4217 code, such as `USD` or `JPY`.
   */
  fun currency(minorUnits: Long, currencyCode: String): TextValue =
    of(ExpressionFactories.TEXT_CURRENCY, JsonPrimitive(minorUnits), JsonPrimitive(currencyCode))

  /** @param fraction 0.075 renders as 7.5% in a locale that writes it that way. */
  fun percent(fraction: Double, maximumFractionDigits: Int? = null): TextValue =
    of(
      ExpressionFactories.TEXT_PERCENT,
      JsonPrimitive(fraction),
      maximumFractionDigits?.let(::JsonPrimitive) ?: JsonNull,
    )

  fun date(epochMillis: Long): TextValue =
    of(ExpressionFactories.TEXT_DATE, JsonPrimitive(epochMillis))

  fun time(epochMillis: Long): TextValue =
    of(ExpressionFactories.TEXT_TIME, JsonPrimitive(epochMillis))

  fun dateTime(epochMillis: Long): TextValue =
    of(ExpressionFactories.TEXT_DATE_TIME, JsonPrimitive(epochMillis))

  /** "3 days ago", in the host's language. [nowMillis] comes from the host clock service. */
  fun relativeTime(epochMillis: Long, nowMillis: Long): TextValue =
    of(ExpressionFactories.TEXT_RELATIVE_TIME, JsonPrimitive(epochMillis), JsonPrimitive(nowMillis))
}

/**
 * Text that is either a literal or a recipe the host renders.
 *
 * The type that lets host-resolved text reach a design system's own components. A parameter typed
 * `String` can only ever carry a finished string, and a finished string is one somebody already
 * formatted -- deciding the currency symbol, the separators and the decimal places on behalf of
 * every device the payload will ever reach.
 *
 * Not named `Text`, because that call site would be ambiguous with the `Text(...)` composable.
 */
class TextValue internal constructor(internal val json: JsonElement) {
  override fun equals(other: Any?): Boolean = other is TextValue && other.json == json

  override fun hashCode(): Int = json.hashCode()

  override fun toString(): String = json.toString()

  internal companion object {
    fun recipe(recipe: Recipe): TextValue = TextValue(recipe.toJson())
  }
}

/**
 * A literal. Reads as a plain string at the call site and crosses as one.
 *
 * A top-level function named for its type, which is how Compose spells `Color(0xFF0770E3)` -- so
 * `TextValue("Explore")` and `Color(0xFF…)` are constructed the same way here as there.
 */
fun TextValue(literal: String): TextValue = TextValue(JsonPrimitive(literal))

/** Clips to a shape the host builds. */
fun Modifier.clip(shape: Shape): Modifier = then(ModifierTags.CLIP, shape.json)

/** Fills the background with a colour the host resolves. */
fun Modifier.background(color: Color): Modifier = then(ModifierTags.BACKGROUND, color.json)

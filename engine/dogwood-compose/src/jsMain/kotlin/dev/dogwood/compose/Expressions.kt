/*
 * Project Dogwood -- the deferred-expression grammar, guest side.
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
 * A recipe the host evaluates.
 *
 * Deliberately opaque: guest code can build one and pass it, and can do nothing else with it.
 * There is no `Shape` on this side to inspect, and pretending otherwise would invite guest logic
 * that branches on a value only the host can compute.
 */
class DogwoodExpression internal constructor(
  private val factory: Int,
  private val args: List<JsonElement>,
) {
  internal fun toJson(): JsonElement =
    JsonArray(listOf(JsonPrimitive(factory)) + args)

  override fun equals(other: Any?): Boolean =
    other is DogwoodExpression && other.factory == factory && other.args == args

  override fun hashCode(): Int = 31 * factory + args.hashCode()
}

/** Shapes the host can build. */
object Shapes {
  fun roundedCorner(dp: Int): DogwoodExpression =
    DogwoodExpression(ExpressionFactories.ROUNDED_CORNER, listOf(JsonPrimitive(dp)))

  val Circle: DogwoodExpression = DogwoodExpression(ExpressionFactories.CIRCLE, emptyList())
}

/** Colours the host can build. */
object Colors {
  /** A literal colour. The argument is an alpha-red-green-blue integer. */
  fun argb(value: Long): DogwoodExpression =
    DogwoodExpression(ExpressionFactories.COLOR_ARGB, listOf(JsonPrimitive(value)))

  /**
   * A named colour from the host's design system.
   *
   * Preferred over [argb] for anything the design system owns: a token follows the host's theme,
   * including dark mode, where a literal cannot. This is the deferred-expression protocol earning
   * its keep -- the guest names an intent and the host resolves it in context.
   */
  fun token(name: String): DogwoodExpression =
    DogwoodExpression(ExpressionFactories.COLOR_TOKEN, listOf(JsonPrimitive(name)))
}

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
  /** @param maximumFractionDigits null lets the host's locale decide. */
  fun number(value: Double, maximumFractionDigits: Int? = null): DogwoodExpression =
    DogwoodExpression(
      ExpressionFactories.TEXT_NUMBER,
      listOf(JsonPrimitive(value), maximumFractionDigits?.let(::JsonPrimitive) ?: JsonNull),
    )

  /**
   * @param minorUnits the amount in the currency's smallest unit -- cents, yen, fils. Integer on
   *   purpose: a price is not a floating-point quantity, and the host knows how many decimal
   *   places [currencyCode] actually has, which the guest does not.
   * @param currencyCode an ISO 4217 code, such as `USD` or `JPY`.
   */
  fun currency(minorUnits: Long, currencyCode: String): DogwoodExpression =
    DogwoodExpression(
      ExpressionFactories.TEXT_CURRENCY,
      listOf(JsonPrimitive(minorUnits), JsonPrimitive(currencyCode)),
    )

  /** @param fraction 0.075 renders as 7.5% in a locale that writes it that way. */
  fun percent(fraction: Double, maximumFractionDigits: Int? = null): DogwoodExpression =
    DogwoodExpression(
      ExpressionFactories.TEXT_PERCENT,
      listOf(JsonPrimitive(fraction), maximumFractionDigits?.let(::JsonPrimitive) ?: JsonNull),
    )

  fun date(epochMillis: Long): DogwoodExpression =
    DogwoodExpression(ExpressionFactories.TEXT_DATE, listOf(JsonPrimitive(epochMillis)))

  fun time(epochMillis: Long): DogwoodExpression =
    DogwoodExpression(ExpressionFactories.TEXT_TIME, listOf(JsonPrimitive(epochMillis)))

  fun dateTime(epochMillis: Long): DogwoodExpression =
    DogwoodExpression(ExpressionFactories.TEXT_DATE_TIME, listOf(JsonPrimitive(epochMillis)))

  /** "3 days ago", in the host's language. [nowMillis] comes from the host clock service. */
  fun relativeTime(epochMillis: Long, nowMillis: Long): DogwoodExpression =
    DogwoodExpression(
      ExpressionFactories.TEXT_RELATIVE_TIME,
      listOf(JsonPrimitive(epochMillis), JsonPrimitive(nowMillis)),
    )
}

/** Clips to a shape the host builds. */
fun DogwoodModifier.clip(shape: DogwoodExpression): DogwoodModifier =
  then(ModifierTags.CLIP, shape.toJson())

/** Fills the background with a colour the host builds. */
fun DogwoodModifier.background(color: DogwoodExpression): DogwoodModifier =
  then(ModifierTags.BACKGROUND, color.toJson())

/*
 * Project Dogwood -- the guest's units, for the generated library tiers.
 *
 * Generator v1 surfaces use `Int` density-independent pixels by convention (`sizeDp`,
 * `cornerRadiusDp`), because a curated surface can. A library signature says `Dp`, `TextUnit`
 * and `PaddingValues`, and a stub that mirrors it has to accept the same shapes -- `16.dp`,
 * `14.sp`, `PaddingValues(horizontal = 8.dp)` -- or the code a Compose developer already writes
 * does not compile against it. These are Dogwood's own types with the same spelling
 * (plans/generator-v2.md, D-E); the wire carries numbers, and the host rebuilds the real ones.
 */
@file:OptIn(DogwoodGeneratedApi::class)

package dev.dogwood.compose

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Density-independent pixels. Crosses as a number; `Dp.Unspecified` crosses as nothing. */
value class Dp(val value: Float) {
  companion object {
    /** Not sent. Absence is the host-default sentinel everywhere, and this is its spelling for a size. */
    val Unspecified: Dp = Dp(Float.NaN)
  }
  val isSpecified: Boolean get() = !value.isNaN()
}

val Int.dp: Dp get() = Dp(toFloat())
val Float.dp: Dp get() = Dp(this)
val Double.dp: Dp get() = Dp(toFloat())

/**
 * Scaled or em-relative text size.
 *
 * `[value, "sp"]` or `[value, "em"]` on the wire: a single number would lose the unit, and a host
 * that guessed would guess wrong for the em case exactly where it matters, in a line-height.
 */
class TextUnit private constructor(val value: Float, val unit: String) {
  val json: JsonElement get() = JsonArray(listOf(JsonPrimitive(value), JsonPrimitive(unit)))
  override fun equals(other: Any?): Boolean = other is TextUnit && other.value == value && other.unit == unit
  override fun hashCode(): Int = 31 * value.hashCode() + unit.hashCode()
  override fun toString(): String = "$value.$unit"

  companion object {
    internal fun sp(value: Float) = TextUnit(value, "sp")
    internal fun em(value: Float) = TextUnit(value, "em")
  }
}

val Int.sp: TextUnit get() = TextUnit.sp(toFloat())
val Float.sp: TextUnit get() = TextUnit.sp(this)
val Double.sp: TextUnit get() = TextUnit.sp(toFloat())
val Float.em: TextUnit get() = TextUnit.em(this)
val Int.em: TextUnit get() = TextUnit.em(toFloat())

/** Four sides, as `[start, top, end, bottom]` in density-independent pixels. */
class PaddingValues(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp) {
  val json: JsonElement get() = JsonArray(listOf(start, top, end, bottom).map { JsonPrimitive(it.value) })
  override fun equals(other: Any?): Boolean =
    other is PaddingValues && other.start == start && other.top == top && other.end == end && other.bottom == bottom
  override fun hashCode(): Int = listOf(start, top, end, bottom).hashCode()
  override fun toString(): String = "PaddingValues($start, $top, $end, $bottom)"
}

fun PaddingValues(all: Dp = 0.dp): PaddingValues = PaddingValues(all, all, all, all)

/**
 * A border's width and colour, which Compose bundles into one parameter.
 *
 * Two values rather than a host-resolved recipe, because neither half needs the host to decide
 * anything a guest could not: the width is a number and the colour is already a recipe the host
 * resolves. The wire form is `[widthDp, colourRecipe]`.
 */
class BorderStroke(val width: Dp, val color: Color) {
  @DogwoodGeneratedApi
  val json: JsonElement get() = JsonArray(listOf(JsonPrimitive(width.value), color.json))
  override fun equals(other: Any?): Boolean =
    other is BorderStroke && other.width == width && other.color == color
  override fun hashCode(): Int = 31 * width.hashCode() + color.hashCode()
  override fun toString(): String = "BorderStroke($width, $color)"
}

/**
 * A closed range of numbers, which is how Compose spells a slider's bounds.
 *
 * Kotlin's own `ClosedFloatingPointRange<Float>` cannot cross -- it is an interface with no
 * serializable form -- and `0f..1f` on the guest would be a range object the recorder cannot
 * encode. This is the two numbers it always was. The wire form is `[start, end]`.
 */
class FloatRange(val start: Float, val end: Float) {
  @DogwoodGeneratedApi
  val json: JsonElement get() = JsonArray(listOf(JsonPrimitive(start), JsonPrimitive(end)))
  override fun equals(other: Any?): Boolean =
    other is FloatRange && other.start == start && other.end == end
  override fun hashCode(): Int = 31 * start.hashCode() + end.hashCode()
  override fun toString(): String = "FloatRange($start, $end)"
}

/** `0f rangeTo 1f`, so a payload reads the way Compose does. */
infix fun Float.rangeTo(other: Float): FloatRange = FloatRange(this, other)
fun PaddingValues(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): PaddingValues =
  PaddingValues(horizontal, vertical, horizontal, vertical)

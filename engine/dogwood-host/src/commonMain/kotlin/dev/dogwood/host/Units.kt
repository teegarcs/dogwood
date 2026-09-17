/*
 * Project Dogwood -- reading the guest's units back into Compose's.
 *
 * The guest's `Dp`, `TextUnit` and `PaddingValues` are Dogwood's own types with Compose's spelling
 * (plans/generator-v2.md, D-E); on the wire they are a number, a `[value, unit]` pair and a
 * four-number array. These readers hand a generated binding the real `androidx` value, and
 * follow the rule every reader here follows: absence is the host-default sentinel, so every one
 * has an `OrNull` form and the binding decides what absence means.
 */
package dev.dogwood.host

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

fun WidgetView.dpOrNull(tag: Int): Dp? = floatOrNull(tag)?.dp

fun WidgetView.dp(tag: Int, default: Dp): Dp = dpOrNull(tag) ?: default

fun WidgetView.textUnitOrNull(tag: Int): TextUnit? {
  val array = property(tag) as? JsonArray ?: return null
  val value = array.getOrNull(0)?.jsonPrimitive?.floatOrNull ?: return null
  return when ((array.getOrNull(1) as? JsonPrimitive)?.content) {
    "em" -> value.em
    // `sp` is the guest's default and the only other unit it can name. An unknown unit reads as
    // scaled pixels rather than as nothing, because a size that arrived is a size the author set.
    else -> value.sp
  }
}

fun WidgetView.textUnit(tag: Int, default: TextUnit): TextUnit = textUnitOrNull(tag) ?: default

// Composable because a clamp is reported into the skew report, which lives in the composition.
@Composable
fun WidgetView.paddingValuesOrNull(tag: Int): PaddingValues? {
  val array = property(tag) as? JsonArray ?: return null
  if (array.size < 4) return null
  val sides = array.take(4).map { it.jsonPrimitive.floatOrNull ?: 0f }
  // Negative padding throws inside layout; a payload delivered over the air must not be able to
  // take a screen down with an off-by-one. Clamped and reported, as every other clamp is.
  val clamped = sides.map { clampModifierValue(it, min = 0f, what = "paddingValues") }
  return PaddingValues(start = clamped[0].dp, top = clamped[1].dp, end = clamped[2].dp, bottom = clamped[3].dp)
}

@Composable
fun WidgetView.paddingValues(tag: Int, default: PaddingValues): PaddingValues = paddingValuesOrNull(tag) ?: default

/**
 * A border's width and colour: `[widthDp, colourRecipe]`.
 *
 * The colour goes through the expression evaluator like every other colour, so a border follows
 * the host's palette and its dark mode rather than freezing whatever the payload's author saw.
 */
@Composable
fun WidgetView.borderStrokeOrNull(tag: Int): BorderStroke? {
  val array = property(tag) as? JsonArray ?: return null
  if (array.size < 2) return null
  val width = array[0].jsonPrimitive.floatOrNull ?: return null
  val colour = resolveColor(array[1].jsonArray)
  return BorderStroke(clampModifierValue(width, min = 0f, what = "borderStroke").dp, colour)
}

/**
 * A closed range of numbers: `[start, end]`.
 *
 * Reversed bounds are a crash inside Compose's own layout rather than a strange-looking slider, so
 * they are ordered here and reported, the way every other hostile value is. A payload delivered
 * over the air must not be able to take a screen down with two numbers in the wrong order.
 */
@Composable
fun WidgetView.floatRangeOrNull(tag: Int): ClosedFloatingPointRange<Float>? {
  val array = property(tag) as? JsonArray ?: return null
  if (array.size < 2) return null
  val start = array[0].jsonPrimitive.floatOrNull ?: return null
  val end = array[1].jsonPrimitive.floatOrNull ?: return null
  if (start <= end) return start..end
  LocalSkewReport.current.clampedValues += "floatRange $start..$end is reversed; ordered"
  return end..start
}

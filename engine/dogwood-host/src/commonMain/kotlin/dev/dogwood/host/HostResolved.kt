/*
 * Project Dogwood -- reading a property the host resolves.
 *
 * The readers in `WidgetView.kt` are plain functions: a property is a primitive and they hand it
 * back. These cannot be, because a host-resolved property is a **recipe** -- a factory and its
 * arguments -- and turning one into a value needs the environment in force: the palette for a
 * colour, the locale and time zone for text. That makes them composable, and it makes them
 * re-resolve for free when the environment moves.
 *
 * One property tag carries either form. A literal crosses as a JavaScript Object Notation
 * primitive and a recipe as an array, which are unambiguous, so no protocol change was needed to
 * let a design system's own components accept either.
 */
package dev.dogwood.host

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import androidx.compose.runtime.getValue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.dogwood.protocol.ExpressionFactories

private fun JsonElement.literalOrNull(): String? =
  (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/**
 * Reads text that may be a literal or a formatting recipe.
 *
 * The recipe form is the one the sandbox cannot produce for itself: the pinned QuickJS ships no
 * ECMA-402 `Intl`, so money, dates and decimal separators have to be rendered here. The *number*
 * crossed, not the rendered string, so a device that changes locale re-renders correctly with no
 * traffic and no guest recomposition.
 */
@Composable
fun WidgetView.text(tag: Int, default: String = ""): String =
  textOrNull(tag) ?: default

/** As [text], but absence stays absent -- the host-default sentinel, preserved. */
@Composable
fun WidgetView.textOrNull(tag: Int): String? {
  val raw = property(tag) ?: return null
  if (raw is JsonArray) {
    val format = formatContext()
    return LocalExpressionEvaluator.current.text(raw, format.locale, format.timeZoneId)
  }
  return raw.literalOrNull()
}

/**
 * Reads a colour the guest named.
 *
 * Always a recipe on the wire -- there is no literal-string form of a colour -- because a colour
 * depends on the palette it is drawn in. A token re-resolves on a theme change; an alpha-red-
 * green-blue literal deliberately does not, which is what choosing a literal means.
 */
@Composable
fun WidgetView.color(tag: Int, default: Color): Color = colorOrNull(tag) ?: default

@Composable
fun WidgetView.colorOrNull(tag: Int): Color? {
  val raw = property(tag) as? JsonArray ?: return null
  return resolveColor(raw)
}

/**
 * Resolves a colour recipe, animating if the recipe says to.
 *
 * Shared by the property readers and the `background` modifier, so an animated colour behaves the
 * same wherever a colour is accepted. The animated form wraps an ordinary colour recipe, so the
 * *target* is resolved against the palette first -- which means a theme flip mid-flight retargets
 * rather than jumping, exactly as a changed target does.
 */
@Composable
fun resolveColor(raw: JsonArray): Color {
  val evaluator = LocalExpressionEvaluator.current
  val palette = palette()
  if (raw.firstOrNull()?.jsonPrimitive?.intOrNull != ExpressionFactories.ANIMATED_COLOR) {
    return evaluator.color(raw, palette)
  }
  val target = (raw.getOrNull(1) as? JsonArray)?.let { evaluator.color(it, palette) }
    ?: palette.ink
  val animated by androidx.compose.animation.animateColorAsState(
    targetValue = target,
    animationSpec = animationSpecOf<Color>(raw.getOrNull(2)),
    label = "dogwood-colour",
  )
  return animated
}


/** Reads a shape the guest named. Unknown factories degrade, as everywhere else. */
@Composable
fun WidgetView.shape(tag: Int, default: Shape): Shape = shapeOrNull(tag) ?: default

@Composable
fun WidgetView.shapeOrNull(tag: Int): Shape? {
  val raw = property(tag) as? JsonArray ?: return null
  return LocalExpressionEvaluator.current.shape(raw)
}

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
import kotlinx.serialization.json.JsonPrimitive

/** Factory identifiers. A closed, versioned set; the host refuses anything it does not know. */
internal object ExpressionFactories {
  const val ROUNDED_CORNER = 1
  const val CIRCLE = 2
  const val COLOR_ARGB = 3
  const val COLOR_TOKEN = 4
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

/** Clips to a shape the host builds. */
fun DogwoodModifier.clip(shape: DogwoodExpression): DogwoodModifier =
  then(ModifierTags.CLIP, shape.toJson())

/** Fills the background with a colour the host builds. */
fun DogwoodModifier.background(color: DogwoodExpression): DogwoodModifier =
  then(ModifierTags.BACKGROUND, color.toJson())

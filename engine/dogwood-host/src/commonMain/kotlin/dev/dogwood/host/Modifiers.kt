/*
 * Project Dogwood -- host-side modifier reconstruction.
 *
 * Compose's own `Modifier.Element` implementations are `internal`, so a guest cannot serialize
 * one. Dogwood therefore defines its own tagged type and reconstructs a real Compose modifier
 * here. This is the Phase 2 subsystem in embryo: it carries only modifiers whose arguments are
 * primitives and value classes, which is exactly the scope roadmap.md gives Phase 2. Modifiers
 * with expression arguments -- `clip(RoundedCornerShape(8.dp))`, `background(brush)` -- need
 * the deferred-expression grammar and are absent by design rather than by oversight.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.dogwood.protocol.ModifierTags
import dev.dogwood.protocol.ExpressionFactories


/**
 * The factory identifier of an animated value, and the event tag base its completion is reported
 * under.
 *
 * The tag is derived from the element's position in the chain rather than allocated, because both
 * sides walk the same ordered chain and the index is therefore an identifier they already agree
 * on. Nothing extra crosses.
 */
private const val ANIMATION_EVENT_BASE = 1000

/**
 * Rebuilds a node's modifier chain, in order.
 *
 * Order is load-bearing: `padding(8).size(48)` and `size(48).padding(8)` produce different
 * layouts, so the chain is replayed exactly as the guest recorded it.
 *
 * [events] has no default, deliberately. An animated argument may declare a completion, and a
 * binding that quietly passed no sink would leave that completion silently never firing -- the
 * kind of defect that is invisible until somebody wonders why a screen never advances.
 *
 * `weight` is a scope function on `RowScope` and `ColumnScope` rather than a free modifier,
 * which is why [LayoutScope] has to be threaded down to every child. A weight applied outside
 * a row or column has no meaning; here it is skipped, and Phase 2 is required to turn that into
 * a build error at the call site rather than a silent drop at runtime.
 */
@Composable
fun WidgetView.composeModifier(scope: LayoutScope, events: EventSink): Modifier {
  val evaluator = LocalExpressionEvaluator.current
  val palette = LocalPalette.current
  var modifier: Modifier = Modifier
  for ((index, element) in modifiers.withIndex()) {
    // Expression arguments are arrays, not primitives, so the primitive view is read lazily.
    val value by lazy(LazyThreadSafetyMode.NONE) { element.v.jsonPrimitive }

    /**
     * A number that may be a target rather than a value.
     *
     * Keyed on the position and the tag, because these hold `remember`ed animation state and the
     * chain can change length between recompositions -- an unkeyed call in a loop would hand one
     * element's in-flight animation to another.
     */
    @Composable
    fun number(fallback: Float): Float =
      androidx.compose.runtime.key(index, element.t.value) {
        animatedNumber(element.v, index, this@composeModifier, events, fallback)
      }

    modifier = when (element.t.local) {
      ModifierTags.PADDING ->
        modifier.padding(clampModifierValue(number(0f), min = 0f, what = "padding").dp)
      ModifierTags.FILL_MAX_WIDTH -> modifier.fillMaxWidth(value.floatOrNull ?: 1f)
      ModifierTags.WEIGHT -> {
        // `weight` requires a positive value; zero and negatives throw.
        val weight = clampModifierValue(value.floatOrNull ?: 1f, min = Float.MIN_VALUE, what = "weight")
        when {
          scope.row != null -> with(scope.row) { modifier.weight(weight) }
          scope.column != null -> with(scope.column) { modifier.weight(weight) }
          // Unreachable from a well-formed guest: the guest's scope types make an out-of-scope
          // weight a compile error. Kept, and kept silent, because a host must tolerate a guest
          // built against a different dictionary rather than crash on one.
          else -> modifier
        }
      }
      ModifierTags.ALIGN -> {
        val ordinal = value.intOrNull ?: 0
        when {
          scope.row != null -> with(scope.row) { modifier.align(verticalAlignment(ordinal)) }
          scope.column != null -> with(scope.column) { modifier.align(horizontalAlignment(ordinal)) }
          else -> modifier
        }
      }
      // Deferred expressions: the argument is a recipe, not a value, and the host builds it.
      ModifierTags.CLIP -> modifier.clip(evaluator.shape(element.v))
      // Through the shared resolver, so `background` accepts an animated colour on exactly the
      // same terms as `Icon`'s tint does.
      ModifierTags.BACKGROUND -> modifier.background(
        (element.v as? kotlinx.serialization.json.JsonArray)
          ?.let { androidx.compose.runtime.key(index, element.t.value) { resolveColor(it) } }
          ?: evaluator.color(element.v, palette),
      )
      ModifierTags.SIZE ->
        modifier.size(clampModifierValue(number(0f), min = 0f, what = "size").dp)
      ModifierTags.WIDTH ->
        modifier.width(clampModifierValue(number(0f), min = 0f, what = "width").dp)
      ModifierTags.HEIGHT ->
        modifier.height(clampModifierValue(number(0f), min = 0f, what = "height").dp)
      ModifierTags.ALPHA -> modifier.alpha(number(1f))
      ModifierTags.ROTATE -> modifier.rotate(number(0f))
      ModifierTags.SCALE -> modifier.scale(number(1f))
      else -> modifier
    }
  }
  return modifier
}


/** Alignments cross as an ordinal; these are the guest enumerations in declaration order. */
private fun verticalAlignment(ordinal: Int): Alignment.Vertical = when (ordinal) {
  0 -> Alignment.Top
  2 -> Alignment.Bottom
  else -> Alignment.CenterVertically
}

private fun horizontalAlignment(ordinal: Int): Alignment.Horizontal = when (ordinal) {
  0 -> Alignment.Start
  2 -> Alignment.End
  else -> Alignment.CenterHorizontally
}

/**
 * Reads a modifier argument that may be a plain number or a **target** the host animates towards.
 *
 * This is the whole animation subsystem in one function, and the reason it can be one function is
 * that the shape was chosen to make Compose do the hard parts:
 *
 *   - **Interruption is free.** `animateFloatAsState` retargets from the current value when its
 *     target changes. A protocol that sent "start an animation" would have had to define that rule
 *     and would have got it wrong; this inherits the behaviour a Compose developer already expects.
 *   - **Nothing ticks the boundary.** The guest sent one property when the target changed. Every
 *     frame between here and there is host work the guest never sees, which is the Layer 4
 *     invariant holding rather than being worked around.
 *   - **Completion is an ordinary event**, on a tag derived from the element's position, sent once
 *     on arrival. A retarget is not a completion: Compose's finished listener does not fire for an
 *     interrupted animation, and reporting one would make "finished" mean two different things.
 */
@Composable
private fun animatedNumber(
  raw: JsonElement,
  index: Int,
  node: WidgetView,
  events: EventSink,
  fallback: Float,
): Float {
  val array = raw as? kotlinx.serialization.json.JsonArray
    ?: return raw.jsonPrimitive.floatOrNull ?: fallback

  val factory = array.firstOrNull()?.jsonPrimitive?.intOrNull
  if (factory == ExpressionFactories.OSCILLATE) return oscillating(array, index, node, events)
  if (factory != ExpressionFactories.ANIMATED_NUMBER) return fallback

  val target = array.getOrNull(1)?.jsonPrimitive?.floatOrNull ?: fallback
  val spec = animationSpecOf<Float>(array.getOrNull(2))
  val notify = array.getOrNull(3)?.jsonPrimitive?.booleanOrNull ?: false

  // `rememberUpdatedState` for the same reason the viewport reporter needs it: this listener
  // outlives the composition that created it, and the sink belongs to a particular experience.
  val currentEvents by androidx.compose.runtime.rememberUpdatedState(events)
  val currentNode by androidx.compose.runtime.rememberUpdatedState(node)

  val animated by androidx.compose.animation.core.animateFloatAsState(
    targetValue = target,
    animationSpec = spec,
    label = "dogwood-modifier-$index",
    finishedListener = {
      if (notify) {
        currentEvents.send(currentNode, dev.dogwood.protocol.EventTag(ANIMATION_EVENT_BASE + index))
      }
    },
  )
  return animated
}

/**
 * Builds an animation specification from a recipe.
 *
 * Named parts, resolved here. A guest sending Compose's own stiffness constants as numbers would
 * be asserting a physical unit it has no way to check; a name is something the host can refuse.
 */
internal fun <T> animationSpecOf(
  raw: JsonElement?,
): androidx.compose.animation.core.FiniteAnimationSpec<T> {
  val array = raw as? kotlinx.serialization.json.JsonArray
    ?: return androidx.compose.animation.core.tween()
  return when (array.firstOrNull()?.jsonPrimitive?.intOrNull) {
    1 -> androidx.compose.animation.core.tween(
      durationMillis = array.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 300,
      delayMillis = array.getOrNull(3)?.jsonPrimitive?.intOrNull ?: 0,
      easing = easingOf(array.getOrNull(2)?.jsonPrimitive?.content),
    )
    2 -> androidx.compose.animation.core.spring(
      dampingRatio = dampingOf(array.getOrNull(2)?.jsonPrimitive?.content),
      stiffness = stiffnessOf(array.getOrNull(1)?.jsonPrimitive?.content),
    )
    3 -> androidx.compose.animation.core.snap()
    else -> androidx.compose.animation.core.tween()
  }
}

internal fun easingOf(name: String?): androidx.compose.animation.core.Easing = when (name) {
  "linear" -> androidx.compose.animation.core.LinearEasing
  "fastOutLinearIn" -> androidx.compose.animation.core.FastOutLinearInEasing
  "linearOutSlowIn" -> androidx.compose.animation.core.LinearOutSlowInEasing
  // Including the unknown case: an easing this client does not carry is skew, and motion that is
  // slightly wrong beats motion that throws.
  else -> androidx.compose.animation.core.FastOutSlowInEasing
}

private fun stiffnessOf(name: String?): Float = when (name) {
  "veryLow" -> androidx.compose.animation.core.Spring.StiffnessVeryLow
  "low" -> androidx.compose.animation.core.Spring.StiffnessLow
  "high" -> androidx.compose.animation.core.Spring.StiffnessHigh
  else -> androidx.compose.animation.core.Spring.StiffnessMedium
}

private fun dampingOf(name: String?): Float = when (name) {
  "lowBouncy" -> androidx.compose.animation.core.Spring.DampingRatioLowBouncy
  "mediumBouncy" -> androidx.compose.animation.core.Spring.DampingRatioMediumBouncy
  "highBouncy" -> androidx.compose.animation.core.Spring.DampingRatioHighBouncy
  else -> androidx.compose.animation.core.Spring.DampingRatioNoBouncy
}

/**
 * A value travelling between two points, repeatedly.
 *
 * Two host primitives, split on finiteness, and the split is not cosmetic. A spike established
 * that `animateFloatAsState` cannot express a repeat at all: it moves only when its *target*
 * changes, so a pulse toward the value it already holds is a no-op that type-checks. Both branches
 * below therefore take an explicit range.
 *
 *   - **Infinite** uses `rememberInfiniteTransition`, which exists for exactly this and stops when
 *     it leaves the composition. It never reports a completion, and the guest API refuses to let
 *     one be asked for.
 *   - **Finite** drives an `Animatable` from an effect keyed on the recipe, so a changed recipe
 *     cancels the old run -- which is the interruption rule, inherited rather than invented, as in
 *     [ADR-020](../../../../../../adrs/layer-5/ADR-020-animation.md).
 */
@Composable
private fun oscillating(
  array: kotlinx.serialization.json.JsonArray,
  index: Int,
  node: WidgetView,
  events: EventSink,
): Float {
  val from = array.getOrNull(1)?.jsonPrimitive?.floatOrNull ?: 0f
  val to = array.getOrNull(2)?.jsonPrimitive?.floatOrNull ?: from
  val iterations = array.getOrNull(3)?.jsonPrimitive?.intOrNull ?: 0
  val reverse = array.getOrNull(4)?.jsonPrimitive?.booleanOrNull ?: true
  val spec = durationSpecOf(array.getOrNull(5))
  val notify = array.getOrNull(6)?.jsonPrimitive?.booleanOrNull ?: false
  val mode = if (reverse) {
    androidx.compose.animation.core.RepeatMode.Reverse
  } else {
    androidx.compose.animation.core.RepeatMode.Restart
  }

  if (iterations == 0) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(
      label = "dogwood-oscillate-$index",
    )
    val value by transition.animateFloat(
      initialValue = from,
      targetValue = to,
      animationSpec = androidx.compose.animation.core.infiniteRepeatable(spec, mode),
      label = "dogwood-oscillate-value",
    )
    return value
  }

  val animatable = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(from) }
  val currentEvents by androidx.compose.runtime.rememberUpdatedState(events)
  val currentNode by androidx.compose.runtime.rememberUpdatedState(node)
  androidx.compose.runtime.LaunchedEffect(array) {
    animatable.snapTo(from)
    animatable.animateTo(to, androidx.compose.animation.core.repeatable(iterations, spec, mode))
    if (notify) {
      currentEvents.send(currentNode, dev.dogwood.protocol.EventTag(ANIMATION_EVENT_BASE + index))
    }
  }
  return animatable.value
}

/**
 * A specification that has a duration, which repeating requires.
 *
 * A spring does not have one -- it settles when the physics say so -- so a repeating spring is not
 * expressible and degrades to a tween rather than failing. Said here because the degradation is
 * silent and somebody will eventually wonder why their bouncy pulse is not bouncy.
 */
private fun durationSpecOf(
  raw: JsonElement?,
): androidx.compose.animation.core.DurationBasedAnimationSpec<Float> {
  val array = raw as? kotlinx.serialization.json.JsonArray
    ?: return androidx.compose.animation.core.tween()
  return when (array.firstOrNull()?.jsonPrimitive?.intOrNull) {
    1 -> androidx.compose.animation.core.tween(
      durationMillis = array.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 300,
      delayMillis = array.getOrNull(3)?.jsonPrimitive?.intOrNull ?: 0,
      easing = easingOf(array.getOrNull(2)?.jsonPrimitive?.content),
    )
    3 -> androidx.compose.animation.core.snap()
    else -> androidx.compose.animation.core.tween()
  }
}

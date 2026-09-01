/*
 * Project Dogwood -- animation.
 *
 * roadmap.md calls this "the largest addition from re-review", and Layer 4 explains why in one
 * line: **no per-frame state in the guest**. The whole `animate*AsState` / `updateTransition` /
 * `Animatable` surface is per-frame state by construction, so none of it can be bridged. Until
 * this existed, Layer 1 had to reject those APIs outright and the product promise "animation
 * without a release" was not true.
 *
 * The replacement is the sentence the roadmap already wrote: **declare a target, the host runs
 * it.** It is the same shape as a scroll target ([ADR-014](…/ADR-014-live-state-holders.md)) and
 * the same shape as a formatting recipe ([ADR-017](…/ADR-017-resources-and-assets.md)) -- the guest
 * names what it wants and the host, which owns the frame clock, gets there.
 *
 * Four things the roadmap asked for, and where each one lives:
 *
 *   - **Declarative targets.** An animated modifier argument is a recipe carrying a target and a
 *     spec instead of a number.
 *   - **Springs and easings.** Named, like every other token, so adding one is an addition rather
 *     than a renumbering.
 *   - **Interruption semantics.** Free, and that is the point of choosing this shape: the host
 *     evaluates the recipe with `animateFloatAsState`, whose defined behaviour when the target
 *     changes mid-flight is to *retarget from the current value*. A protocol that sent "start an
 *     animation" would have had to invent that rule and get it wrong.
 *   - **Completion events.** Carried on the element's **position in the chain**. Both sides walk
 *     the same ordered chain, so the index is an identifier both already agree on -- no allocation,
 *     no registry, nothing extra on the wire.
 *
 * What still crosses per animation is **one property set when the target changes**, not one per
 * frame. A fade from zero to one is one crossing, whatever its duration.
 */
package dev.dogwood.compose

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private const val SPEC_TWEEN = 1
private const val SPEC_SPRING = 2
private const val SPEC_SNAP = 3

/** How the host should travel to a target. Named parts, so the set can grow additively. */
class AnimationSpec internal constructor(internal val json: JsonElement)

object Animations {
  /**
   * @param easing a named curve the host owns: `linear`, `fastOutSlowIn`, `fastOutLinearIn`,
   *   `linearOutSlowIn`. An unknown name falls back to `fastOutSlowIn` and is reported as skew.
   */
  fun tween(
    durationMs: Int = 300,
    easing: String = "fastOutSlowIn",
    delayMs: Int = 0,
  ): AnimationSpec = AnimationSpec(
    JsonArray(
      listOf(
        JsonPrimitive(SPEC_TWEEN),
        JsonPrimitive(durationMs),
        JsonPrimitive(easing),
        JsonPrimitive(delayMs),
      ),
    ),
  )

  /**
   * @param stiffness `veryLow`, `low`, `medium`, `high`.
   * @param damping `noBouncy`, `lowBouncy`, `mediumBouncy`, `highBouncy`.
   *
   * Named rather than numeric because the numbers are Compose's own constants, and a guest sending
   * `1500.0` would be asserting a physical unit it has no way to check.
   */
  fun spring(
    stiffness: String = "medium",
    damping: String = "noBouncy",
  ): AnimationSpec = AnimationSpec(
    JsonArray(listOf(JsonPrimitive(SPEC_SPRING), JsonPrimitive(stiffness), JsonPrimitive(damping))),
  )

  /** No animation. Useful for turning one off without changing the shape of the call site. */
  val Snap: AnimationSpec =
    AnimationSpec(JsonArray(listOf(JsonPrimitive(SPEC_SNAP))))
}

/**
 * A value the host animates towards.
 *
 * Opaque, like every other recipe: guest code can declare one and pass it, and can do nothing else
 * with it. There is deliberately no way to read the current value -- that is per-frame state, it
 * lives on the host, and a guest that could read it would immediately be written as if it could
 * poll it.
 */
class AnimationTarget internal constructor(
  internal val target: JsonElement,
  internal val spec: AnimationSpec,
  internal val onFinished: (() -> Unit)?,
) {
  internal fun toJson(notify: Boolean): JsonElement = JsonArray(
    listOf(
      JsonPrimitive(ANIMATED_NUMBER),
      target,
      spec.json,
      JsonPrimitive(notify),
    ),
  )

  internal companion object {
    const val ANIMATED_NUMBER = 12
  }
}

/**
 * Declares a target for the host to animate to.
 *
 * @param onFinished called once the host arrives. Not called if the target changes on the way --
 *   that is a retarget, not a completion, and reporting it would make "finished" mean two
 *   different things.
 */
fun animate(
  target: Float,
  spec: AnimationSpec = Animations.tween(),
  onFinished: (() -> Unit)? = null,
): AnimationTarget = AnimationTarget(JsonPrimitive(target), spec, onFinished)

/** As [animate], for density-independent pixels. */
fun animateDp(
  target: Int,
  spec: AnimationSpec = Animations.tween(),
  onFinished: (() -> Unit)? = null,
): AnimationTarget = AnimationTarget(JsonPrimitive(target), spec, onFinished)

private fun Modifier.animated(tag: Int, value: AnimationTarget): Modifier =
  then(tag, value.toJson(notify = value.onFinished != null), value.onFinished)

/** Fades to [alpha]. Interrupting with a new target retargets from wherever it currently is. */
fun Modifier.alpha(alpha: AnimationTarget): Modifier =
  animated(ModifierTags.ALPHA, alpha)

fun Modifier.rotate(degrees: AnimationTarget): Modifier =
  animated(ModifierTags.ROTATE, degrees)

fun Modifier.scale(scale: AnimationTarget): Modifier =
  animated(ModifierTags.SCALE, scale)

fun Modifier.height(dp: AnimationTarget): Modifier =
  animated(ModifierTags.HEIGHT, dp)

fun Modifier.width(dp: AnimationTarget): Modifier =
  animated(ModifierTags.WIDTH, dp)

fun Modifier.size(dp: AnimationTarget): Modifier =
  animated(ModifierTags.SIZE, dp)

fun Modifier.padding(dp: AnimationTarget): Modifier =
  animated(ModifierTags.PADDING, dp)

/*
 * Project Dogwood -- declared animation, for the preview path.
 *
 * ADR-020's whole argument is that a guest declares a *target* and a *specification* and the host
 * runs the frames, because an animation driven from inside the sandbox crosses the boundary sixty
 * times a second. That split is invisible at the call site -- `Modifier.alpha(animate(1f))` is the
 * same line on both paths -- and in a preview there is nothing to split: this process declares the
 * target and this process runs the frames, using Compose's own animation machinery.
 *
 * So a preview shows an animation's *shape* faithfully and tells you nothing at all about its
 * cost, which on a device is the only interesting question about it.
 */
@file:Suppress("unused")

package dev.dogwood.compose

import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.unit.Dp as UiDp

private const val SPEC_TWEEN = 1
private const val SPEC_SPRING = 2
private const val SPEC_SNAP = 3

/**
 * How a value should travel to its target.
 *
 * The deployment path encodes this as three or four numbers and strings; here the same three
 * factories build the genuine `androidx.compose.animation.core` specification. The names are
 * Compose's own on both sides, which is why the translation is a `when` and not a judgement.
 */
class AnimationSpec internal constructor(
  private val kind: Int,
  private val durationMs: Int = 300,
  private val easing: String = "fastOutSlowIn",
  private val delayMs: Int = 0,
  private val stiffness: String = "medium",
  private val damping: String = "noBouncy",
) {
  override fun equals(other: Any?): Boolean =
    other is AnimationSpec && other.kind == kind && other.durationMs == durationMs &&
      other.easing == easing && other.delayMs == delayMs && other.stiffness == stiffness &&
      other.damping == damping

  override fun hashCode(): Int = listOf(kind, durationMs, easing, delayMs, stiffness, damping).hashCode()

  internal fun <T> build(): FiniteAnimationSpec<T> = when (kind) {
    SPEC_SPRING -> spring(dampingRatio = dampingRatio(), stiffness = stiffnessValue())
    SPEC_SNAP -> snap()
    else -> tween(durationMillis = durationMs, delayMillis = delayMs, easing = easingValue())
  }

  /**
   * A duration-based reading of the same specification, for the one place Compose requires one.
   *
   * `infiniteRepeatable` cannot repeat a spring, because a spring has no duration to repeat. A
   * spring asked to oscillate therefore previews as a tween of the same length as the default --
   * the fourth stand-in, and the smallest: the host would run the spring.
   */
  internal fun <T> repeatable(): DurationBasedAnimationSpec<T> = when (kind) {
    SPEC_SNAP -> snap()
    SPEC_SPRING -> tween(durationMillis = 300)
    else -> tween(durationMillis = durationMs, delayMillis = delayMs, easing = easingValue())
  }

  internal fun float(): FiniteAnimationSpec<Float> = build()
  internal fun dp(): FiniteAnimationSpec<UiDp> = build()
  internal fun color(): FiniteAnimationSpec<UiColor> = build()

  private fun easingValue(): Easing = when (easing) {
    "linear" -> LinearEasing
    "linearOutSlowIn" -> LinearOutSlowInEasing
    "fastOutLinearIn" -> FastOutLinearInEasing
    else -> FastOutSlowInEasing
  }

  private fun stiffnessValue(): Float = when (stiffness) {
    "low" -> Spring.StiffnessLow
    "veryLow" -> Spring.StiffnessVeryLow
    "high" -> Spring.StiffnessHigh
    else -> Spring.StiffnessMedium
  }

  private fun dampingRatio(): Float = when (damping) {
    "mediumBouncy" -> Spring.DampingRatioMediumBouncy
    "lowBouncy" -> Spring.DampingRatioLowBouncy
    "highBouncy" -> Spring.DampingRatioHighBouncy
    else -> Spring.DampingRatioNoBouncy
  }
}

object Animations {
  fun tween(durationMs: Int = 300, easing: String = "fastOutSlowIn", delayMs: Int = 0): AnimationSpec =
    AnimationSpec(SPEC_TWEEN, durationMs = durationMs, easing = easing, delayMs = delayMs)

  fun spring(stiffness: String = "medium", damping: String = "noBouncy"): AnimationSpec =
    AnimationSpec(SPEC_SPRING, stiffness = stiffness, damping = damping)

  val Snap: AnimationSpec = AnimationSpec(SPEC_SNAP)
}

/** A target, a specification, and -- for a finite animation -- who to tell when it arrives. */
class AnimationTarget internal constructor(
  internal val target: Float,
  internal val spec: AnimationSpec,
  internal val onFinished: (() -> Unit)?,
  internal val oscillation: Float? = null,
  internal val iterations: Int = 0,
  internal val reverse: Boolean = true,
)

fun animate(
  target: Float,
  spec: AnimationSpec = Animations.tween(),
  onFinished: (() -> Unit)? = null,
): AnimationTarget = AnimationTarget(target, spec, onFinished)

fun animateDp(
  target: Int,
  spec: AnimationSpec = Animations.tween(),
  onFinished: (() -> Unit)? = null,
): AnimationTarget = AnimationTarget(target.toFloat(), spec, onFinished)

fun oscillate(
  from: Float,
  to: Float,
  spec: AnimationSpec = Animations.tween(),
  iterations: Int = 0,
  reverse: Boolean = true,
  onFinished: (() -> Unit)? = null,
): AnimationTarget {
  require(iterations >= 0) { "iterations must not be negative; 0 means forever" }
  require(!(iterations == 0 && onFinished != null)) {
    "an infinite oscillation never finishes, so it cannot report a completion. Give it a finite " +
      "iteration count, or drop the callback."
  }
  return AnimationTarget(from, spec, onFinished, oscillation = to, iterations = iterations, reverse = reverse)
}

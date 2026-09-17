/*
 * Project Dogwood -- `Modifier`, for the preview path.
 *
 * On the deployment path a `Modifier` is a list of tagged, serialisable elements: Compose's own
 * `Modifier.Element` implementations are `internal` and cannot be encoded, so Dogwood declares its
 * own chain and the host rebuilds the real one. Here the chain *is* the real one -- every function
 * below wraps `androidx.compose.ui.Modifier` and returns it. The call sites are identical; there
 * is no rebuilding step because there is no wire to rebuild it from.
 *
 * **Why so many of these are `composed { }`.** A Dogwood modifier argument can be a *recipe*: a
 * colour token the host resolves from its palette, or an animation target the host runs frames
 * for. Resolving either needs a composition -- the theme on one hand, `animateFloatAsState` on the
 * other -- and a plain `Modifier` function has none. `composed` is the seam that gives it one, and
 * it is where the preview does in one process what the protocol otherwise splits across two.
 */
@file:Suppress("unused", "DEPRECATION")

package dev.dogwood.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp as uiDp
import androidx.compose.ui.Modifier as UiModifier

/**
 * The preview's modifier: a wrapper around the real one, with the same spelling at a call site.
 *
 * The companion **is** a `Modifier`, exactly as it is on the deployment path and in Compose
 * itself, so `modifier: Modifier = Modifier` and `Modifier.padding(8)` read the same on both.
 */
open class Modifier internal constructor(val real: UiModifier) {
  companion object : Modifier(UiModifier) {
    val Empty: Modifier get() = this
  }

  internal fun wrap(next: UiModifier.() -> UiModifier): Modifier = Modifier(real.next())

  override fun equals(other: Any?): Boolean = other is Modifier && other.real == real
  override fun hashCode(): Int = real.hashCode()
}

// ---------------------------------------------------------------------------------------------
// Size and space
// ---------------------------------------------------------------------------------------------

fun Modifier.padding(dp: Int): Modifier = wrap { padding(dp.uiDp) }

fun Modifier.padding(start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0): Modifier =
  wrap { padding(start.uiDp, top.uiDp, end.uiDp, bottom.uiDp) }

fun Modifier.padding(horizontal: Int, vertical: Int): Modifier =
  padding(start = horizontal, top = vertical, end = horizontal, bottom = vertical)

fun Modifier.fillMaxWidth(fraction: Float = 1.0f): Modifier = wrap { fillMaxWidth(fraction) }

fun Modifier.fillMaxHeight(fraction: Float = 1.0f): Modifier = wrap { fillMaxHeight(fraction) }

fun Modifier.fillMaxSize(fraction: Float = 1.0f): Modifier = wrap { fillMaxSize(fraction) }

fun Modifier.size(dp: Int): Modifier = wrap { size(dp.uiDp) }

fun Modifier.width(dp: Int): Modifier = wrap { width(dp.uiDp) }

fun Modifier.height(dp: Int): Modifier = wrap { height(dp.uiDp) }

fun Modifier.offset(xDp: Int = 0, yDp: Int = 0): Modifier = wrap { offset(xDp.uiDp, yDp.uiDp) }

fun Modifier.wrapContentWidth(): Modifier = wrap { wrapContentWidth() }

fun Modifier.wrapContentHeight(): Modifier = wrap { wrapContentHeight() }

/** -1 leaves a side alone, as it does on the wire. */
fun Modifier.defaultMinSize(minWidthDp: Int = -1, minHeightDp: Int = -1): Modifier = wrap {
  defaultMinSize(
    minWidth = if (minWidthDp < 0) UnspecifiedDp else minWidthDp.uiDp,
    minHeight = if (minHeightDp < 0) UnspecifiedDp else minHeightDp.uiDp,
  )
}

fun Modifier.widthIn(minDp: Int = -1, maxDp: Int = -1): Modifier = wrap {
  widthIn(
    min = if (minDp < 0) UnspecifiedDp else minDp.uiDp,
    max = if (maxDp < 0) UnspecifiedDp else maxDp.uiDp,
  )
}

fun Modifier.heightIn(minDp: Int = -1, maxDp: Int = -1): Modifier = wrap {
  heightIn(
    min = if (minDp < 0) UnspecifiedDp else minDp.uiDp,
    max = if (maxDp < 0) UnspecifiedDp else maxDp.uiDp,
  )
}

private val UnspecifiedDp = androidx.compose.ui.unit.Dp.Unspecified

// ---------------------------------------------------------------------------------------------
// Paint
// ---------------------------------------------------------------------------------------------

fun Modifier.alpha(alpha: Float): Modifier = wrap { alpha(alpha) }

fun Modifier.rotate(degrees: Float): Modifier = wrap { rotate(degrees) }

fun Modifier.scale(scale: Float): Modifier = wrap { scale(scale) }

fun Modifier.shadow(elevationDp: Int): Modifier = wrap { shadow(elevationDp.coerceAtLeast(0).uiDp) }

fun Modifier.aspectRatio(ratio: Float): Modifier = wrap { aspectRatio(ratio.coerceAtLeast(0.01f)) }

fun Modifier.clip(shape: Shape): Modifier = wrap { clip(shape.real) }

fun Modifier.background(color: Color): Modifier = wrap { composed { background(color.resolve()) } }

fun Modifier.border(widthDp: Int, color: Color): Modifier =
  wrap { composed { border(widthDp.uiDp, color.resolve()) } }

fun Modifier.border(widthDp: Int, color: Color, shape: Shape): Modifier =
  wrap { composed { border(widthDp.uiDp, color.resolve(), shape.real) } }

// ---------------------------------------------------------------------------------------------
// Behaviour and semantics
// ---------------------------------------------------------------------------------------------

fun Modifier.clickable(enabled: Boolean = true, role: Role? = null, onClick: () -> Unit): Modifier =
  wrap { clickable(enabled = enabled, role = role?.real(), onClick = onClick) }

fun Modifier.contentDescription(text: String): Modifier =
  wrap { semantics { contentDescription = text } }

fun Modifier.testTag(tag: String): Modifier = wrap { testTag(tag) }

// ---------------------------------------------------------------------------------------------
// Animated arguments
//
// On a device these cross once as a target and a specification, and every frame after that is the
// host's work -- which is the whole of ADR-020. In a preview the host is this process, so the
// frames are run here, by the same `animateFloatAsState` the guest check forbids a *payload* from
// calling. The forbidden thing is a payload that ticks a boundary; there is no boundary here.
// ---------------------------------------------------------------------------------------------

fun Modifier.alpha(alpha: AnimationTarget): Modifier = wrap { composed { alpha(alpha.floatValue()) } }

fun Modifier.rotate(degrees: AnimationTarget): Modifier = wrap { composed { rotate(degrees.floatValue()) } }

fun Modifier.scale(scale: AnimationTarget): Modifier = wrap { composed { scale(scale.floatValue()) } }

fun Modifier.height(dp: AnimationTarget): Modifier = wrap { composed { height(dp.dpValue()) } }

fun Modifier.width(dp: AnimationTarget): Modifier = wrap { composed { width(dp.dpValue()) } }

fun Modifier.size(dp: AnimationTarget): Modifier = wrap { composed { size(dp.dpValue()) } }

fun Modifier.padding(dp: AnimationTarget): Modifier = wrap { composed { padding(dp.dpValue()) } }

fun Modifier.padding(
  start: AnimationTarget? = null,
  top: AnimationTarget? = null,
  end: AnimationTarget? = null,
  bottom: AnimationTarget? = null,
): Modifier = wrap {
  composed {
    padding(
      start = start?.dpValue() ?: 0.uiDp,
      top = top?.dpValue() ?: 0.uiDp,
      end = end?.dpValue() ?: 0.uiDp,
      bottom = bottom?.dpValue() ?: 0.uiDp,
    )
  }
}

@Composable
private fun AnimationTarget.floatValue(): Float {
  val swing = oscillation
  if (swing != null) {
    val transition = rememberInfiniteTransition(label = "dogwood-oscillate")
    val value by transition.animateFloat(
      initialValue = target,
      targetValue = swing,
      animationSpec = infiniteRepeatable(spec.repeatable(), repeatMode = repeatMode()),
      label = "dogwood-oscillate",
    )
    return value
  }
  val value by animateFloatAsState(target, spec.float(), finishedListener = { onFinished?.invoke() })
  return value
}

@Composable
private fun AnimationTarget.dpValue(): androidx.compose.ui.unit.Dp {
  val value by animateDpAsState(target.uiDp, spec.dp(), finishedListener = { onFinished?.invoke() })
  return value
}

private fun AnimationTarget.repeatMode() = if (reverse) RepeatMode.Reverse else RepeatMode.Restart

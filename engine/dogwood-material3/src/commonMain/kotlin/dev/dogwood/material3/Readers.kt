/*
 * Project Dogwood -- reading Compose's closed sets back from the names the guest sends.
 *
 * Arrangements, alignments, font weights and text styles are closed sets in Compose, and the guest
 * names them (plans/generator-v2.md, D-E) so a client one version behind can meet a name it has
 * never heard of and *say so*. Every reader here follows the rule every named thing follows: the
 * host owns the meaning, an unknown name degrades to the library's default rather than throwing,
 * and the name is recorded so a team can see that payloads are ahead of devices.
 *
 * These duplicate resolvers the primitive tier keeps private in `dogwood-host`. Deliberately: the
 * tier is a separate module by design, and a shared resolver would be a public seam between two
 * modules for the sake of a dozen lines that the next tier will copy again anyway.
 */
package dev.dogwood.material3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dogwood.host.LocalSkewReport
import dev.dogwood.host.WidgetView
import dev.dogwood.host.clampModifierValue
import dev.dogwood.host.intOrNull
import dev.dogwood.host.stringOrNull

@Composable
private fun unknown(what: String, name: String) {
  // `unknownNames`, the kind Track SB gave these on the same day: a name from a closed set the
  // client does not carry, distinct from a text-style token, so a dashboard can tell "the payload
  // used an arrangement this client lacks" from "the payload used a typography token it lacks".
  LocalSkewReport.current.unknownNames += "$what:$name"
}

@Composable
private fun spacing(name: String): androidx.compose.ui.unit.Dp {
  val raw = name.substringAfter(':').toFloatOrNull() ?: 0f
  return clampModifierValue(raw, min = 0f, what = "arrangement.spacedBy").dp
}

@Composable
fun WidgetView.verticalArrangementOrNull(tag: Int): Arrangement.Vertical? {
  val name = stringOrNull(tag) ?: return null
  if (name.startsWith("spacedBy:")) return Arrangement.spacedBy(spacing(name))
  return when (name) {
    "start", "top" -> Arrangement.Top
    "center" -> Arrangement.Center
    "end", "bottom" -> Arrangement.Bottom
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceAround" -> Arrangement.SpaceAround
    "spaceEvenly" -> Arrangement.SpaceEvenly
    else -> null.also { unknown("arrangement", name) }
  }
}

@Composable
fun WidgetView.horizontalArrangementOrNull(tag: Int): Arrangement.Horizontal? {
  val name = stringOrNull(tag) ?: return null
  if (name.startsWith("spacedBy:")) return Arrangement.spacedBy(spacing(name))
  return when (name) {
    "start", "top" -> Arrangement.Start
    "center" -> Arrangement.Center
    "end", "bottom" -> Arrangement.End
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceAround" -> Arrangement.SpaceAround
    "spaceEvenly" -> Arrangement.SpaceEvenly
    else -> null.also { unknown("arrangement", name) }
  }
}

@Composable
fun WidgetView.horizontalOrVerticalArrangementOrNull(tag: Int): Arrangement.HorizontalOrVertical? {
  val name = stringOrNull(tag) ?: return null
  if (name.startsWith("spacedBy:")) return Arrangement.spacedBy(spacing(name))
  return when (name) {
    "center" -> Arrangement.Center
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceAround" -> Arrangement.SpaceAround
    "spaceEvenly" -> Arrangement.SpaceEvenly
    else -> null.also { unknown("arrangement", name) }
  }
}

/** Alignments cross as the guest enumeration's ordinal, the encoding `Modifier.align` already uses. */
fun WidgetView.horizontalAlignmentOrNull(tag: Int): Alignment.Horizontal? = when (intOrNull(tag)) {
  null -> null
  0 -> Alignment.Start
  2 -> Alignment.End
  else -> Alignment.CenterHorizontally
}

fun WidgetView.verticalAlignmentOrNull(tag: Int): Alignment.Vertical? = when (intOrNull(tag)) {
  null -> null
  0 -> Alignment.Top
  2 -> Alignment.Bottom
  else -> Alignment.CenterVertically
}

/** `BoxAlignment`'s nine entries, in its declaration order. */
fun WidgetView.alignmentOrNull(tag: Int): Alignment? = when (intOrNull(tag)) {
  null -> null
  0 -> Alignment.TopStart
  1 -> Alignment.TopCenter
  2 -> Alignment.TopEnd
  3 -> Alignment.CenterStart
  4 -> Alignment.Center
  5 -> Alignment.CenterEnd
  6 -> Alignment.BottomStart
  7 -> Alignment.BottomCenter
  else -> Alignment.BottomEnd
}

@Composable
fun WidgetView.fontWeightOrNull(tag: Int): FontWeight? {
  val name = stringOrNull(tag) ?: return null
  name.toIntOrNull()?.let { return FontWeight(clampModifierValue(it.toFloat(), min = 1f, max = 1000f, what = "fontWeight").toInt()) }
  return when (name) {
    "thin" -> FontWeight.Thin
    "light" -> FontWeight.Light
    "normal" -> FontWeight.Normal
    "medium" -> FontWeight.Medium
    "semibold" -> FontWeight.SemiBold
    "bold" -> FontWeight.Bold
    "black" -> FontWeight.Black
    else -> null.also { unknown("fontWeight", name) }
  }
}

@Composable
fun WidgetView.textAlignOrNull(tag: Int): TextAlign? {
  val name = stringOrNull(tag) ?: return null
  return when (name) {
    "start" -> TextAlign.Start
    "center" -> TextAlign.Center
    "end" -> TextAlign.End
    "justify" -> TextAlign.Justify
    else -> null.also { unknown("textAlign", name) }
  }
}

@Composable
fun WidgetView.textOverflowOrNull(tag: Int): TextOverflow? {
  val name = stringOrNull(tag) ?: return null
  return when (name) {
    "clip" -> TextOverflow.Clip
    "ellipsis" -> TextOverflow.Ellipsis
    "visible" -> TextOverflow.Visible
    else -> null.also { unknown("overflow", name) }
  }
}

@Composable
fun WidgetView.textDecorationOrNull(tag: Int): TextDecoration? {
  val name = stringOrNull(tag) ?: return null
  return when (name) {
    "underline" -> TextDecoration.Underline
    "lineThrough" -> TextDecoration.LineThrough
    else -> null.also { unknown("textDecoration", name) }
  }
}

/**
 * The three answers a tri-state control can give, by name.
 *
 * A value rather than a live-state holder: `TriStateCheckbox` takes its `state` the way `Checkbox`
 * takes `checked`, so there is nothing host-owned here and nothing to report back. Only the type's
 * `State` suffix ever made it look otherwise.
 *
 * An unknown name degrades to null, which the binding turns into the library's own default for an
 * optional parameter and into `ToggleableState.Off` for a required one — a box showing less than
 * the payload meant rather than more, and the name lands in the skew report either way.
 */
@Composable
fun WidgetView.toggleableStateOrNull(tag: Int): ToggleableState? {
  val name = stringOrNull(tag) ?: return null
  return when (name) {
    "on" -> ToggleableState.On
    "off" -> ToggleableState.Off
    "indeterminate" -> ToggleableState.Indeterminate
    else -> null.also { unknown("toggleableState", name) }
  }
}

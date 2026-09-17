/*
 * Project Dogwood -- reading Compose's closed sets back from the names the guest sends, for the
 * three tiers this module carries.
 *
 * Arrangements, alignments and text overflow are closed sets in Compose, and the guest names them
 * (plans/generator-v2.md, D-E) so a client one version behind can meet a name it has never heard of
 * and *say so*. Every reader here follows the rule every named thing follows: the host owns the
 * meaning, an unknown name degrades to the library's default rather than throwing, and the name is
 * recorded so a team can see that payloads are ahead of devices.
 *
 * Six readers rather than the Material 3 tier's fifteen, because six is what the generated
 * bindings in this module actually call. The list is not a judgement about what a reader should
 * exist for: it is `grep` over the emitted host files, and it grows when a binding starts asking
 * for something.
 *
 * These duplicate resolvers `dogwood-material3` keeps in its own package and the primitive tier
 * keeps private in `dogwood-host`. Deliberately, and for the reason `dogwood-material3/Readers.kt`
 * gives: a tier is a separate module by design, and a shared resolver would be a public seam
 * between modules for the sake of a hundred lines. What this module does NOT duplicate three times
 * is this file itself -- all three of its tiers emit into one host package, `dev.dogwood.foundation`,
 * precisely so that one copy serves them (the module's build file says why).
 */
package dev.dogwood.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dogwood.host.LocalSkewReport
import dev.dogwood.host.WidgetView
import dev.dogwood.host.clampModifierValue
import dev.dogwood.host.intOrNull
import dev.dogwood.host.stringOrNull

@Composable
private fun unknown(what: String, name: String) {
  // `unknownNames`: a name from a closed set the client does not carry, distinct from a text-style
  // token, so a dashboard can tell "the payload used an arrangement this client lacks" from "the
  // payload used a typography token it lacks".
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
fun WidgetView.textOverflowOrNull(tag: Int): TextOverflow? {
  val name = stringOrNull(tag) ?: return null
  return when (name) {
    "clip" -> TextOverflow.Clip
    "ellipsis" -> TextOverflow.Ellipsis
    "visible" -> TextOverflow.Visible
    else -> null.also { unknown("overflow", name) }
  }
}

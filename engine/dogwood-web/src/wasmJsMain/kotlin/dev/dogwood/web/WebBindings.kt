/*
 * Project Dogwood -- the web host's bindings, deliberately the layout tier and nothing else.
 *
 * `dogwood-host/Bindings.kt` binds ten widgets and dispatches nine more from a generated
 * dictionary; this binds five. That is not a smaller version of the same ambition -- it is the
 * smallest set that proves the path, which is what this phase is for. The design system, the
 * deferred-expression evaluator, the palette, the lazy containers and the text-recipe channel are
 * all absent, and each of them is absent for a reason the report names.
 *
 * What is kept from the real bindings, because dropping it would make the proof dishonest:
 *   - The **property tags are the real ones** -- `Text` reads P1 for its content, P2 for its line
 *     limit, P5 for its colour, exactly as segment 0 defines them -- so a guest written for the
 *     mobile host produces a tree this host reads correctly rather than one written to match.
 *   - **`key(child.id)` around every child**, which is what makes node identity survive a
 *     reorder. Without it Compose matches children positionally and a move looks like a wholesale
 *     rewrite of everything after it.
 *   - **An unrecognised tag renders an empty `Box`**, not nothing, for the sibling-index reason
 *     [WebTree] gives.
 *
 * The layer diagram for this host is in the report; every node in it appears in this package.
 */
package dev.dogwood.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.ModifierTags
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Which scope a child is being composed inside, so scoped modifiers can be applied at all. */
class LayoutScope(
  val row: RowScope? = null,
  val column: ColumnScope? = null,
)

/**
 * Where a binding sends the events its widget produces.
 *
 * The signature matches `dogwood-host`'s, including the argument list, even though nothing in this
 * host sends arguments yet -- the viewport report that needs them belongs to the lazy containers,
 * which are not bound here.
 */
fun interface EventSink {
  fun send(node: WidgetView, tag: EventTag, args: List<JsonElement>)
}

/** Most events carry no arguments; this keeps their call sites from saying so. */
fun EventSink.send(node: WidgetView, tag: EventTag) = send(node, tag, emptyList())

/** The single content slot every container in the layout tier declares. */
private const val CONTENT = 1

/** Property tags are parameter-declaration order, widget-scoped, as segment 0 defines them. */
private const val P1 = 1
private const val P2 = 2
private const val P3 = 3
private const val P5 = 5

/**
 * A transcript of what the bindings actually composed.
 *
 * Deliberately outside the snapshot system, so recording cannot itself invalidate anything. It
 * exists because the only honest way to show that a *guest's* tree reached the screen -- as
 * opposed to showing that Compose Multiplatform drew something -- is to read what the bindings
 * were handed at the moment they ran.
 *
 * Null by default, and every binding checks. A host that is not being verified pays one null
 * check per node.
 */
val LocalRenderTranscript = staticCompositionLocalOf<RenderTranscript?> { null }

/** One line per composed node, plus a count, which is also the recomposition figure. */
class RenderTranscript {
  private val lines = mutableListOf<String>()

  /**
   * The size Compose actually measured each node at, in pixels.
   *
   * This is the part of the transcript that says something composition alone cannot. A recorded
   * line proves a binding ran with the guest's data; a non-zero size on a `Text` proves Skia
   * measured real glyphs for that string and gave the layout a box to put them in. The difference
   * matters, because "the bindings executed" and "the screen is not blank" are separate claims and
   * only the second one is what a user sees.
   */
  private val sizes = LinkedHashMap<Int, String>()

  var count: Int = 0
    private set

  fun record(node: WidgetView, detail: String) {
    count++
    lines += "${WebDictionary.name(node.tag)}#${node.id.value} $detail"
  }

  fun recordSize(node: WidgetView, width: Int, height: Int) {
    sizes[node.id.value] = "${WebDictionary.name(node.tag)}#${node.id.value} measured ${width}x$height"
  }

  fun reset() {
    lines.clear()
    sizes.clear()
    count = 0
  }

  fun dump(): String = (lines + sizes.values).joinToString("\n")
}

/**
 * Renders one node.
 *
 * The dispatch is a `when` over the tag's full value rather than over its local part, because a
 * tag carries its dictionary segment in its top eight bits and two segments may both hold a local
 * tag 1. Comparing locals would make segment 1's first component render as `Text`.
 */
@Composable
fun RenderNode(node: WidgetView, scope: LayoutScope, events: EventSink) {
  val modifier = node.webModifier(scope)
  val transcript = LocalRenderTranscript.current
  when (node.tag.value) {
    WebDictionary.Text.value -> {
      val text = node.string(P1)
      transcript?.record(node, "\"$text\"")
      BasicText(
        text = text,
        // The measurement probe, and only when a harness asked for one. `onSizeChanged` reports
        // the box the layout pass gave this text, which for a `BasicText` is the box Skia's own
        // shaping produced -- so a non-zero value here is evidence from inside the renderer rather
        // than from beside it.
        modifier = if (transcript == null) {
          modifier
        } else {
          modifier.onSizeChanged { transcript.recordSize(node, it.width, it.height) }
        },
        maxLines = node.int(P2, Int.MAX_VALUE),
        overflow = TextOverflow.Ellipsis,
        style = textStyle(node.stringOrNull(P3), node.colorOrNull(P5)),
      )
    }

    WebDictionary.Column.value -> {
      transcript?.record(node, "column")
      Column(modifier) { RenderChildren(node, CONTENT, LayoutScope(column = this), events) }
    }

    WebDictionary.Row.value -> {
      transcript?.record(node, "row")
      // A lambda parameter surfaces on the wire as "this node carries handler n". The host cannot
      // see guest closures, so presence has to be a property or the binding cannot know whether to
      // make the row clickable at all.
      val clickable = if (node.boolean(P1, false)) {
        Modifier.clickable { events.send(node, EventTag(1)) }
      } else {
        Modifier
      }
      Row(modifier.then(clickable), verticalAlignment = Alignment.CenterVertically) {
        RenderChildren(node, CONTENT, LayoutScope(row = this), events)
      }
    }

    // No default background: a Box is a transparent container exactly as Compose's own is, and a
    // host-painted default would draw over whatever the guest's own `background(...)` put down.
    WebDictionary.Box.value -> {
      transcript?.record(node, "box")
      Box(modifier) { RenderChildren(node, CONTENT, LayoutScope(), events) }
    }

    WebDictionary.Spacer.value -> {
      transcript?.record(node, "spacer")
      Spacer(modifier)
    }

    else -> {
      transcript?.record(node, "placeholder for unknown tag ${node.tag.value}")
      Box(modifier)
    }
  }
}

/**
 * Renders one slot's children.
 *
 * `key(child.id)` is what makes node identity survive list reordering. It is the same line the
 * Java-Virtual-Machine host has, and it is here for the same reason rather than by copying.
 */
@Composable
fun RenderChildren(node: WidgetView, slot: Int, scope: LayoutScope, events: EventSink) {
  for (child in node.children(slot)) {
    key(child.id.value) { RenderNode(child, scope, events) }
  }
}

/**
 * Rebuilds a node's modifier chain, in order.
 *
 * Order is load-bearing: `padding(8).size(48)` and `size(48).padding(8)` produce different
 * layouts, so the chain is replayed exactly as the guest recorded it.
 *
 * Only the modifiers whose arguments are primitives are here. The ones whose arguments are
 * *deferred expressions* -- `clip(RoundedCornerShape(8.dp))`, an animated number, a colour token
 * that has to be resolved against the host palette -- need the expression evaluator this host does
 * not have, so they are skipped rather than approximated. A skipped modifier renders a widget that
 * is laid out correctly and painted plainly, which is a visible and recoverable wrongness;
 * approximating one would be neither.
 *
 * `background` is the single exception, and only for a literal `[COLOR_ARGB, value]`, because the
 * verification harness needs at least one painted thing on the page to be able to say that pixels
 * came out.
 */
@Composable
private fun WidgetView.webModifier(scope: LayoutScope): Modifier {
  var modifier: Modifier = Modifier
  for (element in modifiers) {
    val value = element.v as? JsonPrimitive
    modifier = when (element.t.local) {
      ModifierTags.PADDING -> modifier.padding((value?.content?.toFloatOrNull() ?: 0f).dp)
      ModifierTags.FILL_MAX_WIDTH -> modifier.fillMaxWidth(value?.content?.toFloatOrNull() ?: 1f)
      ModifierTags.SIZE -> modifier.size((value?.content?.toFloatOrNull() ?: 0f).dp)
      ModifierTags.WIDTH -> modifier.width((value?.content?.toFloatOrNull() ?: 0f).dp)
      ModifierTags.HEIGHT -> modifier.height((value?.content?.toFloatOrNull() ?: 0f).dp)
      ModifierTags.ALPHA -> modifier.alpha(value?.content?.toFloatOrNull() ?: 1f)
      ModifierTags.BACKGROUND -> literalColor(element.v)?.let { modifier.background(it) } ?: modifier
      ModifierTags.WEIGHT -> {
        val weight = value?.content?.toFloatOrNull() ?: 1f
        when {
          scope.row != null -> with(scope.row) { modifier.weight(weight) }
          scope.column != null -> with(scope.column) { modifier.weight(weight) }
          // Unreachable from a well-formed guest, whose scope types make an out-of-scope weight a
          // compile error. Kept silent because a host must tolerate a guest built against a
          // different dictionary rather than crash on one.
          else -> modifier
        }
      }
      ModifierTags.ALIGN -> {
        val ordinal = value?.content?.toIntOrNull() ?: 1
        when {
          scope.row != null -> with(scope.row) { modifier.align(verticalAlignment(ordinal)) }
          scope.column != null -> with(scope.column) { modifier.align(horizontalAlignment(ordinal)) }
          else -> modifier
        }
      }
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
 * The one deferred expression this host resolves: a literal alpha-red-green-blue colour.
 *
 * `[ExpressionFactories.COLOR_ARGB, value]`. A colour *token* -- factory 4 -- is deliberately not
 * resolved, because resolving it needs the host palette and a palette invented here would put a
 * different colour on the web than on the phone while looking entirely successful.
 */
private fun literalColor(value: JsonElement): Color? {
  val array = value as? JsonArray ?: return null
  if (array.size != 2) return null
  val factory = (array[0] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
  if (factory != dev.dogwood.protocol.ExpressionFactories.COLOR_ARGB) return null
  val argb = (array[1] as? JsonPrimitive)?.longOrNull ?: return null
  // `Color(Int)` reads its argument as packed alpha-red-green-blue, which is what the guest sent.
  // The value arrives as a `Long` because an opaque colour has its top bit set and would not fit a
  // signed 32-bit integer on the wire.
  return Color(argb.toInt())
}

/** A colour property, in the same literal-only terms. */
private fun WidgetView.colorOrNull(tag: Int): Color? = property(tag)?.let(::literalColor)

/**
 * The three type ramp entries the layout tier names, as plain sizes.
 *
 * Not a theme. `dogwood-host` reads these from `MaterialTheme.typography`, and a web host that
 * invented its own ramp would look different from the phone for reasons nobody could find. These
 * are placeholders whose only job is that a heading is visibly bigger than a body line, and the
 * report says so.
 */
private fun textStyle(name: String?, color: Color?): TextStyle {
  val base = when (name) {
    "title" -> TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
    "label" -> TextStyle(fontSize = 12.sp)
    else -> TextStyle(fontSize = 16.sp)
  }
  return if (color == null) base else base.copy(color = color)
}

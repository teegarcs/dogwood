/*
 * Project Dogwood -- the binding dictionary.
 *
 * Ten hand-written bindings, per roadmap.md Phase 1 step 1: five layout primitives in
 * dictionary segment 0 and five design-system components in segment 1, so both segments are
 * exercised from the first screen. In Phase 3 this file stops being hand-written; the segment
 * structure exists now so that change is a substitution rather than a redesign.
 *
 * `Icon` is deliberately absent: its required `Painter` is asset-gated, and the design-system
 * image component covers the need until the resources subsystem lands.
 */
package dev.dogwood.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.Segments
import dev.dogwood.protocol.WidgetTag
import dev.dogwood.protocol.widgetTag
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Which scope a child is being composed inside, so scoped modifiers can be applied at all. */
class LayoutScope(
  val row: RowScope? = null,
  val column: ColumnScope? = null,
)

/**
 * Where a binding sends the events its widget produces.
 *
 * Arguments are serializable values, in declaration order. Most events carry none; the viewport
 * report carries two indices, which is what makes guest-side windowing possible at all -- the
 * guest cannot see the screen, so the host has to tell it what is on it.
 */
fun interface EventSink {
  fun send(node: WidgetView, tag: EventTag, args: List<JsonElement>)
}

/** Most events carry no arguments; this keeps their call sites from saying so. */
fun EventSink.send(node: WidgetView, tag: EventTag) = send(node, tag, emptyList())

/** The single content slot every container in this slice declares. */
private const val CONTENT = 1

/** Property tags are parameter-declaration order, widget-scoped. */
private const val P1 = 1
private const val P2 = 2
private const val P3 = 3
private const val P4 = 4
private const val P5 = 5

object DogwoodDictionary {
  // Segment 0 -- layout primitives.
  val Text = widgetTag(Segments.LAYOUT, 1)
  val Column = widgetTag(Segments.LAYOUT, 2)
  val Row = widgetTag(Segments.LAYOUT, 3)
  val Box = widgetTag(Segments.LAYOUT, 4)
  val Spacer = widgetTag(Segments.LAYOUT, 5)

  // Segment 1 -- the registered design-system slice.
  val PrimaryButton = widgetTag(Segments.DESIGN_SYSTEM, 1)
  val AsyncImage = widgetTag(Segments.DESIGN_SYSTEM, 2)
  val Card = widgetTag(Segments.DESIGN_SYSTEM, 3)
  val Badge = widgetTag(Segments.DESIGN_SYSTEM, 4)
  val Divider = widgetTag(Segments.DESIGN_SYSTEM, 5)

  // Added after the audit. Tags 1-5 keep their meaning, because the protocol's evolution rule is
  // additive: a new component appends rather than renumbering, so a client one dictionary version
  // behind still renders everything it knows.
  val Chip = widgetTag(Segments.DESIGN_SYSTEM, 6)
  val Price = widgetTag(Segments.DESIGN_SYSTEM, 7)
  val StarRating = widgetTag(Segments.DESIGN_SYSTEM, 8)
  val SectionHeader = widgetTag(Segments.DESIGN_SYSTEM, 9)
  val VerticalList = widgetTag(Segments.DESIGN_SYSTEM, 10)
  val HorizontalList = widgetTag(Segments.DESIGN_SYSTEM, 11)

  private val known = setOf(
    Text.value, Column.value, Row.value, Box.value, Spacer.value,
    PrimaryButton.value, AsyncImage.value, Card.value, Badge.value, Divider.value,
    Chip.value, Price.value, StarRating.value, SectionHeader.value,
    VerticalList.value, HorizontalList.value,
  )

  fun knows(tag: WidgetTag): Boolean = tag.value in known

  /** Per-segment versions, handed to the guest so it can branch on client capability. */
  val segmentVersions: Map<String, Int> = mapOf(
    "androidx.layout" to 1,
    // Bumped when components were added. Guest code can branch on this to stay compatible with
    // clients that have not caught up.
    "dogwood.designsystem" to 2,
  )
}

/**
 * Renders one node.
 *
 * An unrecognised tag renders as an empty box rather than as nothing. That is not politeness:
 * a dropped node would shift every sibling index in the same batch, and the skew rules depend
 * on placeholder nodes keeping that arithmetic consistent.
 */
@Composable
fun RenderNode(node: WidgetView, scope: LayoutScope, events: EventSink) {
  LocalRenderCounter.current?.record()
  // The registered segment is dispatched by generated code. Nine of the eleven components in it
  // are bound without a line of hand-written dispatch; what remains below is the layout tier and
  // the two lazy containers the generator does not yet model.
  if (bindDogwoodDesignSystem(node, scope, events)) return
  val modifier = node.composeModifier(scope)
  when (node.tag.value) {
    DogwoodDictionary.Text.value -> Text(
      text = node.string(P1),
      modifier = modifier,
      maxLines = node.int(P2, Int.MAX_VALUE),
      overflow = TextOverflow.Ellipsis,
      color = Palette.Ink,
      style = MaterialTheme.typography.bodyLarge,
    )

    DogwoodDictionary.Column.value -> Column(modifier) {
      RenderChildren(node, CONTENT, LayoutScope(column = this), events)
    }

    DogwoodDictionary.Row.value -> {
      // A lambda parameter surfaces on the wire as "this node carries handler n". The host
      // cannot see guest closures, so presence has to be a property or the binding cannot know
      // whether to make the row clickable at all.
      val clickable = if (node.boolean(P1, false)) {
        Modifier.clickable { events.send(node, EventTag(1)) }
      } else {
        Modifier
      }
      Row(modifier.then(clickable), verticalAlignment = Alignment.CenterVertically) {
        RenderChildren(node, CONTENT, LayoutScope(row = this), events)
      }
    }

    DogwoodDictionary.Box.value -> Box(
      modifier.background(Palette.CanvasContrast, RoundedCornerShape(Radius.Xs)),
    ) {
      RenderChildren(node, CONTENT, LayoutScope(), events)
    }

    DogwoodDictionary.Spacer.value -> Spacer(modifier)

    DogwoodDictionary.VerticalList.value -> LazyColumn(
      modifier = modifier,
      verticalArrangement = Arrangement.spacedBy(node.int(P1, 0).dp),
      contentPadding = PaddingValues(node.int(P2, 0).dp),
    ) {
      items(node.children(CONTENT), key = { it.id.value }) { child ->
        RenderNode(child, LayoutScope(), events)
      }
    }

    DogwoodDictionary.HorizontalList.value -> LazyRow(
      modifier = modifier,
      horizontalArrangement = Arrangement.spacedBy(node.int(P1, 0).dp),
      contentPadding = PaddingValues(horizontal = node.int(P2, 0).dp),
    ) {
      items(node.children(CONTENT), key = { it.id.value }) { child ->
        RenderNode(child, LayoutScope(), events)
      }
    }

    else -> Box(modifier)
  }
}

/**
 * Renders one slot's children.
 *
 * `key(child.id)` is what makes node identity survive list reordering, which is a named Phase 1
 * gate condition. Without it Compose matches children positionally and a move looks like a
 * wholesale rewrite of every node after the move.
 */
@Composable
fun RenderChildren(node: WidgetView, slot: Int, scope: LayoutScope, events: EventSink) {
  for (child in node.children(slot)) {
    key(child.id.value) {
      RenderNode(child, scope, events)
    }
  }
}

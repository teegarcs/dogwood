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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.Segments
import dev.dogwood.protocol.WidgetTag
import dev.dogwood.protocol.widgetTag

/** Which scope a child is being composed inside, so scoped modifiers can be applied at all. */
class LayoutScope(
  val row: RowScope? = null,
  val column: ColumnScope? = null,
)

/** Where a binding sends the events its widget produces. */
fun interface EventSink {
  fun send(node: WidgetView, tag: EventTag)
}

/** The single content slot every container in this slice declares. */
private const val CONTENT = 1

/** Property tags are parameter-declaration order, widget-scoped. */
private const val P1 = 1
private const val P2 = 2

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

  private val known = setOf(
    Text.value, Column.value, Row.value, Box.value, Spacer.value,
    PrimaryButton.value, AsyncImage.value, Card.value, Badge.value, Divider.value,
  )

  fun knows(tag: WidgetTag): Boolean = tag.value in known

  /** Per-segment versions, handed to the guest so it can branch on client capability. */
  val segmentVersions: Map<String, Int> = mapOf(
    "androidx.layout" to 1,
    "dogwood.designsystem" to 1,
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
  val modifier = node.composeModifier(scope)
  when (node.tag.value) {
    DogwoodDictionary.Text.value -> Text(
      text = node.string(P1),
      modifier = modifier,
      maxLines = node.int(P2, Int.MAX_VALUE),
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.bodyMedium,
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
      modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
    ) {
      RenderChildren(node, CONTENT, LayoutScope(), events)
    }

    DogwoodDictionary.Spacer.value -> Spacer(modifier)

    DogwoodDictionary.PrimaryButton.value -> Button(
      onClick = { events.send(node, EventTag(1)) },
      modifier = modifier,
    ) {
      Text(node.string(P1))
    }

    // A placeholder until the resources subsystem exists. It is drawn as a labelled surface
    // rather than silently blank so that a missing image is visible in the slice rather than
    // mistaken for a layout bug.
    DogwoodDictionary.AsyncImage.value -> Box(
      modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp)),
      contentAlignment = Alignment.Center,
    ) {
      Text("image", style = MaterialTheme.typography.labelSmall)
    }

    DogwoodDictionary.Card.value -> Card(modifier) {
      RenderChildren(node, CONTENT, LayoutScope(column = this), events)
    }

    DogwoodDictionary.Badge.value -> {
      val selected = node.boolean(P2, false)
      Surface(
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
        color = if (selected) {
          MaterialTheme.colorScheme.primaryContainer
        } else {
          MaterialTheme.colorScheme.surfaceVariant
        },
      ) {
        Text(
          node.string(P1),
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }

    DogwoodDictionary.Divider.value -> HorizontalDivider(modifier)

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

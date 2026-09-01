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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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

/** A lazy container's placeholder template. One node, repeated wherever the window has no item. */
private const val PLACEHOLDER = 2

/** Property tags are parameter-declaration order, widget-scoped. */
private const val P1 = 1
private const val P2 = 2
private const val P3 = 3
private const val P4 = 4
private const val P5 = 5
private const val P6 = 6
private const val P7 = 7
private const val P8 = 8

object DogwoodDictionary {
  // Segment 0 -- layout primitives, hand-written.
  val Text = widgetTag(Segments.LAYOUT, 1)
  val Column = widgetTag(Segments.LAYOUT, 2)
  val Row = widgetTag(Segments.LAYOUT, 3)
  val Box = widgetTag(Segments.LAYOUT, 4)
  val Spacer = widgetTag(Segments.LAYOUT, 5)

  /**
   * Segment 1's two hand-written members: the lazy containers the generator does not model yet.
   *
   * **Their tags are reserved in the dictionary lock**, and that reservation is load-bearing. The
   * generator allocates by position and knows nothing about this file; without the reservation it
   * gave `Icon` tag 10 -- the tag `VerticalList` already answers to -- and because the generated
   * dispatch runs first, every list on every screen would have rendered as an icon. A tag
   * collision does not fail to render; it renders the wrong widget.
   */
  val VerticalList = widgetTag(Segments.DESIGN_SYSTEM, 10)
  val HorizontalList = widgetTag(Segments.DESIGN_SYSTEM, 11)

  private val layoutTags = setOf(Text.value, Column.value, Row.value, Box.value, Spacer.value)
  private val handWrittenDesignSystemTags = setOf(VerticalList.value, HorizontalList.value)

  /**
   * Everything this client can render.
   *
   * The generated half comes from `DogwoodDesignSystemTags`, which the generator emits, rather
   * than from a hand-maintained copy. A copy would have to be updated every time the surface grew,
   * and the failure mode of forgetting is a component the client renders correctly while
   * *reporting* it as unknown.
   */
  private val known = layoutTags + handWrittenDesignSystemTags + DogwoodDesignSystemTags

  fun knows(tag: WidgetTag): Boolean = tag.value in known

  /** Per-segment versions, handed to the guest so it can branch on client capability. */
  val segmentVersions: Map<String, Int> = mapOf(
    "androidx.layout" to 1,
    // Bumped when components were added. Guest code can branch on this to stay compatible with
    // clients that have not caught up.
    "dogwood.designsystem" to 6,
    // The host service surface is versioned through the same channel, because a guest has the
    // same question about it: what does this client know how to do? It matters more here — an
    // unknown widget tag becomes a placeholder, but calling a service method an older host does
    // not implement is an error at the boundary with no fallback.
    dev.dogwood.protocol.SERVICES_SEGMENT to dev.dogwood.protocol.SERVICES_VERSION,
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
  val modifier = node.composeModifier(scope, events)
  when (node.tag.value) {
    DogwoodDictionary.Text.value -> {
      // Two ways to say what the text is, and the second is the one the sandbox cannot do for
      // itself: P4 is a *recipe* -- a number and a currency, an instant, a fraction -- that only
      // the host can render, because QuickJS ships no ECMA-402 `Intl`. The number crosses, not the
      // rendered string, so a device that changes locale re-renders correctly with no traffic.
      val recipe = node.property(P4)
      val text = if (recipe != null) {
        val format = formatContext()
        LocalExpressionEvaluator.current.text(recipe, format.locale, format.timeZoneId)
      } else {
        node.string(P1)
      }
      Text(
        text = text,
        modifier = modifier,
        maxLines = node.int(P2, Int.MAX_VALUE),
        overflow = TextOverflow.Ellipsis,
        color = palette().ink,
        style = textStyle(node.stringOrNull(P3)),
      )
    }

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

    // No default background. A `Box` is a transparent container, exactly as Compose's own is,
    // and a host-painted default here would draw OVER whatever the guest's own
    // `background(...)` modifier put down -- silently, because the result still renders.
    DogwoodDictionary.Box.value -> Box(modifier) {
      RenderChildren(node, CONTENT, LayoutScope(), events)
    }

    DogwoodDictionary.Spacer.value -> Spacer(modifier)

    DogwoodDictionary.VerticalList.value -> {
      // The real holder lives here, because scroll offset changes every frame and Layer 4
      // forbids per-frame state in the guest. What the guest has is a mirror.
      val listState = rememberLazyListState()
      LazyListMirror(node, listState, events)
      LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(node.int(P1, 0).dp),
        contentPadding = PaddingValues(node.int(P2, 0).dp),
      ) {
        lazyItems(node, events)
      }
    }

    DogwoodDictionary.HorizontalList.value -> {
      val listState = rememberLazyListState()
      LazyListMirror(node, listState, events)
      LazyRow(
        modifier = modifier,
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(node.int(P1, 0).dp),
        contentPadding = PaddingValues(horizontal = node.int(P2, 0).dp),
      ) {
        lazyItems(node, events)
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

/**
 * Keeps a host-owned [LazyListState] and a guest-side holder in step.
 *
 * Two directions, and they are not symmetric.
 *
 * **Targets come down, and they are held until they can be met.** The guest declares where it
 * wants the list to be as a pair of ordinary properties: an index and a sequence number. A target
 * naming an item that does not exist yet waits for the list to grow rather than clamping to the
 * end -- the ordinary case being a restored position declared while the content is still loading. A stale target cannot arrive, because a property
 * carries only its latest value -- a guest that asked for item 40 and then item 0 in the same
 * composition pass sends one property set, for 0. The sequence exists so that asking twice for the
 * *same* index is two requests, which is what a user tapping "back to top" a second time expects.
 *
 * **Reports go up, and only when they mean something.** The visible range is sent when it changes
 * **by an item**, not by a pixel: `distinctUntilChanged` over the index triple turns a sixty-frame
 * fling across three items into three crossings instead of a hundred and eighty. That is the
 * throttle roadmap.md asks for, and it is also the honest limit of the mirror -- a guest cannot
 * build anything frame-accurate on it, which is the point.
 *
 * Nothing is reported at all unless the guest said it was watching. The host cannot see guest
 * closures, so presence has to be a property; without it every list on every screen would pay for
 * an observer nobody reads.
 */
@Composable
private fun LazyListMirror(node: WidgetView, listState: LazyListState, events: EventSink) {
  val targetSequence = node.int(P4, 0)
  if (targetSequence > 0) {
    val targetIndex = node.int(P3, 0)
    val animated = node.boolean(P6, false)
    // Keyed on the sequence, not the index, so two requests for the same place both run.
    LaunchedEffect(node.id.value, targetSequence) {
      // A target may name an item that does not exist yet. The case is not exotic: a guest that
      // restored its position after a code update declares that target in its *first* batch,
      // while its content is still being fetched, so the list at that moment holds a header and
      // a loading row. `scrollToItem` would clamp to the end and the position would be silently
      // lost -- which is what happened the first time this was run on a device.
      //
      // So a target is held until the list is long enough to satisfy it. It is abandoned when a
      // newer target arrives or the node goes away, both of which cancel this effect; a target
      // for an item that never appears simply never fires, which is the right outcome for a
      // position into content that turned out not to exist.
      snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > targetIndex }
      if (animated) {
        listState.animateScrollToItem(targetIndex)
      } else {
        listState.scrollToItem(targetIndex)
      }
    }
  }

  if (node.boolean(P5, false)) {
    // `rememberUpdatedState`, and this is not a stylistic choice -- it fixes a real leak that the
    // leak detector found on a device.
    //
    // The report effect is keyed on the node identifier and the host's own list state, neither of
    // which changes when a code update swaps the guest underneath: the replacement tree hands out
    // the same identifiers, so `key(node.id)` matches the same composition groups and the effect
    // is *not* restarted. It therefore kept the `EventSink` from the composition it was launched
    // in, that sink captured the first `DogwoodExperience`, and that experience holds a Zipline
    // instance and an entire QuickJS heap. Six code updates in a row leaked exactly one
    // generation: the first, permanently.
    //
    // The memory was the symptom. The defect was that viewport reports after a code update were
    // being delivered to the previous, closed guest.
    val currentNode by rememberUpdatedState(node)
    val currentEvents by rememberUpdatedState(events)
    LaunchedEffect(node.id.value, listState) {
      snapshotFlow {
        Triple(
          listState.firstVisibleItemIndex,
          listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
          listState.isScrollInProgress,
        )
      }
        .distinctUntilChanged()
        .collect { (first, last, scrolling) ->
          currentEvents.send(
            currentNode,
            EventTag(1),
            listOf(JsonPrimitive(first), JsonPrimitive(last), JsonPrimitive(scrolling)),
          )
        }
    }
  }
}

/**
 * Resolves a named text style, falling back to body text.
 *
 * The same three-part rule as every other named thing the guest can send: the host owns the
 * meaning, an unknown name degrades to something readable rather than throwing, and the name is
 * recorded so a team can see that payloads are ahead of devices.
 */
@Composable
private fun textStyle(name: String?): androidx.compose.ui.text.TextStyle {
  val typography = typography()
  if (name == null) return typography.bodyLarge
  val resolved = typography.token(name)
  if (resolved == null) LocalSkewReport.current.unknownTextStyles += name
  return resolved ?: typography.bodyLarge
}

/**
 * Lays out a lazy container's items, windowed or not.
 *
 * **Without a window** -- no item count property -- every child the guest sent is an item, keyed by
 * node identifier, which is what a short list wants and what every list did before windowing
 * existed.
 *
 * **With a window**, the list is as long as the guest says it is and the guest has sent only the
 * part it expects to be seen. Everything else draws the placeholder template. Two consequences
 * worth stating:
 *
 *   - **The scroll extent is honest.** A list that only knew about its window would have a
 *     scrollbar the length of a screen and no index to fling to, which is the failure mode of
 *     naive windowing.
 *   - **Identity is the index, not the node.** That is a deliberate departure from
 *     [ADR-015](../../../../../../adrs/layer-5/ADR-015-node-identity-and-reuse.md)'s rule, and it
 *     is the right one here: item five hundred is item five hundred whichever guest node currently
 *     represents it, and keying on the node would destroy and rebuild every visible row each time
 *     the window slid.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.lazyItems(
  node: WidgetView,
  events: EventSink,
) {
  val children = node.children(CONTENT)
  val itemCount = node.int(P7, -1)
  if (itemCount < 0) {
    items(children, key = { it.id.value }) { child ->
      RenderNode(child, LayoutScope(), events)
    }
    return
  }

  val windowStart = node.int(P8, 0)
  val placeholder = node.children(PLACEHOLDER).firstOrNull()
  items(count = itemCount, key = { it }) { index ->
    val child = children.getOrNull(index - windowStart)
    when {
      child != null -> RenderNode(child, LayoutScope(), events)
      placeholder != null -> RenderNode(placeholder, LayoutScope(), events)
      // A guest that declared a window but no placeholder gets nothing rather than a guess: any
      // height this binding invented would make the scroll extent wrong in a way that looks like
      // a layout bug rather than a missing parameter.
      else -> Unit
    }
  }
}

/*
 * Project Dogwood -- Phase 0 measurement harness.
 *
 * The hand-written recording stubs. These are the "Dogwood-shaped stubs" experiment 0.2
 * requires: every one of them records a Create, its properties, its modifier chain, and its
 * event slots, so composition cost includes recording cost.
 *
 * Tag assignments follow adrs/layer-4/ADR-004-change-event-protocol-v0.md section 2.1 and
 * the Phase 1 component list in roadmap.md: segment 0 is the layout primitives in the order
 * Text, Column, Row, Box, Spacer; segment 1 is the design-system slice. Both dictionary
 * segments are exercised from day one, which is the point of the assignment.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import dev.dogwood.protocol.ChildrenTag
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.ModifierElem
import dev.dogwood.protocol.PropertyTag
import dev.dogwood.protocol.Segments
import dev.dogwood.protocol.WidgetTag
import dev.dogwood.protocol.modifierTag
import dev.dogwood.protocol.widgetTag
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** The Phase 0 and Phase 1 binding dictionary, hand-assigned. */
object Tags {
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

  /** The single content slot every container in this slice declares. */
  val Content = ChildrenTag(1)

  /** Property tags are parameter-declaration order, widget-scoped. */
  val P1 = PropertyTag(1)
  val P2 = PropertyTag(2)

  /** Event tags are parameter-declaration order, widget-scoped. */
  val OnClick = EventTag(1)
}

/**
 * The guest-side modifier stand-in.
 *
 * Compose's own `Modifier.Element` implementations are `internal`, so Dogwood cannot
 * serialize them and defines its own tagged, serializable type instead. Phase 0 carries
 * only the value-class-argument modifiers that Phase 2 scopes itself to; expression
 * arguments such as `clip(RoundedCornerShape(8.dp))` need the deferred-expression grammar,
 * which does not exist yet.
 */
open class DogwoodModifier internal constructor(val elements: List<ModifierElem>) {
  companion object Empty : DogwoodModifier(emptyList())

  fun then(tag: Int, value: JsonElement): DogwoodModifier =
    DogwoodModifier(elements + ModifierElem(modifierTag(Segments.LAYOUT, tag), value))

  override fun equals(other: Any?): Boolean =
    other is DogwoodModifier && other.elements == elements

  override fun hashCode(): Int = elements.hashCode()
}

fun DogwoodModifier.padding(dp: Int): DogwoodModifier = then(1, JsonPrimitive(dp))
fun DogwoodModifier.fillMaxWidth(): DogwoodModifier = then(2, JsonPrimitive(1.0f))
fun DogwoodModifier.weight(weight: Float): DogwoodModifier = then(3, JsonPrimitive(weight))
fun DogwoodModifier.size(dp: Int): DogwoodModifier = then(4, JsonPrimitive(dp))
fun DogwoodModifier.alpha(alpha: Float): DogwoodModifier = then(5, JsonPrimitive(alpha))

/**
 * The composition-scoped recording context. It is a plain object threaded through a
 * `CompositionLocal`-free path on purpose: Phase 0 measures recording cost, and a
 * `CompositionLocal` read per node would add cost that the real generator would not pay
 * the same way.
 */
class RecordingContext(
  val recorder: ChangeRecorder,
  val lambdas: LambdaSlots,
)

/** Set once per composition, before `setContent`. Single-threaded guest, so this is safe. */
internal lateinit var recording: RecordingContext

private fun newWidget(tag: WidgetTag): WidgetNode {
  val id = recording.recorder.newId()
  recording.recorder.create(id, tag)
  return WidgetNode(id, tag)
}

/**
 * Emits one content slot. Containers wrap their `content` lambda in this so that children
 * are routed to a named slot rather than to the widget itself. Slots are guest-side only
 * and cost no protocol change.
 */
@Composable
internal fun Children(tag: ChildrenTag, content: @Composable () -> Unit) {
  ComposeNode<ChildrenNode, DogwoodApplier>(
    factory = { ChildrenNode(tag) },
    update = {},
    content = content,
  )
}

// ---------------------------------------------------------------------------
// Segment 0 -- layout primitives
// ---------------------------------------------------------------------------

@Composable
fun Text(text: String, modifier: DogwoodModifier = DogwoodModifier.Empty, maxLines: Int = -1) {
  ComposeNode<WidgetNode, DogwoodApplier>(
    factory = { newWidget(Tags.Text) },
    update = {
      set(text) { recording.recorder.property(id, Tags.P1, JsonPrimitive(it)) }
      // Absence IS the "use host default" sentinel, so an unset maxLines sends nothing.
      set(maxLines) { if (it >= 0) recording.recorder.property(id, Tags.P2, JsonPrimitive(it)) }
      set(modifier) { if (it.elements.isNotEmpty()) recording.recorder.modifiers(id, it.elements) }
    },
  )
}

@Composable
fun Column(modifier: DogwoodModifier = DogwoodModifier.Empty, content: @Composable () -> Unit) {
  Container(Tags.Column, modifier, content)
}

@Composable
fun Row(
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  onClick: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  ComposeNode<WidgetNode, DogwoodApplier>(
    factory = { newWidget(Tags.Row) },
    update = {
      set(modifier) { if (it.elements.isNotEmpty()) recording.recorder.modifiers(id, it.elements) }
      // Stand-in for `Modifier.clickable`, which carries a lambda argument and so needs the
      // modifier subsystem Phase 2 defines.
      //
      // Two `set` calls, doing two different jobs. The lambda itself is stored guest-side and
      // never crosses. But the host cannot see guest closures, so it has no way to know whether
      // to make this row clickable -- and that has to cross as an ordinary property. Presence
      // of a handler is wire-visible; the handler is not.
      set(onClick != null) { clickable ->
        recording.recorder.property(id, Tags.P1, JsonPrimitive(clickable))
      }
      set(onClick) { handler ->
        if (handler != null) recording.lambdas.set(id, Tags.OnClick) { handler() }
      }
    },
    content = { Children(Tags.Content, content) },
  )
}

@Composable
fun Box(modifier: DogwoodModifier = DogwoodModifier.Empty, content: @Composable () -> Unit = {}) {
  Container(Tags.Box, modifier, content)
}

@Composable
fun Spacer(modifier: DogwoodModifier = DogwoodModifier.Empty) {
  ComposeNode<WidgetNode, DogwoodApplier>(
    factory = { newWidget(Tags.Spacer) },
    update = {
      set(modifier) { if (it.elements.isNotEmpty()) recording.recorder.modifiers(id, it.elements) }
    },
  )
}

@Composable
private fun Container(
  tag: WidgetTag,
  modifier: DogwoodModifier,
  content: @Composable () -> Unit,
) {
  ComposeNode<WidgetNode, DogwoodApplier>(
    factory = { newWidget(tag) },
    update = {
      set(modifier) { if (it.elements.isNotEmpty()) recording.recorder.modifiers(id, it.elements) }
    },
    content = { Children(Tags.Content, content) },
  )
}

// ---------------------------------------------------------------------------
// Segment 1 -- the registered design-system slice
// ---------------------------------------------------------------------------

@Composable
fun PrimaryButton(label: String, modifier: DogwoodModifier = DogwoodModifier.Empty, onClick: () -> Unit) {
  ComposeNode<WidgetNode, DogwoodApplier>(
    factory = { newWidget(Tags.PrimaryButton) },
    update = {
      set(label) { recording.recorder.property(id, Tags.P1, JsonPrimitive(it)) }
      set(modifier) { if (it.elements.isNotEmpty()) recording.recorder.modifiers(id, it.elements) }
      // The lambda is stored guest-side; only its tag crosses the boundary.
      set(onClick) { handler -> recording.lambdas.set(id, Tags.OnClick) { handler() } }
    },
  )
}

@Composable
fun AsyncImage(url: String, contentDescription: String?, modifier: DogwoodModifier = DogwoodModifier.Empty) {
  ComposeNode<WidgetNode, DogwoodApplier>(
    factory = { newWidget(Tags.AsyncImage) },
    update = {
      set(url) { recording.recorder.property(id, Tags.P1, JsonPrimitive(it)) }
      set(contentDescription) {
        recording.recorder.property(id, Tags.P2, if (it == null) JsonNull else JsonPrimitive(it))
      }
      set(modifier) { if (it.elements.isNotEmpty()) recording.recorder.modifiers(id, it.elements) }
    },
  )
}

@Composable
fun Card(modifier: DogwoodModifier = DogwoodModifier.Empty, content: @Composable () -> Unit) {
  Container(Tags.Card, modifier, content)
}

@Composable
fun Badge(text: String, selected: Boolean, modifier: DogwoodModifier = DogwoodModifier.Empty) {
  ComposeNode<WidgetNode, DogwoodApplier>(
    factory = { newWidget(Tags.Badge) },
    update = {
      set(text) { recording.recorder.property(id, Tags.P1, JsonPrimitive(it)) }
      set(selected) { recording.recorder.property(id, Tags.P2, JsonPrimitive(it)) }
      set(modifier) { if (it.elements.isNotEmpty()) recording.recorder.modifiers(id, it.elements) }
    },
  )
}

@Composable
fun Divider(modifier: DogwoodModifier = DogwoodModifier.Empty) {
  ComposeNode<WidgetNode, DogwoodApplier>(
    factory = { newWidget(Tags.Divider) },
    update = {
      set(modifier) { if (it.elements.isNotEmpty()) recording.recorder.modifiers(id, it.elements) }
    },
  )
}

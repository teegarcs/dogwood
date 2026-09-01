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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The Phase 0 and Phase 1 binding dictionary, hand-assigned. */
object Tags {
  // Segment 0 -- layout primitives.
  val Text = widgetTag(Segments.LAYOUT, 1)
  val Column = widgetTag(Segments.LAYOUT, 2)
  val Row = widgetTag(Segments.LAYOUT, 3)
  val Box = widgetTag(Segments.LAYOUT, 4)
  val Spacer = widgetTag(Segments.LAYOUT, 5)

  // Segment 1 -- the registered design-system slice.
  val VerticalList = widgetTag(Segments.DESIGN_SYSTEM, 10)
  val HorizontalList = widgetTag(Segments.DESIGN_SYSTEM, 11)

  /** Generated, and named here so tests can find the node they mean. */
  val TextInput = widgetTag(Segments.DESIGN_SYSTEM, 13)

  /** The single content slot every container in this slice declares. */
  val Content = ChildrenTag(1)

  /** A lazy container's placeholder template: one node, repeated by the host outside the window. */
  val Placeholder = ChildrenTag(2)

  /** Property tags are parameter-declaration order, widget-scoped. */
  val P1 = PropertyTag(1)
  val P2 = PropertyTag(2)
  val P3 = PropertyTag(3)
  val P4 = PropertyTag(4)
  val P5 = PropertyTag(5)
  val P6 = PropertyTag(6)
  val P7 = PropertyTag(7)
  val P8 = PropertyTag(8)

  /** Event tags are parameter-declaration order, widget-scoped. */
  val OnClick = EventTag(1)

  /** A lazy container's viewport report: first visible, last visible, scrolling. */
  val OnViewport = EventTag(1)
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

fun DogwoodModifier.padding(dp: Int): DogwoodModifier = then(ModifierTags.PADDING, JsonPrimitive(dp))
fun DogwoodModifier.fillMaxWidth(fraction: Float = 1.0f): DogwoodModifier =
  then(ModifierTags.FILL_MAX_WIDTH, JsonPrimitive(fraction))
fun DogwoodModifier.size(dp: Int): DogwoodModifier = then(ModifierTags.SIZE, JsonPrimitive(dp))
fun DogwoodModifier.alpha(alpha: Float): DogwoodModifier = then(ModifierTags.ALPHA, JsonPrimitive(alpha))

/** `size` sets both dimensions; these set one, which is usually what a card wants. */
fun DogwoodModifier.width(dp: Int): DogwoodModifier = then(ModifierTags.WIDTH, JsonPrimitive(dp))
fun DogwoodModifier.height(dp: Int): DogwoodModifier = then(ModifierTags.HEIGHT, JsonPrimitive(dp))

// ---------------------------------------------------------------------------
// Scoped modifiers
// ---------------------------------------------------------------------------

/**
 * Scope markers, so a scoped modifier used outside its scope is a **compile error**.
 *
 * `weight` and `align` are not free functions in Compose; they are members of `RowScope`,
 * `ColumnScope` and `BoxScope`, and that is not an accident of API style. A weight outside a row
 * or a column has no meaning, and roadmap.md Phase 2 is explicit that "out-of-scope use must be
 * a build error rather than a silent drop".
 *
 * It used to be a silent drop here: the host had a `weight` branch that returned the modifier
 * unchanged when no scope was present, so the layout was quietly wrong and nothing said so. The
 * scopes below make the mistake unwritable instead, which is strictly better than diagnosing it.
 */
@DslMarker
annotation class DogwoodLayoutScope

@DogwoodLayoutScope
interface DogwoodRowScope {
  /** Distributes remaining horizontal space. Only meaningful inside a row. */
  fun DogwoodModifier.weight(weight: Float): DogwoodModifier =
    then(ModifierTags.WEIGHT, JsonPrimitive(weight))

  /** Vertical alignment within the row. */
  fun DogwoodModifier.align(alignment: VerticalAlignment): DogwoodModifier =
    then(ModifierTags.ALIGN, JsonPrimitive(alignment.ordinal))
}

@DogwoodLayoutScope
interface DogwoodColumnScope {
  /** Distributes remaining vertical space. Only meaningful inside a column. */
  fun DogwoodModifier.weight(weight: Float): DogwoodModifier =
    then(ModifierTags.WEIGHT, JsonPrimitive(weight))

  /** Horizontal alignment within the column. */
  fun DogwoodModifier.align(alignment: HorizontalAlignment): DogwoodModifier =
    then(ModifierTags.ALIGN, JsonPrimitive(alignment.ordinal))
}

@DogwoodLayoutScope
interface DogwoodBoxScope {
  /** Alignment within the box. */
  fun DogwoodModifier.align(alignment: BoxAlignment): DogwoodModifier =
    then(ModifierTags.ALIGN, JsonPrimitive(alignment.ordinal))
}

/**
 * Alignments cross as their ordinal.
 *
 * They are enumerations rather than the host's own alignment objects because an ordinal is a
 * value; `Alignment.CenterVertically` is a host object with no serializable form, and reaching
 * for it would need the deferred-expression protocol for no benefit.
 */
enum class VerticalAlignment { Top, CenterVertically, Bottom }
enum class HorizontalAlignment { Start, CenterHorizontally, End }
enum class BoxAlignment { TopStart, TopCenter, TopEnd, CenterStart, Center, CenterEnd, BottomStart, BottomCenter, BottomEnd }

internal object RowScopeInstance : DogwoodRowScope
internal object ColumnScopeInstance : DogwoodColumnScope
internal object BoxScopeInstance : DogwoodBoxScope

/** The modifier tag space. Segment-encoded like widget tags; see ADR-009. */
internal object ModifierTags {
  const val PADDING = 1
  const val FILL_MAX_WIDTH = 2
  const val WEIGHT = 3
  const val SIZE = 4
  const val ALPHA = 5
  const val WIDTH = 6
  const val HEIGHT = 7
  const val ALIGN = 8
  const val CLIP = 9
  const val BACKGROUND = 10
}

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

internal fun newWidget(tag: WidgetTag): WidgetNode {
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
fun Text(
  text: String,
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  maxLines: Int = -1,
  /**
   * A named text style from the host's design system, such as `titleLarge`.
   *
   * A token rather than a `TextStyle`, for the same reason colours are tokens: the guest cannot
   * construct one, and a literal could not follow the host's typography -- including the font
   * family, which is the design-system-first answer to the font half of the resources subsystem.
   */
  style: String? = null,
) {
  ComposeNode<WidgetNode, DogwoodApplier>(
    factory = { newWidget(Tags.Text) },
    update = {
      set(text) { recording.recorder.property(id, Tags.P1, JsonPrimitive(it)) }
      // Absence IS the "use host default" sentinel, so an unset maxLines sends nothing.
      set(maxLines) { if (it >= 0) recording.recorder.property(id, Tags.P2, JsonPrimitive(it)) }
      set(style) { if (it != null) recording.recorder.property(id, Tags.P3, JsonPrimitive(it)) }
      set(modifier) { if (it.elements.isNotEmpty()) recording.recorder.modifiers(id, it.elements) }
    },
  )
}

/**
 * Text the host formats, from a recipe rather than a string.
 *
 * For everything the sandbox cannot render itself -- money, dates, decimal separators, relative
 * times. See [Formats]. The number crosses, not the rendered string, so a device that changes
 * locale re-renders correctly with no traffic at all.
 */
@Composable
fun Text(
  value: DogwoodExpression,
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  maxLines: Int = -1,
  style: String? = null,
) {
  ComposeNode<WidgetNode, DogwoodApplier>(
    factory = { newWidget(Tags.Text) },
    update = {
      set(value) { recording.recorder.property(id, Tags.P4, it.toJson()) }
      set(maxLines) { if (it >= 0) recording.recorder.property(id, Tags.P2, JsonPrimitive(it)) }
      set(style) { if (it != null) recording.recorder.property(id, Tags.P3, JsonPrimitive(it)) }
      set(modifier) { if (it.elements.isNotEmpty()) recording.recorder.modifiers(id, it.elements) }
    },
  )
}

@Composable
fun Column(
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  content: @Composable DogwoodColumnScope.() -> Unit,
) {
  Container(Tags.Column, modifier) { ColumnScopeInstance.content() }
}

@Composable
fun Row(
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  onClick: (() -> Unit)? = null,
  content: @Composable DogwoodRowScope.() -> Unit,
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
    content = { Children(Tags.Content) { RowScopeInstance.content() } },
  )
}

@Composable
fun Box(
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  content: @Composable DogwoodBoxScope.() -> Unit = {},
) {
  Container(Tags.Box, modifier) { BoxScopeInstance.content() }
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

/**
 * A vertically scrolling list.
 *
 * The host renders only the visible children, which is the expensive half of laziness. The guest
 * still composes and sends every child: guest-side windowing is a Phase 4 subsystem and this is
 * not it. A list of ten thousand rows would still cross ten thousand rows.
 */
@Composable
fun VerticalList(
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  spacingDp: Int = 0,
  contentPaddingDp: Int = 0,
  /** Pass one to read the visible range or to declare a scroll target. Null costs nothing. */
  state: DogwoodLazyListState? = null,
  content: @Composable DogwoodColumnScope.() -> Unit,
) {
  ListContainer(Tags.VerticalList, modifier, spacingDp, contentPaddingDp, state) {
    ColumnScopeInstance.content()
  }
}

/** A horizontally scrolling list. Same laziness caveat as [VerticalList]. */
@Composable
fun HorizontalList(
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  spacingDp: Int = 0,
  contentPaddingDp: Int = 0,
  state: DogwoodLazyListState? = null,
  content: @Composable DogwoodRowScope.() -> Unit,
) {
  ListContainer(Tags.HorizontalList, modifier, spacingDp, contentPaddingDp, state) {
    RowScopeInstance.content()
  }
}

@Composable
private fun ListContainer(
  tag: WidgetTag,
  modifier: DogwoodModifier,
  spacingDp: Int,
  contentPaddingDp: Int,
  state: DogwoodLazyListState?,
  /** -1 means "every item is present"; anything else is a window into a longer list. */
  itemCount: Int = -1,
  windowStart: Int = 0,
  placeholder: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  // Read here, in the composable body, not inside `update`. That is what subscribes this call
  // site to the holder's snapshot state; a read inside `update` would happen after the
  // composition had already decided not to recompose, and a declared target would never cross.
  val targetSequence = state?.targetSequence ?: 0
  val targetIndex = state?.targetIndex ?: 0
  val targetAnimated = state?.targetAnimated ?: false
  val observed = state != null

  ComposeNode<WidgetNode, DogwoodApplier>(
    factory = { newWidget(tag) },
    update = {
      set(spacingDp) { recording.recorder.property(id, Tags.P1, JsonPrimitive(it)) }
      set(contentPaddingDp) { recording.recorder.property(id, Tags.P2, JsonPrimitive(it)) }
      set(targetIndex) { recording.recorder.property(id, Tags.P3, JsonPrimitive(it)) }
      set(targetSequence) { recording.recorder.property(id, Tags.P4, JsonPrimitive(it)) }
      // Presence has to be a property: the host cannot see guest closures, so it cannot know
      // whether reporting the viewport would be observed by anyone. Reporting unconditionally
      // would cost a crossing per item boundary on every list on the screen.
      set(observed) { recording.recorder.property(id, Tags.P5, JsonPrimitive(it)) }
      set(targetAnimated) { recording.recorder.property(id, Tags.P6, JsonPrimitive(it)) }
      set(itemCount) { if (it >= 0) recording.recorder.property(id, Tags.P7, JsonPrimitive(it)) }
      set(windowStart) { recording.recorder.property(id, Tags.P8, JsonPrimitive(it)) }
      set(state) { holder ->
        if (holder == null) {
          recording.lambdas.clear(id, Tags.OnViewport)
        } else {
          recording.lambdas.set(id, Tags.OnViewport) { args ->
            holder.report(
              first = args.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 0,
              last = args.getOrNull(1)?.jsonPrimitive?.intOrNull ?: -1,
              scrolling = args.getOrNull(2)?.jsonPrimitive?.booleanOrNull ?: false,
            )
          }
        }
      }
    },
    content = {
      Children(Tags.Content, content)
      // Composed once. The host repeats this one node for every index outside the window, which
      // is why there is no placeholder *pool*: Compose's lazy item recycling is the pool.
      if (placeholder != null) Children(Tags.Placeholder, placeholder)
    },
  )
}

/** The windowed form, used by [LazyVerticalList] and [LazyHorizontalList]. */
@Composable
internal fun ListContainerWindowed(
  tag: WidgetTag,
  modifier: DogwoodModifier,
  spacingDp: Int,
  contentPaddingDp: Int,
  state: DogwoodLazyListState,
  itemCount: Int,
  window: IntRange,
  placeholder: @Composable () -> Unit,
  content: @Composable () -> Unit,
) {
  ListContainer(
    tag = tag,
    modifier = modifier,
    spacingDp = spacingDp,
    contentPaddingDp = contentPaddingDp,
    state = state,
    itemCount = itemCount,
    windowStart = if (window.isEmpty()) 0 else window.first,
    placeholder = placeholder,
    content = content,
  )
}

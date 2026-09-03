/*
 * Project Dogwood -- the web host's mirror of the guest tree.
 *
 * The same "snapshot mirror" strategy `dogwood-host` uses on the Java Virtual Machine and Android:
 * every node property is snapshot state, so applying a change invalidates exactly the composables
 * that read it. It is reimplemented here rather than shared because `dogwood-host` cannot compile
 * for WebAssembly as it stands -- see this module's `build.gradle.kts` -- and duplicating a
 * hundred lines of applier was cheaper than restructuring a module another agent is editing.
 * **That duplication is a debt, not a design**, and the report accompanying this work says what
 * paying it would involve.
 *
 * Two invariants are carried over deliberately, because both are load-bearing and neither is
 * obvious:
 *
 *   - Changes within a batch are applied **strictly in order**. They are interdependent: an add
 *     refers to a node an earlier create made, and a move's indices are relative to the state the
 *     earlier changes left.
 *   - An unknown widget tag becomes a **placeholder node**, never a dropped one. A dropped node
 *     would shift every sibling index in the same batch, so a client one dictionary version behind
 *     would not merely miss a widget -- it would misplace everything after it.
 */
package dev.dogwood.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.dogwood.protocol.ChangeBatch
import dev.dogwood.protocol.ChildAdd
import dev.dogwood.protocol.ChildMove
import dev.dogwood.protocol.ChildRemove
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.ModifierElem
import dev.dogwood.protocol.ModifierSet
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.Segments
import dev.dogwood.protocol.WidgetTag
import dev.dogwood.protocol.widgetTag
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * One node, as a binding sees it.
 *
 * The same interface `dogwood-host` declares, and for the same reason: the bindings read the tree
 * only through this, so the strategy behind the tree can be swapped without touching one of them.
 */
interface WidgetView {
  val id: Id
  val tag: WidgetTag
  val modifiers: List<ModifierElem>

  fun property(tag: Int): JsonElement?

  /** Every property tag this node actually carries, which is how skew becomes visible at all. */
  fun propertyTags(): Set<Int>

  fun children(slot: Int): List<WidgetView>
}

/** One node in the mirror. Every mutable field is snapshot state. */
class WebNode(
  override val id: Id,
  override val tag: WidgetTag,
) : WidgetView {
  private val properties = mutableStateMapOf<Int, JsonElement>()

  override var modifiers by mutableStateOf<List<ModifierElem>>(emptyList())
    internal set

  private val slots = mutableMapOf<Int, SnapshotStateList<WebNode>>()

  override fun property(tag: Int): JsonElement? = properties[tag]

  override fun propertyTags(): Set<Int> = properties.keys

  override fun children(slot: Int): List<WidgetView> = slot(slot)

  internal fun set(tag: Int, value: JsonElement) {
    properties[tag] = value
  }

  internal fun slot(tag: Int): SnapshotStateList<WebNode> = slots.getOrPut(tag) { mutableStateListOf() }

  internal fun allSlots(): Collection<SnapshotStateList<WebNode>> = slots.values
}

/**
 * Applies protocol batches to the mirror.
 *
 * [unknownWidgetTags] accumulates every tag this client could not name, which is the web reading
 * of the skew report: a payload built against a newer dictionary renders placeholders, and without
 * this the only evidence would be a screen that looks slightly wrong.
 */
class WebTree {
  val root = WebNode(Id(0), WidgetTag(0))

  private val byId = HashMap<Int, WebNode>().apply { put(0, root) }

  /**
   * Drops every node, so a re-attached guest starts from an empty screen.
   *
   * Without it, closing an experience left the previous guest's tree rendered until its
   * replacement produced a first batch -- a screen showing one guest's content while another one
   * was starting.
   */
  fun clear() {
    root.slot(1).clear()
    byId.keys.retainAll(setOf(0))
  }

  val unknownWidgetTags = mutableSetOf<Int>()

  /** The sequence number of the last batch applied, which every outbound event carries. */
  var appliedSequence: Int = 0
    private set

  fun apply(batch: ChangeBatch) {
    for (change in batch.g) {
      when (change) {
        is Create -> {
          if (!WebDictionary.knows(change.w)) unknownWidgetTags += change.w.value
          byId[change.i.value] = WebNode(change.i, change.w)
        }

        is PropertySet -> node(change.i).set(change.p.value, change.v)

        is ModifierSet -> node(change.i).modifiers = change.e

        is ChildAdd -> node(change.i).slot(change.s.value).add(change.x, node(change.c))

        is ChildRemove -> {
          val slot = node(change.i).slot(change.s.value)
          for (offset in 0 until change.n) purge(slot[change.x + offset])
          repeat(change.n) { slot.removeAt(change.x) }
        }

        is ChildMove -> {
          val slot = node(change.i).slot(change.s.value)
          val moved = ArrayList<WebNode>(change.n)
          repeat(change.n) { moved.add(slot.removeAt(change.f)) }
          val destination = if (change.f > change.t) change.t else change.t - change.n
          slot.addAll(destination, moved)
        }
      }
    }
    appliedSequence = batch.q
  }

  private fun node(id: Id): WebNode =
    byId[id.value] ?: error("batch referenced node ${id.value} before creating it")

  /**
   * Identifiers are never reused within a composition, so removal may forget them outright.
   *
   * Depth first: forgetting only the root of a detached subtree would leave every descendant in
   * [byId] -- reachable, addressable, and invisible.
   */
  private fun purge(node: WebNode) {
    byId.remove(node.id.value)
    for (slot in node.allSlots()) for (child in slot) purge(child)
  }

  /**
   * The applied tree as text, for a harness that has to prove something arrived.
   *
   * Not a debugging convenience: the browser verification for this profile reads it, because
   * "Compose drew something" and "Compose drew *the guest's tree*" are different claims and only
   * the second one is worth making.
   */
  fun describe(): String = buildString { describe(root, 0, this) }

  private fun describe(node: WebNode, depth: Int, out: StringBuilder) {
    repeat(depth) { out.append("  ") }
    out.append(WebDictionary.name(node.tag)).append('#').append(node.id.value)
    for (tag in node.propertyTags().sorted()) {
      out.append(' ').append(tag).append('=').append(node.property(tag))
    }
    if (node.modifiers.isNotEmpty()) {
      out.append(" mods=")
      out.append(node.modifiers.joinToString(",") { "${it.t.local}:${it.v}" })
    }
    out.append('\n')
    for (slot in node.allSlots()) for (child in slot) describe(child, depth + 1, out)
  }
}

/**
 * Everything this client can render.
 *
 * Segment 0 only -- the five layout primitives -- which is the honest scope of this host. The
 * registered design-system segment is generated by a Java-Virtual-Machine code generator into
 * `dogwood-host`, and reaching it from here is the work the report describes rather than work this
 * module pretends to have done. [segmentVersions] therefore names exactly one segment, and
 * [WebDelivery] will refuse any payload that asks for another -- which is the correct behaviour
 * for a client that genuinely cannot draw one, and is the check working rather than failing.
 */
object WebDictionary {
  val Text = widgetTag(Segments.LAYOUT, 1)
  val Column = widgetTag(Segments.LAYOUT, 2)
  val Row = widgetTag(Segments.LAYOUT, 3)
  val Box = widgetTag(Segments.LAYOUT, 4)
  val Spacer = widgetTag(Segments.LAYOUT, 5)

  private val known = setOf(Text.value, Column.value, Row.value, Box.value, Spacer.value)

  fun knows(tag: WidgetTag): Boolean = tag.value in known

  /** Per-segment versions, handed to [WebDelivery] and compared against the sidecar manifest. */
  val segmentVersions: Map<String, Int> = mapOf(
    // Generated, in `dogwood-wire`, and shared with every other host. This was a literal `1`
    // beside the identical literal in `dogwood-host`, with nothing comparing them -- the exact
    // drift the identifier spaces were consolidated to end.
    //
    // The design-system segment is deliberately absent: this host binds layout only, and saying so
    // by omission is what makes the delivery check refuse a payload that needs more.
    dev.dogwood.protocol.DogwoodSegments.LAYOUT to
      dev.dogwood.protocol.DogwoodSegments.LAYOUT_VERSION,
  )

  /** A readable name for a tag, for reports and for [WebTree.describe]. */
  fun name(tag: WidgetTag): String = when (tag.value) {
    0 -> "Root"
    Text.value -> "Text"
    Column.value -> "Column"
    Row.value -> "Row"
    Box.value -> "Box"
    Spacer.value -> "Spacer"
    else -> "Unknown(${tag.segment}/${tag.local})"
  }
}

/*
 * Absence-reading accessors, carried over from `dogwood-host/WidgetView.kt`.
 *
 * Absence is always the "use host default" sentinel, so a reader must be able to tell "the guest
 * sent nothing" from "the guest sent something that looks empty". `JsonNull` is a `JsonPrimitive`
 * whose `content` is the four-character string "null", so a reader that forgets to exclude it
 * renders the word on screen -- which is why it is excluded here once rather than at each call.
 */

private fun WidgetView.primitive(tag: Int): JsonPrimitive? =
  (property(tag) as? JsonPrimitive)?.takeIf { it !is JsonNull }

fun WidgetView.string(tag: Int, default: String = ""): String = primitive(tag)?.content ?: default

fun WidgetView.int(tag: Int, default: Int): Int = primitive(tag)?.content?.toIntOrNull() ?: default

fun WidgetView.float(tag: Int, default: Float): Float =
  primitive(tag)?.content?.toFloatOrNull() ?: default

fun WidgetView.boolean(tag: Int, default: Boolean): Boolean =
  primitive(tag)?.content?.toBooleanStrictOrNull() ?: default

fun WidgetView.stringOrNull(tag: Int): String? = primitive(tag)?.content

fun WidgetView.intOrNull(tag: Int): Int? = primitive(tag)?.content?.toIntOrNull()

fun WidgetView.floatOrNull(tag: Int): Float? = primitive(tag)?.content?.toFloatOrNull()

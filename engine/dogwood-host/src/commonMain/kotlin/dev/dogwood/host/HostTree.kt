/*
 * Project Dogwood -- the host's mirror of the guest tree.
 *
 * This is the "snapshot mirror" strategy from specs/layer-5-host.md: every node property is
 * snapshot state, so applying a change invalidates exactly the composables that read it and
 * Compose recomputes nothing else. The alternative -- an imperative applier mutating retained
 * nodes, which is what Redwood does -- is the comparison roadmap.md Phase 1 step 7 calls for,
 * and it has NOT been run. This implementation is therefore a choice pending measurement, not
 * a settled one.
 */
package dev.dogwood.host

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
import dev.dogwood.protocol.WidgetTag
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One node in the host's mirror. Every mutable field is snapshot state. */
class HostNode(
  val id: Id,
  val tag: WidgetTag,
) {
  val properties = mutableStateMapOf<Int, JsonElement>()

  var modifiers by mutableStateOf<List<ModifierElem>>(emptyList())
    internal set

  private val slots = mutableMapOf<Int, SnapshotStateList<HostNode>>()

  fun slot(tag: Int): SnapshotStateList<HostNode> = slots.getOrPut(tag) { mutableStateListOf() }

  /** All slots, for the depth-first purge on removal. */
  internal fun allSlots(): Collection<SnapshotStateList<HostNode>> = slots.values

  // Typed reads. Absence is the "use host default" sentinel, so every one takes a default.
  fun string(tag: Int, default: String = ""): String =
    properties[tag]?.jsonPrimitive?.contentOrNullSafe() ?: default

  fun int(tag: Int, default: Int): Int = properties[tag]?.jsonPrimitive?.intOrNull ?: default

  fun float(tag: Int, default: Float): Float = properties[tag]?.jsonPrimitive?.floatOrNull ?: default

  fun boolean(tag: Int, default: Boolean): Boolean =
    properties[tag]?.jsonPrimitive?.booleanOrNull ?: default

  fun has(tag: Int): Boolean = properties.containsKey(tag)
}

private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonPrimitive) content else null

/**
 * Applies protocol batches to the mirror.
 *
 * Two invariants the specification is explicit about, implemented rather than assumed:
 * changes within a batch are applied strictly in order, and an unknown widget tag becomes a
 * placeholder node rather than a dropped one, so that later index arithmetic in the same batch
 * stays consistent when a guest is built against a newer dictionary than the client carries.
 */
class HostTree {
  val root = HostNode(Id(0), WidgetTag(0))

  private val byId = HashMap<Int, HostNode>().apply { put(0, root) }

  /** Node identifiers the client's dictionary did not recognise. Telemetry, not a crash. */
  val unknownTags = mutableSetOf<Int>()

  /** The sequence number of the last batch applied, which every outbound event carries. */
  var appliedSequence: Int = 0
    private set

  fun apply(batch: ChangeBatch) {
    for (change in batch.g) {
      when (change) {
        is Create -> {
          if (!DogwoodDictionary.knows(change.w)) unknownTags += change.w.value
          byId[change.i.value] = HostNode(change.i, change.w)
        }
        is PropertySet -> node(change.i).properties[change.p.value] = change.v
        is ModifierSet -> node(change.i).modifiers = change.e
        is ChildAdd -> node(change.i).slot(change.s.value).add(change.x, node(change.c))
        is ChildRemove -> {
          val slot = node(change.i).slot(change.s.value)
          for (offset in 0 until change.n) purge(slot[change.x + offset])
          repeat(change.n) { slot.removeAt(change.x) }
        }
        is ChildMove -> {
          val slot = node(change.i).slot(change.s.value)
          val moved = ArrayList<HostNode>(change.n)
          repeat(change.n) { moved.add(slot.removeAt(change.f)) }
          val destination = if (change.f > change.t) change.t else change.t - change.n
          slot.addAll(destination, moved)
        }
      }
    }
    appliedSequence = batch.q
  }

  private fun node(id: Id): HostNode =
    byId[id.value] ?: error("batch referenced node ${id.value} before creating it")

  /** Identifiers are never reused within a composition, so removal may forget them outright. */
  private fun purge(node: HostNode) {
    byId.remove(node.id.value)
    for (slot in node.allSlots()) for (child in slot) purge(child)
  }
}

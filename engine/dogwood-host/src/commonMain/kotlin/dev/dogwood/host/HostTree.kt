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
  override val id: Id,
  override val tag: WidgetTag,
) : WidgetView {
  val properties = mutableStateMapOf<Int, JsonElement>()

  override var modifiers by mutableStateOf<List<ModifierElem>>(emptyList())
    internal set

  override fun property(tag: Int): JsonElement? = properties[tag]

  override fun propertyTags(): Set<Int> = properties.keys

  override fun children(slot: Int): List<WidgetView> = this.slot(slot)

  private val slots = mutableMapOf<Int, SnapshotStateList<HostNode>>()

  fun slot(tag: Int): SnapshotStateList<HostNode> = slots.getOrPut(tag) { mutableStateListOf() }

  /** All slots, for the depth-first purge on removal. */
  internal fun allSlots(): Collection<SnapshotStateList<HostNode>> = slots.values

}

/**
 * Applies protocol batches to the mirror.
 *
 * Two invariants the specification is explicit about, implemented rather than assumed:
 * changes within a batch are applied strictly in order, and an unknown widget tag becomes a
 * placeholder node rather than a dropped one, so that later index arithmetic in the same batch
 * stays consistent when a guest is built against a newer dictionary than the client carries.
 */
class HostTree(
  /**
   * Watches nodes as they are detached.
   *
   * Off by default: watching costs a weak reference per detached node and a periodic collection,
   * and a host that is not investigating a leak should not pay for one. See `Leaks.kt`.
   */
  private val leakDetector: DogwoodLeakWatcher = DogwoodLeakWatcher.None,
  /**
   * Where an unrecognised widget tag is reported.
   *
   * Passed in rather than owned, because `Skew.kt` promises one report per experience and a tree
   * that kept its own set would quietly break that promise: the widget tag is the *most* important
   * thing a client can fail to recognise, and for a long time it was the one category that never
   * reached the report anybody actually reads.
   */
  val skew: SkewReport = SkewReport(),
) {
  val root = HostNode(Id(0), WidgetTag(0))

  private val byId = HashMap<Int, HostNode>().apply { put(0, root) }

  /** The sequence number of the last batch applied, which every outbound event carries. */
  var appliedSequence: Int = 0
    private set

  fun apply(batch: ChangeBatch) {
    for (change in batch.g) {
      when (change) {
        is Create -> {
          if (!DogwoodDictionary.knows(change.w)) skew.unknownWidgetTags += change.w.value
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

  /**
   * Identifiers are never reused within a composition, so removal may forget them outright.
   *
   * Depth first, because forgetting only the root of a detached subtree would leave every
   * descendant in [byId] -- reachable, addressable, and invisible.
   */
  private fun purge(node: HostNode) {
    byId.remove(node.id.value)
    leakDetector.watch(node, "detached node ${node.id.value}, widget ${node.tag.value}")
    for (slot in node.allSlots()) for (child in slot) purge(child)
  }
}

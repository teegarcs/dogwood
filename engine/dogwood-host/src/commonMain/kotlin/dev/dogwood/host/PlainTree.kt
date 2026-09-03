/*
 * Project Dogwood -- the imperative alternative to the snapshot mirror.
 *
 * The strategy roadmap.md Phase 1 step 7 asks to measure against, and the one Redwood actually
 * uses: retained nodes holding ordinary fields, mutated in place, with a single invalidation
 * signal at the root when a batch has been applied.
 *
 * The trade is stark and worth stating before the numbers arrive.
 *
 *   - The snapshot mirror allocates a state object per property and a state list per slot, and
 *     records every read. A one-property change then recomposes one binding.
 *   - This tree allocates none of that and applies a change with a plain map write. But nothing
 *     is observed, so the only way Compose learns anything happened is a generation counter at
 *     the root -- which recomposes the entire tree, every time.
 *
 * Which wins is a function of batch size, and that is exactly why the roadmap asks for
 * 1 / 10 / 100 / 1,000 rather than for an opinion.
 */
package dev.dogwood.host

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
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
import dev.dogwood.protocol.ProtocolMismatch
import dev.dogwood.protocol.TreeShape
import dev.dogwood.protocol.rejection
import kotlinx.serialization.json.JsonElement

/** A node with no snapshot state anywhere in it. */
class PlainNode(
  override val id: Id,
  override val tag: WidgetTag,
) : WidgetView {
  private val properties = HashMap<Int, JsonElement>()
  private val slots = HashMap<Int, MutableList<PlainNode>>()

  override var modifiers: List<ModifierElem> = emptyList()
    internal set

  override fun property(tag: Int): JsonElement? = properties[tag]

  override fun propertyTags(): Set<Int> = properties.keys

  override fun children(slot: Int): List<WidgetView> = slot(slot)

  internal fun slot(tag: Int): MutableList<PlainNode> = slots.getOrPut(tag) { mutableListOf() }

  internal fun set(tag: Int, value: JsonElement) {
    properties[tag] = value
  }

  internal fun allSlots(): Collection<MutableList<PlainNode>> = slots.values

  /**
   * Reads a slot without creating one.
   *
   * Validation must not leave a trace: the ordinary accessor materialises an empty slot on first
   * mention, which would make a rejected batch visible in the tree it was rejected from.
   */
  internal fun slotOrNull(tag: Int): List<PlainNode>? = slots[tag]

  /** The slot tags this node holds children under. Validation walks them to model a removal. */
  internal fun slotTags(): Set<Int> = slots.keys
}

/**
 * Applies batches by mutating retained nodes, then bumps [generation].
 *
 * [generation] is the single piece of snapshot state in the whole strategy. A composable that
 * reads it recomposes on every applied batch, however small.
 */
class PlainTree {
  val root = PlainNode(Id(0), WidgetTag(0))

  val generation: MutableState<Int> = mutableStateOf(0)

  private val byId = HashMap<Int, PlainNode>().apply { put(0, root) }

  val unknownTags = mutableSetOf<Int>()

  var appliedSequence: Int = 0
    private set


  /*
   * The shape validation reads. Read-only, and answered from the live tree: the shadow copies out
   * of it lazily, so a batch that touches one slot costs one list copy rather than a tree walk.
   */
  private val shape = object : TreeShape {
    override fun exists(id: Int): Boolean = byId.containsKey(id)

    override fun slotTags(id: Int): Set<Int> = byId[id]?.slotTags() ?: emptySet()

    override fun childIds(id: Int, slot: Int): List<Int> =
      byId[id]?.slotOrNull(slot)?.map { it.id.value } ?: emptyList()
  }

  /**
   * Rejects the whole batch rather than applying a prefix of it.
   *
   * Changes are applied strictly in order, so the eleventh can fail after the first ten have
   * landed -- and what is left on screen is then a tree the guest never composed, which every
   * later diff compounds against. Checking first costs a pass over the change list with no nodes
   * built and no state written; the alternative costs a silently desynchronised tree. See
   * `BatchValidation.kt`.
   */
  fun apply(batch: ChangeBatch) {
    batch.rejection(shape)?.let { throw ProtocolMismatch(it) }

    for (change in batch.g) {
      when (change) {
        is Create -> {
          if (!DogwoodDictionary.knows(change.w)) unknownTags += change.w.value
          byId[change.i.value] = PlainNode(change.i, change.w)
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
          val moved = ArrayList<PlainNode>(change.n)
          repeat(change.n) { moved.add(slot.removeAt(change.f)) }
          val destination = if (change.f > change.t) change.t else change.t - change.n
          slot.addAll(destination, moved)
        }
      }
    }
    appliedSequence = batch.q
    // The one invalidation. Everything that reads it recomposes.
    generation.value = generation.value + 1
  }

  private fun node(id: Id): PlainNode =
    byId[id.value] ?: error("batch referenced node ${id.value} before creating it")

  private fun purge(node: PlainNode) {
    byId.remove(node.id.value)
    for (slot in node.allSlots()) for (child in slot) purge(child)
  }
}

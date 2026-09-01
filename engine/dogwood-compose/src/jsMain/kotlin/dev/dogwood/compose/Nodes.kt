/*
 * Project Dogwood -- Phase 0 measurement harness.
 *
 * The guest node tree and the change recorder, per specs/layer-4-sandbox.md section 3.
 * This is a Phase 0 stand-in for the real DogwoodApplier: it is deliberately the SHAPE the
 * Layer 4 specification describes, including deferred-expression recording and the
 * depth-first purge, because experiment 0.2 explicitly measures "a real composition with a
 * trivial Dogwood Applier and Dogwood-shaped stubs" -- a bare Applier would understate
 * recording cost.
 */
package dev.dogwood.compose

import androidx.compose.runtime.AbstractApplier
import dev.dogwood.protocol.Change
import dev.dogwood.protocol.ChangeBatch
import dev.dogwood.protocol.ChildAdd
import dev.dogwood.protocol.ChildMove
import dev.dogwood.protocol.ChildRemove
import dev.dogwood.protocol.ChildrenTag
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.ModifierElem
import dev.dogwood.protocol.ModifierSet
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.PropertyTag
import dev.dogwood.protocol.WidgetTag
import kotlinx.serialization.json.JsonElement

/**
 * A node in the guest's mirror of the tree the host will build.
 *
 * There are exactly two kinds, matching the Layer 4 diagram: a [WidgetNode] is one bound
 * Compose call, and a [ChildrenNode] is one content slot within it.
 */
sealed class Node

/**
 * One bound Compose call. Its direct children are its content slots, never other widgets;
 * widgets hang off slots. This is what makes multi-slot components (a card with a header
 * slot and a body slot) representable without ambiguity.
 */
class WidgetNode(
  val id: Id,
  val tag: WidgetTag,
) : Node() {
  val slots = mutableListOf<ChildrenNode>()
}

/**
 * One content slot. Holds widgets, in order.
 *
 * A slot learns its parent only when Compose attaches it, and Compose attaches BOTTOM-UP:
 * a slot's children are inserted into it before the slot itself is inserted into its widget.
 * Until then the slot has no parent identifier to name in a `ChildAdd`, so those additions
 * are held and flushed at attach time. This is not an optimisation; without it the applier
 * cannot emit a well-formed batch at all.
 */
class ChildrenNode(
  val tag: ChildrenTag,
) : Node() {
  var parent: WidgetNode? = null
    private set

  val children = mutableListOf<WidgetNode>()

  /** True once this slot has been attached and may emit changes directly. */
  val attached: Boolean get() = parent != null

  fun attachTo(widget: WidgetNode, recorder: ChangeRecorder) {
    check(parent == null) { "children slot $tag attached twice" }
    parent = widget
    // Flush the additions that happened while this slot was still detached, in order.
    for ((index, child) in children.withIndex()) {
      recorder.childAdd(widget.id, tag, child.id, index)
    }
  }
}

/**
 * Accumulates protocol changes for one composition pass and hands them over as a single
 * [ChangeBatch]. Batching is architecturally load-bearing: per-crossing cost is only
 * acceptable because there are very few crossings.
 */
class ChangeRecorder {
  private var nextIdValue = 1
  private var sequence = 0
  private val changes = mutableListOf<Change>()

  /** Node identifiers are monotonic and never reused within a composition. */
  fun newId(): Id = Id(nextIdValue++)

  fun create(id: Id, tag: WidgetTag) {
    changes += Create(id, tag)
  }

  fun property(id: Id, tag: PropertyTag, value: JsonElement) {
    changes += PropertySet(id, tag, value)
  }

  fun modifiers(id: Id, elements: List<ModifierElem>) {
    changes += ModifierSet(id, elements)
  }

  fun childAdd(parent: Id, slot: ChildrenTag, child: Id, index: Int) {
    changes += ChildAdd(parent, slot, child, index)
  }

  fun childRemove(parent: Id, slot: ChildrenTag, index: Int, count: Int) {
    changes += ChildRemove(parent, slot, index, count)
  }

  fun childMove(parent: Id, slot: ChildrenTag, from: Int, to: Int, count: Int) {
    changes += ChildMove(parent, slot, from, to, count)
  }

  val pending: Int get() = changes.size

  /** Takes the accumulated changes, stamps them with the next sequence number, and resets. */
  fun takeBatch(): ChangeBatch {
    val batch = ChangeBatch(++sequence, changes.toList())
    changes.clear()
    return batch
  }

  // There is deliberately no `reset()`. An earlier one existed, unused, and it set `nextIdValue`
  // back to 1 -- which would have reused identifiers inside a live composition and let a stale
  // event land on whichever node inherited the number. The invariant is easier to keep when the
  // only way to get a fresh counter is a fresh recorder.
}

/**
 * Maps an (node identifier, [EventTag]) pair to the Kotlin lambda captured during
 * composition. The protocol carries only the tag; the closure never crosses the boundary.
 *
 * Reclamation is NOT automatic. [purge] is called from the applier's remove and clear paths
 * because otherwise a feed that creates and destroys ten thousand rows retains ten thousand
 * closures, each capturing its row's data.
 */
class LambdaSlots {
  private val slots = HashMap<Long, (List<JsonElement>) -> Unit>()

  private fun key(id: Id, tag: EventTag): Long =
    (id.value.toLong() shl 32) or (tag.value.toLong() and 0xFFFFFFFFL)

  fun set(id: Id, tag: EventTag, handler: (List<JsonElement>) -> Unit) {
    slots[key(id, tag)] = handler
  }

  fun dispatch(id: Id, tag: EventTag, args: List<JsonElement>): Boolean {
    val handler = slots[key(id, tag)] ?: return false
    handler(args)
    return true
  }

  /**
   * Drops one slot.
   *
   * For a handler that stops being registered while its node lives on -- a lazy list whose state
   * holder became null. Without it the old closure would keep receiving events and writing into a
   * holder the composition has forgotten.
   */
  fun clear(id: Id, tag: EventTag) {
    slots.remove(key(id, tag))
  }

  /** Drops every slot belonging to [id]. Called for each node in the depth-first purge. */
  fun purge(id: Id) {
    val prefix = id.value.toLong() shl 32
    val doomed = slots.keys.filter { (it and 0xFFFFFFFF00000000UL.toLong()) == prefix }
    for (k in doomed) slots.remove(k)
  }

  val size: Int get() = slots.size

  fun clear() = slots.clear()
}

/**
 * `AbstractApplier` publishes a `move` helper only for `MutableList<N>`, and this tree's
 * lists are typed more narrowly than that, so the same algorithm lives here.
 */
private fun <T> MutableList<T>.moveRange(from: Int, to: Int, count: Int) {
  if (from == to) return
  val moved = ArrayList<T>(count)
  for (i in 0 until count) moved.add(this[from + i])
  subList(from, from + count).clear()
  val destination = if (from > to) to else to - count
  addAll(destination, moved)
}

/**
 * The Phase 0 applier. Compose calls this as the tree changes; instead of manipulating user
 * interface objects it manipulates protocol nodes and records changes.
 *
 * Widgets are attached to their parent on the BOTTOM-UP pass, so a node's initial properties
 * are set before it is attached -- the subtlety Redwood's `NodeApplier` demonstrates.
 */
class DogwoodApplier(
  root: WidgetNode,
  private val recorder: ChangeRecorder,
  private val lambdas: LambdaSlots,
  /**
   * Called once at the end of applying a composition's changes.
   *
   * Batching here rather than after the frame call is what makes "one batch per composition
   * pass" true by construction rather than by convention, and it is why an exception thrown
   * mid-composition can never produce a partial batch: the hook is not reached.
   */
  private val onBatchReady: () -> Unit,
) : AbstractApplier<Node>(root) {

  override fun onEndChanges() {
    onBatchReady()
  }

  override fun insertTopDown(index: Int, instance: Node) {
    // Deliberately empty: attachment happens bottom-up.
  }

  override fun insertBottomUp(index: Int, instance: Node) {
    when (val parent = current) {
      is WidgetNode -> {
        // A widget's direct children are its content slots. A slot costs no protocol change
        // of its own; attaching it flushes the additions made while it was detached.
        instance as ChildrenNode
        parent.slots.add(index, instance)
        instance.attachTo(parent, recorder)
      }
      is ChildrenNode -> {
        instance as WidgetNode
        parent.children.add(index, instance)
        val owner = parent.parent
        if (owner != null) recorder.childAdd(owner.id, parent.tag, instance.id, index)
      }
    }
  }

  override fun remove(index: Int, count: Int) {
    when (val parent = current) {
      is WidgetNode -> {
        for (i in index until index + count) purgeSlot(parent.slots[i])
        parent.slots.subList(index, index + count).clear()
      }
      is ChildrenNode -> {
        for (i in index until index + count) purgeWidget(parent.children[i])
        parent.children.subList(index, index + count).clear()
        // A detached slot has emitted nothing yet, so there is nothing to undo.
        parent.parent?.let { recorder.childRemove(it.id, parent.tag, index, count) }
      }
    }
  }

  override fun move(from: Int, to: Int, count: Int) {
    when (val parent = current) {
      is WidgetNode -> parent.slots.moveRange(from, to, count)
      is ChildrenNode -> {
        parent.children.moveRange(from, to, count)
        parent.parent?.let { recorder.childMove(it.id, parent.tag, from, to, count) }
      }
    }
  }

  override fun onClear() {
    val rootNode = root
    if (rootNode is WidgetNode) {
      for (slot in rootNode.slots) purgeSlot(slot)
      rootNode.slots.clear()
    }
    lambdas.clear()
  }

  /** The depth-first purge Layer 4 requires. Without it, closures outlive their nodes. */
  private fun purgeWidget(node: WidgetNode) {
    lambdas.purge(node.id)
    for (slot in node.slots) purgeSlot(slot)
  }

  private fun purgeSlot(slot: ChildrenNode) {
    for (child in slot.children) purgeWidget(child)
  }
}

/*
 * Project Dogwood -- deciding whether a batch will apply, before any of it has.
 *
 * Decoding is already all-or-nothing: a batch whose *grammar* this client does not share is
 * rejected whole, and the tree already on screen keeps drawing. Applying was not. The changes in a
 * batch are applied strictly in order, and the eleventh of them can fail -- a reference to a node
 * that was never created, a removal running off the end of a slot -- after the first ten have
 * already landed. What remains on screen is then a tree the guest never composed and cannot reason
 * about, and every later batch is a diff against that.
 *
 * That is worse than the failure it comes from. A rejected batch costs one frame. A half-applied
 * one silently desynchronises the two trees, and the guest goes on sending diffs that assume the
 * half it did not get.
 *
 * So the batch is checked first, against a *shadow* of the tree that no real node ever enters, and
 * the real apply runs only if the shadow survived. The check is pure arithmetic over the change
 * list -- no nodes are built, no state is written, nothing Compose reads is touched -- which is why
 * it can be thrown away for free when it fails.
 *
 * Decision record: `adrs/layer-4/ADR-011-a-batch-applies-whole-or-not-at-all.md`.
 */
package dev.dogwood.protocol

/**
 * As much of a host tree as validation needs to see.
 *
 * Deliberately three read-only questions rather than the tree itself: the mobile host and the web
 * host have separate tree implementations with separate node types, and the thing that must not
 * diverge between them is the definition of "this batch will apply cleanly". Sharing the *rule*
 * while leaving each host its own storage is what this interface is for.
 */
interface TreeShape {
  /** True when [id] currently names a node. The root, id 0, always does. */
  fun exists(id: Int): Boolean

  /** Every slot tag [id] currently holds children under. Empty for an unknown node. */
  fun slotTags(id: Int): Set<Int>

  /** The children of one slot, in order. Empty for an unknown node or an untouched slot. */
  fun childIds(id: Int, slot: Int): List<Int>
}

/**
 * Reports why a batch cannot be applied, or `null` when it can.
 *
 * A string rather than an exception because a rejection is not exceptional: it is skew, and it is
 * recorded and survived exactly like every other kind. The message is what a team reads months
 * later in telemetry, so it names the change index -- the position in the batch, which is what a
 * guest-side recorder can be searched by -- along with what was expected and what arrived.
 */
fun ChangeBatch.rejection(shape: TreeShape): String? = BatchShadow(shape).check(this)

/**
 * A copy-on-write overlay of the parts of the tree a batch touches.
 *
 * Nothing is copied until a change refers to it, so a batch that adds one child to one slot
 * materialises one list, not a tree. This matters because validation runs on every batch, on the
 * user-interface thread, before every frame that carries changes.
 */
private class BatchShadow(private val shape: TreeShape) {
  /** Nodes this batch has created. They do not exist in [shape] yet, and will not until it applies. */
  private val created = HashSet<Int>()

  /** Nodes this batch has detached. They still exist in [shape], and must not be referred to again. */
  private val removed = HashSet<Int>()

  /** Slot contents, keyed by node and slot, materialised on first mention. */
  private val slots = HashMap<Long, MutableList<Int>>()

  private fun key(id: Int, slot: Int): Long = (id.toLong() shl 32) or (slot.toLong() and 0xFFFFFFFFL)

  private fun exists(id: Int): Boolean =
    id !in removed && (id in created || shape.exists(id))

  private fun slot(id: Int, slot: Int): MutableList<Int> =
    slots.getOrPut(key(id, slot)) { ArrayList(shape.childIds(id, slot)) }

  /**
   * Forgets a detached subtree, depth first.
   *
   * The host's own removal does exactly this, and for the same reason: forgetting only the root of
   * a detached subtree leaves every descendant addressable. Validation has to model it, because a
   * batch that removes a subtree and then refers to a node inside it is precisely the batch that
   * fails halfway through.
   *
   * Slots come from the shadow where the batch has touched them and from the real tree otherwise,
   * so a child added and then removed within one batch is forgotten too.
   */
  private fun forget(id: Int) {
    removed += id
    created -= id
    val tags = shape.slotTags(id) + slots.keys.mapNotNull { k ->
      if ((k ushr 32).toInt() == id) k.toInt() else null
    }
    for (tag in tags) for (child in slot(id, tag)) forget(child)
  }

  fun check(batch: ChangeBatch): String? {
    for ((index, change) in batch.g.withIndex()) {
      val problem = when (change) {
        is Create -> {
          // Identifiers are never reused within a composition, so a second create for a live
          // identifier is not a reset -- it orphans the node the first one made, leaving it
          // parented on screen and unreachable by identifier.
          if (exists(change.i.value)) "creates node ${change.i.value}, which already exists"
          else { created += change.i.value; removed -= change.i.value; null }
        }

        is PropertySet -> requireNode(change.i.value, "sets a property on")
        is ModifierSet -> requireNode(change.i.value, "sets modifiers on")

        is ChildAdd ->
          requireNode(change.i.value, "adds a child to")
            ?: requireNode(change.c.value, "adds, as a child,")
            ?: slot(change.i.value, change.s.value).let { children ->
              // An add may land one past the end -- that is an append -- so the legal range is
              // wider by one than it is for a removal.
              if (change.x !in 0..children.size) {
                "adds a child at index ${change.x} of a slot holding ${children.size}"
              } else {
                children.add(change.x, change.c.value); null
              }
            }

        is ChildRemove ->
          requireNode(change.i.value, "removes children from")
            ?: slot(change.i.value, change.s.value).let { children ->
              if (change.n < 0 || change.x < 0 || change.x + change.n > children.size) {
                "removes ${change.n} children from index ${change.x} of a slot holding ${children.size}"
              } else {
                repeat(change.n) { forget(children.removeAt(change.x)) }
                null
              }
            }

        is ChildMove ->
          requireNode(change.i.value, "moves children within")
            ?: slot(change.i.value, change.s.value).let { children ->
              // The destination is expressed before the removal, so it is adjusted by the size of
              // the block when moving forwards -- the same arithmetic the appliers do, restated
              // here because getting it wrong in either place is an out-of-bounds insert.
              val destination = if (change.f > change.t) change.t else change.t - change.n
              if (change.n < 0 || change.f < 0 || change.f + change.n > children.size ||
                destination < 0 || destination > children.size - change.n
              ) {
                "moves ${change.n} children from index ${change.f} to ${change.t} " +
                  "in a slot holding ${children.size}"
              } else {
                val moved = ArrayList<Int>(change.n)
                repeat(change.n) { moved.add(children.removeAt(change.f)) }
                children.addAll(destination, moved)
                null
              }
            }
      }
      if (problem != null) return "change $index $problem"
    }
    return null
  }

  private fun requireNode(id: Int, what: String): String? =
    if (exists(id)) null else "$what node $id, which does not exist"
}

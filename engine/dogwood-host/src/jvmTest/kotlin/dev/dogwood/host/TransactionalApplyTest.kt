/*
 * Project Dogwood -- a batch either applies or it does not.
 *
 * Decoding has always been all-or-nothing. Applying was not: changes land strictly in order, so a
 * bad one halfway down leaves the good ones above it on screen. These tests are about the tree
 * *after* a rejection -- not that the rejection happened, but that nothing of the batch survived
 * it, which is the only property that keeps the guest's diffs meaningful.
 */
package dev.dogwood.host

import dev.dogwood.protocol.ProtocolMismatch
import dev.dogwood.protocol.TreeShape
import dev.dogwood.protocol.rejection
import dev.dogwood.protocol.decodePositional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.measureTime

private fun batch(sequence: Int, vararg changes: String): String =
  "[$sequence,[${changes.joinToString(",")}]]"

private val TEXT = DogwoodDictionary.Text.value
private val COLUMN = DogwoodDictionary.Column.value

class TransactionalApplyTest {

  /** A column under the root holding two texts. The tree every rejection below starts from. */
  private fun seeded(): HostTree = HostTree().also {
    it.apply(
      decodePositional(
        batch(
          1,
          "[0,1,$COLUMN]", "[3,0,1,1,0]",
          "[0,2,$TEXT]", "[1,2,1,\"first\"]", "[3,1,1,2,0]",
          "[0,3,$TEXT]", "[1,3,1,\"second\"]", "[3,1,1,3,1]",
        ),
      ),
    )
  }

  private fun HostTree.texts(): List<String> =
    root.slot(1).first().slot(1).map { it.string(1) }

  private fun assertRejected(tree: HostTree, payload: String, because: String) {
    val before = tree.texts()
    val failure = assertFailsWith<ProtocolMismatch> { tree.apply(decodePositional(payload)) }
    assertTrue(because in (failure.message ?: ""), "expected \"$because\" in \"${failure.message}\"")
    assertEquals(before, tree.texts(), "the tree changed despite the batch being rejected")
    assertEquals(1, tree.appliedSequence, "a rejected batch must not advance the sequence")
  }

  @Test
  fun aDanglingReferenceLeavesTheTreeUntouched() {
    // The first two changes are perfectly good, and before this rule they landed. The third names
    // a node nobody created -- the exact shape of a guest and host that have drifted apart.
    assertRejected(
      seeded(),
      batch(2, "[1,2,1,\"changed\"]", "[1,3,1,\"also changed\"]", "[1,99,1,\"nobody\"]"),
      "node 99, which does not exist",
    )
  }

  @Test
  fun aRemovalPastTheEndLeavesTheTreeUntouched() {
    assertRejected(
      seeded(),
      batch(2, "[1,2,1,\"changed\"]", "[4,1,1,1,5]"),
      "removes 5 children from index 1",
    )
  }

  @Test
  fun anAddPastTheEndLeavesTheTreeUntouched() {
    assertRejected(
      seeded(),
      batch(2, "[0,4,$TEXT]", "[3,1,1,4,9]"),
      "adds a child at index 9",
    )
  }

  @Test
  fun aMoveOutOfRangeLeavesTheTreeUntouched() {
    assertRejected(
      seeded(),
      batch(2, "[5,1,1,0,7,2]"),
      "moves 2 children from index 0 to 7",
    )
  }

  @Test
  fun aReferenceIntoASubtreeThisBatchRemovedIsRejected() {
    // The subtlety the shadow exists for. Node 2 is real and reachable when the batch begins; the
    // removal at change 0 detaches it, and the host forgets every identifier underneath. A
    // validator that tracked only slot *sizes* would wave this through, and the apply would throw
    // after the removal had already happened.
    assertRejected(
      seeded(),
      batch(2, "[4,1,1,0,1]", "[1,2,1,\"gone\"]"),
      "node 2, which does not exist",
    )
  }

  @Test
  fun creatingAnIdentifierTwiceIsRejected() {
    // Not a throw in the applier -- it silently overwrites, orphaning the first node on screen
    // and unreachable by identifier, which is worse than a rejection.
    assertRejected(seeded(), batch(2, "[0,2,$TEXT]"), "already exists")
  }

  @Test
  fun aBatchThatRemovesAndRebuildsStillApplies() {
    // The check must not be so strict that ordinary composition trips it. This is what a list
    // re-keying actually sends: remove a range, create replacements, add them back.
    val tree = seeded()
    tree.apply(
      decodePositional(
        batch(
          2,
          "[4,1,1,0,2]",
          "[0,4,$TEXT]", "[1,4,1,\"third\"]", "[3,1,1,4,0]",
          "[0,5,$TEXT]", "[1,5,1,\"fourth\"]", "[3,1,1,5,1]",
          "[5,1,1,0,2,1]",
        ),
      ),
    )
    // The move is the last change and its arithmetic is the shadow's, restated: one child from
    // index 0 to index 2 in a slot of two lands it at the end, because the destination is
    // expressed before the removal.
    assertEquals(listOf("fourth", "third"), tree.texts())
    assertEquals(2, tree.appliedSequence)
  }

  @Test
  fun anIdentifierMayBeReusedAfterItsNodeIsRemoved() {
    // Identifiers are not reused *while live*; a batch that removes a node and then creates one
    // with the same identifier is legal, and rejecting it would break re-keying.
    val tree = seeded()
    tree.apply(
      decodePositional(
        batch(2, "[4,1,1,0,1]", "[0,2,$TEXT]", "[1,2,1,\"reborn\"]", "[3,1,1,2,0]"),
      ),
    )
    assertEquals(listOf("reborn", "second"), tree.texts())
  }

  /**
   * The cost, which is what decides whether all-or-nothing is affordable.
   *
   * The comparison that matters is against decoding, because the two run back to back on every
   * batch that carries changes: Phase 0 measured a whole-screen decode at 0.17 ms, and a check
   * that costs a fraction of that is free in any sense a frame budget cares about. This asserts a
   * loose ceiling rather than a number -- a continuous integration machine under load is not a
   * benchmark -- and prints the measurement so a regression is visible even when it passes.
   */
  @Test
  fun validationCostsFarLessThanDecoding() {
    val changes = ArrayList<String>()
    changes += "[0,1,$COLUMN]"
    changes += "[3,0,1,1,0]"
    for (id in 2..201) {
      changes += "[0,$id,$TEXT]"
      changes += "[1,$id,1,\"row $id\"]"
      changes += "[3,1,1,$id,${id - 2}]"
    }
    val payload = batch(1, *changes.toTypedArray())

    repeat(50) { HostTree().apply(decodePositional(payload)) }

    // The validator on its own, against a tree in the state a fresh `HostTree` is in: only the
    // root exists, which is what this batch expects. Measured separately from the apply because
    // the apply is the cost we were already paying, and the question is what the check added.
    val empty = object : TreeShape {
      override fun exists(id: Int) = id == 0
      override fun slotTags(id: Int) = emptySet<Int>()
      override fun childIds(id: Int, slot: Int) = emptyList<Int>()
    }

    val decoded = decodePositional(payload)
    val decode = measureTime { repeat(200) { decodePositional(payload) } } / 200
    val validate = measureTime { repeat(200) { decoded.rejection(empty) } } / 200
    val apply = measureTime { repeat(200) { HostTree().apply(decoded) } } / 200

    println("600-change batch: decode $decode, validate $validate, validate+apply $apply")
    assertTrue(
      apply < decode * 20,
      "validation and apply together ($apply) should stay well inside a frame next to decode ($decode)",
    )
  }
}

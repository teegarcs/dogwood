/*
 * Project Dogwood -- the host half of resynchronisation.
 *
 * `TransactionalApplyTest` proves a rejected batch changes nothing. That is containment, and Layer
 * 4 ADR-011 was explicit that containment is not repair: the tree on screen is intact but older
 * than the guest believes, and every later change is a diff against a tree that no longer exists
 * on the other side.
 *
 * These are about the repair. The property that matters is the one a partial implementation would
 * miss: after a rejection the host must **clear** before the guest re-sends, because what arrives
 * is a whole tree rather than a patch, and applying it onto the old one would duplicate every node.
 */
package dev.dogwood.host

import dev.dogwood.protocol.ProtocolMismatch
import dev.dogwood.protocol.decodePositional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun batch(sequence: Int, vararg changes: String): String =
  "[$sequence,[${changes.joinToString(",")}]]"

private val TEXT = DogwoodDictionary.Text.value
private val COLUMN = DogwoodDictionary.Column.value

class ResynchronisationTest {

  private fun seeded(): HostTree = HostTree().also {
    it.apply(
      decodePositional(
        batch(
          1,
          "[0,1,$COLUMN]", "[3,0,1,1,0]",
          "[0,2,$TEXT]", "[1,2,1,\"first\"]", "[3,1,1,2,0]",
        ),
      ),
    )
  }

  private fun HostTree.texts(): List<String> =
    root.slot(1).firstOrNull()?.slot(1)?.map { it.string(1) } ?: emptyList()

  @Test
  fun clearingLeavesNothingBehindForAResentTreeToCollideWith() {
    val tree = seeded()
    assertEquals(listOf("first"), tree.texts())

    tree.clear()
    assertEquals(emptyList(), tree.texts())
    assertEquals(0, tree.appliedSequence, "a cleared tree has applied nothing")

    // The guest re-sends the whole tree with fresh identifiers, continuing its counters. Without
    // the clear this would be a second column under the root and every node would be duplicated.
    tree.apply(
      decodePositional(
        batch(
          9,
          "[0,7,$COLUMN]", "[3,0,1,7,0]",
          "[0,8,$TEXT]", "[1,8,1,\"first\"]", "[3,1,1,8,0]",
        ).replace("[3,1,1,8,0]", "[3,7,1,8,0]"),
      ),
    )
    assertEquals(listOf("first"), tree.texts())
    assertEquals(1, tree.root.slot(1).size, "the resent tree was applied on top of the old one")
  }

  /**
   * A resent tree uses identifiers the old one never used, so the clear is what makes it apply.
   *
   * Without clearing, the *creates* would succeed -- the identifiers are fresh -- and the result
   * would be two parallel trees under one root, which renders as the screen twice. That is why the
   * assertion above counts the root's children rather than only reading the text back.
   */
  @Test
  fun aResentTreeOntoAnUnclearedOneDuplicatesTheScreen() {
    val tree = seeded()
    tree.apply(
      decodePositional(
        batch(
          9,
          "[0,7,$COLUMN]", "[3,0,1,7,0]",
          "[0,8,$TEXT]", "[1,8,1,\"first\"]", "[3,7,1,8,0]",
        ),
      ),
    )
    assertEquals(
      2,
      tree.root.slot(1).size,
      "this is the failure the clear prevents; if it ever becomes 1, the clear is redundant " +
        "and this test is the reason somebody would notice",
    )
  }

  @Test
  fun aRejectedBatchStillChangesNothing() {
    // The containment property, re-asserted here because the repair must not weaken it: the tree
    // has to survive intact long enough for the resend to replace it deliberately.
    val tree = seeded()
    assertFailsWith<ProtocolMismatch> {
      tree.apply(decodePositional(batch(2, "[1,2,1,\"changed\"]", "[1,99,1,\"nobody\"]")))
    }
    assertEquals(listOf("first"), tree.texts())
    assertEquals(1, tree.appliedSequence)
  }

  @Test
  fun aClearedTreeAcceptsABatchWhoseSequenceJumped() {
    // The guest continues its sequence counter across a rebuild, so the batch that arrives after a
    // clear carries a number far ahead of anything applied. Nothing may reject it for that.
    val tree = seeded()
    tree.clear()
    tree.apply(decodePositional(batch(500, "[0,7,$COLUMN]", "[3,0,1,7,0]")))
    assertEquals(500, tree.appliedSequence)
    assertTrue(tree.root.slot(1).isNotEmpty())
  }
}

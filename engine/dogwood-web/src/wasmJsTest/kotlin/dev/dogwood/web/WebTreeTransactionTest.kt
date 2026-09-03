/*
 * Project Dogwood -- the web applier is all-or-nothing on the same terms as the mobile one.
 *
 * The *rule* is shared: `BatchValidation.kt` lives in `dogwood-wire` precisely so there is one
 * definition of "this batch will apply cleanly". What is not shared is the `TreeShape` each host
 * implements over its own node type, and that is where the two can drift -- a slot read that
 * materialises where it should not, a removal that forgets a different set of identifiers. These
 * tests pin the web side of that seam.
 */
package dev.dogwood.web

import dev.dogwood.protocol.ProtocolMismatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun batch(sequence: Int, vararg changes: String): String =
  "[$sequence,[${changes.joinToString(",")}]]"

class WebTreeTransactionTest {

  private val decoder = FastPositionalDecoder()

  /** A column under the root holding two texts, in the web host's own tree. */
  private fun seeded(): WebTree = WebTree().also {
    it.apply(
      decoder.decode(
        batch(
          1,
          "[0,1,${WebDictionary.Column.value}]", "[3,0,1,1,0]",
          "[0,2,${WebDictionary.Text.value}]", "[1,2,1,\"first\"]", "[3,1,1,2,0]",
          "[0,3,${WebDictionary.Text.value}]", "[1,3,1,\"second\"]", "[3,1,1,3,1]",
        ),
      ),
    )
  }

  private fun assertRejected(payload: String, because: String) {
    val tree = seeded()
    val before = tree.describe()
    val failure = assertFailsWith<ProtocolMismatch> { tree.apply(decoder.decode(payload)) }
    assertTrue(because in (failure.message ?: ""), "expected \"$because\" in \"${failure.message}\"")
    assertEquals(before, tree.describe(), "the tree changed despite the batch being rejected")
  }

  @Test
  fun aDanglingReferenceLeavesTheTreeUntouched() =
    assertRejected(
      batch(2, "[1,2,1,\"changed\"]", "[1,99,1,\"nobody\"]"),
      "node 99, which does not exist",
    )

  @Test
  fun aRemovalPastTheEndLeavesTheTreeUntouched() =
    assertRejected(batch(2, "[1,2,1,\"changed\"]", "[4,1,1,1,5]"), "removes 5 children")

  @Test
  fun aReferenceIntoASubtreeThisBatchRemovedIsRejected() =
    assertRejected(batch(2, "[4,1,1,0,1]", "[1,2,1,\"gone\"]"), "node 2, which does not exist")

  @Test
  fun anOrdinaryBatchStillApplies() {
    val tree = seeded()
    tree.apply(decoder.decode(batch(2, "[1,2,1,\"changed\"]", "[4,1,1,1,1]")))
    assertTrue("changed" in tree.describe())
    assertTrue("second" !in tree.describe())
  }
}

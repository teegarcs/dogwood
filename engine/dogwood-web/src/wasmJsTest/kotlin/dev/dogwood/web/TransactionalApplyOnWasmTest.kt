/*
 * Project Dogwood -- the shared applier, compiled to WebAssembly.
 *
 * This used to test the web host's own tree. Since Layer 5 ADR-041 there is no such thing: the web
 * host renders through `dogwood-host`'s core, so the code under test here is the same code
 * `TransactionalApplyTest` covers on a Java Virtual Machine.
 *
 * It is kept, and not as ceremony. The applier's correctness depends on integer arithmetic and on
 * collection behaviour, and Kotlin/Wasm is a different compilation of both -- so "it holds on the
 * Java Virtual Machine" and "it holds in a browser" are two claims, and this file makes the second
 * one. The web decoder in front of it is also still this module's own, which is the other reason
 * the batches here are worth pushing through the real path rather than assumed equivalent.
 */
package dev.dogwood.web

import dev.dogwood.protocol.ProtocolMismatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.dogwood.host.HostTree
import dev.dogwood.host.DogwoodDictionary

private fun batch(sequence: Int, vararg changes: String): String =
  "[$sequence,[${changes.joinToString(",")}]]"

class TransactionalApplyOnWasmTest {

  private val decoder = FastPositionalDecoder()

  /** A column under the root holding two texts, in the web host's own tree. */
  private fun seeded(): HostTree = HostTree().also {
    it.apply(
      decoder.decode(
        batch(
          1,
          "[0,1,${DogwoodDictionary.Column.value}]", "[3,0,1,1,0]",
          "[0,2,${DogwoodDictionary.Text.value}]", "[1,2,1,\"first\"]", "[3,1,1,2,0]",
          "[0,3,${DogwoodDictionary.Text.value}]", "[1,3,1,\"second\"]", "[3,1,1,3,1]",
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

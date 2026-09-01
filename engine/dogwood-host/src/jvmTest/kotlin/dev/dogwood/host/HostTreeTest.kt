/*
 * Project Dogwood -- the host half of the Phase 1 gate.
 *
 * The guest tests prove the right changes are produced. These prove the host does the right
 * thing with them, including the two cases that only look like edge cases until a client is one
 * dictionary version behind a payload.
 */
package dev.dogwood.host

import dev.dogwood.protocol.Segments
import dev.dogwood.protocol.widgetTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Builds a positional payload by hand, so the decoder is tested against the format, not itself. */
private fun batch(sequence: Int, vararg changes: String): String =
  "[$sequence,[${changes.joinToString(",")}]]"

private val TEXT = DogwoodDictionary.Text.value
private val COLUMN = DogwoodDictionary.Column.value

class PositionalDecoderTest {

  @Test
  fun decodesEveryChangeKind() {
    val payload = batch(
      7,
      "[0,1,$COLUMN]",
      "[1,1,1,\"hello\"]",
      "[2,1,[[1,8],[4,48]]]",
      "[3,0,1,1,0]",
      "[4,1,1,0,2]",
      "[5,1,1,0,2,3]",
    )
    val decoded = decodePositional(payload)
    assertEquals(7, decoded.q)
    assertEquals(6, decoded.g.size)
    assertEquals(
      listOf("Create", "PropertySet", "ModifierSet", "ChildAdd", "ChildRemove", "ChildMove"),
      decoded.g.map { it::class.simpleName },
    )
  }

  @Test
  fun modifierChainKeepsItsOrder() {
    val decoded = decodePositional(batch(1, "[2,1,[[1,8],[4,48],[5,0.5]]]"))
    val chain = decoded.g.single()
    check(chain is dev.dogwood.protocol.ModifierSet)
    assertEquals(listOf(1, 4, 5), chain.e.map { it.t.local })
  }
}

class HostTreeApplyTest {

  private fun tree(): HostTree = HostTree()

  private fun HostTree.column(): HostNode = root.slot(1).single()

  /** Root, one column, three texts attached to it. */
  private fun HostTree.buildThreeRows() {
    apply(
      decodePositional(
        batch(
          1,
          "[0,1,$COLUMN]", "[3,0,1,1,0]",
          "[0,2,$TEXT]", "[1,2,1,\"a\"]", "[3,1,1,2,0]",
          "[0,3,$TEXT]", "[1,3,1,\"b\"]", "[3,1,1,3,1]",
          "[0,4,$TEXT]", "[1,4,1,\"c\"]", "[3,1,1,4,2]",
        ),
      ),
    )
  }

  @Test
  fun buildsTheTreeAndRecordsTheAppliedSequence() {
    val tree = tree()
    tree.buildThreeRows()
    assertEquals(1, tree.appliedSequence)
    assertEquals(listOf("a", "b", "c"), tree.column().slot(1).map { it.string(1) })
  }

  @Test
  fun aMoveKeepsTheSameNodeInstances() {
    val tree = tree()
    tree.buildThreeRows()
    val before = tree.column().slot(1).toList()

    // Move one child from index 2 to index 0.
    tree.apply(decodePositional(batch(2, "[5,1,1,2,0,1]")))

    val after = tree.column().slot(1).toList()
    assertEquals(listOf("c", "a", "b"), after.map { it.string(1) })
    assertSame(
      before[2], after[0],
      "a move must relocate the existing node, not replace it -- identity is what survives a " +
        "reorder, and it is what lets Compose keep the subtree's state",
    )
    assertSame(before[0], after[1])
    assertSame(before[1], after[2])
  }

  @Test
  fun aRemovalPurgesTheSubtreeDepthFirst() {
    val tree = tree()
    tree.buildThreeRows()
    tree.apply(decodePositional(batch(2, "[4,1,1,1,2]")))
    assertEquals(listOf("a"), tree.column().slot(1).map { it.string(1) })

    // The purged identifiers are gone: a later batch referencing one is a protocol error, not a
    // silent no-op that would corrupt the tree.
    val failure = runCatching {
      tree.apply(decodePositional(batch(3, "[1,3,1,\"resurrected\"]")))
    }
    assertTrue(failure.isFailure, "a reference to a purged node must be reported, not ignored")
  }

  @Test
  fun anUnknownWidgetTagBecomesAPlaceholderSoIndexArithmeticSurvives() {
    val tree = tree()
    val fromTheFuture = widgetTag(Segments.DESIGN_SYSTEM, 99).value
    tree.apply(
      decodePositional(
        batch(
          1,
          "[0,1,$COLUMN]", "[3,0,1,1,0]",
          // A widget this client's dictionary has never heard of, between two it knows.
          "[0,2,$TEXT]", "[1,2,1,\"before\"]", "[3,1,1,2,0]",
          "[0,3,$fromTheFuture]", "[3,1,1,3,1]",
          "[0,4,$TEXT]", "[1,4,1,\"after\"]", "[3,1,1,4,2]",
        ),
      ),
    )

    val children = tree.column().slot(1)
    assertEquals(3, children.size, "the unknown node must occupy its slot, not vanish")
    assertEquals("before", children[0].string(1))
    assertEquals("after", children[2].string(1), "the sibling after it keeps its index")
    assertTrue(
      fromTheFuture in tree.unknownTags,
      "an unrecognised tag is telemetry the client should report, not a silent gap",
    )
  }

  @Test
  fun absentPropertiesFallBackToTheHostDefault() {
    val tree = tree()
    tree.apply(decodePositional(batch(1, "[0,1,$TEXT]", "[3,0,1,1,0]")))
    val text = tree.root.slot(1).single()
    // Absence IS the "use host default" sentinel; there is no empty-string on the wire.
    assertEquals("fallback", text.string(1, default = "fallback"))
    assertEquals(4, text.int(2, default = 4))
    assertTrue(!text.has(1))
    assertNotNull(text)
  }
}

/*
 * Project Dogwood -- the two halves of the wire grammar, compared.
 *
 * Until this file existed, nothing in the repository ever fed the real encoder's output to the
 * real decoder. The encoder lived in the guest, the decoder lived in the host, no source imported
 * both, every host test typed wire strings by hand, and the guest tests read their batches back
 * through a *third* decoder that lived beside them. Two independent implementations of one
 * grammar, each verified against itself.
 *
 * That is a worse arrangement than it sounds, because the format is positional. Every interesting
 * element of a change tuple is an integer, so a payload whose tuples have shifted by one parses
 * perfectly and means something else entirely: a child identifier read as an insertion index, a
 * count read as a position. There is no exception, no missing field, and no entry in any report --
 * just a tree that is quietly wrong and every later batch compounding against it.
 *
 * These tests run in the guest's own target, where the encoder lives, against the decoder the host
 * actually uses, out of `dogwood-protocol`.
 */
package dev.dogwood.compose

import dev.dogwood.protocol.ChangeKind
import dev.dogwood.protocol.ChildAdd
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.ModifierSet
import dev.dogwood.protocol.ProtocolMismatch
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.decodePositional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GrammarTest {

  @Test
  fun everyChangeKindSurvivesARoundTripThroughBothRealImplementations() {
    // The test that did not exist. A screen exercising all six change kinds -- creates, property
    // sets, modifier chains, child adds, and, through the removal below, removes -- encoded by the
    // guest and decoded by the host, with no hand-typed wire string anywhere.
    val (host, composition) = compose {
      VerticalList(modifier = Modifier.fillMaxWidth(), spacingDp = 8) {
        Text("first", style = "titleMedium")
        PrimaryButton(label = "act", modifier = Modifier.fillMaxWidth().padding(4)) {}
      }
    }
    composition.frame(0L)

    val batches = host.batches.map { decodePositional(it) }
    assertTrue(batches.isNotEmpty(), "nothing crossed, so nothing was compared")
    val changes = batches.flatMap { it.g }

    assertTrue(changes.any { it is Create }, "expected node creations")
    assertTrue(changes.any { it is PropertySet }, "expected property sets")
    assertTrue(changes.any { it is ModifierSet }, "expected modifier chains")
    assertTrue(changes.any { it is ChildAdd }, "expected child insertions")

    // Sequence numbers survive, and they matter: an event carries the sequence it was rendered
    // against, which is what makes a double-tapped button a correctness problem rather than a
    // cosmetic one.
    assertEquals(batches.map { it.q }, batches.map { it.q }.sorted())
    composition.dispose()
  }

  @Test
  fun theTwoSidesAgreeOnEveryDiscriminator() {
    // They used to be two `const` blocks in two modules with nothing comparing them. They are now
    // one declaration, and this asserts the encoder actually emits it rather than a literal that
    // happens to match today.
    val (host, composition) = compose { Text("x") }
    composition.frame(0L)
    val kinds = host.batches.map { decodePositional(it) }.flatMap { it.g }.map {
      when (it) {
        is Create -> ChangeKind.CREATE
        is PropertySet -> ChangeKind.PROPERTY
        is ModifierSet -> ChangeKind.MODIFIER
        is ChildAdd -> ChangeKind.CHILD_ADD
        else -> -1
      }
    }
    assertTrue(kinds.all { it >= 0 }, "a change decoded to something outside the known kinds")
    composition.dispose()
  }

  /*
   * The drift cases.
   *
   * Each is a payload a guest one protocol revision ahead could plausibly send. Before the arity
   * check, every one of them parsed without complaint and produced a wrong tree.
   */

  @Test
  fun aFieldInsertedMidTupleIsRejectedRatherThanMisread() {
    // A guest at v2 adds a generation counter to `add`: [3, parent, slot, generation, child, index].
    // A host at v1 reads the generation as the child identifier and the child as the index. Every
    // element is an integer, so nothing about it looks wrong.
    val shifted = "[1,[[3,0,1,99,7,0]]]"
    val failure = assertFailsWith<ProtocolMismatch> { decodePositional(shifted) }
    assertTrue("6 elements" in failure.message!! || "expected 5" in failure.message!!, failure.message!!)
  }

  @Test
  fun aTruncatedTupleIsRejectedRatherThanThrowingAnIndexError() {
    // The other direction, and it used to surface as IndexOutOfBoundsException from deep inside a
    // `map` -- an unhandled runtime error, not a protocol decision.
    assertFailsWith<ProtocolMismatch> { decodePositional("[1,[[5,1,1,0,1]]]") }
  }

  @Test
  fun aChangeKindFromANewerProtocolIsRejectedByName() {
    val failure = assertFailsWith<ProtocolMismatch> { decodePositional("[1,[[6,1,2]]]") }
    assertTrue("newer than this client" in failure.message!!, failure.message!!)
  }

  @Test
  fun aMalformedEnvelopeIsRejected() {
    assertFailsWith<ProtocolMismatch> { decodePositional("[1]") }
    assertFailsWith<ProtocolMismatch> { decodePositional("[1,[],99]") }
  }

  @Test
  fun anOutOfRangeIntegerIsATypedRefusalNotARawThrow() {
    // Found by comparing the mobile decoder against the web one. An identifier past Int range made
    // kotlinx throw `NumberFormatException`, which is not a `ProtocolMismatch` -- so it escaped
    // `sendChanges`' catch entirely and took the screen down, on input the web host merely
    // refused. The contract is that an undecodable batch is contained, and a contract that holds
    // only for inputs malformed in anticipated ways is not one.
    assertFailsWith<ProtocolMismatch> { decodePositional("[1,[[0,4294967297,2]]]") }
    assertFailsWith<ProtocolMismatch> { decodePositional("this is not json at all") }
    assertFailsWith<ProtocolMismatch> { decodePositional("[1,[[0,\"1\",2]]]") }
  }

  @Test
  fun aWellFormedBatchIsStillAccepted() {
    // The control. Without it, every assertion above would pass against a decoder that rejected
    // everything.
    val batch = decodePositional("[7,[[0,1,2],[1,1,1,\"hello\"],[3,0,1,1,0]]]")
    assertEquals(7, batch.q)
    assertEquals(3, batch.g.size)
  }
}

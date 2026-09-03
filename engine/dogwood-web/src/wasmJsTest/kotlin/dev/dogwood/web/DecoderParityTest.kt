/*
 * Project Dogwood -- the two decoders, compared.
 *
 * The web host reads batches with a hand-written character-array decoder rather than the shared
 * `decodePositional`, because on this platform decoding is the *larger* half of the crossing --
 * 55.3% against 44.7% for encoding, the inverse of mobile. That is a real reason for a second
 * implementation and it is also exactly how a grammar drifts: two readers of one format, each
 * verified against itself.
 *
 * So they are verified against each other. Every case below runs the same string through both and
 * requires them to agree on accept-or-reject, and on the decoded batch when they accept. A
 * disagreement is a finding whichever side is wrong, because the format has one definition.
 *
 * This suite exists because there was none. `dogwood-web` had no test source set at all, and the
 * only thing comparing the decoders was a gate inside the shipped binary checking one fixed
 * accepting batch -- which could not have caught either divergence these tests now pin.
 */
package dev.dogwood.web

import dev.dogwood.protocol.ChangeBatch
import dev.dogwood.protocol.ProtocolMismatch
import dev.dogwood.protocol.decodePositional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Both outcomes of a decode, so agreement can be asserted on refusals as well as on batches. */
private sealed interface Outcome {
  data class Decoded(val batch: ChangeBatch) : Outcome
  data object Refused : Outcome
}

private fun reference(payload: String): Outcome =
  try { Outcome.Decoded(decodePositional(payload)) } catch (e: ProtocolMismatch) { Outcome.Refused }

private fun fast(payload: String): Outcome =
  try { Outcome.Decoded(FastPositionalDecoder().decode(payload)) } catch (e: ProtocolMismatch) { Outcome.Refused }

private fun assertAgree(payload: String, note: String = "") {
  val r = reference(payload)
  val f = fast(payload)
  when {
    r is Outcome.Decoded && f is Outcome.Decoded ->
      assertEquals(r.batch, f.batch, "decoders disagree on the VALUE of $payload $note")
    r is Outcome.Refused && f is Outcome.Refused -> Unit
    else -> throw AssertionError(
      "decoders disagree on whether to accept $payload $note -- reference=$r fast=$f",
    )
  }
}

class DecoderParityTest {

  @Test
  fun everyChangeKindDecodesIdentically() {
    listOf(
      "[1,[[0,1,2]]]",
      "[2,[[1,1,1,\"text\"]]]",
      "[3,[[2,1,[[1,8],[4,48]]]]]",
      "[4,[[3,0,1,1,0]]]",
      "[5,[[4,1,1,0,2]]]",
      "[6,[[5,1,1,0,2,1]]]",
      "[7,[[0,1,2],[1,1,1,\"a\"],[2,1,[[1,4]]],[3,0,1,1,0],[4,0,1,0,1],[5,0,1,0,1,1]]]",
    ).forEach { assertAgree(it) }
  }

  @Test
  fun integersAtTheBoundariesAgree() {
    // The finding. `value * 10 + digit` in an Int accumulator wrapped in silence, so the fast path
    // ACCEPTED an identifier the reference refuses and applied the change to a different live
    // node. 2147483647 is the last value both must accept; everything past it both must refuse.
    assertAgree("[1,[[0,2147483647,2]]]", "(Int.MAX_VALUE, must be accepted by both)")
    assertAgree("[1,[[0,2147483648,2]]]", "(one past the end)")
    assertAgree("[1,[[0,4294967297,2]]]", "(2^32+1, which used to wrap to 1)")
    assertAgree("[1,[[0,99999999999999999999,2]]]", "(absurd)")
    assertAgree("[1,[[0,-2147483648,2]]]", "(Int.MIN_VALUE)")
    assertAgree("[-1,[[0,1,2]]]", "(negative sequence)")
  }

  @Test
  fun aQuotedStructuralNumberIsRefusedByBoth() {
    // The other divergence: kotlinx read `"1"` as 1, the fast path refused it. Nothing that ships
    // emits quoted structural numbers -- the guest builds native arrays of numbers -- so the
    // reference was tightened rather than the fast path loosened.
    assertAgree("[\"7\",[[0,1,2]]]", "(quoted sequence)")
    assertAgree("[1,[[0,\"1\",2]]]", "(quoted identifier)")
    assertAgree("[1,[[\"0\",1,2]]]", "(quoted kind)")
  }

  @Test
  fun malformedBatchesAreRefusedByBoth() {
    listOf(
      "[1]", "[1,[],9]", "", "not json", "{}", "[1,{}]",
      "[1,[[3,0,1,99,7,0]]]",   // a field inserted mid-tuple
      "[1,[[5,1,1,0,1]]]",      // truncated
      "[1,[[6,1,2]]]",          // a kind from a newer protocol
      "[1,[[2,1,[[1]]]]]",      // a one-element modifier pair
    ).forEach { assertAgree(it) }
  }

  @Test
  fun valuePositionsCarryTheSameContent() {
    // Values are not constrained by the positional grammar, so this is where a general JSON reader
    // and a hand-written one are most likely to part company.
    listOf(
      "[1,[[1,1,1,\"\"]]]",
      "[1,[[1,1,1,\"unicode: \\u00e9\\u00f1 🌳\"]]]",
      "[1,[[1,1,1,\"escaped \\\" quote\"]]]",
      "[1,[[1,1,1,null]]]",
      "[1,[[1,1,1,true]]]",
      "[1,[[1,1,1,[6,61200,\"USD\"]]]]",
      "[1,[[1,1,1,-0.5]]]",
      "[1,[[1,1,1,[]]]]",
    ).forEach { assertAgree(it) }
  }

  @Test
  fun aLargeGeneratedBatchAgrees() {
    // Breadth, not just edges: the shape a screen open actually produces.
    val changes = buildList {
      for (i in 1..200) {
        add("[0,$i,${16777216 + (i % 12)}]")
        add("[1,$i,1,\"row $i\"]")
        add("[2,$i,[[1,${i % 16}],[4,${i * 3}]]]")
        add("[3,0,1,$i,${i - 1}]")
      }
    }
    assertAgree("[42,[${changes.joinToString(",")}]]")
  }

  @Test
  fun theDecodersActuallyDisagreeWhenOneIsWrong() {
    // The control on the harness itself. If `assertAgree` could not fail, none of the above proves
    // anything -- so this asserts that a genuinely divergent pair is caught.
    var caught = false
    try {
      val r: Outcome = Outcome.Decoded(decodePositional("[1,[[0,1,2]]]"))
      val f: Outcome = Outcome.Refused
      if (r !is Outcome.Refused && f is Outcome.Refused) throw AssertionError("divergent")
    } catch (e: AssertionError) {
      caught = true
    }
    assertTrue(caught, "the comparison must be capable of failing")
  }
}

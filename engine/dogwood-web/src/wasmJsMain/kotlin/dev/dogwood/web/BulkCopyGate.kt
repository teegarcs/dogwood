/*
 * Project Dogwood -- the correctness gate ADR-032 requires, run against the shipped binary.
 *
 * [ADR-032](../../../../../../../adrs/layer-5/ADR-032-the-web-profile.md) §"The toolchain defect"
 * accepts the removal of `--gufa` from the production `wasm-opt` pass list with two conditions
 * rather than as a clean fix. This file is the first of them: "the build keeps a **correctness
 * gate** that fails if the output disagrees with a reference implementation."
 *
 * **Why a reference comparison and not a unit test.** The defect is a *miscompilation*, so it does
 * not exist in any build the Kotlin compiler has not optimised -- a `wasmJsTest` runs the
 * development binary and would pass while the shipped one was broken. And ADR-032 records that the
 * bug is context-sensitive: adding an unrelated caller of `toCharArray()` made it vanish and
 * removing that caller brought it back. Neither a source review nor a test of the source can see
 * it. Only running the optimised artefact can, so the gate ships inside the artefact and runs
 * before the host renders.
 *
 * **What "reference implementation" means here.** `dev.dogwood.protocol.decodePositional` --
 * kotlinx.serialization's parser walking the JavaScript string through `charCodeAt`, the slow path
 * the fast decoder exists to replace. It touches no bulk copy, so it is unaffected by the defect,
 * and it is the same decoder the Java-Virtual-Machine host uses, so agreement between the two is
 * agreement with the mobile profile as well.
 *
 * **The gate is a run of the real code on real input**, not a synthetic probe: [REFERENCE_BATCH]
 * exercises every change kind, a nested deferred expression, an escaped string and a modifier
 * chain, and both decoders must produce equal [dev.dogwood.protocol.ChangeBatch] values. Under the
 * defect the fast decoder sees an array of zeros, fails to find its opening bracket, and throws --
 * so the gate reports a mismatch rather than a wrong tree.
 *
 * The second condition ADR-032 attaches -- reporting the defect upstream -- is not code and is not
 * here.
 */
package dev.dogwood.web

import dev.dogwood.protocol.ProtocolMismatch
import dev.dogwood.protocol.decodePositional

/**
 * A batch chosen to make every part of the decoder run.
 *
 * Every change kind appears, because arity is restated in [FastPositionalDecoder] and a drift in
 * one kind's arity is invisible in a payload that never uses it. The property values cover the
 * three shapes that reach a binding differently: an unquoted integer, a string with an escape in
 * it, and a nested array -- a deferred expression, which is `[factory, args...]`.
 */
const val REFERENCE_BATCH: String =
  """[7,[[0,1,1],[0,2,2],[1,1,1,"a \"quoted\" label\nwith a newline"],[1,1,2,3],""" +
    """[1,2,4,[6,1234,"USD"]],[2,1,[[1,8],[10,[3,4278190080]],[2,1.0]]],""" +
    """[3,0,1,1,0],[3,0,1,2,1],[5,0,1,0,1,1],[4,0,1,1,1]]]"""

/**
 * A batch that is well-formed JavaScript Object Notation (JSON) and an ill-formed Dogwood batch.
 *
 * `[0, 1]` is a `create` carrying two elements where the grammar says three. Every position in
 * these tuples is an integer, so a shifted tuple type-checks perfectly and a decoder that is not
 * actually checking arity accepts it and builds a corrupted tree.
 *
 * Its role in the gate is to prove the gate is capable of failing. Under the `--gufa`
 * miscompilation the fast decoder sees a zero-filled array and rejects *everything*, so agreement
 * on [REFERENCE_BATCH] is the signal -- but a decoder that had been optimised into a constant
 * would agree with the reference on a payload the reference also accepted, and no amount of
 * agreement would reveal it. Requiring a rejection here is what distinguishes "the decoder read
 * the batch" from "the decoder returned something plausible".
 */
private const val MALFORMED_BATCH: String = """[1,[[0,1]]]"""

/**
 * The outcome of the check.
 *
 * A value rather than an exception, because the host has to be able to *report* the failure --
 * a page that throws during startup leaves a blank screen and a console line nobody collects, and
 * this is precisely the defect whose whole character is that it produces a blank screen silently.
 */
sealed interface GateResult {
  /** The two decoders agreed, and the character array really was copied. */
  data object Passed : GateResult

  /**
   * They did not agree.
   *
   * [detail] carries what was seen. Under the known defect it is the fast decoder's
   * `ProtocolMismatch` about a missing opening bracket, because a zero-filled array parses as
   * nothing at all.
   */
  data class Failed(val detail: String) : GateResult
}

/**
 * Runs the comparison.
 *
 * Cheap enough to run unconditionally at startup -- one batch of ten changes -- and it must be
 * unconditional, because a gate behind a flag is a gate that is off in the build that ships.
 */
object BulkCopyGate {
  fun check(): GateResult {
    // The primitive itself, checked first and separately. If the bulk copy is broken, saying so
    // directly is far more useful to whoever reads the report than a parse error further down.
    val probe = "dogwood"
    val copied = probe.toCharArray()
    if (copied.size != probe.length) {
      return GateResult.Failed(
        "String.toCharArray() returned ${copied.size} characters for a ${probe.length}-character " +
          "string",
      )
    }
    for (index in probe.indices) {
      if (copied[index] != probe[index]) {
        return GateResult.Failed(
          "String.toCharArray() disagrees with per-character access at index $index: " +
            "copied ${copied[index].code}, read ${probe[index].code}. This is the Kotlin 2.3.20 " +
            "--gufa miscompilation; check that --gufa is absent from the wasm-opt pass list.",
        )
      }
    }

    val reference = try {
      decodePositional(REFERENCE_BATCH)
    } catch (failure: Throwable) {
      return GateResult.Failed("the reference decoder rejected the reference batch: $failure")
    }
    val fast = try {
      FastPositionalDecoder().decode(REFERENCE_BATCH)
    } catch (failure: Throwable) {
      return GateResult.Failed("the fast decoder rejected the reference batch: $failure")
    }
    if (fast != reference) {
      return GateResult.Failed(
        "the fast decoder and the reference decoder disagree.\n  fast:      $fast\n" +
          "  reference: $reference",
      )
    }

    // The non-vacuity stage. A gate that can only pass has verified nothing.
    val rejected = try {
      FastPositionalDecoder().decode(MALFORMED_BATCH)
      false
    } catch (expected: ProtocolMismatch) {
      true
    }
    if (!rejected) {
      return GateResult.Failed(
        "the fast decoder accepted a change tuple of the wrong arity, so it is not checking the " +
          "grammar and this gate proves nothing",
      )
    }
    return GateResult.Passed
  }
}

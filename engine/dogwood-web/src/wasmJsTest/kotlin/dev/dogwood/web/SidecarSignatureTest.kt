/*
 * Project Dogwood -- the parts of sidecar signing a browser is not needed to settle.
 *
 * The signature check itself is graded where it runs: on a real browser, against the build's own
 * signed artifacts, by `tools/conformance/web_services.py` as claims `B1` and `B2`. That is the
 * evidence, and these tests do not duplicate it.
 *
 * What they cover is everything around the primitive — the parser, the hexadecimal decoder, and the
 * key-selection rule — because those have edge cases a drill cannot enumerate and a browser cannot
 * make more true. Running here they also run on every build rather than only when somebody has
 * Chrome.
 */
package dev.dogwood.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SidecarSignatureTest {

  /**
   * Runs a suspending verification that is not expected to suspend, and fails if it does.
   *
   * `kotlinx-coroutines-test` is not on this source set, and pulling it in for two assertions would
   * be a dependency bought to avoid four lines. `Dispatchers.Unconfined` runs the body inline up to
   * its first real suspension, and both cases below return before ever reaching one — the key rule
   * decides before `crypto.subtle` is asked anything. The `?: fail` is what keeps that honest: if a
   * future change makes one of these paths await, this reports it rather than silently asserting on
   * a null.
   */
  private fun verdictOf(
    trusted: Map<String, String>,
    signatures: Map<String, String>,
    bytes: ByteArray,
  ): SignatureVerdict {
    var result: SignatureVerdict? = null
    CoroutineScope(Dispatchers.Unconfined).launch {
      result = verifyDetachedSignature(trusted, signatures, bytes)
    }
    return result ?: throw AssertionError(
      "the verification suspended; this harness only covers the paths that decide without awaiting",
    )
  }

  @Test
  fun signaturesAreReadOneKeyPerLine() {
    val parsed = parseDetachedSignatures(
      """
      # Detached Ed25519 signatures, produced by the build.
      dogwood-development aabb

      dogwood-development-2 ccdd
      """.trimIndent(),
    )
    assertEquals(mapOf("dogwood-development" to "aabb", "dogwood-development-2" to "ccdd"), parsed)
  }

  @Test
  fun aLineWithoutTwoFieldsIsDropped() {
    // Dropped rather than fatal: a document with one usable line and one typo should still let the
    // usable key be tried, and a key nothing recognises already lands on `NoRecognisedKey`. What
    // must never happen is a malformed line being read as a *valid* signature, which is why the
    // parser produces pairs and not partial ones.
    assertEquals(mapOf("good" to "aabb"), parseDetachedSignatures("good aabb\nlonelyname\n\n#c\n"))
  }

  @Test
  fun anEmptyDocumentYieldsNothing() {
    assertTrue(parseDetachedSignatures("# only a comment\n\n").isEmpty())
  }

  @Test
  fun hexadecimalDecodesAndRejectsRatherThanGuessing() {
    assertEquals(listOf(0, 255, 16), "00ff10".hexToBytesOrNull()!!.map { it.toInt() and 0xFF })
    // Odd length, a non-hexadecimal digit, and empty: all null, none throwing. A verifier that
    // threw on a malformed signature would turn a publishing typo into an unhandled exception on
    // the page rather than a refusal a host can report.
    assertNull("abc".hexToBytesOrNull())
    assertNull("zz".hexToBytesOrNull())
    assertNull("".hexToBytesOrNull())
  }

  @Test
  fun aSidecarSignedOnlyByKeysThisClientDoesNotKnowIsRefused() {
    val verdict = verdictOf(
      trusted = mapOf("known" to "00".repeat(32)),
      signatures = mapOf("stranger" to "11".repeat(64)),
      bytes = byteArrayOf(1, 2, 3),
    )
    // Named both ways round, because "signature verification failed" is the least useful sentence
    // in operations: the fix for "signed by a key you do not trust" is a client release, and the
    // fix for "signed by a key you trust, badly" is a republish.
    assertTrue(verdict is SignatureVerdict.NoRecognisedKey, verdict.toString())
    assertEquals(listOf("stranger"), (verdict as SignatureVerdict.NoRecognisedKey).offered)
    assertEquals(listOf("known"), verdict.trusted)
  }

  @Test
  fun aMalformedSignatureFromARecognisedKeyIsInvalidRatherThanSkipped() {
    // The rule that matters most here, and the one an implementation gets wrong by being helpful:
    // once a recognised key name is found, that key decides. Falling through to the next signature
    // would mean a document carrying one bad signature and one good one is accepted — and an
    // attacker who can add a signature would then simply add a good one for a key they hold.
    val verdict = verdictOf(
      trusted = mapOf("known" to "00".repeat(32), "other" to "11".repeat(32)),
      signatures = mapOf("known" to "not-hexadecimal", "other" to "22".repeat(64)),
      bytes = byteArrayOf(1, 2, 3),
    )
    assertTrue(verdict is SignatureVerdict.Invalid, verdict.toString())
    assertEquals("known", (verdict as SignatureVerdict.Invalid).keyName)
  }
}

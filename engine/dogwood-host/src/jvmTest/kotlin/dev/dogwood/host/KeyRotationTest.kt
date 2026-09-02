/*
 * Project Dogwood -- the key-rotation drill.
 *
 * `specs/layer-3-delivery.md` calls this a Milestone 4 item that "must be exercised **before the
 * first production payload ships**", and it had never been exercised at all: every signature test
 * built a verifier with exactly one key, and `theBuildActuallySignsTheManifest` actively asserted
 * there was exactly one signature.
 *
 * Rotation matters because the signing key is the single anchor of the whole delivery path. If it
 * leaks, or an employee who held it leaves, the only remedy is to replace it — and replacing it
 * has to be possible without bricking every client in the field, which means a period during which
 * a manifest is trusted by clients holding the old key and clients holding the new one at the same
 * time. A rotation that cannot be rehearsed is a rotation nobody will attempt until it is an
 * emergency.
 *
 * The mechanism, from `specs/layer-3-delivery.md`: the verifier walks the manifest's signatures,
 * **skips key names it does not recognise**, and requires the **first name it does recognise** to
 * verify; if it recognises none, it throws. Two consequences the drill has to pin, because both
 * are load-bearing and neither is obvious:
 *
 *   - the order of signatures in the manifest is part of the contract, not a detail;
 *   - "skips unrecognised" is what lets a client one rotation behind keep working, and is also
 *     what makes retiring a key an act with a blast radius.
 *
 * These run against the manifest the build actually produced, signed by both real keys, rather
 * than a fixture — the same reason `SignatureTest` does.
 */
package dev.dogwood.host

import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestVerifier
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import okio.ByteString.Companion.decodeHex

/** The key clients were built with before the rotation. */
private const val OLD_KEY_NAME = "dogwood-development"
private const val OLD_PUBLIC_KEY =
  "f9037012d6cd2446ec3025da7320bfb593641880b9339d316ba10da2aa18d102"

/** The key being rotated to. Clients that have rolled forward carry this one. */
private const val NEW_KEY_NAME = "dogwood-development-2"
private const val NEW_PUBLIC_KEY =
  "64fcb07226f6b538ec7d09510f9a5073aeb43a50916cfc625761cc9b9a99b097"

/** A valid Ed25519 public key that signed nothing here. Stands in for an attacker's key. */
private const val STRANGER_PUBLIC_KEY =
  "e90183dab09a31fb3f41e3723b0bd20e17a01d28d66da5f5870f76e2cebb20da"

private val builtManifest = File(
  "../samples/slice-guest/build/zipline/ProductionWebpack/manifest.zipline.json",
)

class KeyRotationTest {

  private fun manifestText(): String {
    check(builtManifest.exists()) {
      "no built manifest at ${builtManifest.canonicalPath}; run " +
        ":samples:slice-guest:jsBrowserProductionWebpackZipline first"
    }
    return builtManifest.readText()
  }

  private fun verifierOf(vararg keys: Pair<String, String>): ManifestVerifier {
    val builder = ManifestVerifier.Builder()
    for ((name, hex) in keys) builder.addEd25519(name, hex.decodeHex())
    return builder.build()
  }

  private fun ManifestVerifier.accepts(text: String): String? = runCatching {
    verify(text.encodeToByteArray().let { okio.ByteString.of(*it) }, ZiplineManifest.decodeJson(text))
  }.getOrElse { return null }.let { it }

  @Test
  fun aManifestCarriesBothSignaturesDuringARotation() {
    // The state the fleet is in for as long as the roll-forward takes: one artifact, trusted by
    // clients on either side of the change. Nothing about it is conditional on which client asks.
    val manifest = ZiplineManifest.decodeJson(manifestText())
    assertEquals(setOf(OLD_KEY_NAME, NEW_KEY_NAME), manifest.signatures.keys)
  }

  @Test
  fun aClientThatHasNotRolledForwardStillVerifies() {
    // The whole point. A client shipped before the rotation holds only the old key and must keep
    // accepting updates, or rotating the key would brick every device that had not updated.
    val name = verifierOf(OLD_KEY_NAME to OLD_PUBLIC_KEY).accepts(manifestText())
    assertEquals(OLD_KEY_NAME, name)
  }

  @Test
  fun aClientThatHasRolledForwardVerifiesAgainstTheNewKeyAlone() {
    // The other end of the same window: a client that trusts only the new key must accept the
    // same artifact. Its old-key signature is a name this client does not recognise, and is
    // skipped rather than rejected -- which is the behaviour the whole scheme rests on.
    val name = verifierOf(NEW_KEY_NAME to NEW_PUBLIC_KEY).accepts(manifestText())
    assertEquals(NEW_KEY_NAME, name)
  }

  @Test
  fun aClientTrustingBothUsesTheFirstSignatureTheManifestOffers() {
    // Not an aesthetic detail. The verifier stops at the first key name it recognises, so the
    // manifest's signature ORDER decides which key actually gets checked on a client that holds
    // both. A team that assumed "trusting the new key means the new key is used" would be wrong,
    // and would not find out until they retired the old one.
    val name = verifierOf(
      NEW_KEY_NAME to NEW_PUBLIC_KEY,
      OLD_KEY_NAME to OLD_PUBLIC_KEY,
    ).accepts(manifestText())
    assertEquals(
      OLD_KEY_NAME,
      name,
      "the manifest lists the old key first, so that is the one verified",
    )
  }

  @Test
  fun aClientTrustingNeitherKeyRejects() {
    // The attacker's case, and the reason any of this exists: a manifest whose signatures name
    // nothing this client trusts must not load, rather than loading unverified.
    val outcome = verifierOf("stranger" to STRANGER_PUBLIC_KEY).accepts(manifestText())
    assertTrue(outcome == null, "a manifest signed by nobody we trust must not verify")
  }

  @Test
  fun aRecognisedNameWithTheWrongBytesRejectsRatherThanFallingThrough() {
    // The subtle failure this scheme could have had. If a recognised name that fails verification
    // caused the verifier to move on to the next signature, an attacker who could add a signature
    // under a trusted NAME would only need the manifest to also carry one valid signature. It
    // does not fall through: the first recognised name must verify or the whole manifest is
    // rejected, even though a signature it would have accepted sits right behind it.
    val outcome = verifierOf(
      OLD_KEY_NAME to STRANGER_PUBLIC_KEY,
      NEW_KEY_NAME to NEW_PUBLIC_KEY,
    ).accepts(manifestText())
    assertTrue(
      outcome == null,
      "a recognised key that fails must reject, not fall through to the next signature",
    )
  }

  @Test
  fun retiringAKeyIsWhatEndsTheRotationAndItHasABlastRadius() {
    // Step three: drop the old signature and ship. Simulated by verifying a manifest that carries
    // only the new signature, because that is what the build emits once the old signing key is
    // removed.
    //
    // A client still holding only the old key stops accepting updates at that moment -- not
    // loudly, but by falling back to its cached payload forever. That is why the roll-forward is
    // something to finish rather than start, and it is the fact this test exists to make concrete.
    val text = manifestText()
    val decoded = ZiplineManifest.decodeJson(text)
    check(decoded.signatures.containsKey(OLD_KEY_NAME)) { "the fixture must start with both" }
    val onlyNew = text.replace("\"$OLD_KEY_NAME\":\"${decoded.signatures.getValue(OLD_KEY_NAME)}\",", "")
    // Checked structurally rather than by searching the text: the old key's name is a *prefix* of
    // the new one, so "the old name is absent" is a substring question that answers itself wrongly.
    assertEquals(
      setOf(NEW_KEY_NAME),
      ZiplineManifest.decodeJson(onlyNew).signatures.keys,
      "the retired manifest must carry only the new signature",
    )

    val rolledForward = verifierOf(NEW_KEY_NAME to NEW_PUBLIC_KEY).accepts(onlyNew)
    assertEquals(NEW_KEY_NAME, rolledForward, "a rolled-forward client keeps updating")

    val leftBehind = verifierOf(OLD_KEY_NAME to OLD_PUBLIC_KEY).accepts(onlyNew)
    assertTrue(
      leftBehind == null,
      "a client that never rolled forward stops accepting updates once the key is retired",
    )
  }
}

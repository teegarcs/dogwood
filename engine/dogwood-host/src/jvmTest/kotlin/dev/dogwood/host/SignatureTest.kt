/*
 * Project Dogwood -- Layer 3's security boundary, tested.
 *
 * The Ed25519 manifest signature is the whole reason downloaded code is acceptable at all:
 * without it, anyone who can answer the manifest request runs arbitrary code inside the
 * application. A check that is never exercised is a check nobody knows works, so this asserts
 * both directions -- the trusted key accepts, and every other key rejects.
 *
 * It reads the manifest the build actually produced rather than a fixture, so a change to the
 * signing configuration that silently stopped signing would fail here.
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
import okio.ByteString.Companion.encodeUtf8

/** The public half of the development key, as compiled into the sample host. */
private const val TRUSTED_PUBLIC_KEY =
  "f9037012d6cd2446ec3025da7320bfb593641880b9339d316ba10da2aa18d102"

/** A different, equally valid Ed25519 public key. It signed nothing here. */
private const val UNTRUSTED_PUBLIC_KEY =
  "e90183dab09a31fb3f41e3723b0bd20e17a01d28d66da5f5870f76e2cebb20da"

private val manifestFile = File(
  "../samples/slice-guest/build/zipline/ProductionWebpack/manifest.zipline.json",
)

class SignatureTest {

  private fun manifestText(): String {
    check(manifestFile.exists()) {
      "no built manifest at ${manifestFile.canonicalPath}; this test asserts on the artifact the " +
        "build produces, so :samples:slice-guest:jsBrowserProductionWebpackZipline must run first"
    }
    return manifestFile.readText()
  }

  @Test
  fun theBuildActuallySignsTheManifest() {
    val manifest = ZiplineManifest.decodeJson(manifestText())
    assertTrue(
      manifest.signatures.isNotEmpty(),
      "the manifest carries no signature at all; Layer 3 would have nothing to verify",
    )
    // Two, and in this order. The rotation drill depends on both the count and the sequence --
    // Zipline verifies against the *first* key name it recognises -- so this is pinned here and
    // exercised in `KeyRotationTest`.
    assertEquals(
      listOf("dogwood-development", "dogwood-development-2"),
      manifest.signatures.keys.toList(),
    )
  }

  @Test
  fun theTrustedKeyVerifiesTheManifest() {
    val text = manifestText()
    val verifier = ManifestVerifier.Builder()
      .addEd25519("dogwood-development", TRUSTED_PUBLIC_KEY.decodeHex())
      .build()

    val keyName = verifier.verify(text.encodeUtf8(), ZiplineManifest.decodeJson(text))
    assertEquals("dogwood-development", keyName)
  }

  @Test
  fun anUntrustedKeyRejectsTheManifest() {
    val text = manifestText()
    // Same key name, different key: this is the attack the signature exists to stop, an
    // impostor answering the manifest request.
    val verifier = ManifestVerifier.Builder()
      .addEd25519("dogwood-development", UNTRUSTED_PUBLIC_KEY.decodeHex())
      .build()

    try {
      verifier.verify(text.encodeUtf8(), ZiplineManifest.decodeJson(text))
      fail("a manifest signed by an untrusted key must be rejected, and was not")
    } catch (expected: IllegalStateException) {
      // The failure is the pass.
    }
  }

  @Test
  fun swappingTheBytecodeHashIsRejected() {
    val text = manifestText()
    // The module's sha256 is what binds this manifest to specific bytecode. Substituting it is
    // the attack that matters: a valid signature over a manifest pointing at someone else's
    // code would be worthless.
    val original = ZiplineManifest.decodeJson(text).modules.values.single().sha256.hex()
    val tampered = text.replace(original, "0".repeat(original.length))
    check(tampered != text) { "the tamper did not change the manifest; the test proves nothing" }

    val verifier = ManifestVerifier.Builder()
      .addEd25519("dogwood-development", TRUSTED_PUBLIC_KEY.decodeHex())
      .build()

    try {
      verifier.verify(tampered.encodeUtf8(), ZiplineManifest.decodeJson(tampered))
      fail("a manifest whose module hash was swapped must be rejected, and was not")
    } catch (expected: IllegalStateException) {
      // The failure is the pass.
    }
  }

  @Test
  fun changingTheEntryPointIsRejected() {
    val text = manifestText()
    val tampered = text.replace("dev.dogwood.slice.main", "dev.dogwood.slice.evil")
    check(tampered != text)

    val verifier = ManifestVerifier.Builder()
      .addEd25519("dogwood-development", TRUSTED_PUBLIC_KEY.decodeHex())
      .build()

    try {
      verifier.verify(tampered.encodeUtf8(), ZiplineManifest.decodeJson(tampered))
      fail("a manifest whose entry point was redirected must be rejected, and was not")
    } catch (expected: IllegalStateException) {
      // The failure is the pass.
    }
  }

  @Test
  fun reformattingTheManifestIsDeliberatelyNotTampering() {
    val text = manifestText()
    // Not a gap. Zipline signs a canonical payload: the `unsigned` block is removed and the
    // remainder is re-encoded with no unnecessary whitespace, documented in Zipline's
    // `signaturePayload.kt`. That is what lets a cached manifest record the URL it was fetched
    // from without invalidating its own signature.
    //
    // This test exists so nobody later "fixes" a whitespace-insensitive verifier that is
    // working exactly as designed, and so the boundary of what the signature covers is written
    // down somewhere a reader will find it.
    val reformatted = text.replace("\"mainFunction\":", "\"mainFunction\" :")
    check(reformatted != text)

    val verifier = ManifestVerifier.Builder()
      .addEd25519("dogwood-development", TRUSTED_PUBLIC_KEY.decodeHex())
      .build()

    assertEquals(
      "dogwood-development",
      verifier.verify(reformatted.encodeUtf8(), ZiplineManifest.decodeJson(reformatted)),
    )
  }

  @Test
  fun movingTheModuleAddressIsRejected() {
    val text = manifestText()
    // **The reason a module cannot be re-addressed after it is signed**, which is the fact
    // ADR-077 turns on. A module's `url` sits beside its `sha256` inside the signed region, so a
    // publishing step that renamed modules to make their addresses unique per release would
    // invalidate every signature it touched, and every client would refuse to start.
    //
    // That is why the content address is written by the build, before signing, rather than by
    // `server.py`'s `publish` where the first reading of the defect put it.
    val original = ZiplineManifest.decodeJson(text).modules.values.single().url
    val tampered = text.replace("\"$original\"", "\"pool/$original\"")
    check(tampered != text) { "the tamper did not change the manifest; the test proves nothing" }

    val verifier = ManifestVerifier.Builder()
      .addEd25519("dogwood-development", TRUSTED_PUBLIC_KEY.decodeHex())
      .build()

    try {
      verifier.verify(tampered.encodeUtf8(), ZiplineManifest.decodeJson(tampered))
      fail("a manifest whose module address was moved must be rejected, and was not")
    } catch (expected: IllegalStateException) {
      // The failure is the pass.
    }
  }
}

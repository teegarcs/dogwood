/*
 * Project Dogwood -- Ed25519 verification of the web sidecar manifest.
 *
 * The gap this closes was written down before it was closed. The mobile profile verifies an
 * Ed25519 signature over the manifest against keys compiled into the binary, so a compromised or
 * substituted server cannot make a client run code the signing key never approved. The web profile
 * had HyperText Transfer Protocol Secure (HTTPS) and nothing else, which authenticates the *server*
 * and not the *payload*, and `DogwoodWebManifest.disabled` carried a comment saying in as many
 * words that the kill switch on this profile was only as trustworthy as its origin. That is a fine
 * thing to write down and a poor thing to leave.
 *
 * **Detached, in a second file, and that choice removes a whole class of bug.** Zipline's manifest
 * carries its signatures inside itself and excludes them from the signed body with an `unsigned`
 * section, which works but means the verifier and the signer must agree byte-for-byte on which
 * subset of a JavaScript Object Notation (JSON) document was signed -- a canonicalisation problem,
 * and canonicalisation problems fail silently and late. A detached signature has no such problem:
 * the signature covers the manifest file's exact bytes, whatever they are, and the host verifies
 * over the same bytes it is about to parse. `manifest.json` is signed; `manifest.json.sig` holds
 * the signatures.
 *
 * **The verification runs on the bytes that are used**, not on a second fetch of the same URL. A
 * verifier that fetches the document twice verifies one copy and parses another, and a server that
 * answers differently the second time defeats it entirely. `WebDelivery` fetches the manifest text
 * once and passes that same string here.
 *
 * ## The rotation rule, mirrored from Zipline rather than invented
 *
 * `ManifestVerifier` on mobile iterates the manifest's signatures, **skips key names it does not
 * recognise**, requires the first recognised name to verify, and throws if no name is recognised at
 * all. That is what makes key rotation work: a manifest carries signatures from the old key and the
 * new one at once, clients holding either verify it, and the fleet rolls forward before the old key
 * is retired. This implements the same rule, because a web profile that rotated keys differently
 * from the mobile one would be a second procedure for an operation nobody performs often enough to
 * get right twice. See `specs/layer-3-delivery.md`.
 *
 * ## What this needs from the browser
 *
 * `crypto.subtle` with Ed25519. Verified rather than assumed, by generating a key pair, signing,
 * importing the bare 32-byte public key -- the form a manifest carries -- and verifying both a good
 * and a tampered signature, in the same headless Chrome the conformance drill uses:
 *
 * ```
 * UA ... HeadlessChrome/152.0.0.0
 * Ed25519 generate+sign+verify=true rawPublicKeyBytes=32
 * importKey raw -> verify=true
 * tampered signature verify=false
 * ```
 *
 * Two browser facts follow, and both are handled as refusals rather than as assumptions:
 *
 *  * **`crypto.subtle` exists only in a secure context.** Over plain HyperText Transfer Protocol on
 *    a non-localhost origin it is `undefined`, so a host that configured trusted keys and cannot
 *    reach the primitive is refused rather than silently downgraded. Refusing is the safe direction:
 *    the alternative is a page that stops checking signatures precisely when its transport is
 *    weakest.
 *  * **Ed25519 in `crypto.subtle` is recent** (Chrome 137, Safari 17, Firefox 129). An older browser
 *    throws on `importKey`, which lands in the same refusal.
 */
package dev.dogwood.web

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * The detached signature document: `keyName hexSignature`, one pair per line.
 *
 * Deliberately not JSON. It is produced by a build step and consumed by a parser that must not be
 * able to fail in interesting ways; a line-oriented format has one failure mode -- a line that does
 * not split in two -- and no nesting for a mistake to hide in. Blank lines and `#` comments are
 * ignored so a publisher can say which key is which.
 */
fun parseDetachedSignatures(document: String): Map<String, String> =
  document.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .mapNotNull { line ->
      val name = line.substringBefore(' ', "").trim()
      val signature = line.substringAfter(' ', "").trim()
      if (name.isEmpty() || signature.isEmpty()) null else name to signature
    }
    .toMap()

/** Why a sidecar's signature was not accepted. */
sealed interface SignatureVerdict {
  /** A recognised key signed these exact bytes. [keyName] is the one that verified. */
  data class Verified(val keyName: String) : SignatureVerdict

  /** No signature carried a key name this client trusts. */
  data class NoRecognisedKey(val offered: List<String>, val trusted: List<String>) :
    SignatureVerdict

  /** A recognised key was offered and its signature did not verify over these bytes. */
  data class Invalid(val keyName: String) : SignatureVerdict

  /** The browser could not perform the check at all; see this file's header. */
  data class Unavailable(val reason: String) : SignatureVerdict
}

/**
 * Verifies [signatures] over [signedBytes] against [trustedPublicKeys].
 *
 * @param trustedPublicKeys key name to a 32-byte Ed25519 public key, hexadecimal. More than one
 *   entry is how rotation works.
 * @param signatures key name to a 64-byte Ed25519 signature, hexadecimal, as published beside the
 *   manifest.
 * @param signedBytes the manifest's exact bytes, as fetched.
 */
suspend fun verifyDetachedSignature(
  trustedPublicKeys: Map<String, String>,
  signatures: Map<String, String>,
  signedBytes: ByteArray,
): SignatureVerdict {
  val subtle = subtleCrypto()
    ?: return SignatureVerdict.Unavailable(
      "crypto.subtle is unavailable; a page served over plain HTTP on a non-localhost origin has no " +
        "Web Crypto and cannot check a signature",
    )

  // Zipline's order, not the map's: iterate what the *publisher* offered and take the first name
  // this client recognises. Iterating the trusted keys instead would let a client's own map order
  // decide which of several valid signatures is used, which is the same answer by luck.
  for ((name, signatureHex) in signatures) {
    val publicKeyHex = trustedPublicKeys[name] ?: continue
    val publicKey = publicKeyHex.hexToBytesOrNull() ?: return SignatureVerdict.Invalid(name)
    val signature = signatureHex.hexToBytesOrNull() ?: return SignatureVerdict.Invalid(name)
    val ok = try {
      verifyEd25519(subtle, publicKey, signature, signedBytes)
    } catch (failure: Throwable) {
      return SignatureVerdict.Unavailable("Ed25519 is unavailable in this browser: ${failure.message}")
    }
    // The **first** recognised name decides, verified or not. Falling through to try another
    // signature would mean a manifest carrying one good signature and one bad one is accepted, and
    // an attacker who can add a signature could then simply add a good one for a key they hold.
    return if (ok) SignatureVerdict.Verified(name) else SignatureVerdict.Invalid(name)
  }
  return SignatureVerdict.NoRecognisedKey(signatures.keys.toList(), trustedPublicKeys.keys.toList())
}

/** Hexadecimal to bytes, returning null rather than throwing on anything malformed. */
internal fun String.hexToBytesOrNull(): ByteArray? {
  if (length % 2 != 0 || isEmpty()) return null
  val out = ByteArray(length / 2)
  for (i in out.indices) {
    val high = this[i * 2].digitToIntOrNull(16) ?: return null
    val low = this[i * 2 + 1].digitToIntOrNull(16) ?: return null
    out[i] = ((high shl 4) or low).toByte()
  }
  return out
}

private fun subtleCrypto(): JsAny? = js("(globalThis.crypto && globalThis.crypto.subtle) || null")

private fun ed25519Verify(
  subtle: JsAny,
  publicKey: JsAny,
  signature: JsAny,
  message: JsAny,
): Promise<JsAny?> = js(
  """
  subtle.importKey("raw", publicKey, { name: "Ed25519" }, false, ["verify"])
    .then(function (key) { return subtle.verify({ name: "Ed25519" }, key, signature, message); })
  """,
)

private fun toUint8Array(bytes: ByteArray): JsAny {
  val array = newUint8Array(bytes.size)
  for (i in bytes.indices) setUint8(array, i, bytes[i].toInt() and 0xFF)
  return array
}

private fun newUint8Array(size: Int): JsAny = js("new Uint8Array(size)")

private fun setUint8(array: JsAny, index: Int, value: Int) {
  js("array[index] = value")
}

private suspend fun verifyEd25519(
  subtle: JsAny,
  publicKey: ByteArray,
  signature: ByteArray,
  message: ByteArray,
): Boolean {
  val result = ed25519Verify(
    subtle,
    toUint8Array(publicKey),
    toUint8Array(signature),
    toUint8Array(message),
  ).await<JsAny?>()
  return jsTruthy(result)
}

private fun jsTruthy(value: JsAny?): Boolean = js("!!value")

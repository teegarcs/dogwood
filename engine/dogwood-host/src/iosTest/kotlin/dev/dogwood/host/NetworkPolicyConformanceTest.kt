/*
 * Project Dogwood -- conformance claims F1, F2 and F4 against the real loading system.
 *
 * `UrlSessionNetworkTest` next door says in its own header that it "tests *our* logic exhaustively
 * and does not test that NSURLSession" behaves. That honesty is the reason this file exists: the
 * one policy defect this project ever shipped was `file://` served out of the application's own
 * container, because the allow rule waived "must be https" and NSURLSession then happily served a
 * file. The rule was tested. The loading system was not.
 *
 * So these drive a real `NSURLSession` at a witness server on the development machine, and prove a
 * refusal by the **absence of a request there** -- a stronger claim than the client reporting one,
 * because a client can report a refusal and still have opened the connection.
 *
 * The simulator shares the machine's network, so `127.0.0.1` is the witness. Skipped, loudly, when
 * it is not running: a refusal claim that passes because nothing was listening proves nothing, and
 * `tools/conformance/run-ios-policy.sh` starts it.
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.dogwood.host

import dev.dogwood.protocol.HttpRequest
import dev.dogwood.protocol.HttpResponse
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfURL

private const val WITNESS_HOST = "127.0.0.1"

private fun emit(line: String) = println(line)

/**
 * Refused, in the shape the host actually refuses.
 *
 * `HttpResponse(code = 0, failure = ...)` rather than a throw, deliberately: a payload delivered
 * over the air must not be able to crash a screen by naming a host the client does not permit.
 */
private fun HttpResponse.wasRefused(): Boolean = code == 0 && failure != null

class NetworkPolicyConformanceTest {

  private val port: Int = 8123
  private val base get() = "http://$WITNESS_HOST:$port"

  private var passed = 0
  private var failed = 0

  private fun conform(id: String, condition: Boolean, detail: String = "") {
    val suffix = if (detail.isEmpty()) "" else " -- $detail"
    if (condition) {
      passed++
      emit("CONF $id PASS$suffix")
    } else {
      failed++
      emit("CONF $id FAIL$suffix")
    }
  }

  /** Read directly, not through the host: this is the witness talking, not the thing under test. */
  private fun witnessRead(path: String): String? {
    val url = NSURL.URLWithString("$base$path") ?: return null
    return NSString.stringWithContentsOfURL(url, NSUTF8StringEncoding, null)
  }

  private fun witnessLog(): List<String> =
    witnessRead("/__log")?.trim()?.lines()?.filter { it.isNotBlank() } ?: emptyList()

  private fun resetWitness() {
    witnessRead("/__reset")
  }

  private fun permissive() = UrlSessionNetwork(
    allow = allowUrlHosts(WITNESS_HOST, allowCleartextHosts = setOf(WITNESS_HOST)),
  )

  @Test
  fun networkPolicyConformance() = runBlocking {
    if (witnessRead("/__reset") == null) {
      // A skip, not a pass. Every refusal below would hold vacuously against a dead server.
      emit("CONF F1 SKIP -- the witness server is not running; see tools/conformance")
      emit("CONF F2 SKIP -- the witness server is not running; see tools/conformance")
      emit("CONF F4 SKIP -- the witness server is not running; see tools/conformance")
      return@runBlocking
    }

    // Control. Without it, every refusal could be a broken server rather than a policy.
    resetWitness()
    val allowed = permissive().fetch(HttpRequest(url = "$base/allowed"))
    conform(
      "F1-control",
      allowed.body?.contains("dogwood-policy-ok") == true,
      "a permitted request reaches the witness: ${allowed.failure ?: "ok"}",
    )

    // F1 -- default-deny.
    resetWitness()
    val denied = UrlSessionNetwork().fetch(HttpRequest(url = "$base/allowed"))
    conform(
      "F1",
      denied.wasRefused() && witnessLog().isEmpty(),
      "refused=${denied.failure}, witness saw ${witnessLog()}",
    )

    // F2 -- every redirect hop is re-checked against the rule.
    resetWitness()
    val redirected = permissive().fetch(HttpRequest(url = "$base/redirect-to-blocked"))
    val hops = witnessLog()
    conform(
      "F2",
      redirected.wasRefused() && hops == listOf("/redirect-to-blocked"),
      "refused=${redirected.failure}, hops=$hops",
    )

    // ...and a permitted redirect is still followed, or F2 would pass on a client following none.
    resetWitness()
    val followed = permissive().fetch(HttpRequest(url = "$base/redirect-to-allowed"))
    conform(
      "F2-follows-allowed",
      followed.body?.contains("dogwood-policy-ok") == true && witnessLog().contains("/landing"),
      "hops=${witnessLog()}",
    )

    // F4 -- naming a host is not excusing cleartext for it.
    resetWitness()
    val namedNotCleartext = UrlSessionNetwork(allow = allowUrlHosts(WITNESS_HOST))
      .fetch(HttpRequest(url = "$base/allowed"))
    conform(
      "F4",
      namedNotCleartext.wasRefused() && witnessLog().isEmpty(),
      "refused=${namedNotCleartext.failure}, witness saw ${witnessLog()}",
    )

    // F4-scheme -- the defect, in the form it shipped. NSURL parses any scheme and NSURLSession
    // serves `file:` natively, so a rule that only waived "must be https" made the application's
    // own container readable through the network policy.
    val fileResult = permissive().fetch(HttpRequest(url = "file:///etc/hosts"))
    conform("F4-scheme", fileResult.wasRefused(), "file:// refused=${fileResult.failure}")

    emit("CONF NOTE ios network policy: passed=$passed failed=$failed")
    kotlin.test.assertEquals(0, failed, "failed conformance claims")
  }
}

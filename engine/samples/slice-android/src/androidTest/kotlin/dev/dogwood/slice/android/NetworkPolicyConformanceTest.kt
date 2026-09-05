/*
 * Project Dogwood -- conformance claims F1, F2 and F4 against a real network stack.
 *
 * The policy unit tests are tests of the allow *rule*: the Java Virtual Machine one hands
 * `OkHttpNetwork` a client that fails if it is called, and the iOS one says in its own header that
 * it "tests our logic exhaustively and does not test that NSURLSession" behaves. Neither opens a
 * socket, and that is the shape of the one policy defect this project shipped -- `file://` served
 * out of the application's own container, because the rule waived "must be https" and the loading
 * system then happily served a file. Asserting on a lambda cannot find that.
 *
 * So these ask the real stack to make real requests at a witness server, and prove a refusal by the
 * **absence of a request there**. That is a stronger claim than the client reporting a refusal: a
 * client can report one and still have opened the connection.
 *
 * The witness runs on the development machine; `10.0.2.2` is how the emulator reaches it.
 */
package dev.dogwood.slice.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dogwood.host.OkHttpNetwork
import dev.dogwood.host.allowHosts
import dev.dogwood.protocol.HttpRequest
import java.net.URL
import dev.dogwood.protocol.HttpResponse
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val WITNESS_HOST = "10.0.2.2"
private const val CONF_TAG = "DogwoodConf"

private fun emit(line: String) {
  android.util.Log.i(CONF_TAG, line)
  println(line)
}

@RunWith(AndroidJUnit4::class)
class NetworkPolicyConformanceTest {

  private val port = System.getProperty("dogwood.policyPort")?.toIntOrNull() ?: 8123
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

  /**
   * Whether the host refused, in the shape it actually refuses.
   *
   * `OkHttpNetwork` returns `HttpResponse(code = 0, failure = ...)` rather than throwing, and that
   * is deliberate: a payload delivered over the air should not be able to crash a screen by naming
   * a host the client does not permit. The first draft of this drill asserted on an exception and
   * reported four failures against a policy that was working perfectly -- the witness log, which is
   * the claim that matters, was empty every time.
   */
  private fun HttpResponse.wasRefused(): Boolean = code == 0 && failure != null

  /** What the witness actually received. The refusal claims are assertions about this being empty. */
  private fun witnessLog(): List<String> =
    URL("$base/__log").readText().trim().lines().filter { it.isNotBlank() }

  private fun resetWitness() {
    URL("$base/__reset").readText()
  }

  @Before
  fun clearTheWitness() {
    resetWitness()
  }

  /**
   * A network that permits the witness over cleartext, which is what a development host does.
   *
   * `allowHosts` takes the cleartext opt-in per host: naming a host is not the same as excusing
   * plain HyperText Transfer Protocol for it, and F4 is the claim that those are separate.
   */
  private fun permissive() = OkHttpNetwork(
    client = OkHttpClient(),
    allow = allowHosts(WITNESS_HOST, allowCleartextHosts = setOf(WITNESS_HOST)),
  )

  @Test
  fun networkPolicyConformance() = runBlocking {
    // Control. Without this, every refusal below could be a broken server rather than a policy.
    val allowed = permissive().fetch(HttpRequest(url = "$base/allowed"))
    conform(
      "F1-control",
      allowed.body?.contains("dogwood-policy-ok") == true,
      "a permitted request reaches the witness: ${allowed.failure ?: "ok"}",
    )

    // F1 -- default-deny. A host nobody named must not be reachable, and the proof is that the
    // witness never heard from us.
    resetWitness()
    val denied = OkHttpNetwork(client = OkHttpClient())
    val deniedResult = denied.fetch(HttpRequest(url = "$base/allowed"))
    conform(
      "F1",
      deniedResult.wasRefused() && witnessLog().isEmpty(),
      "refused=${deniedResult.failure}, witness saw ${witnessLog()}",
    )

    // F2 -- every redirect hop is re-checked. The witness answers hop one with a redirect to a
    // host the rule does not permit; following it would be the defect.
    resetWitness()
    val redirected = permissive().fetch(HttpRequest(url = "$base/redirect-to-blocked"))
    val hops = witnessLog()
    conform(
      "F2",
      redirected.wasRefused() && hops == listOf("/redirect-to-blocked"),
      "refused=${redirected.failure}, hops=$hops",
    )

    // ...and a permitted redirect still works, or F2 would pass on a client that follows none.
    resetWitness()
    val followed = permissive().fetch(HttpRequest(url = "$base/redirect-to-allowed"))
    conform(
      "F2-follows-allowed",
      followed.body?.contains("dogwood-policy-ok") == true && witnessLog().contains("/landing"),
      "hops=${witnessLog()}",
    )

    // F4 -- cleartext is opted into per host, separately from naming the host. This network names
    // the witness and does *not* excuse plain HyperText Transfer Protocol for it.
    resetWitness()
    val namedButNotCleartext = OkHttpNetwork(
      client = OkHttpClient(),
      allow = allowHosts(WITNESS_HOST),
    )
    val cleartext = namedButNotCleartext.fetch(HttpRequest(url = "$base/allowed"))
    conform(
      "F4",
      cleartext.wasRefused() && witnessLog().isEmpty(),
      "refused=${cleartext.failure}, witness saw ${witnessLog()}",
    )

    // F4-scheme -- the shipped defect, in the form it shipped. A named host must not smuggle a
    // non-HyperText-Transfer-Protocol scheme past the rule.
    resetWitness()
    val fileResult = permissive().fetch(HttpRequest(url = "file:///etc/hosts"))
    conform(
      "F4-scheme",
      fileResult.wasRefused(),
      "file:// refused=${fileResult.failure}",
    )

    assertEquals("failed conformance claims", 0, failed)
  }
}

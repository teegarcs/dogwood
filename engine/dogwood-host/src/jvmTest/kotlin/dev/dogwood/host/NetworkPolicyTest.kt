/*
 * Project Dogwood -- the host as the network policy point.
 *
 * The payload this service serves was downloaded and can be replaced over the air without a store
 * review. If it could reach an arbitrary address through the application's network stack it would
 * be an exfiltration channel with the application's name on it. These tests are about that, not
 * about HTTP.
 */
package dev.dogwood.host

import dev.dogwood.protocol.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class NetworkPolicyTest {

  /** A client that would fail loudly if a request ever escaped the policy check. */
  private val neverCalled = OkHttpClient.Builder()
    .addInterceptor { error("a request reached the network stack; the allow rule did not hold") }
    .build()

  @Test
  fun theDefaultIsToRefuseEverything() {
    // An embedder that forgets to configure the allow rule gets a service that does nothing,
    // which is the safe direction for it to fail in.
    val network = OkHttpNetwork(client = neverCalled)
    val response = runBlocking { network.fetch(HttpRequest(url = "https://example.com/data")) }
    assertFalse(response.isSuccessful)
    assertEquals(0, response.code)
    assertTrue(response.failure!!.contains("does not allow"), response.failure!!)
  }

  @Test
  fun aRefusalIsAValueNotAnException() {
    // A guest must be able to write "this client will not let me do that" as an ordinary branch.
    // An exception here would surface as a boundary error with no state for a screen to render.
    val network = OkHttpNetwork(client = neverCalled, allow = allowHosts("api.example.com"))
    val response = runBlocking { network.fetch(HttpRequest(url = "https://elsewhere.example/data")) }
    assertEquals(0, response.code)
    assertTrue(response.failure != null)
  }

  @Test
  fun aMalformedUrlIsRefusedBeforeItReachesTheNetworkStack() {
    val network = OkHttpNetwork(client = neverCalled, allow = { true })
    val response = runBlocking { network.fetch(HttpRequest(url = "not a url")) }
    assertTrue(response.failure!!.contains("not a valid URL"), response.failure!!)
  }

  @Test
  fun theAllowRuleMatchesHostsAndRequiresTransportSecurity() {
    val allow = allowHosts("api.example.com", "cdn.example.com")
    assertTrue(allow("https://api.example.com/v1/explore".toHttpUrl()))
    assertTrue(allow("https://cdn.example.com/a.json".toHttpUrl()))
    // Right host, wrong scheme. Cleartext is not implied by naming a host.
    assertFalse(allow("http://api.example.com/v1/explore".toHttpUrl()))
    // A subdomain is a different host, and a suffix match here would allow
    // `api.example.com.attacker.test`.
    assertFalse(allow("https://evil.api.example.com/".toHttpUrl()))
    assertFalse(allow("https://api.example.com.attacker.test/".toHttpUrl()))
    assertFalse(allow("https://other.example/".toHttpUrl()))
  }

  @Test
  fun cleartextIsOptedIntoPerHostRatherThanGlobally() {
    // Development servers need plain HTTP; a global "allow cleartext" switch is the kind that
    // gets turned on for a demo and shipped.
    val allow = allowHosts(
      "10.0.2.2",
      "api.example.com",
      allowCleartextHosts = setOf("10.0.2.2"),
    )
    assertTrue(allow("http://10.0.2.2:8080/explore.json".toHttpUrl()))
    assertTrue(allow("https://api.example.com/v1".toHttpUrl()))
    assertFalse(allow("http://api.example.com/v1".toHttpUrl()))
  }
}

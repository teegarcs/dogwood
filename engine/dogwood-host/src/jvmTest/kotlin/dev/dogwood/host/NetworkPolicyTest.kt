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
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

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

/*
 * Redirects, which the allow rule did not see.
 *
 * The policy runs once, before the request, on the URL the guest named. Both platforms' clients
 * follow redirects by default, so a guest that names an allowed host with an open-redirect
 * endpoint reaches whatever that endpoint points at -- and reads up to the body cap from it, with
 * headers of the guest's choosing. The allow list is the exfiltration boundary for code delivered
 * over the air without a store review, and a boundary checked only at the front door is not one.
 *
 * These use a loopback server so the assertion can be made from the side that matters: whether the
 * disallowed host was ever *contacted*. A test that only inspected the returned value could pass
 * while the request had already happened.
 */
class RedirectPolicyTest {

  /** A server that 302s to [target], and a second that records whether it was ever reached. */
  private fun servers(target: (Int) -> String): Triple<HttpServer, HttpServer, () -> Int> {
    val forbidden = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val hits = java.util.concurrent.atomic.AtomicInteger()
    forbidden.createContext("/") { exchange ->
      hits.incrementAndGet()
      val body = "secret".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    forbidden.start()

    val allowed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    allowed.createContext("/redirect") { exchange ->
      exchange.responseHeaders.add("Location", target(forbidden.address.port))
      exchange.sendResponseHeaders(302, -1)
      exchange.close()
    }
    allowed.createContext("/ok") { exchange ->
      val body = "fine".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    allowed.start()
    return Triple(allowed, forbidden, hits::get)
  }

  @Test
  fun aRedirectToADisallowedHostIsNotFollowed() = runBlocking {
    val (allowed, forbidden, hits) = servers { port -> "http://127.0.0.1:$port/" }
    try {
      // Only the redirecting host is named. `127.0.0.1` covers both servers by host, so the
      // *port* is what differs -- which is exactly the case a host-only allow list must still
      // refuse, and the reason the rule is given the whole URL rather than a hostname.
      val network = OkHttpNetwork(
        client = OkHttpClient(),
        allow = { url -> url.port == allowed.address.port },
      )
      val response = network.fetch(
        HttpRequest(url = "http://127.0.0.1:${allowed.address.port}/redirect"),
      )
      assertEquals(0, hits(), "the disallowed target must never be contacted")
      assertFalse(response.isSuccessful, "a refused redirect is not a success")
      assertTrue(
        response.body != "secret",
        "the disallowed body must not reach the guest; got ${response.body}",
      )
    } finally {
      allowed.stop(0); forbidden.stop(0)
    }
  }

  @Test
  fun aRedirectWithinTheAllowListIsStillFollowed() = runBlocking {
    // The control. Without it the test above would pass against a client that refused every
    // redirect, or every request.
    val (allowed, forbidden, _) = servers { _ -> "/ok" }
    try {
      val network = OkHttpNetwork(
        client = OkHttpClient(),
        allow = { url -> url.port == allowed.address.port },
      )
      val response = network.fetch(
        HttpRequest(url = "http://127.0.0.1:${allowed.address.port}/redirect"),
      )
      assertTrue(response.isSuccessful, "an allowed redirect must still work: ${response.failure}")
      assertEquals("fine", response.body)
    } finally {
      allowed.stop(0); forbidden.stop(0)
    }
  }
}

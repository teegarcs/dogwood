/*
 * Project Dogwood -- the iOS fetch policy, tested where the decisions are made.
 *
 * `NetworkPolicyIosTest` covers the allow rule as a pure function. This covers the two decisions
 * that rule cannot make on its own, and that the completion-handler API this replaced could not
 * make at all: refusing an oversized body **before the host buffers it**, and refusing to follow a
 * redirect to a host outside the allow list.
 *
 * These drive `PolicedSessionDelegate` directly rather than through a socket. That is a deliberate
 * trade and worth stating: it tests *our* logic exhaustively and does not test that NSURLSession
 * calls these methods, which is Foundation's documented contract and is exercised for real by the
 * iOS sample fetching its payload and its images on every launch. The alternative -- a loopback
 * server or a stubbed `NSURLProtocol` -- buys coverage of Apple's wiring at a cost in fragility
 * that this boundary does not need.
 *
 * What it *does* prove is the part that was broken: the cap is now consulted on the declared
 * length and again on a running count, with a cancel rather than a post-hoc complaint, so host
 * memory is bounded by the limit instead of by whatever a server chooses to send.
 */
package dev.dogwood.host

import dev.dogwood.protocol.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.dataUsingEncoding
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create

@OptIn(ExperimentalForeignApi::class)
private fun data(text: String): NSData =
  (NSString.create(string = text)).dataUsingEncoding(NSUTF8StringEncoding)!!

private fun response(url: String, length: Long): NSHTTPURLResponse = NSHTTPURLResponse(
  uRL = NSURL.URLWithString(url)!!,
  statusCode = 200,
  HTTPVersion = "HTTP/1.1",
  headerFields = if (length >= 0) mapOf<Any?, Any?>("Content-Length" to length.toString()) else emptyMap<Any?, Any?>(),
)!!

private val anySession: NSURLSession get() = NSURLSession.sharedSession

@OptIn(ExperimentalForeignApi::class)
class UrlSessionPolicyTest {

  private fun delegate(
    cap: Long = 1024,
    allow: (NSURL) -> Boolean = { true },
    onOutcome: (HttpResponse) -> Unit,
  ) = PolicedSessionDelegate(maxBodyBytes = cap, allow = allow, onOutcome = onOutcome)

  @Test
  fun aDeclaredLengthOverTheCapIsRefusedAndTheTransferCancelled() {
    // The first of the two teeth the Java Virtual Machine side always had. Refusing here means the
    // body is never requested, so nothing is allocated for it at all.
    var outcome: HttpResponse? = null
    var disposition: NSURLSessionResponseDisposition? = null
    val d = delegate(cap = 512) { outcome = it }
    d.URLSession(
      anySession,
      dataTask = anySession.dataTaskWithURL(NSURL.URLWithString("https://h/x")!!),
      didReceiveResponse = response("https://h/x", length = 4096),
      completionHandler = { disposition = it },
    )
    assertEquals(NSURLSessionResponseCancel, disposition, "an oversized body must be cancelled, not read")
    assertTrue("over this client's 512 byte limit" in (outcome?.failure ?: ""), outcome?.failure ?: "none")
  }

  @Test
  fun anUndeclaredBodyIsCancelledTheMomentItPassesTheCap() {
    // The chunked case: no length to refuse on, so the running count is the only bound. Before
    // this, Foundation buffered the whole response and the check ran afterwards -- a refusal that
    // reported the right thing while the memory had already been spent.
    var outcome: HttpResponse? = null
    var disposition: NSURLSessionResponseDisposition? = null
    val d = delegate(cap = 1000) { outcome = it }
    val task = anySession.dataTaskWithURL(NSURL.URLWithString("https://h/x")!!)
    d.URLSession(anySession, dataTask = task, didReceiveResponse = response("https://h/x", length = -1), completionHandler = { disposition = it })
    assertEquals(NSURLSessionResponseAllow, disposition, "an undeclared length must be allowed to start")

    d.URLSession(anySession, dataTask = task, didReceiveData = data("a".repeat(600)))
    assertNull(outcome, "600 bytes is under the cap; nothing should have settled yet")
    d.URLSession(anySession, dataTask = task, didReceiveData = data("b".repeat(600)))
    assertTrue("exceeds this client's 1000 byte limit" in (outcome?.failure ?: ""), outcome?.failure ?: "none")
  }

  @Test
  fun aBodyWithinTheCapIsDeliveredWhole() {
    // The control. Without it every assertion here would pass against a delegate that refused
    // everything.
    var outcome: HttpResponse? = null
    val d = delegate(cap = 1024) { outcome = it }
    val task = anySession.dataTaskWithURL(NSURL.URLWithString("https://h/x")!!)
    d.URLSession(anySession, dataTask = task, didReceiveResponse = response("https://h/x", length = 5), completionHandler = {})
    d.URLSession(anySession, dataTask = task, didReceiveData = data("hello"))
    d.URLSession(anySession, task = task, didCompleteWithError = null)
    assertEquals(200, outcome?.code)
    assertEquals("hello", outcome?.body)
  }

  @Test
  fun aRedirectToADisallowedHostIsNotFollowed() {
    // The allow rule used to run once, on the URL the guest named, while NSURLSession followed
    // 302s to anywhere. Any allowed host with an open-redirect endpoint was a way out.
    var followed: NSURLRequest? = null
    var called = false
    val d = delegate(allow = { it.host == "allowed.example" }) {}
    d.URLSession(
      anySession,
      task = anySession.dataTaskWithURL(NSURL.URLWithString("https://allowed.example/go")!!),
      willPerformHTTPRedirection = response("https://allowed.example/go", -1),
      newRequest = NSURLRequest.requestWithURL(NSURL.URLWithString("https://evil.example/steal")!!),
      completionHandler = { followed = it; called = true },
    )
    assertTrue(called, "the delegate must answer the redirect question")
    assertNull(followed, "a redirect outside the allow list must not be followed")
  }

  @Test
  fun aRedirectWithinTheAllowListIsStillFollowed() {
    var followed: NSURLRequest? = null
    val d = delegate(allow = { it.host == "allowed.example" }) {}
    val next = NSURLRequest.requestWithURL(NSURL.URLWithString("https://allowed.example/ok")!!)
    d.URLSession(
      anySession,
      task = anySession.dataTaskWithURL(NSURL.URLWithString("https://allowed.example/go")!!),
      willPerformHTTPRedirection = response("https://allowed.example/go", -1),
      newRequest = next,
      completionHandler = { followed = it },
    )
    assertEquals(next, followed, "an allowed redirect must still be followed")
  }
}

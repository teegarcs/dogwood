/*
 * Project Dogwood -- the iOS implementations of the two host services that cannot be common.
 *
 * The same two the Java-Virtual-Machine file names, for the same two reasons: the clock needs a
 * time-zone database and the network needs sockets. Every other service in the surface is
 * composition and already lives in the common `HostServices.kt`.
 *
 * The network policy is the Java-Virtual-Machine one, transliterated rather than reinvented,
 * because it is a security boundary and a second policy engine would be a second thing to get
 * wrong. Default-deny; an explicit allow list; Hypertext Transfer Protocol Secure (HTTPS) unless
 * a host is named for cleartext; a body cap enforced on what actually arrived; failures returned
 * as values rather than thrown. The one structural difference is the type the rule is written
 * against -- `NSURL` here, OkHttp's `HttpUrl` there -- because neither type exists on the other
 * platform.
 */
package dev.dogwood.host

import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import dev.dogwood.protocol.DogwoodClock
import dev.dogwood.protocol.DogwoodNetwork
import dev.dogwood.protocol.HttpRequest
import dev.dogwood.protocol.HttpResponse
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.localTimeZone
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSMutableData
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.appendData
import platform.darwin.NSObject
import platform.Foundation.NSURLRequest

/**
 * The platform clock.
 *
 * The same contract the Java-Virtual-Machine `SystemClock` keeps: QuickJS has `Date.now()`, so the
 * epoch reading is about *agreement* rather than availability, and the time zone is the part the
 * guest genuinely cannot obtain because the pinned QuickJS ships no ECMA-402 `Intl`.
 */
class NSDateClock : DogwoodClock {
  override fun nowEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

  override fun timeZoneId(): String = NSTimeZone.localTimeZone.name

  override fun close() = Unit
}

/**
 * The guest's route off the device on iOS, with the host as the policy point.
 *
 * [allow] defaults to refusing everything. See `OkHttpNetwork` for why that default is the design
 * rather than an inconvenience.
 */
class UrlSessionNetwork(
  private val session: NSURLSession = NSURLSession.sharedSession,
  private val maxBodyBytes: Long = 1L * 1024 * 1024,
  private val allow: (NSURL) -> Boolean = { false },
  /**
   * Last chance to adjust the session configuration before a request goes out.
   *
   * Exists so a test can install an `NSURLProtocol` and drive the loading system directly. That
   * matters more here than convenience: the properties worth asserting are that an oversized body
   * is refused *before* the host buffers it and that a disallowed redirect is never followed, and
   * neither can be observed from the client -- a client with an unbounded buffer reports a tidy
   * refusal right up until the process dies. A stub protocol can report how much it was allowed
   * to deliver before being cancelled, which is the boundedness proof itself.
   */
  private val configure: (NSURLSessionConfiguration) -> Unit = {},
) : DogwoodNetwork {

  @OptIn(ExperimentalForeignApi::class)
  override suspend fun fetch(request: HttpRequest): HttpResponse {
    val url = NSURL.URLWithString(request.url)
      ?: return HttpResponse(code = 0, failure = "not a valid URL: ${request.url}")
    if (!allow(url)) {
      return HttpResponse(
        code = 0,
        failure = "this client does not allow requests to ${url.host ?: request.url}",
      )
    }

    val built = NSMutableURLRequest(
      uRL = url,
      cachePolicy = NSURLRequestUseProtocolCachePolicy,
      timeoutInterval = 60.0,
    ).apply {
      setHTTPMethod(request.method)
      request.headers.forEach { (name, value) -> setValue(value, forHTTPHeaderField = name) }
      request.body?.let { setHTTPBody(it.toNSData()) }
    }

    return suspendCancellableCoroutine { continuation ->
      /*
       * A delegate rather than the completion-handler API, and the difference is the whole cap.
       *
       * `dataTaskWithRequest(request) { data, ... }` hands back a finished `NSData`, which means
       * Foundation has already buffered the entire body in memory before any check of ours can
       * run: a refusal after the fact bounds what the guest sees and not what the host allocates,
       * so a large enough response kills the application before the limit is consulted. The Java
       * Virtual Machine side never had that problem -- it refuses on the declared length and then
       * reads at most one byte past the cap -- and this restores the same two teeth here.
       *
       * The delegate also owns redirects, which is the other half: the allow rule ran once, on the
       * URL the guest named, while NSURLSession quietly followed 302s to anywhere.
       */
      val delegate = PolicedSessionDelegate(
        maxBodyBytes = maxBodyBytes,
        allow = allow,
        onOutcome = { outcome -> if (continuation.isActive) continuation.resume(outcome) },
      )
      val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration().apply {
        // `timeoutInterval` on the request is an *inter-byte* timer, so a server dripping one byte
        // under the interval holds the request open indefinitely; the resource timeout is the one
        // that bounds the whole exchange, and its default is seven days.
        setTimeoutIntervalForRequest(60.0)
        setTimeoutIntervalForResource(60.0)
        configure(this)
      }
      val session = NSURLSession.sessionWithConfiguration(
        configuration,
        delegate,
        delegateQueue = null,
      )
      val task = session.dataTaskWithRequest(built)
      continuation.invokeOnCancellation {
        task.cancel()
        session.finishTasksAndInvalidate()
      }
      delegate.onFinished = { session.finishTasksAndInvalidate() }
      task.resume()
    }
  }

  override fun close() = Unit
}

/**
 * The common allow rule on iOS: these hosts, over Hypertext Transfer Protocol Secure (HTTPS) only.
 *
 * @param allowCleartextHosts hosts that may also be reached over plain Hypertext Transfer Protocol,
 *   for development servers. Naming them individually keeps "cleartext is allowed" from becoming a
 *   global setting nobody remembers turning on. **On iOS the operating system has a say too**: App
 *   Transport Security refuses cleartext regardless of what this rule permits, unless the
 *   application's `Info.plist` also names the host.
 */
fun allowUrlHosts(
  vararg hosts: String,
  allowCleartextHosts: Set<String> = emptySet(),
): (NSURL) -> Boolean {
  val permitted = hosts.toSet()
  return { url ->
    val host = url.host
    val scheme = url.scheme?.lowercase()
    /*
     * The scheme is constrained explicitly, and on iOS that is load-bearing rather than pedantic.
     * On the Java Virtual Machine, `toHttpUrlOrNull()` returns null for anything that is not
     * http(s), so other schemes are structurally unreachable and the policy never sees them.
     * `NSURL` parses any scheme, and NSURLSession natively serves `file:`, `data:` and `ftp:` --
     * so the original transliteration, which only waived "must be https" for cleartext hosts,
     * passed `file://localhost/<anything in the app container>` the moment `localhost` was named
     * for cleartext. The waiver must name the scheme it waives, not merely excuse the one it
     * prefers: cleartext opt-in means http, never "anything that is not https".
     */
    host in permitted &&
      (scheme == "https" || (scheme == "http" && host in allowCleartextHosts))
  }
}

/**
 * Bytes in and out of Foundation, without the `String`-to-`NSString` cast.
 *
 * Kotlin/Native bridges `String` and `NSString` for parameters and returns but does not allow the
 * cast between them, so a request body has to be copied through a pinned `ByteArray`. The copy is
 * bounded by the same cap the response side enforces.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun String.toNSData(): NSData {
  val bytes = encodeToByteArray()
  if (bytes.isEmpty()) return NSData()
  return bytes.usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.utf8(): String {
  val length = this.length.toInt()
  if (length == 0) return ""
  val pointer = this.bytes ?: return ""
  return pointer.readBytes(length).decodeToString()
}

/**
 * Applies this client's network policy to a response as it arrives, rather than after it has.
 *
 * Three jobs, all of which the completion-handler API made impossible:
 *
 *  - refuse on the **declared** length before a byte of body is read;
 *  - keep a running count and cancel the moment the body passes the cap, so host memory is bounded
 *    by the limit rather than by what a server chooses to send;
 *  - re-apply the allow rule to **every redirect**, because a policy checked only at the front
 *    door lets any allowed host with an open-redirect endpoint forward a guest anywhere.
 */
@OptIn(ExperimentalForeignApi::class)
internal class PolicedSessionDelegate(
  private val maxBodyBytes: Long,
  private val allow: (NSURL) -> Boolean,
  private val onOutcome: (HttpResponse) -> Unit,
) : NSObject(), NSURLSessionDataDelegateProtocol {

  private val buffer = NSMutableData()
  private var status: Long = 0
  private var headers: Map<String, String> = emptyMap()
  private var settled = false
  var onFinished: (() -> Unit)? = null

  private fun settle(response: HttpResponse) {
    if (settled) return
    settled = true
    onOutcome(response)
    onFinished?.invoke()
  }

  override fun URLSession(
    session: NSURLSession,
    dataTask: NSURLSessionDataTask,
    didReceiveResponse: NSURLResponse,
    completionHandler: (NSURLSessionResponseDisposition) -> Unit,
  ) {
    val http = didReceiveResponse as? NSHTTPURLResponse
    status = http?.statusCode ?: 0
    headers = buildMap {
      http?.allHeaderFields?.forEach { (k, v) -> put(k.toString(), v.toString()) }
    }
    val declared = didReceiveResponse.expectedContentLength
    if (declared > maxBodyBytes) {
      completionHandler(NSURLSessionResponseCancel)
      settle(
        HttpResponse(
          code = 0,
          failure = "response declares $declared bytes, over this client's $maxBodyBytes byte limit",
        ),
      )
      return
    }
    completionHandler(NSURLSessionResponseAllow)
  }

  override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
    if (settled) return
    buffer.appendData(didReceiveData)
    if (buffer.length.toLong() > maxBodyBytes) {
      dataTask.cancel()
      settle(
        HttpResponse(
          code = 0,
          failure = "response body exceeds this client's $maxBodyBytes byte limit",
        ),
      )
    }
  }

  override fun URLSession(
    session: NSURLSession,
    task: NSURLSessionTask,
    willPerformHTTPRedirection: NSHTTPURLResponse,
    newRequest: NSURLRequest,
    completionHandler: (NSURLRequest?) -> Unit,
  ) {
    val next = newRequest.URL
    if (next == null || !allow(next)) {
      /*
       * Refused, in the same shape the Java Virtual Machine host refuses -- and this used to
       * differ.
       *
       * It previously declined to follow and let the 3xx surface as the response, reasoning that
       * "the 3xx itself is a perfectly ordinary response for a guest to see". It is not. This host
       * follows redirects on the guest's behalf, so a 3xx never reaches a guest through any other
       * path: its only meaning is "policy stopped a hop", and delivering it as an ordinary
       * response with no `failure` means a guest checking for one reads a blocked request as a
       * successful one.
       *
       * The conformance drill found this by asking the same claim of both hosts and getting two
       * answers (`F2`). The other half of the old reasoning still stands and is honoured: a
       * refusal must not look like a transport error, which is why this is a `failure` string
       * naming the policy rather than an `NSError`.
       */
      settle(
        HttpResponse(
          code = 0,
          failure = "this client does not allow requests to ${next?.host ?: newRequest.URL}",
        ),
      )
      completionHandler(null)
      return
    }
    completionHandler(newRequest)
  }

  override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
    if (settled) return
    if (didCompleteWithError != null) {
      settle(
        HttpResponse(
          code = 0,
          failure = "${didCompleteWithError.domain}: ${didCompleteWithError.localizedDescription}",
        ),
      )
      return
    }
    settle(HttpResponse(code = status.toInt(), headers = headers, body = buffer.utf8()))
  }
}

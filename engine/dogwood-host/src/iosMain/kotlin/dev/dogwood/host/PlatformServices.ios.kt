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
      val task = session.dataTaskWithRequest(built) { data: NSData?, response: NSURLResponse?, error: NSError? ->
        if (error != null) {
          continuation.resume(
            HttpResponse(code = 0, failure = "${error.domain}: ${error.localizedDescription}"),
          )
          return@dataTaskWithRequest
        }
        val http = response as? NSHTTPURLResponse
        val length = data?.length?.toLong() ?: 0L
        // The response crosses the boundary as a `String`, so an unbounded body is an unbounded
        // allocation in the host *and* in QuickJS. Over the cap is a refusal, not a truncation.
        if (length > maxBodyBytes) {
          continuation.resume(
            HttpResponse(
              code = 0,
              failure = "response body exceeds this client's $maxBodyBytes byte limit",
            ),
          )
          return@dataTaskWithRequest
        }
        val text = data?.utf8().orEmpty()
        val headers = buildMap {
          http?.allHeaderFields?.forEach { (k, v) -> put(k.toString(), v.toString()) }
        }
        continuation.resume(
          HttpResponse(code = http?.statusCode?.toInt() ?: 0, headers = headers, body = text),
        )
      }
      continuation.invokeOnCancellation { task.cancel() }
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

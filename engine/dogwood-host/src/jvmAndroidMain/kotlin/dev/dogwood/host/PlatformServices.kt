/*
 * Project Dogwood -- the Java-Virtual-Machine implementations of two host services.
 *
 * The clock and the network are the two services that cannot be written in common Kotlin: one
 * needs a time zone database and the other needs sockets. Everything else in the service surface
 * is composition and lives in `HostServices.kt`.
 */
package dev.dogwood.host

import dev.dogwood.protocol.DogwoodClock
import dev.dogwood.protocol.DogwoodNetwork
import dev.dogwood.protocol.HttpRequest
import dev.dogwood.protocol.HttpResponse
import java.io.IOException
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The platform clock.
 *
 * QuickJS has a `Date.now()`, so the epoch reading here is about *agreement* rather than
 * availability: a guest that timestamps an analytics event should use the same clock the host
 * does, and a test that pins time needs one place to pin. The time zone is different — the guest
 * genuinely cannot obtain it, because the pinned QuickJS ships no ECMA-402 `Intl`.
 */
class SystemClock : DogwoodClock {
  override fun nowEpochMillis(): Long = System.currentTimeMillis()

  override fun timeZoneId(): String = TimeZone.getDefault().id

  override fun close() = Unit
}

/**
 * The guest's route off the device, with the host as the policy point.
 *
 * **[allow] defaults to refusing everything, and that default is the design.** This payload was
 * downloaded and can be replaced over the air without a store review; if it could reach an
 * arbitrary host through the application's network stack it would be an exfiltration channel with
 * the application's name on it. An embedder opts in to destinations deliberately, and
 * [allowHosts] is the one-liner for the common case.
 *
 * Three further limits, each closing a way a guest could hurt the host rather than itself:
 *
 *  - **A body cap.** The response crosses the boundary as a `String`, so an unbounded body is an
 *    unbounded allocation in the host *and* in QuickJS. Over the cap is a refusal, not a
 *    truncation, because a silently truncated JavaScript Object Notation (JSON) document is worse
 *    than none.
 *  - **Input/output on [Dispatchers.IO].** The call arrives on the Zipline thread, which is the
 *    only thread that may touch the guest; blocking it would stall composition, the frame clock
 *    and every pending event until the network answered.
 *  - **Failures as values.** A refusal, a timeout and a connection error all return
 *    [HttpResponse] with `failure` set. An exception thrown here would surface in guest code as a
 *    boundary error rather than as something a screen can render an empty state for.
 */
class OkHttpNetwork(
  private val client: OkHttpClient,
  private val maxBodyBytes: Long = 1L * 1024 * 1024,
  private val allow: (HttpUrl) -> Boolean = { false },
) : DogwoodNetwork {

  override suspend fun fetch(request: HttpRequest): HttpResponse {
    val url = request.url.toHttpUrlOrNull()
      ?: return HttpResponse(code = 0, failure = "not a valid URL: ${request.url}")
    if (!allow(url)) {
      return HttpResponse(code = 0, failure = "this client does not allow requests to ${url.host}")
    }

    return withContext(Dispatchers.IO) {
      try {
        val body = request.body?.toRequestBody()
        val built = Request.Builder()
          .url(url)
          .method(request.method, body)
          .apply { request.headers.forEach { (name, value) -> header(name, value) } }
          .build()

        client.newCall(built).execute().use { response ->
          val length = response.body?.contentLength() ?: -1L
          if (length > maxBodyBytes) {
            return@use HttpResponse(
              code = 0,
              failure = "response of $length bytes exceeds this client's $maxBodyBytes byte limit",
            )
          }
          // contentLength() is -1 for a chunked response, so the cap is enforced again on what
          // actually arrived. Reading at most one byte past the limit is what makes the check
          // meaningful rather than advisory.
          val source = response.body?.source()
          val text = source?.let {
            it.request(maxBodyBytes + 1)
            if (it.buffer.size > maxBodyBytes) {
              return@use HttpResponse(
                code = 0,
                failure = "response body exceeds this client's $maxBodyBytes byte limit",
              )
            }
            it.readUtf8()
          }.orEmpty()

          HttpResponse(
            code = response.code,
            headers = response.headers.toMultimap().mapValues { (_, v) -> v.joinToString(",") },
            body = text,
          )
        }
      } catch (e: IOException) {
        HttpResponse(code = 0, failure = "${e::class.simpleName}: ${e.message}")
      }
    }
  }

  override fun close() = Unit
}

/**
 * The common allow rule: these hosts, over Hypertext Transfer Protocol Secure (HTTPS) only.
 *
 * @param allowCleartextHosts hosts that may also be reached over plain Hypertext Transfer Protocol,
 *   for development servers. Naming them individually keeps "cleartext is allowed" from becoming a
 *   global setting nobody remembers turning on.
 */
fun allowHosts(
  vararg hosts: String,
  allowCleartextHosts: Set<String> = emptySet(),
): (HttpUrl) -> Boolean {
  val permitted = hosts.toSet()
  return { url ->
    url.host in permitted && (url.isHttps || url.host in allowCleartextHosts)
  }
}

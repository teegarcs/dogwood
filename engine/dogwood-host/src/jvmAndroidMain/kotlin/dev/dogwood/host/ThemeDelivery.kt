/*
 * Project Dogwood -- Route C: the theme document from the payload origin.
 *
 * Route A is the compiled-in default and needs no code. Route B is the product's own
 * configuration channel and needs four lines of the product's code. This is Route C: fetch
 * `theme.json` from the same origin the payloads come from, keep the last good copy on disk, and
 * never let the network decide what the first frame looks like.
 *
 * **Deliberately unsigned, and the asymmetry is reasoned rather than lazy.** Payloads are
 * Ed25519-signed because they are code -- a tampered payload runs in the process. A theme is
 * data: the worst a tampered document can do is make the application ugly or hard to read, and
 * the transport to the origin is already authenticated by Hypertext Transfer Protocol Secure
 * (HTTPS). A product whose posture wants parity can wrap the fetch and verify a detached
 * signature before handing the bytes over; the seam takes bytes, not trust decisions.
 */
package dev.dogwood.host

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The theme lifecycle, in the order that keeps the first frame honest:
 *
 *  1. [cached] at startup -- the last document this device saw, or the default. Applied
 *     immediately, so there is no flash of the wrong brand while a fetch runs.
 *  2. [refresh] in the background. On success the new document is cached and returned; apply it
 *     and the swap repaints live.
 *  3. Any failure -- offline, a bad response, a malformed document -- leaves the cached copy in
 *     force. A theme fetch failing is never a reason a screen looks broken.
 */
class ThemeStore(
  private val cacheFile: File,
  private val fallback: Theme = Theme.Default,
  private val onProblem: (String) -> Unit = {},
) {
  /** The last known good document, or [fallback]. Fast enough for startup; reads one small file. */
  fun cached(): Theme {
    val text = runCatching { cacheFile.takeIf { it.exists() }?.readText() }.getOrNull()
      ?: return fallback
    return Theme.fromJson(text, fallback, onProblem)
  }

  /**
   * Fetches [url], caches the document if it parses at all, and returns the theme in force.
   *
   * The parse happens **before** the cache write: a document that is not even JSON must not
   * replace a good cached copy. Field-level problems still fall back per field and are reported,
   * but do not reject the document -- a rename of one colour should not freeze a brand rollout.
   */
  suspend fun refresh(client: OkHttpClient, url: String): Theme = withContext(Dispatchers.IO) {
    val body = runCatching {
      client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) {
          onProblem("theme fetch: HTTP ${response.code} from $url")
          return@use null
        }
        response.body?.string()
      }
    }.getOrElse {
      onProblem("theme fetch: ${it::class.simpleName}: ${it.message}")
      null
    } ?: return@withContext cached()

    var structurallySound = true
    val theme = Theme.fromJson(body, cached()) { problem ->
      if (problem.startsWith("not a JSON object")) structurallySound = false
      onProblem(problem)
    }
    if (structurallySound) {
      runCatching {
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeText(body)
      }.onFailure { onProblem("theme cache: ${it.message}") }
    }
    theme
  }
}

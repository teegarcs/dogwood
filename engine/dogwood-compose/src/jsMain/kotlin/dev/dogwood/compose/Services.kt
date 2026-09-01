/*
 * Project Dogwood -- host services, as guest code sees them.
 *
 * The protocol side is `dogwood-protocol/HostServices.kt`. This is the ergonomic half: the
 * services are resolved once when the experience starts and published as one static composition
 * local, so guest code reads them the way it reads anything else in Compose.
 *
 * Resolution happens once because it is not free. Every call to `DogwoodServices.log()` crosses
 * the boundary and allocates a fresh service proxy on both sides; calling it per composition would
 * leak proxies at the rate the screen recomposes.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.dogwood.protocol.DogwoodAnalytics
import dev.dogwood.protocol.DogwoodClock
import dev.dogwood.protocol.DogwoodFeatureFlags
import dev.dogwood.protocol.DogwoodLog
import dev.dogwood.protocol.DogwoodNetwork
import dev.dogwood.protocol.DogwoodServices
import dev.dogwood.protocol.HttpRequest
import dev.dogwood.protocol.HttpResponse
import dev.dogwood.protocol.LogLevel
import dev.dogwood.protocol.SERVICES_SEGMENT
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * What this host offers, resolved once.
 *
 * Every field may be null, and a null is normal rather than exceptional: it means this client does
 * not implement that service. Guest code is expected to degrade — record nothing, log nothing,
 * fall back to `Date.now()` — not to fail.
 *
 * @param version the client's `dogwood.services` revision. A guest that wants a method added after
 *   the revision it is talking to **must check this before calling it**. Unlike an unknown widget
 *   tag, which becomes a placeholder, calling a `ZiplineService` method an older host does not
 *   implement is an error at the boundary with no fallback path.
 */
class HostServices(
  val version: Int,
  val available: Set<String>,
  val log: DogwoodLog?,
  val clock: DogwoodClock?,
  val analytics: DogwoodAnalytics?,
  val network: DogwoodNetwork?,
  /**
   * Flags as they stood when the experience started.
   *
   * A snapshot, not a feed: a flag flipped while this screen is open does not reach it. Stated
   * here because the failure mode is silent — the screen keeps working, with the old answer.
   */
  val flags: Map<String, String>,
) {
  fun debug(tag: String, message: String) = log?.log(LogLevel.Debug, tag, message)

  fun info(tag: String, message: String) = log?.log(LogLevel.Info, tag, message)

  fun warn(tag: String, message: String) = log?.log(LogLevel.Warn, tag, message)

  fun error(tag: String, message: String) = log?.log(LogLevel.Error, tag, message)

  fun track(name: String, properties: Map<String, String> = emptyMap()) {
    analytics?.track(name, properties)
  }

  fun flag(name: String, default: String = ""): String = flags[name] ?: default

  fun flagEnabled(name: String, default: Boolean = false): Boolean =
    flags[name]?.equals("true", ignoreCase = true) ?: default

  fun nowEpochMillis(): Long? = clock?.nowEpochMillis()

  /**
   * Fetches, or returns a refusal.
   *
   * Never throws for a network outcome: a host that declines the request answers with
   * [HttpResponse.failure] set, because "this client will not let me do that" is a branch a guest
   * has to be able to write. A null network service is reported the same way, so guest code has
   * one shape to handle rather than two.
   */
  suspend fun fetch(request: HttpRequest): HttpResponse {
    val service = network ?: return HttpResponse(code = 0, failure = "this client offers no network service")
    return service.fetch(request)
  }

  companion object {
    /** What a composition sees when nothing provided services — tests, and hosts that offer none. */
    val None = HostServices(0, emptySet(), null, null, null, null, emptyMap())

    internal fun resolve(services: DogwoodServices, version: Int): HostServices {
      val flagService: DogwoodFeatureFlags? = services.featureFlags()
      return HostServices(
        version = version,
        available = services.available(),
        log = services.log(),
        clock = services.clock(),
        analytics = services.analytics(),
        network = services.network(),
        flags = flagService?.snapshot() ?: emptyMap(),
      )
    }
  }
}

/**
 * Static, because the set of services is fixed for a composition's lifetime.
 *
 * A host cannot start offering analytics halfway through a screen; if it ever needs to, that is a
 * pushed value with dedupe rules, like `HostEnvironment`, not a mutable local.
 */
val LocalHostServices = staticCompositionLocalOf { HostServices.None }

/**
 * The parameters this experience was launched with.
 *
 * Deliberately a raw `JsonElement` rather than a typed value: the host cannot construct guest
 * types, so the launch payload has to be data. Guest code decodes it into whatever it declared,
 * which keeps the type on the side that owns it.
 */
val LocalLaunchParams = staticCompositionLocalOf<JsonElement> { JsonNull }

/** Shorthand for the services in force. */
@Composable
@ReadOnlyComposable
fun services(): HostServices = LocalHostServices.current

/** The name of the segment carrying the service-surface version, for a `LocalSegmentVersions` read. */
const val ServicesSegment = SERVICES_SEGMENT

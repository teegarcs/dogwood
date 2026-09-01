/*
 * Project Dogwood -- the host service surface.
 *
 * roadmap.md Phase 4, "Host services & entry points". A guest experience is sandboxed: it has no
 * filesystem, no sockets, no clock it can trust, no logger, and no way to learn anything about the
 * account or the build it is running in. Everything it can reach, it reaches through here.
 *
 * Three properties this surface has, and each is a decision rather than an accident.
 *
 * **Services are handed to the guest, not looked up by it.** They arrive as parameters of
 * `DogwoodGuestUi.start`, exactly as `DogwoodHost` already does, rather than through
 * `zipline.take(name)`. A name lookup would make "does this host offer analytics?" a runtime
 * string question answered by an exception; a nullable typed accessor makes it a value.
 *
 * **Every service is optional and its absence is normal.** A host that does not implement
 * analytics returns null, and guest code written against a host that does still runs -- it simply
 * records nothing. The alternative, a host obliged to stub every service Dogwood ever defines,
 * makes adding a service a breaking change for every host.
 *
 * **The surface is versioned like the dictionary.** `segmentVersions["dogwood.services"]` says
 * which revision of this file the client was built against. This matters more here than for
 * widgets: an unknown widget tag degrades to a placeholder, but calling a `ZiplineService` method
 * an older host does not implement is an error at the boundary, not a fallback. A guest that
 * wants a method added after version N must check the version before calling it.
 */
package dev.dogwood.protocol

import app.cash.zipline.ZiplineService
import kotlinx.serialization.Serializable

/** The service-surface revision this file describes. Reported as `segmentVersions[SERVICES_SEGMENT]`. */
const val SERVICES_SEGMENT = "dogwood.services"
const val SERVICES_VERSION = 1

/** Names reported by [DogwoodServices.available], so a guest can branch without calling. */
object ServiceNames {
  const val LOG = "log"
  const val CLOCK = "clock"
  const val ANALYTICS = "analytics"
  const val FEATURE_FLAGS = "featureFlags"
  const val NETWORK = "network"
}

/**
 * The one object the guest is handed, from which every other service comes.
 *
 * A vendor rather than a long parameter list on `start`: adding a service is adding a method here,
 * which leaves the entry-point signature alone. Each accessor is called once, at start, and the
 * result cached for the composition's lifetime -- every call allocates a fresh service proxy.
 */
interface DogwoodServices : ZiplineService {
  /**
   * Which services this host offers, by the names in [ServiceNames].
   *
   * Redundant with the accessors returning null, and deliberately so: a guest can log what it is
   * missing in one line at startup rather than discovering it feature by feature.
   */
  fun available(): Set<String>

  fun log(): DogwoodLog?

  fun clock(): DogwoodClock?

  fun analytics(): DogwoodAnalytics?

  fun featureFlags(): DogwoodFeatureFlags?

  fun network(): DogwoodNetwork?
}

@Serializable
enum class LogLevel { Debug, Info, Warn, Error }

/**
 * Guest logging, routed to whatever the host uses.
 *
 * The guest cannot write to Logcat, to the console, or to a file. Without this, a guest's only
 * diagnostic channel is `handleUncaughtException`, which requires the guest to be crashing.
 */
interface DogwoodLog : ZiplineService {
  fun log(level: LogLevel, tag: String, message: String)
}

/**
 * The host's clock.
 *
 * QuickJS provides `Date.now()`, so this is not about *reading* a time. It is about reading the
 * time the host agrees with -- a device whose clock is wrong, a host that pins time in tests, a
 * server-corrected time -- and about the time zone, which the guest genuinely cannot obtain,
 * because the pinned QuickJS ships no ECMA-402 `Intl`.
 */
interface DogwoodClock : ZiplineService {
  fun nowEpochMillis(): Long

  /** An Internet Assigned Numbers Authority (IANA) identifier, such as `Asia/Tokyo`. */
  fun timeZoneId(): String
}

/**
 * Feature flags, as a snapshot taken when the experience starts.
 *
 * A snapshot rather than a live feed, and the limit is worth stating plainly: a flag flipped while
 * a screen is open does not reach that screen. Making flags live means making them a pushed value
 * with the same dedupe rules as `HostEnvironment`, which is a design, not an addition.
 */
interface DogwoodFeatureFlags : ZiplineService {
  fun snapshot(): Map<String, String>
}

interface DogwoodAnalytics : ZiplineService {
  fun track(name: String, properties: Map<String, String>)
}

/**
 * The guest's only route off the device.
 *
 * `suspend`, because it must be: the guest is single-threaded, and a blocking fetch would stop
 * composition, the frame clock, and every pending event until the network answered.
 *
 * **The host is the policy point, and must behave like one.** This payload was downloaded and can
 * be replaced over the air; it must not be able to reach an arbitrary host with the application's
 * ambient credentials. An implementation is expected to enforce an allow rule and to default to
 * refusing, which is why [HttpResponse.failure] exists as an ordinary outcome rather than an
 * exception.
 */
interface DogwoodNetwork : ZiplineService {
  suspend fun fetch(request: HttpRequest): HttpResponse
}

@Serializable
data class HttpRequest(
  val url: String,
  val method: String = "GET",
  val headers: Map<String, String> = emptyMap(),
  val body: String? = null,
)

/**
 * @param failure set when the request did not complete, or was refused by the host's policy.
 *   [code] is then 0. A refused request is not an exception, because a guest must be able to
 *   handle "this client will not let me do that" as an ordinary branch.
 */
@Serializable
data class HttpResponse(
  val code: Int,
  val headers: Map<String, String> = emptyMap(),
  val body: String = "",
  val failure: String? = null,
) {
  val isSuccessful: Boolean get() = failure == null && code in 200..299
}

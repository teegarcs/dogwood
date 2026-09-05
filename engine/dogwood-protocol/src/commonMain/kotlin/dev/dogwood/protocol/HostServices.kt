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
 * That property is real but it is **not** delivered by the null itself. A null service cannot
 * cross the Zipline boundary; the accessor throws instead of answering. What makes absence normal
 * is [DogwoodServices.available], which a guest consults before calling anything. Read the note
 * there before adding a service to this file.
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
import kotlinx.serialization.json.JsonObject

// `SERVICES_SEGMENT` and `SERVICES_VERSION` moved to `dogwood-wire` (`ServiceSurface.kt`), same
// package. They are two integers describing a revision, and living here made them unreachable from
// any host that cannot link Zipline -- which `Bindings.kt` needs in common code that now compiles
// for WebAssembly too. See Layer 5 ADR-041.

/**
 * The revision that introduced [DogwoodNavigation].
 *
 * A guest must not call [DogwoodServices.navigation] on a host older than this. The file header
 * explains why in general; this is the first case where it bites in practice, so it is named as a
 * constant rather than left as a number in a comment.
 */
const val NAVIGATION_MIN_VERSION = 2

/** Names reported by [DogwoodServices.available], so a guest can branch without calling. */
object ServiceNames {
  const val LOG = "log"
  const val CLOCK = "clock"
  const val ANALYTICS = "analytics"
  const val FEATURE_FLAGS = "featureFlags"
  const val NETWORK = "network"
  const val NAVIGATION = "navigation"
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
   * **Ask this before calling any accessor below.** An earlier version of this file called it a
   * convenience, redundant with the accessors returning null. It is not: **Zipline cannot carry a
   * null service**, so an accessor for a service the host did not wire throws at the boundary and
   * takes the whole experience down at `start` rather than answering null.
   *
   * The mechanism, because it is invisible at the call site and the compiler says nothing.
   * Zipline's plugin chooses a serializer per parameter and return type in
   * [`BridgedInterface.serializerExpression`](https://github.com/cashapp/zipline/blob/1.27.0/zipline-kotlin-plugin/src/main/kotlin/app/cash/zipline/kotlin/BridgedInterface.kt#L209-L223).
   * The service branch is tested first, with `IrType.isSubtypeOfClass`, which compares classifiers
   * and **ignores nullability** -- so `DogwoodLog?` takes it and receives the plain non-null
   * adapter. The nullable wrapper applied to contextual, `Flow` and generic types is never reached
   * for a service. The value then meets `checkNotNullParameter` in
   * [`ZiplineServiceAdapter.serialize`](https://github.com/cashapp/zipline/blob/1.27.0/zipline/src/commonMain/kotlin/app/cash/zipline/internal/bridge/ZiplineServiceAdapter.kt#L51-L53).
   *
   * Nothing warns. It compiles clean, and `api/zipline-api.toml` records these members **without**
   * their question marks -- which is the honest rendering of what Zipline actually does with them.
   * It is not fixed in a later release: 1.27.0 is current and the plugin file is unchanged on
   * trunk.
   *
   * The nullable return types below are therefore about what a *host* may hold, not about what a
   * guest may safely ask for. `HostServices.resolve` on the guest side gates every accessor on this
   * set, and any other caller must do the same.
   */
  fun available(): Set<String>

  /*
   * Every accessor below: **do not call unless [available] names it.** See the note there.
   * A `DogwoodServiceHost` returns null for a service it was not given, and that null cannot
   * cross.
   */

  fun log(): DogwoodLog?

  fun clock(): DogwoodClock?

  fun analytics(): DogwoodAnalytics?

  fun featureFlags(): DogwoodFeatureFlags?

  fun network(): DogwoodNetwork?

  /** Added in service surface version [NAVIGATION_MIN_VERSION]. Do not call on an older host. */
  fun navigation(): DogwoodNavigation?
}

/**
 * The guest's way of asking to go somewhere it cannot go itself.
 *
 * Without this, a guest experience is a dead end: it can render, fetch and record, but it cannot
 * move the user to another screen, and in a product assembled from several experiences that makes
 * every experience an island. It is the counterpart of entry points -- the host decides which
 * experience runs, and this is how a guest asks it to decide again.
 *
 * **The host interprets routes, and the guest learns nothing about the outcome.** A route may
 * become another Dogwood experience, a native screen, a browser, or nothing at all; that is app
 * chrome, exactly like the tab bar, and it belongs to the side that owns the back stack. A guest
 * told which of those happened would start depending on it.
 *
 * **Routes are strings**, for the same reason entry points are: a deep link is a string the host
 * already holds, and forcing it through a generated enumeration would mean a client build for
 * every new destination.
 *
 * Fire-and-forget rather than suspending. Navigation is initiated from an event handler, where a
 * suspending call would mean a guest coroutine outliving the screen that started it, and the
 * answer -- "the host went somewhere" -- is not one a guest should branch on. What a guest may
 * legitimately want to know is whether a control is worth showing at all, and [routes] answers
 * that **before** anything is drawn.
 */
interface DogwoodNavigation : ZiplineService {
  /**
   * Routes this host declares it handles, so a guest can hide a control it cannot use.
   *
   * **Advisory, and possibly empty.** A host that resolves routes dynamically -- from a deep-link
   * table, a remote configuration, a back stack that changes -- cannot enumerate them, and returns
   * an empty set. An empty set therefore means "this host does not enumerate", never "this host
   * handles nothing"; a guest must treat it as permission to try rather than as a refusal.
   */
  fun routes(): Set<String>

  /**
   * Asks the host to go to [route], carrying [params] as the destination's launch parameters.
   *
   * An unknown route is not an error. The host reports it as skew and stays where it is, on the
   * same rule as an unknown widget tag: a payload built against a newer client must degrade on an
   * older one rather than break it.
   *
   * **[params] are launch parameters, and launch parameters are read when an experience starts.**
   * A host that keeps experiences warm will route to a destination that is already running and did
   * not restart, so it never reads them. Treat them as "what to open this with if it opens", not
   * as a message: a guest that needs to tell a *running* experience something needs a pushed value
   * with its own dedupe rules, which this is not.
   */
  fun navigate(route: String, params: JsonObject)
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

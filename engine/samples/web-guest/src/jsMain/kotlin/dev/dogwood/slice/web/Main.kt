/*
 * Project Dogwood -- the real guest, in a Web Worker.
 *
 * `plans/production-readiness.md` called this the largest hole in the alignment story, and it was:
 * `web-slice`'s guest is a hundred lines of hand-written JavaScript. That was the right thing to
 * build — a hand-written guest proves the protocol is an interface rather than an artefact of
 * having Kotlin on both ends — and it left the architecture's central claim undemonstrated on the
 * web, because **no Kotlin/Compose guest had ever run in a Worker**.
 *
 * This is that guest, and the interesting thing about it is how little there is. `DogwoodGuest`,
 * `DogwoodComposition`, the applier, the recorder, every screen and every generated stub are the
 * same code the mobile payload runs. What differs is the twenty lines below that carry a batch
 * from `sendChanges` to `postMessage` instead of to a Zipline service.
 *
 * **The screens do not know which.** They are in `samples/slice-screens`, they name no transport,
 * and they are compiled here unchanged.
 *
 * ## What a Worker gives, and what it does not
 *
 * There is no `document`, no `window`, and no access to the host page or its storage, which is the
 * isolation half of [ADR-032](../../../../../../../../../adrs/layer-5/ADR-032-the-web-profile.md).
 * What a Worker does **not** give is origin isolation: this script shares the page's origin. The
 * Content Security Policy is what makes the network allow-list enforcement rather than advice, and
 * the page sets it.
 */
package dev.dogwood.slice.web

import dev.dogwood.compose.DogwoodGuest
import dev.dogwood.protocol.DogwoodAnalytics
import dev.dogwood.protocol.DogwoodClock
import dev.dogwood.protocol.DogwoodFeatureFlags
import dev.dogwood.protocol.DogwoodHost
import dev.dogwood.protocol.DogwoodLog
import dev.dogwood.protocol.LogLevel
import dev.dogwood.protocol.DogwoodNavigation
import dev.dogwood.protocol.DogwoodNetwork
import dev.dogwood.protocol.DogwoodServices
import dev.dogwood.protocol.DogwoodJson
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.StateSnapshot
import dev.dogwood.protocol.WidgetTag
import dev.dogwood.slice.AboutScreen
import dev.dogwood.slice.AppShell
import dev.dogwood.slice.ExploreScreen
import dev.dogwood.slice.FeedScreen
import dev.dogwood.slice.CrashOnLaunchScreen
import dev.dogwood.slice.CrashScreen
import dev.dogwood.slice.exploreParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.coroutines.await
import kotlin.js.Promise
import dev.dogwood.protocol.WebStartPayload
import dev.dogwood.protocol.WebNavigationRequest
import dev.dogwood.protocol.WebAnalyticsEvent
import dev.dogwood.protocol.HttpResponse
import dev.dogwood.protocol.HttpRequest

/*
 * The envelope, mirrored from `dogwood-web/WorkerProtocol.kt`.
 *
 * Mirrored rather than shared, and that is a real constraint rather than an oversight: the host is
 * Kotlin/WebAssembly and this is Kotlin/JavaScript, and they do not link. The host checks the
 * revision this guest announces before exchanging anything else, so a mirror that has drifted is
 * refused rather than guessed at.
 */
private const val REVISION = 1

private const val READY = "ready"
private const val CHANGES = "changes"
private const val REQUEST_FRAME = "requestFrame"
private const val RESULT = "result"
private const val ERROR = "error"
private const val FRAME = "frame"
private const val SEND_EVENT = "event"
private const val UPDATE_CONFIGURATION = "configuration"
private const val SNAPSHOT_STATE = "snapshotState"
private const val START = "start"
private const val ANALYTICS = "analytics"
private const val NAVIGATE = "navigate"

private val json = Json { ignoreUnknownKeys = true }

private fun post(t: String, c: Int, p: String) {
  js("self.postMessage({ t: t, c: c, p: p })")
}

/**
 * Correlation identifiers this guest allocates: **even numbers only.**
 *
 * The host allocates odd ones. Two counters over one number space would collide the moment both
 * sides had a request outstanding, and the collision would not fail — it would deliver one side's
 * answer to the other side's caller.
 */
private var nextCorrelation = 2

/**
 * The guest's egress, which on mobile is a Zipline service and here is `postMessage`.
 *
 * This is the whole of the platform difference, and it is worth seeing how narrow it is: one
 * interface, five methods, and nothing above it knows which implementation it has.
 */
private object WorkerHost : DogwoodHost {
  override fun sendChanges(positionalBatch: String) = post(CHANGES, 0, positionalBatch)

  override fun requestFrame() {
    post(REQUEST_FRAME, nextCorrelation, "")
    nextCorrelation += 2
  }

  // Skew, reported rather than thrown. An event for a widget or a node this guest no longer has is
  // the ordinary consequence of a tree that moved on while a tap was in flight — wider here than on
  // mobile, because the event has a Worker hop to make.
  override fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag) {
    post(ERROR, 0, "unknown event ${tag.value} for widget ${widgetTag.value}")
  }

  override fun onUnknownEventNode(id: Id, tag: EventTag) {
    post(ERROR, 0, "unknown event ${tag.value} for node ${id.value}")
  }

  override fun handleUncaughtException(exception: Throwable) {
    // Reported, never swallowed. A guest that throws into the void is a blank screen with no
    // explanation, which is the failure the whole skew story exists to avoid.
    //
    // **With its stack, not only its message.** The mobile profile carries a source-mapped stack
    // through Zipline; this path carried one sentence, which ADR-059 recorded as the remainder. A
    // Kotlin/JavaScript `Throwable` is backed by a JavaScript `Error`, so `.stack` exists -- what a
    // *production webpack* build leaves in it is a measured fact rather than an assumption, and
    // `tools/upstream-reports/README.md` and ADR-063 record what the measurement found.
    post(ERROR, 0, encodeGuestFailure(exception))
  }

  override fun close() = Unit
}

/**
 * The services this guest is offered.
 *
 * Deliberately thin, and honestly so: the web profile's service surface is
 * [ADR-032](../../../../../../../../../adrs/layer-5/ADR-032-the-web-profile.md)'s remaining work,
 * and pretending otherwise would put a screen on the wire that quietly does nothing. `log` answers
 * locally because a Worker has a console; everything else says it is absent, and every screen is
 * written to degrade when a service is missing rather than to assume one.
 */
/**
 * What the host told this guest about itself, before it composed.
 *
 * Replaced once, by the `start` message, and read from there on. A guest that is never sent one --
 * an older page, or a harness that only sends a configuration -- keeps the empty default and every
 * service below degrades to what it can answer on its own, which is most of them.
 */
private var startPayload = WebStartPayload()

/**
 * The services this guest has, and where each answer actually comes from.
 *
 * The mobile profile carries `DogwoodServiceHost`'s objects across Zipline and the host answers
 * every call. A Worker boundary carries no object references
 * ([ADR-032](../../../../../../../../adrs/layer-5/ADR-032-the-web-profile.md)), so this profile
 * splits the same surface three ways, and the split is by **who can answer**, not by convenience:
 *
 *  | Service | Answered by | Why |
 *  |---|---|---|
 *  | `log` | the Worker | it has a `console` |
 *  | `clock` | the Worker | it has `Date.now()` and `Intl`; the page's clock is the same clock |
 *  | `network` | the Worker | it has `fetch`, and see the warning below |
 *  | `featureFlags` | the host, once, in `start` | only the application knows them |
 *  | `navigation` | the host | it owns the routing, so a request crosses and an answer does not |
 *  | `analytics` | the host | it owns the pipeline |
 *
 * **`network` is not the mobile guarantee and this is the one place that matters.** On Android and
 * iOS `DogwoodNetwork` is implemented by the host, which enforces an allow-list that **refuses
 * everything by default** -- the payload is replaceable over the air without a store review, so an
 * open network service inside it would be an exfiltration channel. Here the guest calls `fetch`
 * itself, inside the page's origin, and what constrains it is the page's Content Security Policy.
 * That is weaker, it is the browser's rather than Dogwood's, and ADR-032 records it as such. A
 * product that wants the mobile guarantee on the web sets a `connect-src` policy on the page.
 *
 * Routing `fetch` through the page would not fix it: the Worker would still have `fetch`, and a
 * payload that wanted to bypass the host would simply not ask.
 */
private object WorkerServices : DogwoodServices {
  override fun available(): Set<String> = buildSet {
    add("log")
    add("clock")
    add("network")
    add("analytics")
    if (startPayload.featureFlags.isNotEmpty()) add("featureFlags")
    add("navigation")
  }

  override fun log(): DogwoodLog? = object : DogwoodLog {
    override fun log(level: LogLevel, tag: String, message: String) =
      console(level.name.lowercase(), "[$tag] $message")
    override fun close() = Unit
  }

  /**
   * The Worker's own clock.
   *
   * `Intl` exists here and does not exist in QuickJS, which is why the mobile guest has to ask its
   * host for a time zone and this one does not. It does **not** follow that a guest may format
   * dates itself: formatting is host-resolved on every platform because the *result* has to look
   * the way the platform's own applications look, and `Intl` in a Worker is not the platform's
   * formatter. This answers what time it is; `TextValue` still decides how it reads.
   */
  override fun clock(): DogwoodClock? = object : DogwoodClock {
    override fun nowEpochMillis(): Long = nowMillis().toLong()
    override fun timeZoneId(): String = resolvedTimeZone()
    override fun close() = Unit
  }

  override fun analytics(): DogwoodAnalytics? = object : DogwoodAnalytics {
    override fun track(name: String, properties: Map<String, String>) {
      post(ANALYTICS, 0, DogwoodJson.encodeToString(WebAnalyticsEvent.serializer(), WebAnalyticsEvent(name, properties)))
    }
    override fun close() = Unit
  }

  override fun featureFlags(): DogwoodFeatureFlags? =
    if (startPayload.featureFlags.isEmpty()) {
      null
    } else {
      object : DogwoodFeatureFlags {
        override fun snapshot(): Map<String, String> = startPayload.featureFlags
        override fun close() = Unit
      }
    }

  override fun network(): DogwoodNetwork? = object : DogwoodNetwork {
    override suspend fun fetch(request: HttpRequest): HttpResponse = workerFetch(request)
    override fun close() = Unit
  }

  override fun navigation(): DogwoodNavigation? = object : DogwoodNavigation {
    override fun routes(): Set<String> = startPayload.routes
    override fun navigate(route: String, params: JsonObject) {
      post(NAVIGATE, 0, DogwoodJson.encodeToString(WebNavigationRequest.serializer(), WebNavigationRequest(route, params)))
    }
    override fun close() = Unit
  }

  override fun close() = Unit
}

private fun nowMillis(): Double = js("Date.now()")

private fun resolvedTimeZone(): String =
  js("(Intl && Intl.DateTimeFormat().resolvedOptions().timeZone) || 'UTC'")

/**
 * One request, as a promise of a JavaScript Object Notation (JSON) document.
 *
 * The whole request lives in JavaScript and comes back as one string rather than as a `Response`
 * object, for a reason worth stating: a `Response` is read asynchronously *again* for its body, and
 * a Kotlin wrapper around that is two suspension points and a second place errors can be dropped.
 * Failing inside the `catch` here means a network failure and a refusal arrive as the same shape as
 * a success, which is what `HttpResponse.failure` already asks for.
 */
private fun jsFetch(url: String, method: String, headersJson: String, body: String?): Promise<JsAny?> =
  js("""
    fetch(url, { method: method, headers: JSON.parse(headersJson), body: body })
      .then(function (r) {
        return r.text().then(function (t) {
          var h = {};
          r.headers.forEach(function (v, k) { h[k] = v; });
          return JSON.stringify({ code: r.status, headers: h, body: t });
        });
      })
      .catch(function (e) {
        return JSON.stringify({ code: 0, headers: {}, body: '', failure: String(e) });
      })
  """)

private suspend fun workerFetch(request: HttpRequest): HttpResponse {
  val headers = json.encodeToString(
    MapSerializer(String.serializer(), String.serializer()),
    request.headers,
  )
  val raw = try {
    jsFetch(request.url, request.method, headers, request.body).await().toString()
  } catch (failure: Throwable) {
    // A rejected promise the `catch` above did not see -- a malformed URL rejects synchronously.
    return HttpResponse(code = 0, failure = failure.message ?: "fetch failed")
  }
  return try {
    json.decodeFromString(HttpResponse.serializer(), raw)
  } catch (failure: Throwable) {
    HttpResponse(code = 0, failure = "the fetch bridge returned something undecodable")
  }
}

private fun console(level: String, message: String) {
  // `debug`, `info`, `warn` and `error` are all methods on a Worker's console; anything else falls
  // back to `log`, so a level this mapping has not met is still visible rather than swallowed.
  js("(console[level] || console.log)('[guest] ' + message)")
}

/**
 * The same entry points the mobile payload offers, from the same screens.
 *
 * If this list and `slice-guest`'s ever disagree, one of the two platforms is running different
 * product code, which is the thing this module exists to make impossible to do by accident.
 */
private val guest = DogwoodGuest(
  "explore" to { params -> ExploreScreen(exploreParams(params)) },
  "about" to { _ -> AboutScreen() },
  "feed" to { _ -> FeedScreen() },
  "app" to { params -> AppShell(exploreParams(params)) },
  // A payload that fails on purpose, so that the two mechanisms built for a bad publish
  // -- a readable crash (ADR-059) and the crash-loop quarantine (ADR-049) -- can be graded
  // against a real failure instead of a simulated one. See `CrashScreen.kt`.
  "crash" to { _ -> CrashScreen() },
  // ...and one that never gets far enough to be mounted, which is what a crash-loop is.
  "crash-launch" to { _ -> CrashOnLaunchScreen() },
)

private var started = false

fun main() {
  // Bound as a Kotlin function reference rather than through a `js("self.onmessage = ...")` string.
  // The string form compiles and then fails at runtime: the name inside it is resolved by the
  // JavaScript engine against the *minified* module scope, where this function is not called what
  // the source called it. The symptom was a guest that announced itself and then never sent a
  // batch, which reads exactly like a composition that produced nothing.
  val worker = js("self")
  worker.onmessage = { event: dynamic -> onMessage(event.data) }
  post(READY, 0, REVISION.toString())
}

/** One inbound message. */
private fun onMessage(message: dynamic) {
  val kind = message?.t as? String ?: return
  val correlation = (message.c as? Int) ?: 0
  val payload = (message.p as? String).orEmpty()
  try {
    when (kind) {
      UPDATE_CONFIGURATION -> {
        val configuration = json.decodeFromString(HostEnvironment.serializer(), payload)
        if (started) {
          guest.updateConfiguration(configuration)
        } else {
          started = true
          // The first composition happens here and not before: a guest cannot lay out without
          // knowing its viewport, which is the one fact it can never work out for itself.
          guest.start(
            host = WorkerHost,
            services = WorkerServices,
            // The host's choice when it made one, and the Worker's own URL when it did not. The
            // fallback is what keeps a newer guest working on a page that never sends `start`.
            entryPoint = startPayload.entryPoint.ifBlank { entryPoint() },
            configuration = configuration,
            launchParams = startPayload.launchParams,
            segmentVersions = startPayload.segmentVersions,
            // Carried across a code update by the host, because a new Worker is a new module with
            // a new composition and nothing survives implicitly. Null on a first load.
            restoredState = startPayload.restoredState,
          )
        }
      }

      // Everything the host knows about this experience, before it composes. Stored rather than
      // acted on: the composition starts on the first configuration, which arrives next.
      START -> startPayload = DogwoodJson.decodeFromString(WebStartPayload.serializer(), payload)

      SEND_EVENT -> guest.sendEvent(json.decodeFromString(Event.serializer(), payload))

      FRAME -> guest.frame(payload.toLongOrNull() ?: 0L)

      SNAPSHOT_STATE ->
        post(RESULT, correlation, json.encodeToString(StateSnapshot.serializer(), guest.snapshotState()))

      else -> post(ERROR, correlation, "guest does not implement message kind '$kind'")
    }
  } catch (failure: Throwable) {
    // The Worker survives a bad message. A guest that died on one would take the screen with it,
    // and the host would see silence rather than a reason.
    post(ERROR, correlation, encodeGuestFailure(failure, "guest failed on '$kind'"))
  }
}

/**
 * A failure as the host receives it: a message, and the stack if the build kept one.
 *
 * **Two lines of a plain string rather than a structured message, deliberately.** The `ERROR`
 * envelope's payload is a string on every path, and widening it to carry a typed failure would mean
 * an envelope revision -- which every guest and host in the fleet then has to agree on -- for a
 * diagnostic. The host splits on the first blank line; anything after it is a stack, and a guest
 * that sends no stack is indistinguishable from one built before this existed, which is the point.
 *
 * A Kotlin/JavaScript `Throwable` is backed by a JavaScript `Error`, so `.stack` is present at run
 * time. Whether it is *legible* after a production webpack build is a separate question and a
 * measured one; see ADR-063.
 */
private fun encodeGuestFailure(failure: Throwable, prefix: String = "uncaught in guest"): String {
  val message = failure.message ?: failure.toString()
  val stack = stackOf(failure)
  return if (stack.isNullOrBlank()) "$prefix: $message" else "$prefix: $message\n\n$stack"
}

/** Reads `.stack` off the underlying JavaScript `Error`, or null when there is not one. */
private fun stackOf(failure: Throwable): String? =
  js("(failure && failure.stack) ? String(failure.stack) : null") as? String

/** Which screen to compose. Read from the Worker's own URL, so one script serves all four. */
private fun entryPoint(): String {
  val fromUrl = js("(new URL(self.location.href)).searchParams.get('entry')") as? String
  return fromUrl ?: "about"
}

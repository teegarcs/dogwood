/*
 * Project Dogwood -- the guest's side of the Web Worker boundary, as a library.
 *
 * On mobile a payload's egress is a Zipline service and Zipline supplies the transport. On the web
 * the guest runs in a Worker and the transport is `postMessage`, and until 2026-09-13 the code that
 * carried a batch from `sendChanges` to `postMessage` -- and answered `start`, `configuration`,
 * `event`, `frame` and `snapshotState` -- lived in one sample file that a product would have had to
 * copy. Four hundred lines of transport is not something a product should own, so it lives here,
 * and a product's Worker entry point is the entry-point list plus one call to [runInWorker].
 *
 * `DogwoodGuest`, `DogwoodComposition`, the applier, the recorder, every screen and every generated
 * stub are the same code the mobile payload runs. This file is the whole of the platform difference:
 * one `DogwoodHost` whose five methods post messages, one `DogwoodServices` that answers what a
 * Worker can answer on its own, and a dispatcher over the inbound envelope.
 *
 * ## What a Worker gives, and what it does not
 *
 * There is no `document`, no `window`, and no access to the host page or its storage, which is the
 * isolation half of [ADR-032](../../../../../../../adrs/layer-5/ADR-032-the-web-profile.md). What a
 * Worker does **not** give is origin isolation: the script shares the page's origin. The Content
 * Security Policy is what makes the network allow-list enforcement rather than advice, and the page
 * sets it.
 *
 * ## The envelope is a mirror
 *
 * The message kinds below are mirrored from `dogwood-web/WorkerProtocol.kt`, and mirrored rather
 * than shared because they cannot be: the host is Kotlin/WebAssembly and this is Kotlin/JavaScript,
 * and the two do not link. The handshake defends the mirror -- the guest announces `ready` with
 * [WORKER_ENVELOPE_REVISION], and a host that disagrees refuses rather than exchanging messages one
 * side is guessing at. **Bump both copies together.**
 */
package dev.dogwood.compose

import dev.dogwood.protocol.DogwoodAnalytics
import dev.dogwood.protocol.DogwoodClock
import dev.dogwood.protocol.DogwoodFeatureFlags
import dev.dogwood.protocol.DogwoodHost
import dev.dogwood.protocol.DogwoodJson
import dev.dogwood.protocol.DogwoodLog
import dev.dogwood.protocol.DogwoodNavigation
import dev.dogwood.protocol.DogwoodNetwork
import dev.dogwood.protocol.DogwoodServices
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.HttpRequest
import dev.dogwood.protocol.HttpResponse
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.LogLevel
import dev.dogwood.protocol.StateSnapshot
import dev.dogwood.protocol.WebAnalyticsEvent
import dev.dogwood.protocol.WebNavigationRequest
import dev.dogwood.protocol.WebStartPayload
import dev.dogwood.protocol.WidgetTag
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The envelope revision this guest was written against. Mirrors `WorkerMessages.REVISION`. */
const val WORKER_ENVELOPE_REVISION = 1

/** The message kinds, mirrored from `dogwood-web/WorkerProtocol.kt`. See the file comment. */
object WorkerEnvelope {
  const val READY = "ready"
  const val CHANGES = "changes"
  const val REQUEST_FRAME = "requestFrame"
  const val RESULT = "result"
  const val ERROR = "error"
  const val FRAME = "frame"
  const val SEND_EVENT = "event"
  const val UPDATE_CONFIGURATION = "configuration"
  const val SNAPSHOT_STATE = "snapshotState"
  const val START = "start"
  const val ANALYTICS = "analytics"
  const val NAVIGATE = "navigate"
}

/**
 * What the guest can send the page that is not a batch: the two services only the host can answer.
 *
 * The analytics pipeline and the router belong to the application, so a call to either *crosses*
 * and an answer does not. Everything else a Worker answers itself -- see [defaultWorkerServices].
 */
interface WorkerEgress {
  fun analytics(event: WebAnalyticsEvent)
  fun navigate(request: WebNavigationRequest)
}

/**
 * Runs [guest] inside the current Web Worker.
 *
 * Installs `onmessage`, announces `ready` with the envelope revision, and answers the host's
 * envelope from then on. The first composition happens on the first `configuration` message, not
 * before: a guest cannot lay out without knowing its viewport, which is the one fact it can never
 * work out for itself.
 *
 * @param services the services this guest is offered, built once the host's `start` payload has
 *   arrived. The default is what a Worker can answer on its own plus the two calls that cross;
 *   a product with its own answers passes its own factory.
 */
fun runInWorker(
  guest: DogwoodGuest,
  services: (WebStartPayload, WorkerEgress) -> DogwoodServices = ::defaultWorkerServices,
) {
  val bridge = WorkerGuestBridge(
    guest = guest,
    services = services,
    post = ::postToPage,
    // The Worker's own URL is the fallback for a page that never sends `start` -- an older page,
    // or a harness that only sends a configuration. It keeps a newer guest working on one.
    fallbackEntryPoint = ::entryPointFromWorkerUrl,
  )
  // Bound as a Kotlin function reference rather than through a `js("self.onmessage = ...")`
  // string. The string form compiles and then fails at runtime: the name inside it is resolved by
  // the JavaScript engine against the *minified* module scope, where this function is not called
  // what the source called it. The symptom was a guest that announced itself and then never sent a
  // batch, which reads exactly like a composition that produced nothing.
  val worker = js("self")
  worker.onmessage = { event: dynamic -> bridge.onMessage(event.data) }
  bridge.announce()
}

/**
 * The inbound half of the envelope, separated from the Worker so it can be exercised on Node.
 *
 * [post] is the only way anything leaves; the Worker supplies `self.postMessage`, a test supplies a
 * list. Nothing here reads `self`.
 */
class WorkerGuestBridge(
  private val guest: DogwoodGuest,
  private val services: (WebStartPayload, WorkerEgress) -> DogwoodServices,
  private val post: (kind: String, correlation: Int, payload: String) -> Unit,
  private val fallbackEntryPoint: () -> String = { "" },
) {
  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Correlation identifiers this guest allocates: **even numbers only.**
   *
   * The host allocates odd ones. Two counters over one number space would collide the moment both
   * sides had a request outstanding, and the collision would not fail -- it would deliver one
   * side's answer to the other side's caller.
   */
  private var nextCorrelation = 2

  /**
   * What the host told this guest about itself, before it composed.
   *
   * Replaced once, by the `start` message, and read from there on. A guest that is never sent one
   * keeps the empty default and every default service degrades to what it can answer on its own,
   * which is most of them.
   */
  private var startPayload = WebStartPayload()
  private var started = false

  private val egress = object : WorkerEgress {
    override fun analytics(event: WebAnalyticsEvent) =
      post(WorkerEnvelope.ANALYTICS, 0, DogwoodJson.encodeToString(WebAnalyticsEvent.serializer(), event))

    override fun navigate(request: WebNavigationRequest) =
      post(WorkerEnvelope.NAVIGATE, 0, DogwoodJson.encodeToString(WebNavigationRequest.serializer(), request))
  }

  /**
   * The guest's egress, which on mobile is a Zipline service and here is `postMessage`.
   *
   * This is the whole of the platform difference, and it is worth seeing how narrow it is: one
   * interface, five methods, and nothing above it knows which implementation it has.
   */
  private val host = object : DogwoodHost {
    override fun sendChanges(positionalBatch: String) = post(WorkerEnvelope.CHANGES, 0, positionalBatch)

    override fun requestFrame() {
      post(WorkerEnvelope.REQUEST_FRAME, nextCorrelation, "")
      nextCorrelation += 2
    }

    // Skew, reported rather than thrown. An event for a widget or a node this guest no longer has
    // is the ordinary consequence of a tree that moved on while a tap was in flight -- wider here
    // than on mobile, because the event has a Worker hop to make.
    override fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag) =
      post(WorkerEnvelope.ERROR, 0, "unknown event ${tag.value} for widget ${widgetTag.value}")

    override fun onUnknownEventNode(id: Id, tag: EventTag) =
      post(WorkerEnvelope.ERROR, 0, "unknown event ${tag.value} for node ${id.value}")

    override fun handleUncaughtException(exception: Throwable) {
      // Reported, never swallowed. A guest that throws into the void is a blank screen with no
      // explanation, which is the failure the whole skew story exists to avoid. With its stack,
      // not only its message: see [encodeGuestFailure] and ADR-063.
      post(WorkerEnvelope.ERROR, 0, encodeGuestFailure(exception))
    }

    override fun close() = Unit
  }

  /** Sent exactly once, before anything else; the host waits for it before sending the environment. */
  fun announce() = post(WorkerEnvelope.READY, 0, WORKER_ENVELOPE_REVISION.toString())

  /** One inbound envelope, as the Worker's `onmessage` receives it. */
  fun onMessage(message: dynamic) {
    val kind = message?.t as? String ?: return
    val correlation = (message.c as? Int) ?: 0
    val payload = (message.p as? String).orEmpty()
    handle(kind, correlation, payload)
  }

  /** One inbound message, already unpacked. */
  fun handle(kind: String, correlation: Int, payload: String) {
    try {
      when (kind) {
        WorkerEnvelope.UPDATE_CONFIGURATION -> {
          val configuration = json.decodeFromString(HostEnvironment.serializer(), payload)
          if (started) {
            guest.updateConfiguration(configuration)
          } else {
            started = true
            guest.start(
              host = host,
              services = services(startPayload, egress),
              // The host's choice when it made one, and the fallback when it did not.
              entryPoint = startPayload.entryPoint.ifBlank(fallbackEntryPoint),
              configuration = configuration,
              launchParams = startPayload.launchParams,
              segmentVersions = startPayload.segmentVersions,
              // Carried across a code update by the host, because a new Worker is a new module
              // with a new composition and nothing survives implicitly. Null on a first load.
              restoredState = startPayload.restoredState,
            )
          }
        }

        // Everything the host knows about this experience, before it composes. Stored rather than
        // acted on: the composition starts on the first configuration, which arrives next.
        WorkerEnvelope.START ->
          startPayload = DogwoodJson.decodeFromString(WebStartPayload.serializer(), payload)

        WorkerEnvelope.SEND_EVENT -> guest.sendEvent(json.decodeFromString(Event.serializer(), payload))

        WorkerEnvelope.FRAME -> guest.frame(payload.toLongOrNull() ?: 0L)

        WorkerEnvelope.SNAPSHOT_STATE -> post(
          WorkerEnvelope.RESULT,
          correlation,
          json.encodeToString(StateSnapshot.serializer(), guest.snapshotState()),
        )

        else -> post(WorkerEnvelope.ERROR, correlation, "guest does not implement message kind '$kind'")
      }
    } catch (failure: Throwable) {
      // The Worker survives a bad message. A guest that died on one would take the screen with it,
      // and the host would see silence rather than a reason.
      post(WorkerEnvelope.ERROR, correlation, encodeGuestFailure(failure, "guest failed on '$kind'"))
    }
  }
}

/**
 * The services a Worker can answer on its own, and where each answer actually comes from.
 *
 * The mobile profile carries `DogwoodServiceHost`'s objects across Zipline and the host answers
 * every call. A Worker boundary carries no object references
 * ([ADR-032](../../../../../../../adrs/layer-5/ADR-032-the-web-profile.md)), so this profile splits
 * the same surface three ways, and the split is by **who can answer**, not by convenience:
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
fun defaultWorkerServices(start: WebStartPayload, egress: WorkerEgress): DogwoodServices =
  object : DogwoodServices {
    override fun available(): Set<String> = buildSet {
      add("log")
      add("clock")
      add("network")
      add("analytics")
      if (start.featureFlags.isNotEmpty()) add("featureFlags")
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
      override fun track(name: String, properties: Map<String, String>) =
        egress.analytics(WebAnalyticsEvent(name, properties))

      override fun close() = Unit
    }

    override fun featureFlags(): DogwoodFeatureFlags? =
      if (start.featureFlags.isEmpty()) {
        null
      } else {
        object : DogwoodFeatureFlags {
          override fun snapshot(): Map<String, String> = start.featureFlags
          override fun close() = Unit
        }
      }

    override fun network(): DogwoodNetwork? = object : DogwoodNetwork {
      override suspend fun fetch(request: HttpRequest): HttpResponse = workerFetch(request)
      override fun close() = Unit
    }

    override fun navigation(): DogwoodNavigation? = object : DogwoodNavigation {
      override fun routes(): Set<String> = start.routes
      override fun navigate(route: String, params: JsonObject) =
        egress.navigate(WebNavigationRequest(route, params))

      override fun close() = Unit
    }

    override fun close() = Unit
  }

/**
 * A failure as the host receives it: a message, and the stack if the build kept one.
 *
 * **Two lines of a plain string rather than a structured message, deliberately.** The `error`
 * envelope's payload is a string on every path, and widening it to carry a typed failure would mean
 * an envelope revision -- which every guest and host in the fleet then has to agree on -- for a
 * diagnostic. The host splits on the first blank line; anything after it is a stack, and a guest
 * that sends no stack is indistinguishable from one built before this existed, which is the point.
 *
 * A Kotlin/JavaScript `Throwable` is backed by a JavaScript `Error`, so `.stack` is present at run
 * time. Whether it is *legible* after a production webpack build is a separate question and a
 * measured one; see ADR-063.
 */
internal fun encodeGuestFailure(failure: Throwable, prefix: String = "uncaught in guest"): String {
  val message = failure.message ?: failure.toString()
  val stack = stackOf(failure)
  return if (stack.isNullOrBlank()) "$prefix: $message" else "$prefix: $message\n\n$stack"
}

/** Reads `.stack` off the underlying JavaScript `Error`, or null when there is not one. */
private fun stackOf(failure: Throwable): String? =
  js("(failure && failure.stack) ? String(failure.stack) : null") as? String

private fun postToPage(t: String, c: Int, p: String) {
  js("self.postMessage({ t: t, c: c, p: p })")
}

/** Which screen to compose when the page did not say. Read from the Worker's own URL. */
private fun entryPointFromWorkerUrl(): String {
  val fromUrl = js("(new URL(self.location.href)).searchParams.get('entry')") as? String
  return fromUrl ?: "about"
}

private fun nowMillis(): Double = js("Date.now()")

private fun resolvedTimeZone(): String =
  js("(Intl && Intl.DateTimeFormat().resolvedOptions().timeZone) || 'UTC'")

private fun console(level: String, message: String) {
  // `debug`, `info`, `warn` and `error` are all methods on a Worker's console; anything else falls
  // back to `log`, so a level this mapping has not met is still visible rather than swallowed.
  js("(console[level] || console.log)('[guest] ' + message)")
}

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
  js(
    """
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
  """,
  )

private val fetchJson = Json { ignoreUnknownKeys = true }

private suspend fun workerFetch(request: HttpRequest): HttpResponse {
  val headers = fetchJson.encodeToString(
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
    fetchJson.decodeFromString(HttpResponse.serializer(), raw)
  } catch (failure: Throwable) {
    HttpResponse(code = 0, failure = "the fetch bridge returned something undecodable")
  }
}

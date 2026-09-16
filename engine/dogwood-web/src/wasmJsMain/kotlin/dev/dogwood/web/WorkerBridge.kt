/*
 * Project Dogwood -- the host half of the `postMessage` bridge.
 *
 * This is the adapter [Layer 5 ADR-032](../../../../../../../adrs/layer-5/ADR-032-the-web-profile.md)
 * §5 prefers over asynchronous variants of `DogwoodHost` and `DogwoodGuestUi`: it presents the
 * same four host-to-guest calls and receives the same two guest-to-host calls, over a Worker.
 *
 * Two things it does NOT do, both deliberate:
 *
 *   - **It does not create the Worker.** [WebDelivery] does, and only after the dictionary check
 *     has passed. A bridge that could construct its own Worker would make it possible to skip the
 *     check by calling the wrong constructor, and ADR-032 is explicit that the check runs before
 *     any guest code executes.
 *   - **It does not decode batches.** It hands the payload string on. Decoding is 55.3% of a frame
 *     on this platform and belongs somewhere it can be measured and replaced; see
 *     [FastPositionalDecoder].
 */
package dev.dogwood.web

import dev.dogwood.protocol.DogwoodJson
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.WebAnalyticsEvent
import dev.dogwood.protocol.WebNavigationRequest
import dev.dogwood.protocol.WebStartPayload
import dev.dogwood.protocol.StateSnapshot
import kotlinx.serialization.json.Json
import org.w3c.dom.MessageEvent
import org.w3c.dom.Worker
import kotlinx.browser.window

/** Builds the three-field envelope object that actually crosses. */
private fun envelope(t: String, c: Int, p: String): JsAny = js("({ t: t, c: c, p: p })")

private fun envelopeKind(m: JsAny): String = js("String(m.t)")

private fun envelopeCorrelation(m: JsAny): Int = js("(m.c | 0)")

private fun envelopePayload(m: JsAny): String =
  js("(m.p === undefined || m.p === null) ? '' : String(m.p)")

/** Whether the message is even shaped like one of ours, before anything reads a field off it. */
private fun looksLikeEnvelope(m: JsAny?): Boolean =
  js("m !== null && m !== undefined && typeof m === 'object' && typeof m.t === 'string'")

private val BridgeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * What the host wants told about a guest.
 *
 * An interface rather than a set of lambdas because all four arrive on the same channel and a
 * caller that implemented three of them would otherwise silently drop the fourth.
 */
interface WorkerBridgeListener {
  /** The guest announced itself with a compatible envelope revision. */
  fun onReady()

  /** One composition pass arrived. [batch] is the positional payload, undecoded. */
  fun onChanges(batch: String)

  /**
   * The guest sent an analytics event.
   *
   * On the interface rather than as a lambda for the reason the interface's own comment gives: all
   * of these arrive on one channel, and a listener that implemented five of six would silently
   * drop the sixth.
   */
  fun onAnalytics(event: WebAnalyticsEvent)

  /** The guest asked the host to navigate. The host may decline; the guest cannot tell. */
  fun onNavigate(request: WebNavigationRequest)

  /**
   * The guest asked for a frame callback and is waiting on [correlation].
   *
   * The host answers by calling [WorkerBridge.deliverFrame] whenever its own display next
   * produces a frame. Nothing here schedules that: the Worker cannot see the display, which is
   * the entire reason the call exists.
   */
  fun onFrameRequested(correlation: Int)

  /**
   * The guest failed. [correlation] is the request it failed, or `0` for an unattached failure.
   *
   * Reported rather than thrown. A guest is over-the-air code and its failures are operational
   * events on the host, not exceptions in host control flow.
   */
  fun onGuestError(correlation: Int, message: String)

  /**
   * A guest failure with its stack, when the payload sent one.
   *
   * Defaulted to dropping the stack and calling [onGuestError], so that a host written before this
   * existed keeps compiling and keeps working. The default is what makes the stack an addition
   * rather than a migration -- which matters because the stack is a diagnostic, and a diagnostic
   * that breaks a build is a diagnostic teams turn off.
   *
   * [stack] is whatever the guest's runtime produced. It is **not** guaranteed to be legible: on a
   * production build it is minified, and Dogwood does not symbolicate it in the host. See ADR-063
   * for what the measurement found and for where the source map goes.
   */
  fun onGuestFailure(correlation: Int, message: String, stack: String?) {
    onGuestError(correlation, message)
  }
}

/**
 * The `postMessage` channel to one guest.
 *
 * Not thread-safe and does not need to be: every method here runs on the main thread, which is
 * where Compose Multiplatform draws and where a `Worker`'s `onmessage` is dispatched. That is the
 * web reading of `DogwoodThreads.checkUi()`; the Worker side is the reading of `checkZipline()`.
 */
class WorkerBridge(
  private val worker: Worker,
  private val listener: WorkerBridgeListener,
  /**
   * Called once when the Worker itself fails, as distinct from the guest reporting an error.
   *
   * The host needs this to tear down and report: a Worker whose script never loaded produces no
   * messages at all, so silence is the only symptom and it looks exactly like a guest with
   * nothing to say.
   */
  private val onWorkerFailure: (String) -> Unit = {},
) {
  /**
   * Correlation identifiers the host allocates, for host-initiated requests.
   *
   * Odd numbers only, and the guest allocates even ones. Two independent counters over one number
   * space would collide the moment both sides had a request outstanding, and the collision would
   * not fail -- it would deliver one side's answer to the other side's caller.
   */
  private var nextHostCorrelation = 1

  /** Host-initiated requests awaiting a `result`. */
  private val pending = HashMap<Int, (String) -> Unit>()

  /** Requests that failed, so a caller waiting on one is told rather than left waiting. */
  private val pendingFailures = HashMap<Int, (String) -> Unit>()

  /** Set once the guest has announced a compatible revision. */
  var isReady: Boolean = false
    private set

  init {
    worker.onmessage = { event: MessageEvent -> receive(event.data); Unit }
    /*
     * A Worker can die in ways `onmessage` never mentions.
     *
     * `new Worker(url)` succeeds even when the script 404s or is refused by a Content Security
     * Policy -- the failure arrives as an `error` event, and with nobody listening it arrived
     * nowhere. A deployment that shipped a manifest naming a missing guest reported a clean start
     * and rendered an empty box forever, which is indistinguishable from a guest that simply has
     * nothing to say.
     */
    worker.onerror = { event ->
      failEverything("the guest Worker failed to start or crashed")
      Unit
    }
  }

  /**
   * Fails every outstanding request, and tells the host once.
   *
   * Shared by the error events and by [close] because the caller's problem is identical in all
   * three cases: something it is waiting for is never going to arrive, and waiting forever is the
   * one outcome that leaks an experience rather than reporting one.
   */
  private fun failEverything(reason: String) {
    val failures = pendingFailures.values.toList()
    pending.clear()
    pendingFailures.clear()
    timeouts.values.forEach { window.clearTimeout(it) }
    timeouts.clear()
    for (failure in failures) failure(reason)
    if (!notifiedFailure) {
      notifiedFailure = true
      onWorkerFailure(reason)
      // And the listener, always. The optional callback above is what a host wires for its own
      // recovery; the listener is what puts the reason where the page's report and the drills
      // read. Without this line a Content Security Policy that refused the blob: Worker produced
      // `workerCreated=true` and "the guest never sent a batch" and nothing in between -- found
      // 2026-09-15, when the accessibility drill saw an empty page and every diagnostic said fine.
      listener.onGuestError(0, "the Worker died before it could speak: $reason")
    }
  }

  private var notifiedFailure = false

  /** Timeout handles, so an answered request stops its own alarm. */
  private val timeouts = HashMap<Int, Int>()

  // ---------------------------------------------------------------------------------------------
  // Host to guest.
  // ---------------------------------------------------------------------------------------------

  /**
   * Everything the page knows about this experience, in one message before it composes.
   *
   * Sent before [updateConfiguration], because that is what starts the composition and a guest
   * that composed first would compose without its launch parameters -- which is a screen rendering
   * the wrong thing rather than failing to render.
   */
  fun start(payload: WebStartPayload) {
    post(WorkerMessages.START, 0, DogwoodJson.encodeToString(WebStartPayload.serializer(), payload))
  }

  /** `DogwoodGuestUi.updateConfiguration`, over the wire. */
  fun updateConfiguration(environment: HostEnvironment) {
    post(WorkerMessages.UPDATE_CONFIGURATION, 0, BridgeJson.encodeToString(environment))
  }

  /** `DogwoodGuestUi.sendEvent`, over the wire. [event] is the serialised `Event`. */
  fun sendEvent(event: String) {
    post(WorkerMessages.SEND_EVENT, 0, event)
  }

  /**
   * Answers a [WorkerBridgeListener.onFrameRequested] with the frame time the host just observed.
   *
   * The nanosecond figure is a decimal string because it does not fit the envelope's integer
   * field, and because the guest hands it straight to a `withFrameNanos` continuation that wants a
   * 64-bit value.
   */
  fun deliverFrame(correlation: Int, frameTimeNanos: Long) {
    post(WorkerMessages.FRAME, correlation, frameTimeNanos.toString())
  }

  /**
   * `snapshotState()`, which on this platform cannot return a value.
   *
   * Takes the continuation as a callback rather than suspending, so the bridge itself stays free
   * of a coroutine dependency and a caller may use whichever concurrency it already has. The
   * failure path is separate and mandatory for the same reason
   * [WorkerBridgeListener.onGuestError] exists: an eviction that waits forever on a dead guest
   * leaks the whole experience.
   */
  fun snapshotState(
    timeoutMillis: Int = SNAPSHOT_TIMEOUT_MILLIS,
    onResult: (StateSnapshot) -> Unit,
    onFailure: (String) -> Unit,
  ) {
    val correlation = allocateHostCorrelation()
    pending[correlation] = { payload ->
      val decoded = runCatching { BridgeJson.decodeFromString<StateSnapshot>(payload) }
      // A snapshot that will not decode is a FAILURE, not an empty snapshot. It used to become
      // `StateSnapshot()`, which the eviction path would then persist -- wiping the user's saved
      // state with no signal at all, while a transport failure of the same call was reported.
      // "Could not read the guest" and "the guest has nothing saved" are different facts.
      decoded.fold(
        onSuccess = onResult,
        onFailure = { fail(correlation, "the guest's saved state could not be decoded") },
      )
    }
    pendingFailures[correlation] = onFailure
    // Without this a crashed guest leaves the caller suspended forever. The sample survived only
    // because its own poll loop timed out -- a mitigation living one layer above the problem.
    timeouts[correlation] = window.setTimeout({
      fail(correlation, "the guest did not answer within ${'$'}timeoutMillis ms")
      null
    }, timeoutMillis)
    post(WorkerMessages.SNAPSHOT_STATE, correlation, "")
  }

  /** Fails one outstanding request and forgets it. */
  private fun fail(correlation: Int, reason: String) {
    timeouts.remove(correlation)?.let { window.clearTimeout(it) }
    pending.remove(correlation)
    pendingFailures.remove(correlation)?.invoke(reason)
  }

  /** Stops the guest. Everything still outstanding is failed rather than abandoned. */
  fun close() {
    worker.terminate()
    failEverything("the guest Worker was terminated")
  }

  private fun allocateHostCorrelation(): Int {
    val correlation = nextHostCorrelation
    nextHostCorrelation += 2
    return correlation
  }

  private fun post(kind: String, correlation: Int, payload: String) {
    worker.postMessage(envelope(kind, correlation, payload))
  }

  // ---------------------------------------------------------------------------------------------
  // Guest to host.
  // ---------------------------------------------------------------------------------------------

  private fun receive(data: JsAny?) {
    // A Worker is same-origin and speaks only to us, but the page may also host other message
    // traffic, and a handler that reads `.t` off whatever arrives fails somewhere unhelpful.
    if (!looksLikeEnvelope(data)) return
    val message = data!!
    val kind = envelopeKind(message)
    val correlation = envelopeCorrelation(message)
    val payload = envelopePayload(message)
    when (kind) {
      WorkerMessages.READY -> {
        val revision = payload.toIntOrNull() ?: -1
        if (revision != WorkerMessages.REVISION) {
          listener.onGuestError(
            0,
            "guest speaks envelope revision $revision; this host speaks " +
              "${WorkerMessages.REVISION}",
          )
          return
        }
        isReady = true
        listener.onReady()
      }

      WorkerMessages.CHANGES -> listener.onChanges(payload)

      WorkerMessages.REQUEST_FRAME -> listener.onFrameRequested(correlation)

      // Decoded here rather than handed on as a string, unlike a change batch: these are small,
      // and a listener that had to parse them would be a second place the shape is known.
      WorkerMessages.ANALYTICS -> {
        val event = runCatching {
          DogwoodJson.decodeFromString(WebAnalyticsEvent.serializer(), payload)
        }.getOrNull()
        if (event == null) {
          listener.onGuestError(correlation, "undecodable analytics event: $payload")
        } else {
          listener.onAnalytics(event)
        }
      }

      WorkerMessages.NAVIGATE -> {
        val request = runCatching {
          DogwoodJson.decodeFromString(WebNavigationRequest.serializer(), payload)
        }.getOrNull()
        if (request == null) {
          listener.onGuestError(correlation, "undecodable navigation request: $payload")
        } else {
          listener.onNavigate(request)
        }
      }

      WorkerMessages.RESULT -> {
        pendingFailures.remove(correlation)
        // An unknown correlation is dropped rather than reported. It is the normal shape of a
        // reply that lost a race with `close()`, and reporting it would make an orderly teardown
        // look like a fault.
        pending.remove(correlation)?.invoke(payload)
      }

      WorkerMessages.ERROR -> {
        pending.remove(correlation)
        val failure = pendingFailures.remove(correlation)
        if (failure != null) {
          failure(payload)
        } else {
          // The guest encodes a failure as `message`, a blank line, then the stack. Splitting on
          // the first blank line rather than parsing a structure is what lets a stack ride the
          // existing envelope: a payload that sends no stack produces exactly the string this path
          // always produced, so nothing about the envelope revision changes.
          val separator = payload.indexOf("\n\n")
          if (separator < 0) {
            listener.onGuestFailure(correlation, payload, null)
          } else {
            listener.onGuestFailure(
              correlation,
              payload.substring(0, separator),
              payload.substring(separator + 2).ifBlank { null },
            )
          }
        }
      }

      // A kind this host does not implement is skew, not corruption: the guest may have been
      // built against a newer envelope. It is reported for the same reason an unknown widget tag
      // is -- silence here is how a protocol drifts.
      else -> listener.onGuestError(correlation, "unknown message kind '$kind'")
    }
  }
}

/** How long a correlated request waits before it is failed rather than left outstanding. */
private const val SNAPSHOT_TIMEOUT_MILLIS = 10_000

/*
 * Project Dogwood -- the `postMessage` envelope, and nothing inside it.
 *
 * [Layer 5 ADR-032](../../../../../../../adrs/layer-5/ADR-032-the-web-profile.md) puts the guest in
 * a Web Worker, which makes the boundary asynchronous and stops services crossing as object
 * references. What crosses instead is a message, and this file is the whole of what a message is.
 *
 * **It is deliberately not a second wire format.** The change batch a message carries is the same
 * positional JavaScript Object Notation (JSON) string the mobile profile puts through Zipline's
 * `CallChannel`, byte for byte -- `[sequence, [change, ...]]`, decoded by the grammar in
 * `dev.dogwood.protocol.decodePositional`. This envelope only says *which call* the string
 * belongs to. Where a call returns a value, the envelope also carries a correlation identifier,
 * because a Worker boundary has no return values, only later messages.
 *
 * **Ordering.** ADR-032 assumes a Worker's `postMessage` delivers in order per channel, which the
 * HyperText Markup Language (HTML) specification requires, and states that the existing sequence
 * numbers -- the `q` field of a `ChangeBatch`, and the sequence an `Event` echoes back -- remain
 * the authority. Nothing here renumbers or reorders anything; the envelope carries no sequence of
 * its own, precisely so there is only one thing that could be wrong.
 *
 * **The envelope is a structured-cloneable JavaScript object, not a JSON string.** Wrapping an
 * already-serialised batch inside another JSON document would escape every quote in it and roughly
 * double the payload for no benefit. A three-field object clones cheaply, and the batch travels as
 * one `JsString` inside it.
 *
 * A message is `{ t, c, p }`:
 *   - `t` -- the kind, one of the string constants in [WorkerMessages].
 *   - `c` -- the correlation identifier, or `0` when the message expects no reply.
 *   - `p` -- the payload string. Its meaning is per-kind and documented on each constant.
 *
 * **This file has a twin.** The guest half is plain JavaScript, so these constants are mirrored by
 * hand in the guest's own source. The handshake is what defends the mirror: a guest announces
 * [READY] with the protocol revision it was written against, and a host that disagrees refuses to
 * proceed rather than exchanging messages one side is guessing at.
 */
package dev.dogwood.web

/** The message kinds, and the revision both halves must agree on. */
object WorkerMessages {
  /**
   * The envelope revision.
   *
   * Bumped when a kind changes meaning or a field is added, never when a *batch* grammar changes
   * -- that is [dev.dogwood.protocol.ChangeKind]'s business and has its own compatibility rules.
   */
  const val REVISION = 1

  // -------------------------------------------------------------------------------------------
  // Guest to host.
  // -------------------------------------------------------------------------------------------

  /**
   * The guest has loaded and is ready. `p` is the envelope revision it was compiled against, as
   * a decimal string; `c` is unused.
   *
   * Sent exactly once, and before anything else. The host does not send the environment until it
   * arrives, because a guest that has not finished evaluating its script has no `onmessage`
   * handler installed and would drop it.
   */
  const val READY = "ready"

  /**
   * One composition pass. `p` is the positional batch; `c` is unused.
   *
   * The direct analogue of `DogwoodHost.sendChanges(String)`. Fire and forget: the mobile call
   * returns nothing, so nothing is waiting on it here either.
   */
  const val CHANGES = "changes"

  /**
   * The guest wants a frame callback. `c` is the guest's correlation identifier; `p` is unused.
   *
   * The analogue of `DogwoodHost.requestFrame()`. It is answered with a [FRAME] carrying the same
   * `c`, so a guest that asked twice can tell the answers apart -- which matters because the
   * Worker has no `requestAnimationFrame` tied to the host's display.
   */
  const val REQUEST_FRAME = "requestFrame"

  /**
   * The value half of a host-initiated request. `c` echoes the request's identifier; `p` is the
   * result, encoded per the request kind.
   *
   * There is one of these today, [SNAPSHOT_STATE], and the shape is general because ADR-032 is
   * explicit that every host-to-guest call that returns a value has to become one of these.
   */
  const val RESULT = "result"

  /**
   * The guest failed. `c` echoes a request identifier when the failure belongs to one, `0` when
   * it does not; `p` is a human-readable message.
   *
   * Separate from [RESULT] because a request that never completes and a request that completed
   * badly are different bugs, and a caller that cannot tell them apart hangs on the first.
   */
  const val ERROR = "error"

  // -------------------------------------------------------------------------------------------
  // Host to guest.
  // -------------------------------------------------------------------------------------------

  /**
   * The frame the guest asked for. `c` echoes the [REQUEST_FRAME] identifier; `p` is the frame
   * time in nanoseconds, as a decimal string.
   *
   * Nanoseconds because that is what `withFrameNanos` gives a guest composition, and a decimal
   * string because the value exceeds what a 32-bit integer holds and the envelope's `c` field is
   * the only numeric one.
   */
  const val FRAME = "frame"

  /**
   * One inbound interaction. `p` is the serialised `Event`; `c` is unused.
   *
   * The analogue of `DogwoodGuestUi.sendEvent(String)`. The event carries the sequence number of
   * the last batch the host applied, which is what lets the guest drop an event aimed at a tree
   * it has already replaced -- and that is *more* necessary here than on mobile, because a Worker
   * hop widens the window in which a stale event can be in flight.
   */
  const val SEND_EVENT = "event"

  /**
   * The environment changed. `p` is the serialised [dev.dogwood.protocol.HostEnvironment]; `c` is
   * unused.
   *
   * The analogue of `DogwoodGuestUi.updateConfiguration(String)`. Also the *first* message the
   * host sends after [READY], because a guest cannot compose without knowing its viewport.
   */
  const val UPDATE_CONFIGURATION = "configuration"

  /**
   * Asks the guest for its saveable state. `c` is the host's correlation identifier; `p` is
   * unused. Answered with a [RESULT] carrying a serialised
   * [dev.dogwood.protocol.StateSnapshot].
   *
   * ADR-032 §3 names this as the sharpest cost of the Worker decision: `snapshotState()` is
   * synchronous on mobile and is called from `DogwoodSession` and the shell's eviction path.
   * Across this boundary it cannot be, and pretending otherwise would mean blocking the
   * user-interface thread on a Worker -- which is the exact failure the Worker was chosen to
   * prevent.
   */
  const val SNAPSHOT_STATE = "snapshotState"

  /**
   * Host to guest: everything the page knows about this experience, once, before it composes.
   *
   * The payload is a serialised [WebStartPayload] -- entry point, launch parameters, feature
   * flags, the routes the host handles, and the dictionary versions it implements. One message
   * rather than five accessors, because a Worker boundary carries no object references and five
   * round trips before the first frame would be five round trips before the first frame.
   *
   * **Sent before the first [UPDATE_CONFIGURATION]**, which is what actually starts the
   * composition. A guest that receives no start message must still compose: this is additive, and
   * a page serving a newer guest to an older host is the ordinary shape of skew here.
   */
  const val START = "start"

  /**
   * Guest to host: one analytics event, as a serialised [WebAnalyticsEvent].
   *
   * One-way, with correlation `0`. `DogwoodAnalytics.track` returns nothing on every platform, and
   * a reply the guest would not read is a round trip nobody needs.
   */
  const val ANALYTICS = "analytics"

  /**
   * Guest to host: a navigation request, as a serialised [WebNavigationRequest].
   *
   * One-way for the same reason, and with the same meaning it has on mobile: the guest is *asking*.
   * A host that does not handle the route does nothing, and records it as skew -- a guest cannot
   * tell the difference and must not depend on one.
   */
  const val NAVIGATE = "navigate"
}

/**
 * A decoded envelope.
 *
 * A Kotlin value rather than a `JsAny` walked in place, so that everything above the bridge reads
 * ordinary Kotlin and only [WorkerBridge] touches JavaScript objects at all.
 */
class WorkerMessage(
  val kind: String,
  val correlation: Int,
  val payload: String,
)

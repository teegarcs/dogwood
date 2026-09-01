/*
 * Project Dogwood -- the Layer 4 service boundary.
 *
 * Services are named for the side that IMPLEMENTS them, following specs/layer-4-sandbox.md
 * section 4. This is the real boundary, not measurement scaffolding: the Phase 0 harness's
 * extra methods are gone.
 */
package dev.dogwood.protocol

import app.cash.zipline.ZiplineService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Host-owned facts a guest composition may read, delivered as a flow because every one of
 * them changes at runtime.
 *
 * Locale is present twice over: the resources subsystem needs it, and the pinned QuickJS
 * ships no ECMA-402 `Intl`, so guest-side locale-aware formatting has no built-in primitive.
 */
@Serializable
data class DogwoodConfiguration(
  val density: Float = 1f,
  val fontScale: Float = 1f,
  val darkMode: Boolean = false,
  val layoutDirectionRtl: Boolean = false,
  val viewportWidthDp: Int = 0,
  val viewportHeightDp: Int = 0,
  val safeAreaTopDp: Int = 0,
  val safeAreaBottomDp: Int = 0,
  val locale: String = "en-US",
)

/** Implemented by the host, called by the guest. */
interface DogwoodHost : ZiplineService {
  /**
   * The single egress point: one call per completed composition pass.
   *
   * The payload is the positional encoding of [ADR-007](../../../adrs/layer-4/ADR-007-v1-wire-format-positional-json.md),
   * passed as an already-built string rather than as a `ChangeBatch`, because the guest can
   * produce that string roughly twenty times faster than `kotlinx.serialization` can produce
   * the equivalent. This is the one place in the architecture where hand-rolled encoding beats
   * the generated serializer, and it is measured, not assumed.
   */
  fun sendChanges(positionalBatch: String)

  /** Asks the host to schedule exactly one frame, so an idle experience produces no traffic. */
  fun requestFrame()

  fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag)

  fun onUnknownEventNode(id: Id, tag: EventTag)

  fun handleUncaughtException(exception: Throwable)
}

/**
 * Guest state, captured so it can survive a code update.
 *
 * Layer 4 is explicit that a code update while a screen is live is the NORMAL case, since
 * `ZiplineLoader.load()` returns a flow. Without this, every update loses scroll position,
 * expanded rows, and half-typed text.
 *
 * The values are whatever the guest's `rememberSaveable` call sites produced. They cross the
 * boundary, so they must be serializable -- which is a real constraint on what a guest may
 * declare saveable, not an implementation detail.
 */
@Serializable
data class StateSnapshot(
  val values: Map<String, List<JsonElement>> = emptyMap(),
) {
  val isEmpty: Boolean get() = values.isEmpty()
}

/** Implemented by the guest, called by the host. */
interface DogwoodGuestUi : ZiplineService {
  /**
   * Starts the experience.
   *
   * The minimal entry-point contract of ADR-004 section 2.5: the manifest names the entry
   * composable, [launchParams] carries serializable launch parameters, and outcomes are
   * signalled by calling ordinary host services. Host-directed lambdas are not supported.
   */
  fun start(
    host: DogwoodHost,
    configuration: DogwoodConfiguration,
    launchParams: JsonElement,
    segmentVersions: Map<String, Int>,
    /** State captured from a previous guest, or null on a cold start. */
    restoredState: StateSnapshot? = null,
  )

  /**
   * Captures the composition's saveable state, for handing to a replacement guest.
   *
   * Called immediately before teardown on a code update, and available for backgrounding and
   * process death once those are designed.
   */
  fun snapshotState(): StateSnapshot

  /** Delivers one interaction. [Event.q] lets the guest drop events rendered against a stale batch. */
  fun sendEvent(event: Event)

  /** Drives the guest frame clock. Called only after [DogwoodHost.requestFrame]. */
  fun frame(timeNanos: Long)

  /** Pushes a configuration change into the composition. */
  fun updateConfiguration(configuration: DogwoodConfiguration)
}

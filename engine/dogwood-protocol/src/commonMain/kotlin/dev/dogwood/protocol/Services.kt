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

interface DogwoodGuestUi : ZiplineService {
  /**
   * Starts the experience.
   *
   * The entry-point contract of ADR-004 section 2.5, grown into its Phase 4 form
   * ([Layer 5 ADR-013](../../../adrs/layer-5/ADR-013-host-services-and-entry-points.md)): the
   * manifest's `mainFunction` registers the guest's named entry points, the host names one,
   * [launchParams] carries serializable launch parameters, and outcomes are signalled by calling
   * ordinary host services. Host-directed lambdas are still not supported.
   */
  fun start(
    host: DogwoodHost,
    /** Everything the sandbox can reach. See `HostServices.kt`. */
    services: DogwoodServices,
    /**
     * Which experience to run.
     *
     * A payload carries several -- an explore screen, a checkout flow, a settings pane -- and the
     * host names one. Named rather than positional so that adding an entry point cannot renumber
     * an existing one, and so that a host holding a deep link can route on a string it already
     * has. A name this guest does not offer is a startup failure reported through
     * [DogwoodHost.handleUncaughtException], never a blank screen.
     */
    entryPoint: String,
    configuration: HostEnvironment,
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
  fun updateConfiguration(configuration: HostEnvironment)
}

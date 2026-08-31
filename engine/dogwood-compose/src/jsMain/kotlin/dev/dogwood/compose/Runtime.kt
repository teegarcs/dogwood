/*
 * Project Dogwood -- the guest runtime.
 *
 * One live composition per mounted experience, paced by a frame clock the HOST drives.
 * specs/layer-4-sandbox.md, "The Frame Loop".
 */
package dev.dogwood.compose

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import dev.dogwood.protocol.DogwoodConfiguration
import dev.dogwood.protocol.DogwoodGuestUi
import dev.dogwood.protocol.DogwoodHost
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.Id
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Host facts, exposed to guest code as ordinary `CompositionLocal`s.
 *
 * Configuration is a value that changes, so it is a plain `compositionLocalOf`. The dictionary
 * segment versions are fixed for a composition's lifetime, so they are `static` -- and they are
 * a map rather than a scalar, because a client can carry several independently versioned
 * design-system segments ([Layer 5 ADR-006]).
 */
val LocalDogwoodConfiguration = compositionLocalOf { DogwoodConfiguration() }
val LocalDogwoodSegments = staticCompositionLocalOf { emptyMap<String, Int>() }

/**
 * The composition, its applier, and the channel back to the host.
 *
 * Changes are flushed from [DogwoodApplier.onEndChanges], which Compose calls once at the end
 * of applying a composition's changes. That is deliberately not "after `sendFrame` returns":
 * tying the flush to the applier means one batch per composition pass by construction, whatever
 * dispatcher the recomposer ends up on, and it is the same hook Redwood uses.
 */
class DogwoodComposition(
  private val host: DogwoodHost,
  initialConfiguration: DogwoodConfiguration,
  private val segmentVersions: Map<String, Int>,
  content: @Composable () -> Unit,
) {
  private val recorder = ChangeRecorder()
  private val lambdas = LambdaSlots()
  private val root = WidgetNode(Id(0), Tags.Column)

  /** The last batch sequence number handed to the host, for the stale-event check. */
  var lastSentSequence: Int = 0
    private set

  private val applier = DogwoodApplier(root, recorder, lambdas) { flush() }

  /**
   * `onNewAwaiters` is what makes an idle experience free: the guest asks for a frame only when
   * something is actually waiting for one, so no traffic crosses while nothing is animating.
   */
  private val frameClock = BroadcastFrameClock { host.requestFrame() }

  // Unconfined so that `frame()` completes its recomposition before returning, which keeps the
  // host's frame callback and the guest's work in a single, reasoned-about sequence. The
  // threading contract is a Phase 1 deliverable in its own right; this is the simple end of it.
  private val scope = CoroutineScope(frameClock + Dispatchers.Unconfined + Job())
  private val recomposer = Recomposer(scope.coroutineContext)
  private val composition = Composition(applier, recomposer)

  private val configuration = mutableStateOf(initialConfiguration)

  init {
    recording = RecordingContext(recorder, lambdas)
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      recomposer.runRecomposeAndApplyChanges()
    }
    composition.setContent {
      CompositionLocalProvider(
        LocalDogwoodConfiguration provides configuration.value,
        LocalDogwoodSegments provides segmentVersions,
      ) {
        Children(Tags.Content) { content() }
      }
    }
  }

  private fun flush() {
    if (recorder.pending == 0) return
    val batch = recorder.takeBatch()
    lastSentSequence = batch.q
    host.sendChanges(encodeBatch(batch))
  }

  fun frame(timeNanos: Long) {
    Snapshot.sendApplyNotifications()
    frameClock.sendFrame(timeNanos)
  }

  fun updateConfiguration(next: DogwoodConfiguration) {
    configuration.value = next
    Snapshot.sendApplyNotifications()
  }

  /**
   * Dispatches one interaction.
   *
   * Two things can go wrong and both are expected rather than exceptional. The event may name a
   * node the guest has already removed, because the user tapped a frame the host had not yet
   * repainted; that is telemetry, not a crash. And the event may have been rendered against a
   * batch older than the one the guest has since sent, which is what makes a double-tapped
   * "Pay" button a correctness problem rather than a cosmetic one.
   */
  fun sendEvent(event: Event) {
    if (event.q < lastSentSequence - STALE_EVENT_TOLERANCE) {
      host.onUnknownEventNode(event.i, event.e)
      return
    }
    if (!lambdas.dispatch(event.i, event.e, event.a)) {
      host.onUnknownEventNode(event.i, event.e)
      return
    }
    Snapshot.sendApplyNotifications()
  }

  fun dispose() {
    composition.dispose()
    recomposer.cancel()
    scope.cancel()
  }

  private companion object {
    /**
     * How many batches an event may lag before it is dropped. Zero would drop legitimate taps
     * that raced a repaint by one frame; the host is always at least one batch behind.
     */
    const val STALE_EVENT_TOLERANCE = 2
  }
}

/**
 * The guest side of the Layer 4 boundary.
 *
 * One instance per experience. The entry composable is supplied by the experience's own `main`,
 * which is what the manifest's `mainFunction` names.
 */
class DogwoodGuest(
  private val content: @Composable () -> Unit,
) : DogwoodGuestUi {
  private var composition: DogwoodComposition? = null

  override fun start(
    host: DogwoodHost,
    configuration: DogwoodConfiguration,
    launchParams: JsonElement,
    segmentVersions: Map<String, Int>,
  ) {
    check(composition == null) { "start() called twice on one guest" }
    composition = DogwoodComposition(host, configuration, segmentVersions, content)
  }

  override fun sendEvent(event: Event) {
    composition?.sendEvent(event)
  }

  override fun frame(timeNanos: Long) {
    composition?.frame(timeNanos)
  }

  override fun updateConfiguration(configuration: DogwoodConfiguration) {
    composition?.updateConfiguration(configuration)
  }

  override fun close() {
    composition?.dispose()
    composition = null
  }
}

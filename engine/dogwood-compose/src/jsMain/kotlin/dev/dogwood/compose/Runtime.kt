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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import dev.dogwood.protocol.DogwoodConfiguration
import dev.dogwood.protocol.DogwoodGuestUi
import dev.dogwood.protocol.DogwoodHost
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.StateSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

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
  restoredState: StateSnapshot?,
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

  /**
   * Where `rememberSaveable` in guest code stores and restores itself.
   *
   * `canBeSaved` is deliberately narrow: a saved value has to cross the Zipline boundary, so it
   * has to be serializable. A guest that tries to save something else is told at the call site
   * rather than discovering on the next code update that its state quietly vanished.
   */
  private val saveableRegistry = SaveableStateRegistry(
    restoredValues = restoredState?.values?.mapValues { (_, values) -> values.map(::fromJson) },
    canBeSaved = ::canBeSaved,
  )

  init {
    recording = RecordingContext(recorder, lambdas)
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      recomposer.runRecomposeAndApplyChanges()
    }
    composition.setContent {
      CompositionLocalProvider(
        LocalDogwoodConfiguration provides configuration.value,
        LocalDogwoodSegments provides segmentVersions,
        LocalSaveableStateRegistry provides saveableRegistry,
      ) {
        Children(Tags.Content) { content() }
      }
    }
  }

  /**
   * The guest half of the threading contract.
   *
   * Phase 1 step 6 asks for dispatcher assertions on *both* sides, and on the guest the thread
   * check degenerates: QuickJS is single-threaded, so "am I on the right thread" is always yes
   * and asserting it would be theatre. The hazard that is real here is **re-entrancy** -- a host
   * call arriving while composition or change-flushing is already in progress, which would
   * interleave two composition passes into one batch and break the invariant that a batch is
   * exactly one pass.
   *
   * It cannot happen today, because every host entry point is one synchronous crossing. It would
   * start happening the moment anything on this side awaits, which is why the check exists now
   * rather than after the bug.
   */
  private var inGuestCall = false

  private inline fun <T> guestCall(name: String, body: () -> T): T {
    check(!inGuestCall) {
      "re-entrant guest call: $name arrived while another was still running. A change batch is " +
        "one composition pass by construction, and interleaving two would silently break that."
    }
    inGuestCall = true
    try {
      return body()
    } finally {
      inGuestCall = false
    }
  }

  private fun flush() {
    if (recorder.pending == 0) return
    val batch = recorder.takeBatch()
    lastSentSequence = batch.q
    host.sendChanges(encodeBatch(batch))
  }

  fun frame(timeNanos: Long) = guestCall("frame") {
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
  fun sendEvent(event: Event) = guestCall("sendEvent") {
    if (event.q < lastSentSequence - STALE_EVENT_TOLERANCE) {
      host.onUnknownEventNode(event.i, event.e)
      return@guestCall
    }
    if (!lambdas.dispatch(event.i, event.e, event.a)) {
      host.onUnknownEventNode(event.i, event.e)
      return@guestCall
    }
    Snapshot.sendApplyNotifications()
  }

  /** Captures saveable state for a replacement guest to restore. */
  fun snapshotState(): StateSnapshot = StateSnapshot(
    saveableRegistry.performSave().mapValues { (_, values) -> values.map(::toJson) },
  )

  /** How many event closures are currently retained. Reclamation is not automatic. */
  val lambdaSlotCount: Int get() = lambdas.size

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
    restoredState: StateSnapshot?,
  ) {
    check(composition == null) { "start() called twice on one guest" }
    composition = DogwoodComposition(host, configuration, segmentVersions, restoredState, content)
  }

  override fun snapshotState(): StateSnapshot =
    composition?.snapshotState() ?: StateSnapshot()

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

/**
 * Whether a value the guest wants to save can cross the boundary.
 *
 * `rememberSaveable(stateSaver = ...)` does not hand the registry the value itself. Compose wraps
 * it in a `MutableState` envelope so the restored state keeps its mutation policy, so the
 * predicate has to look inside that envelope rather than reject it. Getting this wrong fails at
 * composition time with a message about `MutableState` that reads like a call-site mistake.
 */
private fun canBeSaved(value: Any?): Boolean = when (value) {
  null -> true
  is MutableState<*> -> canBeSaved(value.value)
  is Int, is Long, is Float, is Double, is Boolean, is String -> true
  // Deliberately narrow. A saved value has to survive JSON, and a guest that tries to save
  // something richer should be told at the call site rather than discover on the next code
  // update that its state quietly vanished.
  else -> false
}

/** Saveable values cross the boundary, so they are carried as JSON. */
private fun toJson(value: Any?): JsonElement = when (value) {
  null -> JsonNull
  // The `MutableState` envelope is preserved on the wire, because the restore side of Compose's
  // state saver requires one back.
  is MutableState<*> -> buildJsonObject { put(STATE_ENVELOPE, toJson(value.value)) }
  is Boolean -> JsonPrimitive(value)
  is Int -> JsonPrimitive(value)
  is Long -> JsonPrimitive(value)
  is Float -> JsonPrimitive(value)
  is Double -> JsonPrimitive(value)
  is String -> JsonPrimitive(value)
  // Unreachable: canBeSaved rejects anything else before it gets here.
  else -> error("guest tried to save an unsupported value")
}

private fun fromJson(value: JsonElement): Any? {
  if (value is JsonObject) {
    val inner = value[STATE_ENVELOPE] ?: return null
    return mutableStateOf(fromJson(inner))
  }
  val primitive = value as? JsonPrimitive ?: return null
  if (primitive is JsonNull) return null
  if (primitive.isString) return primitive.content
  primitive.booleanOrNull?.let { return it }
  primitive.intOrNull?.let { return it }
  primitive.doubleOrNull?.let { return it }
  return primitive.content
}

private const val STATE_ENVELOPE = "s"

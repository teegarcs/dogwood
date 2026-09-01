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
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.DogwoodGuestUi
import dev.dogwood.protocol.DogwoodHost
import dev.dogwood.protocol.DogwoodServices
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.SERVICES_SEGMENT
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
val LocalHostEnvironment = compositionLocalOf { HostEnvironment() }
val LocalSegmentVersions = staticCompositionLocalOf { emptyMap<String, Int>() }

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
  initialConfiguration: HostEnvironment,
  private val segmentVersions: Map<String, Int>,
  restoredState: StateSnapshot?,
  /** Resolved once by the caller, because every accessor call allocates a service proxy. */
  private val services: HostServices = HostServices.None,
  private val launchParams: JsonElement = JsonNull,
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
        LocalHostEnvironment provides configuration.value,
        LocalSegmentVersions provides segmentVersions,
        LocalHostServices provides services,
        LocalLaunchParams provides launchParams,
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

  /**
   * Wakes the frame loop for state the guest changes **on its own**.
   *
   * Until host services existed, every guest state change began with a host call -- a frame, an
   * event, a configuration push -- so sending apply notifications at the end of that call was
   * enough, and this observer would have had nothing to do. A suspending service call breaks that
   * assumption: the coroutine resumes long after the call that started it returned, writes state,
   * and there is nobody left to notice.
   *
   * The symptom is precise and was seen before this existed: the network fetch completed, the log
   * recorded the parsed feed, and the screen sat on "Loading..." until an unrelated tap happened
   * to deliver the apply notification the write never sent.
   *
   * Writes made *during* composition go to the composition's own snapshot and do not reach a
   * global observer, so this fires exactly for the case it is for. The frame request is coalesced
   * on this side as well as the host's, because the observer fires per write and a crossing per
   * write would be a boundary call for every field of every object a guest touches.
   */
  private var frameRequested = false

  private val writeObserver = Snapshot.registerGlobalWriteObserver {
    if (!frameRequested) {
      frameRequested = true
      host.requestFrame()
    }
  }

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
    frameRequested = false
    Snapshot.sendApplyNotifications()
    frameClock.sendFrame(timeNanos)
  }

  /**
   * Pushes a new host environment into the composition.
   *
   * Guarded like every other host entry point, because it is one: it arrives on the Zipline
   * dispatcher while a frame or an event may already be in flight, and two composition passes
   * interleaved into one batch would break the invariant that a batch is one pass.
   *
   * An unchanged configuration is free. The value is snapshot state with the default structural
   * equality policy, so an equal value invalidates nothing and no frame is requested -- which is
   * what makes it safe for a host to push the environment liberally rather than trying to work
   * out whether it moved.
   */
  fun updateConfiguration(next: HostEnvironment) = guestCall("updateConfiguration") {
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
    // Before anything else: the observer is registered globally and captures this composition's
    // host. A guest replaced by a code update that left its observer behind would keep asking a
    // dead host for frames, once per generation, for the life of the QuickJS instance.
    writeObserver.dispose()
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
  /**
   * The experiences this payload offers, by name.
   *
   * Each takes the launch parameters as raw data, because the host cannot construct guest types.
   * A payload with one experience registers one entry; a payload serving a whole application
   * registers several and lets the host route.
   */
  private val entryPoints: Map<String, @Composable (JsonElement) -> Unit>,
) : DogwoodGuestUi {
  constructor(vararg entryPoints: Pair<String, @Composable (JsonElement) -> Unit>) :
    this(entryPoints.toMap())

  private var composition: DogwoodComposition? = null

  /** Which entry points this guest offers, for diagnostics and for the unknown-name message. */
  val offers: Set<String> get() = entryPoints.keys

  override fun start(
    host: DogwoodHost,
    services: DogwoodServices,
    entryPoint: String,
    configuration: HostEnvironment,
    launchParams: JsonElement,
    segmentVersions: Map<String, Int>,
    restoredState: StateSnapshot?,
  ) {
    check(composition == null) { "start() called twice on one guest" }
    val content = entryPoints[entryPoint]
    if (content == null) {
      // Reported, not thrown into the void. An unknown entry point is a routing mistake between
      // a host and a payload that ship separately, and the only way anyone finds out is if the
      // host is told which names this payload actually offers.
      host.handleUncaughtException(
        IllegalArgumentException(
          "no entry point named '$entryPoint'; this payload offers ${entryPoints.keys.sorted()}",
        ),
      )
      return
    }
    composition = DogwoodComposition(
      host = host,
      initialConfiguration = configuration,
      segmentVersions = segmentVersions,
      restoredState = restoredState,
      services = HostServices.resolve(services, segmentVersions[SERVICES_SEGMENT] ?: 0),
      launchParams = launchParams,
      content = { content(launchParams) },
    )
  }

  override fun snapshotState(): StateSnapshot =
    composition?.snapshotState() ?: StateSnapshot()

  override fun sendEvent(event: Event) {
    composition?.sendEvent(event)
  }

  override fun frame(timeNanos: Long) {
    composition?.frame(timeNanos)
  }

  override fun updateConfiguration(configuration: HostEnvironment) {
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
  // Lists of saveable values, which is what Compose's own `listSaver` produces and therefore the
  // idiomatic way to save a holder with more than one field. Checked element by element rather
  // than assumed: a list is only saveable if everything in it is.
  is List<*> -> value.all(::canBeSaved)
  // Deliberately narrow beyond that. A saved value has to survive JSON, and a guest that tries to
  // save something richer should be told at the call site rather than discover on the next code
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
  is List<*> -> kotlinx.serialization.json.JsonArray(value.map(::toJson))
  // Unreachable: canBeSaved rejects anything else before it gets here.
  else -> error("guest tried to save an unsupported value")
}

private fun fromJson(value: JsonElement): Any? {
  if (value is kotlinx.serialization.json.JsonArray) return value.map(::fromJson)
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

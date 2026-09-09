/*
 * Project Dogwood -- code update while a screen is live.
 *
 * Layer 4 calls this the *normal* case rather than an edge one, because `ZiplineLoader.load()`
 * returns a flow. A session watches that flow, and when new code arrives it captures the running
 * guest's saveable state, tears the old guest down, stands the replacement up, and hands the
 * state across.
 *
 * What survives is what the guest declared with `rememberSaveable`, and nothing else. That is a
 * real limit, not a temporary one: the new code may have a different composition shape, so
 * restoring anything the guest did not explicitly nominate would be restoring state into a tree
 * that may no longer have a place for it.
 */
package dev.dogwood.host

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.DogwoodServices
import dev.dogwood.protocol.StateSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** What a session has done so far, for logging and for the sample's on-screen banner. */
data class SessionStatus(
  val loadCount: Int = 0,
  val version: String? = null,
  val verifiedByKey: String? = null,
  val restoredKeys: Int = 0,
)

/**
 * A live experience that replaces itself when new code is published.
 *
 * @param onSwap called on the user-interface thread after each swap, for telemetry.
 */
class DogwoodSession(
  private val delivery: DogwoodDelivery,
  private val applicationName: String,
  private val manifestUrl: String,
  private val ziplineDispatcher: CoroutineDispatcher,
  private val uiScope: CoroutineScope,
  initialConfiguration: HostEnvironment = HostEnvironment(),
  /** Which of the payload's experiences to run, and what to launch it with. */
  private val entryPoint: String = "main",
  private val launchParams: JsonObject = JsonObject(emptyMap()),
  /**
   * What this client lets the guest reach.
   *
   * Held by the session rather than passed per start, because a replacement guest adopted by a
   * code update gets the same offer as the one it replaced. A client that changed what it offered
   * mid-session would have a guest built for one set of services running against another.
   */
  private val services: DogwoodServices = DogwoodServiceHost(),
  /**
   * Watches each replaced guest generation.
   *
   * This is the leak that matters in this architecture, and it is not one an ordinary application
   * can have. A retained [DogwoodExperience] is not one stale object: it holds the Zipline
   * instance, and through it an entire QuickJS heap with a whole composition inside. A code update
   * while a screen is live is the normal case here, so leaking a generation per publish means a
   * long-lived screen accumulating interpreters.
   */
  private val leakDetector: DogwoodLeakWatcher = DogwoodLeakWatcher.None,
  /**
   * State to restore into the **first** guest this session starts.
   *
   * Normally null: a session begins cold. The shell uses it to bring back an experience it
   * evicted under memory pressure, which is the same restore path a code update already uses --
   * pointed at a different reason for the guest having gone away.
   */
  private val initialState: StateSnapshot? = null,
  /**
   * Whether closing this session closes the delivery it was given.
   *
   * True for a session that was handed its own; false when a shell owns one delivery across
   * several sessions, where closing it with the first session would take the others down and
   * leave a second `ZiplineCache` open on the same directory.
   */
  private val ownsDelivery: Boolean = true,
  private val pollIntervalMs: Long = 5_000,
  private val onSwap: (SessionStatus) -> Unit = {},
  /** Every failed poll, including the first. A silent failure is a blank screen with no cause. */
  private val onFailure: (Exception) -> Unit = {},
  /**
   * Decides whether a delivered release may run at all.
   *
   * Optional, because a host that has no persistent storage — a test, a preview — has nowhere to
   * remember what worked, and a guard with no memory is worse than none: it would count every
   * launch as a first one. Null means every release runs, which is what happened before this
   * existed.
   */
  private val releaseGuard: ReleaseGuard? = null,
  /** What dictionary this client implements; see `DogwoodDelivery`'s parameter of the same name. */
  private val clientSegmentVersions: () -> Map<String, Int> = { DogwoodDictionary.segmentVersions },
  /**
   * Called when a release is refused, on the user-interface thread.
   *
   * A refusal is not an error and must not be reported as one: the previous guest is still running
   * and the screen is still up. What the host owes the user is its own fallback if there is no
   * previous guest at all, which is the only case a refusal is visible in.
   */
  private val onRefused: (GuardedRelease) -> Unit = {},
) {
  private val currentExperience = mutableStateOf<DogwoodExperience?>(null)

  /** The experience to render. Changes when new code is adopted. */
  val experience: State<DogwoodExperience?> get() = currentExperience

  /**
   * The host environment, held so a guest adopted mid-session starts with the environment the
   * device is actually in.
   *
   * Holding it is the whole point. Without it a code update published after the user rotated the
   * device, enlarged their text, or switched to dark mode would hand the replacement guest the
   * environment captured when the session was constructed -- and the screen would come back
   * laid out for a device the user is no longer holding, with nothing to indicate why.
   *
   * Not synchronised, and it does not need to be: [updateConfiguration] is called from
   * composition and [run] collects on the same user-interface scope, so both touch this from the
   * user-interface thread. `flowOn` moves the *upstream* to the Zipline dispatcher, not the
   * collector.
   */
  private var configuration: HostEnvironment = initialConfiguration

  private var status = SessionStatus()

  /** Consumed by the first load only; a later code update carries its own state across. */
  private var pendingInitialState: StateSnapshot? = initialState

  /**
   * Runs until cancelled. Call from the user-interface scope; the Zipline work inside switches
   * dispatchers explicitly rather than relying on where the caller happened to be.
   */
  suspend fun run() {
    delivery.updates(applicationName, manifestUrl, pollIntervalMs, onFailure)
      // The loader instantiates QuickJS and runs the guest's entry point, so all of it must
      // happen on the Zipline thread -- which is the one created with an eight-megabyte stack.
      // Without this the work runs on whichever thread collects, and on Android's main thread
      // interpreted composition overflows the stack. The threading contract does not stop at the
      // frame loop; it reaches into Layer 3.
      .flowOn(ziplineDispatcher)
      .collect { delivered ->
      val previous = currentExperience.value

      // Before anything is mounted, and before any guest code composes. Loading a payload is not
      // the dangerous part; running it is.
      val version = delivered.releaseVersion

      /*
       * The pre-flight dictionary check, on the shell's path as well as the bare one.
       *
       * A shell reaches a payload through `updates(...)` rather than `loadGuarded`, so the check in
       * `DogwoodDelivery.loadGuarded` never runs here -- and a code update is exactly when a
       * too-new payload arrives, because that is what publishing means. Missing this would have
       * left the protection on the path a product uses least.
       */
      val skew = checkDeclaredDictionary(delivered.declaredSegments, clientSegmentVersions())
      if (skew != null) {
        withContext(ziplineDispatcher) { delivered.zipline.close() }
        withContext(uiScope.coroutineContext) {
          onRefused(GuardedRelease(version, skew.message, releaseGuard?.lastGoodVersion()))
        }
        return@collect
      }

      val verdict = releaseGuard?.verdict(version, delivered.disabledByPublisher)
      if (verdict is ReleaseVerdict.Refused) {
        // Closed rather than left open: a refused guest is a live QuickJS instance and a whole
        // heap, and keeping one because it might be wanted is the leak this project already fixed
        // from the other direction.
        withContext(ziplineDispatcher) { delivered.zipline.close() }
        withContext(uiScope.coroutineContext) {
          onRefused(GuardedRelease(version, verdict.reason, verdict.fallbackVersion))
        }
        return@collect
      }
      // Recorded and **persisted** before the guest runs. An attempt counted in memory is erased
      // by the crash it is counting, so an application that crashes on launch would relaunch, load
      // the same payload, and crash again, forever.
      releaseGuard?.starting(version)

      // Capture before teardown. The old guest is still alive at this point, which is the only
      // moment its state can be read at all.
      val carried: StateSnapshot? = pendingInitialState.also { pendingInitialState = null }
        ?: previous?.let {
        withContext(ziplineDispatcher) {
          val snapshot = it.snapshotState()
          it.close()
          snapshot
        }
      }

      // Constructed on the user-interface thread, because that is the thread it binds.
      val next = DogwoodExperience(
        delivered.zipline,
        ziplineDispatcher,
        uiScope,
        leakDetector = leakDetector,
      )
      withContext(ziplineDispatcher) {
        next.start(
          entryPoint = entryPoint,
          services = services,
          configuration = configuration,
          launchParams = launchParams,
          restoredState = carried,
        )
      }

      currentExperience.value = next
      // Watched only after the replacement is in place, so that the reference handed to the
      // detector is the last one this session itself holds.
      if (previous != null) watchOutgoing(previous, status.loadCount)
      status = SessionStatus(
        loadCount = status.loadCount + 1,
        version = delivered.manifest.version,
        verifiedByKey = delivered.verifiedByKey,
        restoredKeys = carried?.values?.size ?: 0,
      )
      // The release worked, and *this* is what working means: a guest started, produced a tree, and
      // the host mounted it. "It loaded" would not do -- a payload that throws on its first
      // composition has loaded -- which is why this call is here and not next to `starting`.
      releaseGuard?.succeeded(version)
      onSwap(status)
    }
  }

  /**
   * Records the environment and pushes it into the live guest, if there is one.
   *
   * Call from the user-interface thread. Safe to call with an unchanged value: an equal
   * configuration is dropped here rather than crossing the boundary, because a crossing is not
   * free and a guest recomposition is less free still.
   */
  fun updateConfiguration(next: HostEnvironment) {
    if (next == configuration) return
    configuration = next
    currentExperience.value?.updateConfiguration(next)
  }

  private fun watchOutgoing(previous: DogwoodExperience, generation: Int) {
    leakDetector.watch(
      previous,
      "guest generation #$generation, replaced by a code update",
    )
  }

  /** How many frames the live guest has asked for; see [DogwoodExperience.frameRequests]. */
  val frameRequests: Int get() = currentExperience.value?.frameRequests ?: 0

  /** Captures the running guest's saveable state. Must be called off the user-interface thread. */
  suspend fun snapshotState(): StateSnapshot {
    val live = currentExperience.value ?: return StateSnapshot()
    return withContext(ziplineDispatcher) { live.snapshotState() }
  }

  /**
   * Tears the session down, crossing to the Zipline thread to do it.
   *
   * Suspending, because [DogwoodExperience.close] talks to the interpreter and the interpreter has
   * one thread. This used to be an ordinary function called from wherever the caller happened to
   * be, which meant the shell's eviction path closed a guest from the user-interface thread.
   */
  suspend fun close() {
    val live = currentExperience.value
    currentExperience.value = null
    if (live != null) withContext(ziplineDispatcher) { live.close() }
    if (ownsDelivery) delivery.close()
  }
}

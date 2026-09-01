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
import dev.dogwood.protocol.DogwoodConfiguration
import dev.dogwood.protocol.StateSnapshot
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
  initialConfiguration: DogwoodConfiguration = DogwoodConfiguration(),
  private val pollIntervalMs: Long = 5_000,
  private val onSwap: (SessionStatus) -> Unit = {},
  /** Every failed poll, including the first. A silent failure is a blank screen with no cause. */
  private val onFailure: (Exception) -> Unit = {},
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
  private var configuration: DogwoodConfiguration = initialConfiguration

  private var status = SessionStatus()

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

      // Capture before teardown. The old guest is still alive at this point, which is the only
      // moment its state can be read at all.
      val carried: StateSnapshot? = previous?.let {
        withContext(ziplineDispatcher) {
          val snapshot = it.snapshotState()
          it.close()
          snapshot
        }
      }

      // Constructed on the user-interface thread, because that is the thread it binds.
      val next = DogwoodExperience(delivered.zipline, ziplineDispatcher, uiScope)
      withContext(ziplineDispatcher) {
        next.start(configuration = configuration, restoredState = carried)
      }

      currentExperience.value = next
      status = SessionStatus(
        loadCount = status.loadCount + 1,
        version = delivered.manifest.version,
        verifiedByKey = delivered.verifiedByKey,
        restoredKeys = carried?.values?.size ?: 0,
      )
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
  fun updateConfiguration(next: DogwoodConfiguration) {
    if (next == configuration) return
    configuration = next
    currentExperience.value?.updateConfiguration(next)
  }

  fun close() {
    currentExperience.value?.close()
    currentExperience.value = null
    delivery.close()
  }
}

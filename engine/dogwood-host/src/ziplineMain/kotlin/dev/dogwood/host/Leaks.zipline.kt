/*
 * Project Dogwood -- the leak detector behind `DogwoodLeakWatcher`.
 *
 * Separated from the interface (`Leaks.kt`, host core) because `redwood-leak-detector` publishes
 * no WebAssembly artifact, and the core compiles for the browser since Layer 5 ADR-041. Every
 * platform Zipline serves gets a real detector; the web host gets `DogwoodLeakWatcher.None` until
 * something equivalent exists for it, which is a gap worth naming rather than papering over --
 * a leaked guest generation is as real in a tab as on a phone.
 */
package dev.dogwood.host

import app.cash.redwood.leaks.LeakDetector
import app.cash.redwood.leaks.RedwoodLeakApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope

/**
 * A running leak detector.
 *
 * @see dogwoodLeakDetector
 */
class DogwoodLeakDetector @OptIn(RedwoodLeakApi::class) internal constructor(
  private val delegate: LeakDetector,
) : DogwoodLeakWatcher, AutoCloseable {

  @OptIn(RedwoodLeakApi::class)
  override fun watch(reference: Any, note: String) {
    delegate.watchReference(reference, note)
  }

  /**
   * Stops accepting new references and suspends until every watched one has either been collected
   * or reported.
   *
   * For a test that wants a verdict rather than a background report. In an application, prefer
   * [close] and let the callback do its work.
   */
  @OptIn(RedwoodLeakApi::class)
  suspend fun awaitAllSettled() {
    delegate.awaitClose()
  }

  @OptIn(RedwoodLeakApi::class)
  override fun close() {
    delegate.close()
  }
}

/**
 * A leak detector for a Dogwood host, reporting through [onLeak].
 *
 * @param scope the detector's own scope. It runs a periodic collection while references are being
 *   watched, so this should be a scope that ends with the host surface rather than a global one.
 * @param leakThreshold how long a watched reference may survive before it is called a leak. The
 *   default is generous on purpose: a reference merely waiting for the next collection is not a
 *   leak, and a detector that cries wolf gets switched off.
 * @param onLeak called with the surviving reference and the note it was watched under. Report it;
 *   do not throw. A leak is a defect to fix in the next build, not a reason to take the screen
 *   down in this one.
 */
@OptIn(RedwoodLeakApi::class)
fun dogwoodLeakDetector(
  scope: CoroutineScope,
  leakThreshold: Duration = 10.seconds,
  timeSource: TimeSource = TimeSource.Monotonic,
  onLeak: (reference: Any, note: String) -> Unit,
): DogwoodLeakDetector = DogwoodLeakDetector(
  LeakDetector.timeBasedIn(
    scope = scope,
    timeSource = timeSource,
    leakThreshold = leakThreshold,
    callback = LeakDetector.Callback(onLeak),
  ),
)

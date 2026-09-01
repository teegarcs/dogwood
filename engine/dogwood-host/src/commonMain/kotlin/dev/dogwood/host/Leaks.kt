/*
 * Project Dogwood -- leak detection.
 *
 * roadmap.md Phase 4 says "Adopt `redwood-leak-detector`. **Before iOS, not after** --
 * cross-language reference cycles span Kotlin/Native garbage collection and Swift reference
 * counting." Adopted rather than reinvented, exactly as written:
 * `app.cash.redwood:redwood-leak-detector` (Apache 2.0) publishes Java Virtual Machine,
 * JavaScript, WebAssembly and iOS targets, and an Android consumer resolves its Java Virtual
 * Machine variant.
 *
 * **Where Dogwood leaks, if it leaks, is not where an ordinary application leaks.** Two places,
 * and both are watched:
 *
 *   1. **A detached subtree.** Removing children from the mirror must forget them, or a feed that
 *      creates and destroys ten thousand rows retains ten thousand nodes and every property map
 *      in them.
 *   2. **A replaced guest generation.** This is the one that matters, and it is peculiar to this
 *      architecture. A code update while a screen is live is the *normal* case, so a retained
 *      `DogwoodExperience` is not one stale object -- it holds the Zipline instance, and through
 *      it an entire QuickJS heap with a whole composition in it. Leak a generation per publish and
 *      a long-lived screen accumulates interpreters.
 *
 * The guest's own heap is deliberately **not** watched. Its retention hazard -- an event closure
 * outliving the node that registered it -- is structural rather than collectible, and a test that
 * counts `lambdaSlotCount` after a removal is a deterministic assertion where a garbage-collection
 * probe would be a flaky one. That test already exists.
 */
package dev.dogwood.host

import app.cash.redwood.leaks.LeakDetector
import app.cash.redwood.leaks.RedwoodLeakApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope

/**
 * Where Dogwood hands a reference it expects to become unreachable.
 *
 * Dogwood's own type rather than Redwood's, and the reason is on the tin: `LeakDetector` is marked
 * `@RedwoodLeakApi`, "unstable and for Redwood internal use only". Putting an explicitly-internal
 * third-party type in this project's public signatures would make every host that watches for
 * leaks depend on it directly, and Redwood is a discontinued project. The opt-in lives in this one
 * file, and the implementation behind it can be replaced without touching a caller.
 */
fun interface DogwoodLeakWatcher {
  fun watch(reference: Any, note: String)

  companion object {
    /** What a host that is not investigating a leak uses, which is most hosts most of the time. */
    val None: DogwoodLeakWatcher = DogwoodLeakWatcher { _, _ -> }
  }
}

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

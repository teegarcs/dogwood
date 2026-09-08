/*
 * Project Dogwood -- what bounds a payload that will not stop.
 *
 * The containment story was thorough about *values* (clamps), *names* (skew), *crashes*
 * (`ReleaseGuard`) and *per-frame cost* (the authoring check bans the APIs by name) -- and silent
 * about `while (true)` and unbounded allocation, either of which a payload can ship over the air
 * this afternoon. The authoring check cannot catch a loop; only a runtime bound can
 * (`plans/adoption-audit.md` A5).
 *
 * Both bounds are QuickJS's own, reached through the API Zipline exposes on its engine:
 * `QuickJs.memoryLimit` refuses allocations past a ceiling, and `QuickJs.interruptHandler` is
 * polled from inside JavaScript execution and stops it when told to. Neither takes the host down:
 * a breach surfaces as the failing call's exception, which flows through the same
 * `onGuestException` channel every other guest failure uses (ADR-059).
 */
package dev.dogwood.host

import app.cash.zipline.InterruptHandler
import app.cash.zipline.Zipline

/**
 * The bounds a host places on a guest, applied by [DogwoodDelivery] to every payload it loads.
 *
 * Defaults are ON, deliberately -- the same argument that removed `releaseGuard`'s default
 * (ADR-058): a payload is replaceable over the air without review, so an unbounded interpreter is
 * a standing invitation, and protection that must be asked for is protection most hosts do not
 * have. A host with a real reason passes [none].
 *
 * @param memoryLimitBytes the ceiling on the guest heap. The default is far above anything a
 *   screen needs -- Phase 0 measured whole real screens in single-digit megabytes -- and far below
 *   taking a phone down with it.
 * @param sliceBudgetMillis how long one uninterrupted run of guest execution may last. This is not
 *   a frame budget; it is a tourniquet. Guest work is sliced by its nature -- compose, send a
 *   batch, yield, wait for an event or a frame -- so anything still running after whole seconds is
 *   not slow, it is stuck, and interrupting it is what keeps the Zipline thread answerable.
 */
class GuestLimits(
  val memoryLimitBytes: Long = 256L * 1024 * 1024,
  val sliceBudgetMillis: Long = 5_000,
  /**
   * When the interpreter collects, in bytes allocated since the last collection.
   *
   * **Null leaves QuickJS's own default alone, and that is deliberate rather than lazy.** This is
   * the one public knob that shapes collection *pause length* -- a smaller threshold means more
   * frequent, smaller collections and a shorter tail -- and it is exposed because Phase 0's worst
   * recorded sample is a collection tail: **22.1 ms at a 16 MiB threshold on a Pixel 10 Pro**,
   * outside a 60 Hz frame (`roadmap.md` 0.4).
   *
   * What is *not* done here is pick a different number, because nothing this project can run would
   * justify one. `PauseWatcher` (Layer 4 ADR-013) attributes pauses through the same public API,
   * and on both hosts available -- a development machine and an emulator -- **the worst pause was
   * not a collection at all** (3.69 ms against 7.45 ms; 5.80 ms against 7.48 ms), and neither
   * reproduced the 22.1 ms sample. Changing a default on hardware that cannot reproduce the
   * problem would be tuning against noise.
   *
   * So: the knob is here, its purpose is written down, and the number stays the runtime's until
   * somebody runs `--es experiment pauses` on a device that shows the tail. That run is one
   * command; the device is the owner's (`DECISIONS-FOR-THE-OWNER.md` §4).
   */
  val gcThresholdBytes: Long? = null,
) {
  companion object {
    /** No bounds, as a written decision rather than an omission. */
    val none: GuestLimits? = null
  }
}

/**
 * Applies [limits] to a loaded guest.
 *
 * The interrupt heuristic deserves its two sentences. [InterruptHandler.poll] is called
 * periodically from inside JavaScript execution and not at all while the engine is idle -- so a
 * long *gap* between polls means a new slice of work began, and a long *unbroken run* of polls
 * means one slice has not yielded. The handler needs no second thread and no cooperation from the
 * call sites: it times the run it is inside of.
 */
internal fun applyGuestLimits(zipline: Zipline, limits: GuestLimits) {
  val quickJs = zipline.quickJs
  quickJs.memoryLimit = limits.memoryLimitBytes
  limits.gcThresholdBytes?.let { quickJs.gcThreshold = it }
  quickJs.interruptHandler = object : InterruptHandler {
    // `TimeSource.Monotonic` rather than a platform clock, because this file compiles for
    // Kotlin/Native too -- the first draft used `System.nanoTime()` and the iOS target said no.
    private val clock = kotlin.time.TimeSource.Monotonic

    /** When the current unbroken run of polls began; null between slices. */
    private var sliceStart: kotlin.time.TimeMark? = null

    /** The previous poll, for detecting the idle gap that separates slices. */
    private var lastPoll: kotlin.time.TimeMark? = null

    override fun poll(): Boolean {
      val previous = lastPoll
      lastPoll = clock.markNow()
      // A gap of more than 100 ms since the previous poll means the engine went idle and this is
      // a fresh slice: polls during execution arrive far faster than that, and nothing polls at
      // all between slices.
      if (sliceStart == null || previous == null ||
        previous.elapsedNow().inWholeMilliseconds > 100
      ) {
        sliceStart = clock.markNow()
      }
      return (sliceStart ?: return false).elapsedNow().inWholeMilliseconds > limits.sliceBudgetMillis
    }
  }
}

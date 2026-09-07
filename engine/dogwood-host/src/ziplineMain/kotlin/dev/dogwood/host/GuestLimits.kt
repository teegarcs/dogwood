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
  quickJs.interruptHandler = object : InterruptHandler {
    /** Nanotime when the current unbroken run of polls began; 0 between slices. */
    private var sliceStart = 0L

    /** Nanotime of the previous poll, for detecting the idle gap that separates slices. */
    private var lastPoll = 0L

    override fun poll(): Boolean {
      val now = System.nanoTime()
      // A gap of more than 100 ms since the previous poll means the engine went idle and this is
      // a fresh slice: polls during execution arrive far faster than that, and nothing polls at
      // all between slices.
      if (sliceStart == 0L || now - lastPoll > 100_000_000L) sliceStart = now
      lastPoll = now
      return now - sliceStart > limits.sliceBudgetMillis * 1_000_000L
    }
  }
}

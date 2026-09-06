/*
 * Project Dogwood -- telling a collection pause from a scheduler pause, without patching QuickJS.
 *
 * Phase 0's appendix specified a **patched Zipline native build**: wrap `JS_RunGC` in the vendored
 * QuickJS with timestamps and a counter, exported through a debug method. Experiment 0.4 used the
 * permitted `gc()`-forced fallback instead, and the roadmap has carried the caveat ever since:
 *
 *   > without the hook, a 22 ms outlier cannot be attributed to garbage collection rather than to
 *   > the scheduler.
 *
 * **The patch route cannot answer that question here**, and it is worth saying why rather than
 * attempting it: the 22.1 ms outlier was measured on a Pixel 10 Pro, so the hook would have to be
 * built for Android -- which needs the Native Development Kit, and building one for the development
 * machine instead would instrument a host where the outlier has never appeared.
 *
 * So this uses the public application programming interface, which turns out to be enough.
 *
 * **`InterruptHandler` is the instrument.** QuickJS calls it periodically while interpreting. The
 * interval between successive calls is therefore an upper bound on how long the interpreter went
 * without making progress -- and `JS_RunGC` runs to completion without calling it, so a collection
 * appears as a gap.
 *
 * A gap alone proves nothing, because scheduler preemption looks identical from inside. What
 * separates them is the heap: **QuickJS is primarily reference-counted**, so `memoryAllocatedSize`
 * falls when the mark-and-sweep collector runs and does not fall when the operating system merely
 * takes the thread away. Pairing the two answers the question the hook was for:
 *
 * | gap | heap fell | reading |
 * | --- | --- | --- |
 * | long | yes | a collection pause |
 * | long | no | something else -- the scheduler, or a long uninterrupted native call |
 * | short | yes | a collection, and a cheap one |
 *
 * **What this cannot do**, and the patched hook could: separate two collections inside one gap, or
 * time a collection that begins and ends between two interrupt callbacks without the heap moving
 * measurably. It bounds a pause rather than timing it exactly, and that is the honest claim.
 */
package dev.dogwood.host

import app.cash.zipline.InterruptHandler
import app.cash.zipline.QuickJs

/** One interval in which the interpreter made no observable progress. */
data class ObservedPause(
  /** Upper bound on the pause, in milliseconds: the interval between two interrupt callbacks. */
  val millis: Double,
  /** How far the interpreter heap fell across it. Positive means bytes were reclaimed. */
  val heapReclaimed: Long,
) {
  /**
   * Whether this pause is attributable to a collection.
   *
   * The heap is the discriminator, not the duration. A reference-counted free happens continuously
   * and does not pause anything; a fall large enough to see across one interrupt interval is the
   * mark-and-sweep collector having run.
   */
  val isCollection: Boolean get() = heapReclaimed > 0
}

/**
 * Watches one interpreter and records the intervals in which it stopped making progress.
 *
 * Installed for a measurement run and removed afterwards. It costs a callback per interrupt and a
 * heap read per callback, which is why nothing installs it by default -- the heap read is a call
 * into the interpreter, and a probe that changed the thing it measures would be worse than no probe.
 */
class PauseWatcher(
  private val quickJs: QuickJs,
  /**
   * How long an interval has to be before it is worth recording, in milliseconds.
   *
   * Interrupt callbacks are frequent, so recording every interval would produce megabytes of
   * noise. The default is well below one frame, so anything that could plausibly be felt is kept.
   */
  private val thresholdMillis: Double = 1.0,
) : InterruptHandler {

  private val observed = mutableListOf<ObservedPause>()
  private var lastNanos = 0L
  private var lastHeap = 0L

  /** Every recorded interval, in the order it happened. */
  val pauses: List<ObservedPause> get() = observed.toList()

  /** The longest interval attributable to a collection, in milliseconds. Zero if none was. */
  val worstCollectionMillis: Double
    get() = observed.filter { it.isCollection }.maxOfOrNull { it.millis } ?: 0.0

  /** The longest interval **not** attributable to a collection. This is the scheduler's share. */
  val worstOtherMillis: Double
    get() = observed.filterNot { it.isCollection }.maxOfOrNull { it.millis } ?: 0.0

  fun install() {
    lastNanos = nanoTime()
    lastHeap = quickJs.memoryUsage.memoryAllocatedSize
    quickJs.interruptHandler = this
  }

  fun remove() {
    quickJs.interruptHandler = null
  }

  fun reset() {
    observed.clear()
    lastNanos = nanoTime()
    lastHeap = quickJs.memoryUsage.memoryAllocatedSize
  }

  /**
   * Returns false, always: `true` would ask QuickJS to abort the running script.
   *
   * This is a probe, and a probe that could halt the program it is watching is a different and much
   * worse thing than a probe.
   */
  override fun poll(): Boolean {
    val now = nanoTime()
    val elapsedMillis = (now - lastNanos) / 1_000_000.0
    if (elapsedMillis >= thresholdMillis) {
      val heap = quickJs.memoryUsage.memoryAllocatedSize
      observed += ObservedPause(elapsedMillis, heapReclaimed = lastHeap - heap)
      lastHeap = heap
    }
    lastNanos = now
    return false
  }
}

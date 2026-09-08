/*
 * Project Dogwood -- the bounds on a runaway guest, watched to hold.
 *
 * `plans/adoption-audit.md` A5: nothing bounded `while (true)` or unbounded allocation, either of
 * which a payload can ship over the air. These tests drive a REAL QuickJS through the same
 * `applyGuestLimits` the delivery path uses, with hostile scripts, and watch each bound fire --
 * and, just as deliberately, watch the engine stay usable afterwards, because a tourniquet that
 * takes the limb is not the treatment.
 */
package dev.dogwood.host

import app.cash.zipline.Zipline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class GuestLimitsTest {

  private fun withZipline(limits: GuestLimits, body: (Zipline) -> Unit) = runTest {
    val zipline = Zipline.create(UnconfinedTestDispatcher(testScheduler))
    try {
      applyGuestLimits(zipline, limits)
      body(zipline)
    } finally {
      zipline.close()
    }
  }

  @Test
  fun anInfiniteLoopIsInterruptedInsideItsBudget() = withZipline(
    GuestLimits(sliceBudgetMillis = 200),
  ) { zipline ->
    val started = System.nanoTime()
    val failure = assertFailsWith<Exception> {
      zipline.quickJs.evaluate("while (true) {}", "hostile.js")
    }
    val elapsedMillis = (System.nanoTime() - started) / 1_000_000
    // Two bounds on the same observation: it fired (an exception at all), and it fired for the
    // budget rather than for something else (well before this test's own timeout, well after
    // an instantaneous failure would have).
    assertTrue(elapsedMillis in 100..5_000, "interrupted after ${elapsedMillis}ms: $failure")
  }

  @Test
  fun theEngineAnswersAgainAfterAnInterrupt() = withZipline(
    GuestLimits(sliceBudgetMillis = 200),
  ) { zipline ->
    assertFailsWith<Exception> { zipline.quickJs.evaluate("while (true) {}", "hostile.js") }
    // The tourniquet, not the limb: the next slice must run. This is also what exercises the
    // handler's gap heuristic -- a fresh slice after an idle gap gets a fresh budget.
    assertEquals(7, zipline.quickJs.evaluate("3 + 4", "healthy.js"))
  }

  @Test
  fun unboundedAllocationHitsTheCeilingRatherThanTheHost() = withZipline(
    GuestLimits(memoryLimitBytes = 8L * 1024 * 1024),
  ) { zipline ->
    val failure = assertFailsWith<Exception> {
      zipline.quickJs.evaluate(
        """
        var hoard = [];
        while (true) { hoard.push(new Array(65536).fill('x')); }
        """,
        "hoarder.js",
      )
    }
    assertTrue(
      "memory" in (failure.message ?: "").lowercase() || "null" in (failure.message ?: "").lowercase(),
      "expected an allocation failure, got: $failure",
    )
  }

  @Test
  fun theCollectionThresholdIsTheRuntimesUntilAHostSaysOtherwise() = withZipline(GuestLimits()) {
    // The default must not silently retune the collector: Phase 0's worst sample is a collection
    // tail, and no host this project can run reproduces it, so a number chosen here would be
    // tuning against noise. This pins "we left it alone" as a decision rather than an oversight.
    val untouched = it.quickJs.gcThreshold
    applyGuestLimits(it, GuestLimits())
    assertEquals(untouched, it.quickJs.gcThreshold)
  }

  @Test
  fun aHostThatAsksForACollectionThresholdGetsIt() = withZipline(GuestLimits()) {
    applyGuestLimits(it, GuestLimits(gcThresholdBytes = 4L * 1024 * 1024))
    assertEquals(4L * 1024 * 1024, it.quickJs.gcThreshold)
  }

  @Test
  fun aHealthyScriptRunsUntouchedUnderTheDefaultLimits() = withZipline(GuestLimits()) { zipline ->
    // The control: bounds that fire on ordinary work teach a team to remove them.
    val result = zipline.quickJs.evaluate(
      "var s = 0; for (var i = 0; i < 100000; i++) s += i; s",
      "healthy.js",
    )
    assertEquals(4999950000.0, result)
  }
}

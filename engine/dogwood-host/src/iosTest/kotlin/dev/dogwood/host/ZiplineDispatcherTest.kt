/*
 * Project Dogwood -- the Zipline dispatcher's failure modes, which are the interesting half.
 *
 * A dispatcher that runs blocks in order on one thread is easy to get right and was. What was
 * wrong is what happens when it stops: `dispatch` was `sendChannel?.trySend(block)` with the
 * result discarded, so a block dispatched after `close()` -- or racing it -- simply vanished.
 *
 * A vanished block is a vanished *continuation*. Everything that crosses to the guest goes through
 * `withContext(ziplineDispatcher)`: the session swap, `snapshotState`, the shell's eviction. Any
 * of those, hit by a close, suspends and never resumes -- and cancelling it cannot help, because
 * the cancellation resume is dispatched to the same dead channel and dropped too. The coroutine
 * becomes uncompletable, and it holds a `DogwoodExperience`, which holds an interpreter and an
 * eight-megabyte stack. That is precisely the leak class Phase 6 built an instrument to detect.
 *
 * Loud beats hung, so these assert that it now throws.
 *
 * A note on the harness: `runTest` drives *virtual* time, which fast-forwards past any delay --
 * useless here, because the work being awaited happens on a real thread that a virtual clock does
 * not move. The awaits below therefore run inside `withContext(Dispatchers.Default)` so the
 * timeouts are real ones. A test that skipped this passes instantly and proves nothing.
 */
package dev.dogwood.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest

class ZiplineDispatcherTest {

  @Test
  fun workDispatchedAfterCloseFailsLoudlyRatherThanVanishing() {
    val dispatcher = DogwoodZiplineDispatcher(name = "test-closed")
    dispatcher.close()
    // The whole finding. Silently dropping this is what left coroutines uncompletable.
    assertFailsWith<IllegalStateException> {
      dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { })
    }
  }

  @Test
  fun aCoroutineCaughtByACloseFailsInsteadOfHanging() = runTest {
    val dispatcher = DogwoodZiplineDispatcher(name = "test-race")
    dispatcher.close()
    // Before the fix this suspended forever; the timeout is here so a regression fails the suite
    // rather than hanging it.
    withContext(Dispatchers.Default) {
      withTimeout(5_000) {
        assertFailsWith<IllegalStateException> { withContext(dispatcher) { 1 } }
      }
    }
  }

  @Test
  fun aBlockThatThrowsDoesNotKillTheThread() = runTest {
    // The drain loop had no catch, so one throwing block exited it while leaving the channel
    // OPEN: every later dispatch succeeded into a queue nobody was reading. Silent, and
    // indistinguishable from a hung guest.
    val failures = mutableListOf<Throwable>()
    val dispatcher = DogwoodZiplineDispatcher(name = "test-throw", onUncaught = { failures += it })
    try {
      dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { throw RuntimeException("boom") })
      val answered = CompletableDeferred<Int>()
      dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { answered.complete(42) })
      val got = withContext(Dispatchers.Default) { withTimeout(5_000) { answered.await() } }
      assertEquals(42, got, "the dispatcher stopped after one throw")
      assertTrue(failures.any { it.message == "boom" }, "the throw must be reported, not swallowed")
    } finally {
      dispatcher.close()
    }
  }

  @Test
  fun orderingAndTheReentrancyFastPathStillHold() = runTest {
    // The parts that were already right and must not regress: FIFO, and `withContext` from the
    // dispatcher's own thread taking the undispatched path rather than deadlocking on itself.
    val dispatcher = DogwoodZiplineDispatcher(name = "test-order")
    try {
      val seen = mutableListOf<Int>()
      val done = CompletableDeferred<Unit>()
      repeat(50) { i -> dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { seen += i }) }
      dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { done.complete(Unit) })
      withContext(Dispatchers.Default) { withTimeout(5_000) { done.await() } }
      assertEquals((0 until 50).toList(), seen)

      val nested = withContext(Dispatchers.Default) {
        withTimeout(5_000) { withContext(dispatcher) { withContext(dispatcher) { "no deadlock" } } }
      }
      assertEquals("no deadlock", nested)
    } finally {
      dispatcher.close()
    }
  }
}

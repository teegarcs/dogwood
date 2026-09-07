/*
 * Project Dogwood -- leak detection in the browser, both directions.
 *
 * A detector that reports everything is as useless as one that reports nothing, so the negative
 * control is the load-bearing test here: an object that becomes unreachable must NOT be reported.
 * These run under Chrome's `--js-flags=--expose-gc` (see `karma.config.d/expose-gc.js`), because
 * the negative direction cannot be asserted without making a collection happen on demand.
 *
 * The two disciplines these tests encode, both learned by watching the probe report "alive" when
 * it should not have:
 *
 *   1. A `WeakRef` keeps its target alive for the whole of the current job, so the check has to
 *      cross a task boundary. `delay` does that.
 *   2. The subject must not be held in an inlining caller's frame -- `run { }` is inline, and the
 *      local survives into the caller. Hence the separate functions below, the same discipline
 *      `LeakTest.kt` and `CrossLanguageLeakTest.kt` document for the other platforms.
 */
package dev.dogwood.host

import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise

private class Subject(val note: String)

private fun forceGc() {
  js("(function () { if (typeof globalThis.gc === 'function') { globalThis.gc(); globalThis.gc(); } })()")
}

private fun canForceGc(): Boolean = js("typeof globalThis.gc === 'function'")

/** Watches an object and drops every strong reference to it before returning. */
private fun watchAndDrop(watcher: BrowserLeakWatcher) {
  watcher.watch(Subject("collectable"), "collectable")
}

class BrowserLeakWatcherTest {

  private val scope = CoroutineScope(Dispatchers.Default)

  @Test
  fun aRetainedReferenceIsReported(): Promise<JsAny?> = scope.promise {
    val reported = mutableListOf<String>()
    val retained = mutableListOf<Any>()
    val watcher = BrowserLeakWatcher(scope, leakThreshold = 900.milliseconds) { reported += it }

    val leaked = Subject("deliberately retained")
    retained += leaked
    watcher.watch(leaked, "deliberately retained")

    delay(200)
    forceGc()

    // Waits for the outcome rather than for a fixed span, and the difference is not stylistic.
    // The watcher's own threshold is 900 ms, so `delay(1_200)` left 500 ms of slack -- comfortable
    // on a development machine and not on a loaded continuous-integration runner, where this went
    // red. The assertion is unchanged: a watcher that never reports still fails, it just fails
    // after four seconds instead of after one.
    var waited = 0
    while (reported.isEmpty() && waited < 4_000) {
      delay(100)
      waited += 100
    }

    assertEquals(listOf("deliberately retained"), reported)
    assertTrue(retained.single() is Subject, "and it really is still alive, not merely reported")
    null
  }

  /**
   * The negative control, and the reason this file is worth having.
   *
   * Without it the test above passes on a watcher that calls `onLeak` for everything it is handed.
   */
  @Test
  fun aCollectableReferenceIsNotReported(): Promise<JsAny?> = scope.promise {
    val reported = mutableListOf<String>()
    // The threshold is long enough for the collection below to land inside it. A shorter one
    // reports the object as leaked and is *correct to*: nothing had collected it yet. That is the
    // detector's real contract -- "still reachable when asked" -- and the reason the threshold has
    // to outlast a plausible collection rather than merely a plausible leak.
    val watcher = BrowserLeakWatcher(scope, leakThreshold = 900.milliseconds) { reported += it }

    watchAndDrop(watcher)

    delay(200)
    forceGc()
    delay(1_200)

    assertEquals(
      emptyList(),
      reported,
      "an object nothing holds must not be called a leak; a detector that reports everything " +
        "teaches a team to ignore it",
    )
    null
  }

  @Test
  fun theTestBrowserCanActuallyCollect(): Promise<JsAny?> = scope.promise {
    // If this fails, the negative control above proves nothing -- it would pass on a browser that
    // never collects, for the wrong reason.
    assertTrue(canForceGc(), "the test browser has no forced collection; see karma.config.d")
    null
  }
}

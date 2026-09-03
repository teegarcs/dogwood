/*
 * Project Dogwood -- which experiences stay warm.
 *
 * The shell's policy, tested as policy. What to keep alive is a memory decision a product makes,
 * and a decision buried inside a coroutine is one nobody can check — so it lives in a class with
 * no interpreters in it, and these are the rules it has to obey.
 */
package dev.dogwood.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WarmPoolTest {

  @Test
  fun theActiveEntryIsTheMostRecentlyTouched() {
    val pool = WarmPool(capacity = 3)
    pool.touch("explore")
    pool.touch("feed")
    assertEquals("feed", pool.active)
    assertEquals(listOf("feed", "explore"), pool.warm)
  }

  @Test
  fun stayingUnderTheCapEvictsNothing() {
    val pool = WarmPool(capacity = 3)
    assertEquals(emptyList(), pool.touch("a"))
    assertEquals(emptyList(), pool.touch("b"))
    assertEquals(emptyList(), pool.touch("c"))
  }

  @Test
  fun exceedingTheCapEvictsTheLeastRecentlyUsed() {
    val pool = WarmPool(capacity = 2)
    pool.touch("a")
    pool.touch("b")
    assertEquals(listOf("a"), pool.touch("c"), "'a' was the coldest")
    assertEquals(listOf("c", "b"), pool.warm)
  }

  @Test
  fun returningToAWarmEntryEvictsNothingAndReordersIt() {
    // The case the shell exists for: back and forth between two surfaces must never evict.
    val pool = WarmPool(capacity = 2)
    pool.touch("a")
    pool.touch("b")
    assertEquals(emptyList(), pool.touch("a"))
    assertEquals(emptyList(), pool.touch("b"))
    assertEquals(listOf("b", "a"), pool.warm)
  }

  @Test
  fun aMemoryTrimNeverDropsWhatIsOnScreen() {
    // Answering a memory warning with a blank frame is not an improvement, so the active entry
    // survives even a request to keep none.
    val pool = WarmPool(capacity = 4)
    pool.touch("a")
    pool.touch("b")
    pool.touch("c")
    assertEquals(listOf("b", "a"), pool.trim(keep = 0))
    assertEquals(listOf("c"), pool.warm, "the active experience is still warm")
  }

  @Test
  fun aTrimToMoreThanIsWarmEvictsNothing() {
    val pool = WarmPool(capacity = 4)
    pool.touch("a")
    assertEquals(emptyList(), pool.trim(keep = 3))
  }

  @Test
  fun forgettingRemovesAnEntryWithoutDisturbingTheOrder() {
    val pool = WarmPool(capacity = 3)
    pool.touch("a")
    pool.touch("b")
    pool.touch("c")
    pool.forget("b")
    assertEquals(listOf("c", "a"), pool.warm)
    assertTrue(!pool.isWarm("b"))
  }

  @Test
  fun aCapacityBelowOneIsRefused() {
    // A shell that could not keep the active experience warm would cold-start every frame.
    assertFailsWith<IllegalArgumentException> { WarmPool(capacity = 0) }
  }

  @Test
  fun aCapacityOfOneKeepsOnlyTheActiveOne() {
    // The memory-constrained configuration: every switch is a snapshot-and-restore, which is
    // still correct and merely slower.
    val pool = WarmPool(capacity = 1)
    pool.touch("a")
    assertEquals(listOf("a"), pool.touch("b"))
    assertEquals(listOf("b"), pool.warm)
  }
  @Test
  fun anEntryOnScreenIsNeverEvictedByTheCap() {
    // The side-by-side case: a navigation rail composed beside a content pane. The rail is not the
    // most recently touched -- the user keeps tapping the content -- so recency alone would make
    // it the coldest thing in the pool and evict it while it is being looked at.
    val pool = WarmPool(capacity = 2)
    pool.mount("rail")
    pool.touch("content")
    // The cap is still honoured -- something has to go -- but the victim is the entry nobody is
    // looking at, not the coldest one. Recency alone would have chosen the rail.
    assertEquals(listOf("content"), pool.touch("other"))
    assertEquals(listOf("other", "rail"), pool.warm, "the rail is on screen and must survive")
  }

  @Test
  fun mountingBeyondTheCapOverrunsItRatherThanBlankingASurface() {
    // Deliberate, and the lesser of two wrongs. A host that composes more experiences than it
    // budgeted for should see the memory, not a pane that renders nothing.
    val pool = WarmPool(capacity = 1)
    pool.mount("a")
    pool.mount("b")
    assertEquals(setOf("a", "b"), pool.onScreen)
    assertEquals(listOf("b", "a"), pool.warm, "the cap is reported honestly as overrun")
  }

  @Test
  fun unmountingMakesAnEntryEvictableAgain() {
    // A cap of one with two entries on screen is the overrun case from the test above. Taking the
    // rail off screen is what lets the pool settle back to its cap, and it settles immediately
    // rather than waiting for the next activation.
    val pool = WarmPool(capacity = 1)
    pool.mount("rail")
    pool.touch("content")
    assertEquals(listOf("content", "rail"), pool.warm, "both on screen, so the cap is overrun")

    assertEquals(listOf("rail"), pool.unmount("rail"))
    assertEquals(listOf("content"), pool.warm)
  }

  @Test
  fun aMemoryTrimSpareEverythingOnScreen() {
    val pool = WarmPool(capacity = 4)
    pool.mount("rail")
    pool.touch("cold")
    pool.touch("content")
    assertEquals(listOf("cold"), pool.trim(keep = 0), "only the entry nobody can see")
    assertEquals(listOf("content", "rail"), pool.warm)
  }

  @Test
  fun aTrimWithEverythingOnScreenEvictsNothing() {
    // It reports that it could not help rather than overriding the host to hit a number.
    val pool = WarmPool(capacity = 3)
    pool.mount("a")
    pool.mount("b")
    assertEquals(emptyList(), pool.trim(keep = 0))
    assertEquals(2, pool.warm.size)
  }

  @Test
  fun forgettingAMountedEntryAlsoUnmountsIt() {
    // Otherwise a closed experience would go on protecting a key that names nothing, and the cap
    // would quietly stop being enforceable.
    val pool = WarmPool(capacity = 2)
    pool.mount("a")
    pool.forget("a")
    assertEquals(emptySet(), pool.onScreen)
    pool.touch("b")
    pool.touch("c")
    assertEquals(listOf("b"), pool.touch("d"))
  }

}

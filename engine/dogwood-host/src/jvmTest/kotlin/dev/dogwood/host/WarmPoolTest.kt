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
}

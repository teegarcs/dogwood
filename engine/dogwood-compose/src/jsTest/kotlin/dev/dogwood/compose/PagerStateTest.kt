/*
 * Project Dogwood -- the guest's half of the sixth holder shape.
 *
 * The rules this side owns: asking twice is two requests, the count comes from the host, presence
 * is claimed only by a real read, and a restored holder does not re-fire a request the user has
 * moved past.
 */
package dev.dogwood.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(DogwoodGeneratedApi::class)
class PagerStateTest {

  @Test
  fun nothingIsRequestedUntilSomethingIsAsked() {
    assertEquals(0, PagerState(0).targetSequence)
  }

  @Test
  fun aRestoredPositionIsARequestLikeAnyOther() {
    // Restoring is not a special path: a pager restored to page 4 must ask to be on page 4, or a
    // code update would leave the guest believing one thing and the host showing another.
    val restored = PagerState(4)
    assertEquals(4, restored.targetPage)
    assertEquals(1, restored.targetSequence)
  }

  @Test
  fun askingTwiceIsTwoRequests() {
    // The rule that makes "back to the first page" work after the user has swiped away: the page
    // number is the same, and a value-keyed effect would do nothing with it.
    val pager = PagerState(0)
    pager.animateScrollToPage(0)
    val first = pager.targetSequence
    pager.animateScrollToPage(0)
    assertEquals(first + 1, pager.targetSequence)
  }

  @Test
  fun aNegativePageIsRefusedRatherThanSent() {
    // The host would clamp it anyway; a request the guest can see is wrong is one it should not
    // make, and sending it would put a number on the wire that means nothing.
    val pager = PagerState(0)
    pager.animateScrollToPage(-3)
    assertEquals(0, pager.targetPage)
  }

  @Test
  fun theCountComesFromTheHost() {
    // A guest cannot count its own pages -- one behind an `if` changes the answer -- so until the
    // host reports, the count is honestly zero rather than guessed.
    val pager = PagerState(0)
    assertEquals(0, pager.pageCount)
    pager.report(page = 1, pageCount = 3, byUser = true)
    assertEquals(3, pager.pageCount)
    assertEquals(1, pager.currentPage)
    assertTrue(pager.lastChangeByUser)
  }

  @Test
  fun presenceIsClaimedOnlyWhenTheGuestActuallyReads() {
    val pager = PagerState(0)
    assertFalse(pager.watching, "a holder nobody read claimed to be watched")
    pager.page
    assertTrue(pager.watching)
  }

  @Test
  fun restoringCarriesThePositionAndNotTheRequestCount() {
    val pager = PagerState(0)
    pager.animateScrollToPage(2)
    pager.report(page = 2, pageCount = 3, byUser = false)

    val restored = PagerState.Saver.restore(listOf(pager.currentPage))!!

    assertEquals(2, restored.currentPage, "the position was lost")
    // One request -- the restore itself -- and not the guest's original three.
    assertEquals(1, restored.targetSequence)
  }
}

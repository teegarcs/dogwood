/*
 * Project Dogwood -- the guest's half of the fifth holder shape.
 *
 * The host mirror's tests assert what the platform does; these assert the rules the guest side owns
 * and a screen depends on: that asking twice is two requests, that an unknown reported state is
 * contained rather than trusted, that presence is only claimed when a composition actually reads
 * the position, and that a restored holder does not re-fire a request the user has moved past.
 */
package dev.dogwood.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(DogwoodGeneratedApi::class)
class SheetStateTest {

  private fun state(skipPartial: Boolean = false) =
    SheetState(SheetValues.HIDDEN, skipPartial)

  @Test
  fun nothingIsRequestedUntilSomethingIsAsked() {
    // Sequence zero is the "a holder exists, a request was not made" distinction the whole table
    // draws. Without it, a sheet would open the moment a screen remembered one.
    assertEquals(0, state().targetSequence)
  }

  @Test
  fun askingTwiceIsTwoRequests() {
    // The rule that makes a sheet reopenable after the user closes it. A flag would collapse the
    // second ask into nothing and leave a control that works once.
    val sheet = state()
    sheet.expand()
    val first = sheet.targetSequence
    sheet.expand()
    assertEquals(first + 1, sheet.targetSequence)
    assertEquals(SheetValues.EXPANDED, sheet.targetState)
  }

  @Test
  fun showRespectsWhetherThereIsAHalfStop() {
    // `skipPartiallyExpanded` changes what the gesture does, so it must change what `show` means --
    // a sheet with no half stop that was asked to go half open would sit in a position the user
    // cannot drag it out of.
    assertEquals(SheetValues.PARTIAL, state(skipPartial = false).also { it.show() }.targetState)
    assertEquals(SheetValues.EXPANDED, state(skipPartial = true).also { it.show() }.targetState)
  }

  @Test
  fun anUnknownReportedStateIsContainedAsHidden() {
    // Skew, in the shape this holder can meet it: a client one dictionary version AHEAD naming a
    // stop this guest has never heard of. Hidden is the safe direction -- a sheet nobody asked for
    // staying shut -- and it is why the vocabulary crosses as a string rather than an integer.
    val sheet = state()
    sheet.report("some-future-stop", byUser = true)
    assertEquals(SheetValues.HIDDEN, sheet.currentState)
  }

  @Test
  fun aReportSaysWhoMovedIt() {
    val sheet = state()
    sheet.report(SheetValues.EXPANDED, byUser = false)
    assertFalse(sheet.lastChangeByUser)
    sheet.report(SheetValues.HIDDEN, byUser = true)
    assertTrue(sheet.lastChangeByUser)
  }

  @Test
  fun presenceIsClaimedOnlyWhenTheGuestActuallyReads() {
    // The host cannot see guest closures, so it cannot know whether reporting would be observed.
    // A holder nobody reads must not cost traffic -- and the flag must not be set by merely
    // existing, or presence would mean nothing.
    val sheet = state()
    assertFalse(sheet.watching, "a holder nobody read claimed to be watched")
    sheet.isVisible
    assertTrue(sheet.watching)
  }

  @Test
  fun restoringCarriesThePositionAndNotTheRequest() {
    // The rule the focus requester established: a restored holder must not re-fire a request the
    // user has since moved past. What survives a code update is where the sheet *is*.
    val sheet = state()
    sheet.expand()
    sheet.report(SheetValues.EXPANDED, byUser = false)

    val restored = SheetState.Saver.restore(listOf(sheet.currentState, false))!!

    assertEquals(SheetValues.EXPANDED, restored.currentState, "the position was lost")
    assertEquals(0, restored.targetSequence, "a restored holder re-fired a request")
  }
}

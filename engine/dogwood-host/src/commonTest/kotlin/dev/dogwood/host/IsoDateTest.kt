/*
 * Project Dogwood -- the date arithmetic the pickers cross on.
 *
 * A date crosses as `yyyy-MM-dd` and the host turns it into the platform's millisecond form and
 * back. That conversion is exact integer arithmetic on a proleptic Gregorian calendar, hand-rolled
 * rather than reached for through a date library, because a platform formatter is locale-sensitive
 * and the wire form must not be — it is a calendar date, not a rendering of one.
 *
 * Hand-rolled arithmetic earns hostile tests. These are the cases that break naive implementations:
 * leap days, century rules, the epoch itself, and the far side of it.
 */
package dev.dogwood.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IsoDateTest {

  @Test
  fun theEpochIsDayZero() {
    assertEquals(0L, isoDateToEpochDay("1970-01-01"))
    assertEquals("1970-01-01", epochDayToIsoDate(0))
  }

  @Test
  fun everyRoundTripReturnsTheSameDay() {
    // The property that actually matters to a guest: what it sent is what comes back. Spread over
    // leap years, century boundaries, month ends and both sides of the epoch.
    val dates = listOf(
      "1969-12-31", "1970-01-01", "1972-02-29", "1999-12-31", "2000-02-29",
      "2000-03-01", "2026-03-14", "2026-12-31", "2100-02-28", "2400-02-29",
    )
    for (date in dates) {
      val day = isoDateToEpochDay(date)
      assertEquals(date, day?.let { epochDayToIsoDate(it) }, "round trip failed for $date")
    }
  }

  @Test
  fun theCenturyRuleIsRight() {
    // 1900 is not a leap year and 2000 is — the rule a naive `year % 4` gets wrong, and the reason
    // this is tested rather than trusted. If 2000-02-29 and 2000-03-01 were the same day, or a day
    // apart when they should be, every date after 2000 would be off by one.
    val feb29 = isoDateToEpochDay("2000-02-29")!!
    val mar01 = isoDateToEpochDay("2000-03-01")!!
    assertEquals(1L, mar01 - feb29)

    // And 1900 has no 29th of February at all, so the 28th and the 1st of March are adjacent.
    val feb28 = isoDateToEpochDay("1900-02-28")!!
    val marFirst = isoDateToEpochDay("1900-03-01")!!
    assertEquals(1L, marFirst - feb28)
  }

  @Test
  fun consecutiveDaysAreConsecutiveNumbers() {
    // Across a year boundary, where an off-by-one hides best.
    assertEquals(1L, isoDateToEpochDay("2027-01-01")!! - isoDateToEpochDay("2026-12-31")!!)
  }

  @Test
  fun malformedTextIsRefusedRatherThanGuessed() {
    // The picker opens on "the host's idea of today" when it gets null, which is the right
    // degradation for a payload sending something this client cannot parse. Guessing a date would
    // put a day the guest never named in front of a user.
    assertNull(isoDateToEpochDay(""))
    assertNull(isoDateToEpochDay("2026-03"))
    assertNull(isoDateToEpochDay("14/03/2026"))
    assertNull(isoDateToEpochDay("2026-13-01"))
    assertNull(isoDateToEpochDay("2026-03-32"))
    assertNull(isoDateToEpochDay("not-a-date"))
  }
}

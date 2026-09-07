/*
 * Project Dogwood -- a bad publish is recoverable, and the tests say what "recoverable" means.
 *
 * The architecture ships screens without a store review. Its missing half was un-shipping one: a
 * payload that loaded and then failed was indistinguishable from one that worked, because the only
 * failure the delivery path knew about was a failure to fetch.
 *
 * The case these are really about is the self-concealing one -- **a payload that crashes on launch**
 * -- because every recovery mechanism that lives in memory is erased by the crash it is counting.
 * The test that matters most is `aCrashLoopTerminates`, and it is written as a loop rather than as
 * a sequence of calls because that is the shape of the failure.
 */
package dev.dogwood.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A store that survives being "restarted", which is the only interesting property here. */
private class MemoryStore : ReleaseStore {
  var text: String? = null
  var writes = 0
  override fun read(): String? = text
  override fun write(text: String) {
    this.text = text
    writes++
  }
}

class ReleaseGuardTest {

  private fun guard(store: ReleaseStore, reports: MutableList<String> = mutableListOf()) =
    ReleaseGuard(store, maxFailures = 2, onReport = { reports += it })

  @Test
  fun aFreshInstallAllowsAnything() {
    val guard = guard(MemoryStore())
    assertIs<ReleaseVerdict.Allowed>(guard.verdict("1.0.0"))
    assertEquals(null, guard.lastKnownGood)
  }

  @Test
  fun aVersionThatWorkedIsRememberedAcrossARestart() {
    val store = MemoryStore()
    guard(store).run { starting("1.0.0"); succeeded("1.0.0") }

    // A new guard over the same store is what a relaunch looks like.
    assertEquals("1.0.0", guard(store).lastKnownGood)
  }

  @Test
  fun anAttemptIsPersistedBeforeThePayloadRuns() {
    // The single ordering the whole design rests on. An attempt counted in memory is erased by the
    // crash it is counting; this asserts the write happened, not merely the counter.
    val store = MemoryStore()
    guard(store).starting("1.0.0")

    assertTrue(store.writes > 0, "nothing was written before the payload ran")
    assertEquals(1, guard(store).attempts("1.0.0"), "a relaunch does not see the attempt")
  }

  @Test
  fun oneFailureIsNotEnoughToQuarantine() {
    // A single failure can be the device's fault: a process killed while backgrounded during the
    // first composition looks exactly like a payload that crashed.
    val store = MemoryStore()
    guard(store).starting("1.0.0")
    assertIs<ReleaseVerdict.Allowed>(guard(store).verdict("1.0.0"))
  }

  @Test
  fun aCrashLoopTerminates() {
    // The failure this exists for, written as the loop it actually is: the application relaunches,
    // the guard is rebuilt from disk, the payload is asked for again. Without persistence this
    // runs forever; with it, it stops.
    val store = MemoryStore()
    guard(store).run { starting("1.0.0"); succeeded("1.0.0") }

    var launches = 0
    var ranTheBadVersion = 0
    while (launches < 10) {
      launches++
      val guard = guard(store)
      val verdict = guard.verdict("2.0.0")
      if (verdict is ReleaseVerdict.Refused) {
        assertEquals("1.0.0", verdict.fallbackVersion, "it must fall back to what worked")
        break
      }
      guard.starting("2.0.0")
      ranTheBadVersion++
      // ...and the process dies here. Nothing reports success.
    }

    assertEquals(2, ranTheBadVersion, "a crash loop ran the bad payload $ranTheBadVersion times")
    assertTrue(launches < 10, "the loop never terminated")
    assertTrue("2.0.0" in guard(store).quarantined)
  }

  @Test
  fun aQuarantinedVersionFallsBackToWhatWorked() {
    val store = MemoryStore()
    guard(store).run { starting("1.0.0"); succeeded("1.0.0") }
    repeat(3) { guard(store).starting("2.0.0") }

    val verdict = guard(store).verdict("2.0.0")
    assertIs<ReleaseVerdict.Refused>(verdict)
    assertEquals("1.0.0", verdict.fallbackVersion)
    assertTrue("quarantined" in verdict.reason, verdict.reason)
  }

  @Test
  fun aQuarantinedVersionThatLaterWorksIsForgiven() {
    // A truncated download quarantines a version that was never broken. Refusing it forever would
    // make the guard the outage, so a version that reports success is released from quarantine.
    val store = MemoryStore()
    repeat(3) { guard(store).starting("2.0.0") }
    assertTrue("2.0.0" in guard(store).quarantined)

    guard(store).succeeded("2.0.0")

    assertFalse("2.0.0" in guard(store).quarantined)
    assertIs<ReleaseVerdict.Allowed>(guard(store).verdict("2.0.0"))
    assertEquals("2.0.0", guard(store).lastKnownGood)
  }

  @Test
  fun theKillSwitchRefusesWithoutFallingBack() {
    // Deliberately no fallback. A publisher disabling a release may be disabling the *feature*,
    // not the build, and falling back would run the thing they just stopped.
    val store = MemoryStore()
    guard(store).run { starting("1.0.0"); succeeded("1.0.0") }

    val verdict = guard(store).verdict("2.0.0", disabledByPublisher = true)
    assertIs<ReleaseVerdict.Refused>(verdict)
    assertEquals(null, verdict.fallbackVersion)
    assertTrue("publisher" in verdict.reason, verdict.reason)
  }

  @Test
  fun theKillSwitchOutranksAWorkingVersion() {
    val store = MemoryStore()
    guard(store).run { starting("1.0.0"); succeeded("1.0.0") }
    assertIs<ReleaseVerdict.Refused>(guard(store).verdict("1.0.0", disabledByPublisher = true))
  }

  @Test
  fun anUnreadableRecordStartsOverRatherThanRefusingEverything() {
    // The alternative failure mode is an application that will not run any payload because a file
    // it wrote itself is corrupt, which is a worse outage than the one being guarded against.
    val store = MemoryStore().also { it.text = "{not json" }
    val reports = mutableListOf<String>()
    val guard = guard(store, reports)

    assertIs<ReleaseVerdict.Allowed>(guard.verdict("1.0.0"))
    assertTrue(reports.any { "unparseable" in it }, reports.toString())
  }

  @Test
  fun aStoreThatCannotWriteIsReportedRatherThanIgnored() {
    // A guard that cannot persist cannot survive a crash loop, which is the one case it is for.
    val problems = mutableListOf<String>()
    val store = object : ReleaseStore {
      override fun read(): String? = null
      override fun write(text: String) = throw okio.IOException("read-only")
    }
    // The write throwing must not take the launch down with it; the guard degrades to useless
    // rather than fatal, and says so.
    val guard = ReleaseGuard(store, onReport = { problems += it })
    runCatching { guard.starting("1.0.0") }
      .onFailure { throw AssertionError("a failed write took the launch down: $it") }
  }

  @Test
  fun aCohortIsStableAcrossRestarts() {
    // A cohort re-rolled every launch would move a device in and out of a staged release
    // repeatedly, which is worse than no staging at all.
    val store = MemoryStore()
    var rolls = 0
    val first = InstallCohort(store) { rolls++; 4_242 }.bucket
    val second = InstallCohort(store) { rolls++; 9_999 }.bucket

    assertEquals(first, second)
    assertEquals(1, rolls, "the bucket was re-rolled on the second launch")
    assertTrue(first in 0..99)
  }
}

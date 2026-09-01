/*
 * Project Dogwood -- tags a segment's generator does not own.
 *
 * A dictionary segment is not necessarily all generated. Dogwood's design-system segment carries
 * two hand-written lazy containers alongside its generated components, because the generator does
 * not model lazy layouts yet. The generator allocates by position and knows nothing about that
 * file, so without a reservation it will eventually hand a new component a tag a hand-written
 * binding already answers to.
 *
 * That is not hypothetical. Adding `Icon` as the tenth component took local tag 10, which
 * `VerticalList` already owns — and because the generated dispatch runs first, every vertical list
 * on every screen would have rendered as an icon. A tag collision does not fail to render; it
 * renders the wrong widget, which is the failure this whole locking mechanism exists to prevent.
 */
package dev.dogwood.codegen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val THREE = """
  package acme.design
  import androidx.compose.runtime.Composable

  @Composable fun AcmeA(text: String) {}
  @Composable fun AcmeB(text: String) {}
  @Composable fun AcmeC(text: String) {}
"""

private const val FOUR = """
  package acme.design
  import androidx.compose.runtime.Composable

  @Composable fun AcmeA(text: String) {}
  @Composable fun AcmeB(text: String) {}
  @Composable fun AcmeC(text: String) {}
  @Composable fun AcmeD(text: String) {}
"""

private fun dictionary(source: String, version: Int, reserved: Set<Int> = emptySet()) =
  buildDictionary("acme", 1, version, SurfaceParser().parse(source, "Surface.kt"), reserved)

private fun tempLock(): File =
  File.createTempFile("dogwood-lock", ".json").also { it.delete() }

class ReservedTagTest {

  @Test
  fun theAllocatorSkipsReservedTags() {
    val d = dictionary(THREE, 1, reserved = setOf(2))
    assertEquals(
      listOf(1, 3, 4),
      d.components.map { it.localTag },
      "tag 2 belongs to a hand-written binding and must be stepped over, not shared",
    )
  }

  @Test
  fun aReservationDoesNotDisturbTagsAllocatedBeforeIt() {
    // The first component keeps tag 1 whether or not something later is reserved, which is what
    // makes adding a reservation safe for already-published components.
    assertEquals(1, dictionary(THREE, 1, reserved = setOf(3)).components.first().localTag)
    assertEquals(listOf(1, 2, 4), dictionary(THREE, 1, reserved = setOf(3)).components.map { it.localTag })
  }

  @Test
  fun reservationsAreRecordedInTheDictionarySoTheLockCanCheckThem() {
    assertEquals(listOf(2, 5), dictionary(THREE, 1, reserved = setOf(5, 2)).reservedLocalTags)
  }

  @Test
  fun aGeneratedTagLandingOnAReservedOneFailsTheBuild() {
    // Belt and braces: the allocator already avoids this, but the allocator and the reservation
    // list are edited by different people at different times.
    val lock = tempLock()
    checkAgainstLock(dictionary(THREE, 1), lock)
    val colliding = dictionary(THREE, 1).copy(reservedLocalTags = listOf(2))
    val result = checkAgainstLock(colliding, lock)
    assertTrue(result is LockResult.Violated, "expected a violation, got $result")
    assertTrue(
      (result as LockResult.Violated).problems.any { it.contains("reserved") },
      result.problems.toString(),
    )
  }

  @Test
  fun aWithdrawnReservationFailsTheBuild() {
    // A reservation may be added but never removed. Withdrawing one hands the next component
    // added a tag that a hand-written binding has already published.
    val lock = tempLock()
    checkAgainstLock(dictionary(THREE, 1, reserved = setOf(9)), lock)
    val result = checkAgainstLock(dictionary(THREE, 2), lock)
    assertTrue(result is LockResult.Violated, "expected a violation, got $result")
    assertTrue(
      (result as LockResult.Violated).problems.any { it.contains("no longer is") },
      result.problems.toString(),
    )
  }

  @Test
  fun addingAComponentWithoutRaisingTheVersionFailsTheBuild() {
    // Guest code branches on the segment version to decide what it may use. A component clients
    // cannot detect is worse than no component: a payload that used it gets a placeholder with no
    // explanation.
    val lock = tempLock()
    checkAgainstLock(dictionary(THREE, 1), lock)
    val result = checkAgainstLock(dictionary(FOUR, 1), lock)
    assertTrue(result is LockResult.Violated, "expected a violation, got $result")
    assertTrue(
      (result as LockResult.Violated).problems.any { it.contains("segment version") },
      result.problems.toString(),
    )
  }

  @Test
  fun addingAComponentWithARaisedVersionIsAccepted() {
    val lock = tempLock()
    checkAgainstLock(dictionary(THREE, 1), lock)
    val result = checkAgainstLock(dictionary(FOUR, 2), lock)
    assertTrue(result is LockResult.Updated, "expected an update, got $result")
    assertEquals(listOf("AcmeD"), (result as LockResult.Updated).added)
  }
}

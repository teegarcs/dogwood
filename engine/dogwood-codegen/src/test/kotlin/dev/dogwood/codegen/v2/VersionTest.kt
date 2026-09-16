/*
 * Project Dogwood -- the version a generated tier declares (ADR-073).
 *
 * The encoding is the whole of the compatibility contract for a generated tier: it is the number
 * a payload puts in its signed manifest and the number a host compares before it agrees to run
 * anything. Everything here is about the ways that number could be wrong without anybody noticing
 * -- two library versions colliding on one integer, a pre-release that means two surfaces, a lock
 * that remembers a version the host no longer has.
 */
package dev.dogwood.codegen.v2

import dev.dogwood.codegen.Dictionary
import dev.dogwood.codegen.LockResult
import dev.dogwood.codegen.checkAgainstLock
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VersionTest {

  @Test
  fun aReleasedVersionEncodesToTheNumberAPayloadDeclares() {
    assertEquals(1_090_000, encodeLibraryVersion("1.9.0"))
    assertEquals(1_090_100, encodeLibraryVersion("1.9.1"))
    assertEquals(1_100_300, encodeLibraryVersion("1.10.3"))
    assertEquals(2_000_000, encodeLibraryVersion("2.0.0"))
  }

  /**
   * The second author, in the last two digits.
   *
   * A generator revision orders *after* the library version it belongs to and *before* the next
   * patch, which is the whole requirement: a host that has 1.9.0 at revision 0 must refuse a
   * payload built at revision 1, and a host at 1.9.1 must accept both.
   */
  @Test
  fun aGeneratorRevisionOrdersInsideItsLibraryVersion() {
    assertEquals(1_090_001, encodeLibraryVersion("1.9.0", revision = 1))
    assertTrue(encodeLibraryVersion("1.9.0", 1) > encodeLibraryVersion("1.9.0", 0))
    assertTrue(encodeLibraryVersion("1.9.1") > encodeLibraryVersion("1.9.0", 99))
    assertEquals("1.9.0", decodeLibraryVersion(encodeLibraryVersion("1.9.0", 7)))
    assertEquals(7, generatorRevisionOf(encodeLibraryVersion("1.9.0", 7)))
    val failure = assertFailsWith<IllegalArgumentException> { encodeLibraryVersion("1.9.0", 100) }
    assertTrue("collide" in failure.message!!, failure.message!!)
  }

  @Test
  fun theEncodingRoundTrips() {
    for (version in listOf("1.9.0", "1.10.3", "2.0.0", "0.0.1")) {
      assertEquals(version, decodeLibraryVersion(encodeLibraryVersion(version)))
    }
  }

  @Test
  fun versionsThatWouldCollideAreRefusedRatherThanTruncated() {
    // 1.100.0 and 2.0.0 would both be 20000, and two libraries sharing a segment version is the
    // one arithmetic failure this number cannot survive: a host would accept a payload built for
    // a surface it has never seen.
    val failure = assertFailsWith<IllegalArgumentException> { encodeLibraryVersion("1.100.0") }
    assertTrue("collide" in failure.message!!, failure.message!!)
    assertFailsWith<IllegalArgumentException> { encodeLibraryVersion("1.0.100") }
  }

  @Test
  fun aPreReleaseIsRefusedBecauseItIsRepublishedUnderItsOwnName() {
    val failure = assertFailsWith<IllegalArgumentException> { encodeLibraryVersion("1.10.0-beta01") }
    assertTrue("pre-release" in failure.message!!, failure.message!!)
    assertFailsWith<IllegalArgumentException> { encodeLibraryVersion("1.10.0+build7") }
  }

  @Test
  fun somethingThatIsNotAVersionSaysSo() {
    assertFailsWith<IllegalArgumentException> { encodeLibraryVersion("1.9") }
    assertFailsWith<IllegalStateException> { encodeLibraryVersion("1.nine.0") }
    assertFailsWith<IllegalArgumentException> { encodeLibraryVersion("") }
  }

  @Test
  fun theResolvedVersionsAreReadFromTheFileTheFetchWrote() {
    val file = File.createTempFile("versions", ".json")
    file.writeText("""{
      "foundation": "1.10.3",
      "material3": "1.9.0"
    }""")
    val versions = readResolvedVersions(file)
    assertEquals("1.9.0", versions["material3"])
    assertEquals(1_090_000, tierVersion(versions, "material3"))
    assertEquals(1_090_002, tierVersion(versions, "material3", revision = 2))
    val missing = assertFailsWith<IllegalStateException> { tierVersion(versions, "ui") }
    assertTrue("no version for" in missing.message!!, missing.message!!)
    file.delete()
  }

  /**
   * The downgrade refusal, watched rather than assumed.
   *
   * A host whose library went backwards would write a lower version over the lock, and every
   * payload in the field that declares the higher one would be refused at launch by a host that
   * used to run it. That is a decision; the default is to refuse it.
   */
  @Test
  fun aVersionGoingBackwardsIsRefusedUnlessItIsIntended() {
    // Deleted, not merely created: `checkAgainstLock` reads a lock that exists, and an empty file
    // exists. The first call below is the one that writes it.
    val lock = File.createTempFile("lock", ".json").also { it.delete() }
    val at10901 = dictionary(version = 1_090_100)
    assertTrue(checkAgainstLock(at10901, lock) is LockResult.Updated)

    val at10900 = dictionary(version = 1_090_000)
    val refused = checkAgainstLock(at10900, lock)
    assertTrue(refused is LockResult.Violated, "a downgrade must be refused, got $refused")
    assertTrue(refused.problems.single().contains("1090100"), refused.problems.toString())

    // The same run, said to be intended, writes.
    assertTrue(checkAgainstLock(at10900, lock, acceptDowngrade = true) !is LockResult.Violated)
    lock.delete()
  }

  private fun dictionary(version: Int) = Dictionary(
    segmentName = "material3",
    segmentId = 255,
    version = version,
    wireName = "androidx.material3",
    components = emptyList(),
  )
}

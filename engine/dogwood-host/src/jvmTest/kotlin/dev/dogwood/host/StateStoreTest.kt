/*
 * Project Dogwood -- state that has to survive a process, and the limits on it.
 *
 * Everything else about saved state is verified in memory, inside one process, because that is
 * where a code update and an eviction happen. This is the one that crosses a process boundary, so
 * it is also the one where a snapshot stops being a transient and becomes user data at rest. The
 * tests below are as much about what the store *refuses* as about what it carries.
 */
package dev.dogwood.host

import dev.dogwood.protocol.StateSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

private const val NOW = 1_800_000_000_000L

private fun snapshot(vararg pairs: Pair<String, String>) =
  StateSnapshot(pairs.associate { (k, v) -> k to listOf(JsonPrimitive(v)) })

class StateStoreTest {

  private fun store(
    fs: FakeFileSystem,
    maxBytes: Long = 256L * 1024,
    maxAge: Long = 24L * 60 * 60 * 1000,
    onProblem: (String) -> Unit = {},
  ) = DogwoodStateStore("/state/dogwood.json".toPath(), fs, maxBytes, maxAge, onProblem)

  @Test
  fun stateWrittenByOneProcessIsReadableByTheNext() {
    val fs = FakeFileSystem()
    store(fs).write(mapOf("explore" to snapshot("address" to "10 Downing")), NOW)
    // A different store object over the same file: the same relationship two processes have.
    val restored = store(fs).consume(NOW + 60_000)
    assertEquals(setOf("explore"), restored.keys)
    assertEquals(1, restored.getValue("explore").values.size)
  }

  @Test
  fun readingConsumesIt() {
    // It exists to cross exactly one gap. Left in place, it would put a user back into a form they
    // had already finished with, after the next clean exit.
    val fs = FakeFileSystem()
    store(fs).write(mapOf("explore" to snapshot("a" to "b")), NOW)
    assertTrue(store(fs).consume(NOW).isNotEmpty())
    assertTrue(store(fs).consume(NOW).isEmpty(), "a second read must find nothing")
  }

  @Test
  fun stateOlderThanTheLimitIsIgnored() {
    // Returning to a form a day later and finding it half-filled is stranger than finding it empty.
    val fs = FakeFileSystem()
    val problems = mutableListOf<String>()
    store(fs).write(mapOf("explore" to snapshot("a" to "b")), NOW)
    val restored = store(fs, onProblem = { problems += it }).consume(NOW + 25L * 60 * 60 * 1000)
    assertTrue(restored.isEmpty())
    assertTrue(problems.any { "past the" in it }, problems.toString())
  }

  @Test
  fun aClockThatWentBackwardsIsTreatedAsStaleRatherThanFresh() {
    // Device clocks move. A negative age is not "very fresh"; it is a snapshot we cannot reason
    // about, and the safe reading is to discard it.
    val fs = FakeFileSystem()
    store(fs).write(mapOf("explore" to snapshot("a" to "b")), NOW)
    assertTrue(store(fs).consume(NOW - 60_000).isEmpty())
  }

  @Test
  fun anOversizedSnapshotIsDroppedWholeRatherThanTruncated() {
    // A realistic snapshot is tens of bytes. A large one means a guest is saving something it
    // should not, and a partial restore would put a screen into a state its guest never composed.
    val fs = FakeFileSystem()
    val problems = mutableListOf<String>()
    val big = snapshot("blob" to "x".repeat(2000))
    store(fs, maxBytes = 512, onProblem = { problems += it }).write(mapOf("e" to big), NOW)
    assertTrue(store(fs).consume(NOW).isEmpty(), "nothing should have been written")
    assertTrue(problems.any { "over the" in it }, problems.toString())
  }

  @Test
  fun theCapIsBytesNotCharacters() {
    // `maxBytes` was checked against `encoded.length` -- the number of UTF-16 characters -- while
    // the file is written with `writeUtf8`. Non-Latin state therefore overran the cap it claimed
    // to enforce by up to three or four times, and the refusal message reported characters as
    // bytes. The cap exists to stop a guest bug becoming a disk problem, so counting the wrong
    // unit is the whole failure.
    val fs = FakeFileSystem()
    val problems = mutableListOf<String>()
    // 200 characters of Japanese: 200 by `length`, 600 by UTF-8.
    val text = "\u3042".repeat(200)
    store(fs, maxBytes = 400, onProblem = { problems += it }).write(mapOf("e" to snapshot("k" to text)), NOW)
    assertTrue(store(fs).consume(NOW).isEmpty(), "a 600-byte payload must not pass a 400-byte cap")
    assertTrue(problems.any { "over the" in it }, problems.toString())
  }

  @Test
  fun anUnreadableStoreDegradesToNoStateRatherThanThrowing() {
    // A snapshot written by a build whose format has since changed. Skew, not a crash: the user
    // loses their half-typed form, which is exactly what happened before any of this existed.
    val fs = FakeFileSystem()
    val path = "/state/dogwood.json".toPath()
    fs.createDirectories(path.parent!!)
    fs.write(path) { writeUtf8("this is not the state you are looking for") }
    val problems = mutableListOf<String>()
    assertTrue(store(fs, onProblem = { problems += it }).consume(NOW).isEmpty())
    assertTrue(problems.any { "decode" in it }, problems.toString())
    assertTrue(!fs.exists(path), "and the unreadable file is cleared rather than retried forever")
  }

  @Test
  fun writingNothingClearsWhateverWasThere() {
    val fs = FakeFileSystem()
    store(fs).write(mapOf("explore" to snapshot("a" to "b")), NOW)
    store(fs).write(emptyMap(), NOW)
    assertTrue(store(fs).consume(NOW).isEmpty())
  }
}

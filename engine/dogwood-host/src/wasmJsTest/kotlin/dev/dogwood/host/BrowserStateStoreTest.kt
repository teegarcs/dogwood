/*
 * Project Dogwood -- saved state on the web really survives, run in a real browser.
 *
 * ADR-010's claim is that a screen's saveable state outlives the process. On the web the process
 * is a tab, and the only place that claim can be tested is a browser with real `localStorage`;
 * these run in headless Chrome for that reason rather than for symmetry with the other hosts.
 *
 * `DogwoodStateStore` is common code, so what is under test is the file system beneath it -- and,
 * through it, that the store's own rules hold on this platform: consume-once, the staleness
 * window, and bytes surviving as bytes.
 */
package dev.dogwood.host

import dev.dogwood.protocol.StateSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath

private fun snapshot(value: String) =
  StateSnapshot(mapOf("field" to listOf(JsonPrimitive(value))))

private fun Map<String, StateSnapshot>.field(key: String) =
  getValue(key).values.getValue("field").single()

class BrowserStateStoreTest {

  private val path = "/dogwood-test/state.json".toPath()

  private fun store(maxAgeMillis: Long = 24L * 60 * 60 * 1000) =
    DogwoodStateStore(path, maxAgeMillis = maxAgeMillis)

  private fun freshStore() = store().also { it.clear() }

  @Test
  fun browserStorageIsReachableAtAll() {
    // If this fails, every other assertion in this file is about an in-memory file system and
    // proves nothing -- so it is asserted rather than assumed.
    assertTrue(browserStorageAvailable(), "localStorage is not reachable in this browser")
  }

  @Test
  fun stateWrittenByOneStoreIsReadByAnother() {
    // Two stores over one path is what surviving a process looks like from inside a test: the
    // second has none of the first's memory and must find the bytes on the device. This is the
    // assertion the whole file exists for -- before this file, the web actual was in-memory and
    // this would have returned nothing.
    freshStore().write(mapOf("explore" to snapshot("kyoto")), nowEpochMillis = 1_000)

    val restored = store().consume(nowEpochMillis = 2_000)

    assertEquals(setOf("explore"), restored.keys)
    assertEquals(JsonPrimitive("kyoto"), restored.field("explore"))
  }

  @Test
  fun stateIsConsumedRatherThanMerelyRead() {
    // The store's own rule, exercised on this platform's storage: a snapshot crosses exactly one
    // gap, so reading it takes it. Restoring twice would put a user back into a form they had
    // already finished with.
    freshStore().write(mapOf("explore" to snapshot("kyoto")), nowEpochMillis = 1_000)

    assertEquals(setOf("explore"), store().consume(nowEpochMillis = 1_100).keys)
    assertEquals(emptyMap(), store().consume(nowEpochMillis = 1_200))
  }

  @Test
  fun anAbsentStoreReadsEmptyRatherThanThrowing() {
    freshStore()
    assertEquals(emptyMap(), store().consume(nowEpochMillis = 1_000))
  }

  @Test
  fun aLaterWriteReplacesAnEarlierOne() {
    val writer = freshStore()
    writer.write(mapOf("explore" to snapshot("kyoto")), nowEpochMillis = 1_000)
    writer.write(mapOf("explore" to snapshot("reykjavik")), nowEpochMillis = 1_100)

    assertEquals(JsonPrimitive("reykjavik"), store().consume(nowEpochMillis = 1_200).field("explore"))
  }

  @Test
  fun staleStateIsIgnored() {
    // Returning to a form a day later and finding it half-filled is stranger than finding it
    // empty. The window is the store's; that it is applied to what browser storage returned is
    // this test's business.
    freshStore().write(mapOf("explore" to snapshot("kyoto")), nowEpochMillis = 0)

    assertEquals(emptyMap(), store(maxAgeMillis = 60_000).consume(nowEpochMillis = 3_600_000))
  }

  @Test
  fun clearRemovesIt() {
    val writer = freshStore()
    writer.write(mapOf("explore" to snapshot("kyoto")), nowEpochMillis = 1_000)
    writer.clear()

    assertEquals(emptyMap(), store().consume(nowEpochMillis = 1_100))
  }

  @Test
  fun anEmptyWriteClearsTheStore() {
    val writer = freshStore()
    writer.write(mapOf("explore" to snapshot("kyoto")), nowEpochMillis = 1_000)
    writer.write(emptyMap(), nowEpochMillis = 1_100)

    assertEquals(emptyMap(), store().consume(nowEpochMillis = 1_200))
  }

  /**
   * Bytes survive as bytes.
   *
   * `localStorage` stores strings and a snapshot is bytes, which is why `BrowserFileSystem`
   * round-trips through base64 rather than through text. This is the case that would have caught
   * the shortcut: it works until somebody saves an emoji.
   */
  @Test
  fun nonAsciiSurvivesTheRoundTrip() {
    val text = "Reykjavík 🇮🇸 東京"
    freshStore().write(mapOf("explore" to snapshot(text)), nowEpochMillis = 1_000)

    assertEquals(JsonPrimitive(text), store().consume(nowEpochMillis = 1_100).field("explore"))
  }

  @Test
  fun severalExperiencesRoundTripTogether() {
    freshStore().write(
      mapOf(
        "explore" to snapshot("kyoto"),
        "feed" to snapshot("top"),
        "about" to snapshot("open"),
      ),
      nowEpochMillis = 1_000,
    )

    val restored = store().consume(nowEpochMillis = 1_100)
    assertEquals(setOf("explore", "feed", "about"), restored.keys)
    assertEquals(JsonPrimitive("top"), restored.field("feed"))
  }
}

/*
 * Project Dogwood -- a position mirror over a continuous quantity, as tests.
 *
 * `LazyListState` reports per item, and can, because a list has items. A scrolling container has a
 * length that changes every frame, which is the quantity Layer 4 forbids the guest to hold, so the
 * guest declares the quantum and it crosses as a property (ADR-044).
 *
 * These pin the guest half: the quantum reaches the host, targets cross as ADR-014 says they must,
 * the end is asked for as an intent rather than a number, and a code update does not send the
 * reader back to the top. The host half -- where the quantising and the exact ends actually happen
 * -- is verified on a device, for the reason ADR-014 already records.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.StateSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** `ScrollArea`'s property tags, appended by the generator after the declared `horizontal`. */
private const val TARGET_DP = 2
private const val SEQUENCE = 3
private const val ANIMATED = 4
private const val WATCHING = 5
private const val QUANTUM_DP = 6

private fun RecordingHost.properties(tag: Int): List<PropertySet> =
  decoded().flatMap { it.g }.filterIsInstance<PropertySet>().filter { it.p.value == tag }

private fun RecordingHost.ints(tag: Int): List<Int> =
  properties(tag).mapNotNull { it.v.jsonPrimitive.intOrNull }

private fun RecordingHost.lastBoolean(tag: Int): Boolean? =
  properties(tag).lastOrNull()?.v?.jsonPrimitive?.booleanOrNull

class ScrollStateTest {

  private var captured: ScrollState? = null

  @Composable
  private fun Page(scroll: ScrollState?) {
    ScrollArea(scroll = scroll) { Text("body") }
  }

  private fun RecordingHost.scrollNode() = decoded().first().g
    .filterIsInstance<dev.dogwood.protocol.Create>()
    .first { it.w.value == dev.dogwood.protocol.widgetTag(1, 15).value }
    .i

  @Test
  fun aContainerWithoutAHolderSendsNoScrollPropertiesAtAll() {
    // It still scrolls under the user's finger -- that is the host's modifier, not a property.
    // What it does not do is report, or accept a target.
    val (host, _) = compose { Page(null) }
    assertTrue(host.properties(SEQUENCE).isEmpty())
    assertTrue(host.properties(WATCHING).isEmpty())
  }

  @Test
  fun aHolderDeclaresItselfWatchingAndSaysHowOftenToReport() {
    // The quantum is the whole design: there is no natural boundary in a continuous offset, so the
    // guest names one and pays for it visibly.
    val (host, _) = compose { Page(ScrollState(reportEveryDp = 120)) }
    assertEquals(true, host.lastBoolean(WATCHING))
    assertEquals(listOf(120), host.ints(QUANTUM_DP))
  }

  @Test
  fun theDefaultQuantumCrossesRatherThanBeingAssumed() {
    // The host has a default too, and the two agreeing today is not a contract. Sending it means a
    // guest and a host that disagree produce a wrong *number*, not a silently different one.
    val (host, _) = compose { Page(ScrollState()) }
    assertEquals(listOf(48), host.ints(QUANTUM_DP))
  }

  @Test
  fun aScrollTargetCrossesAsAnOffsetAndASequence() {
    lateinit var scroll: ScrollState
    val (host, composition) = compose {
      scroll = rememberScrollState()
      Page(scroll)
    }

    scroll.scrollTo(640)
    composition.frame(0L)

    assertEquals(640, host.ints(TARGET_DP).last())
    assertEquals(listOf(0, 1), host.ints(SEQUENCE))
    assertEquals(false, host.lastBoolean(ANIMATED))
  }

  @Test
  fun theEndIsAskedForAsAnIntentRatherThanANumber() {
    // A guest cannot compute the end: the maximum is host layout and the guest's copy of it is as
    // stale as its last report. Sending a number would scroll to where the end *was*, which for
    // the case this exists for -- content that grows while you watch it -- is the wrong place.
    lateinit var scroll: ScrollState
    val (host, composition) = compose {
      scroll = rememberScrollState()
      Page(scroll)
    }

    scroll.animateScrollToEnd()
    composition.frame(0L)

    assertEquals(SCROLL_TO_END, host.ints(TARGET_DP).last())
    assertEquals(true, host.lastBoolean(ANIMATED))
  }

  @Test
  fun askingTwiceForTheSamePlaceIsTwoRequests() {
    lateinit var scroll: ScrollState
    val (host, composition) = compose {
      scroll = rememberScrollState()
      Page(scroll)
    }

    scroll.scrollToTop()
    composition.frame(0L)
    scroll.scrollToTop()
    composition.frame(16L)

    assertEquals(listOf(0, 1, 2), host.ints(SEQUENCE))
  }

  @Test
  fun theNewestTargetWinsAndAStaleOneCannotArrive() {
    lateinit var scroll: ScrollState
    val (host, composition) = compose {
      scroll = rememberScrollState()
      Page(scroll)
    }
    val batches = host.batches.size

    scroll.scrollTo(2000)
    scroll.scrollTo(0)
    composition.frame(0L)

    assertEquals(batches + 1, host.batches.size, "one composition pass, one batch")
    assertEquals(0, host.ints(TARGET_DP).last())
    assertEquals(listOf(0, 2), host.ints(SEQUENCE), "both counted, only the last crossed")
  }

  @Test
  fun aScrollReportReachesTheHolder() {
    lateinit var scroll: ScrollState
    val (host, composition) = compose {
      scroll = rememberScrollState()
      Page(scroll)
    }

    composition.sendEvent(
      Event(
        i = host.scrollNode(),
        e = EventTag(1),
        q = composition.lastSentSequence,
        a = listOf(JsonPrimitive(480), JsonPrimitive(1200), JsonPrimitive(true)),
      ),
    )

    assertEquals(480, scroll.offsetDp)
    assertEquals(1200, scroll.maxOffsetDp)
    assertTrue(scroll.isScrollInProgress)
    assertFalse(scroll.isAtTop)
    assertFalse(scroll.isAtBottom)
    assertTrue(scroll.isNearEnd(withinDp = 800))
  }

  @Test
  fun bothEndsAreRecognisedFromWhatTheHostReports() {
    // The reason the host reports the true value at the ends rather than a quantised one. A guest
    // asks "am I at the bottom?" to fetch the next page; if that were answerable only when the
    // scroll happened to land on a multiple of the quantum, it would be answerable only by luck.
    lateinit var scroll: ScrollState
    val (host, composition) = compose {
      scroll = rememberScrollState()
      Page(scroll)
    }
    val node = host.scrollNode()

    fun report(offset: Int, max: Int) = composition.sendEvent(
      Event(
        i = node,
        e = EventTag(1),
        q = composition.lastSentSequence,
        a = listOf(JsonPrimitive(offset), JsonPrimitive(max), JsonPrimitive(false)),
      ),
    )

    report(0, 1200)
    assertTrue(scroll.isAtTop)
    assertFalse(scroll.isAtBottom)

    report(1200, 1200)
    assertFalse(scroll.isAtTop)
    assertTrue(scroll.isAtBottom)
  }

  @Test
  fun beforeTheFirstReportNeitherEndIsClaimed() {
    // -1 rather than 0, so a guest can tell "the host has not told me yet" from "there is nothing
    // to scroll". A zero maximum here would make every page claim it was at the bottom before it
    // had laid out, and a paginating guest would fetch on sight.
    val scroll = ScrollState()
    assertEquals(-1, scroll.maxOffsetDp)
    assertFalse(scroll.isAtBottom)
    assertFalse(scroll.isNearEnd(withinDp = 10_000))
  }

  /** One call site, so both generations produce the same `rememberSaveable` composite key. */
  @Composable
  private fun Article() {
    val scroll = rememberScrollState(reportEveryDp = 96)
    captured = scroll
    Page(scroll)
  }

  private fun article(host: RecordingHost, restored: StateSnapshot?) =
    DogwoodComposition(host, dev.dogwood.protocol.HostEnvironment(), emptyMap(), restored) {
      Article()
    }

  @Test
  fun theReadingPositionSurvivesAReplacementGuest() {
    // The property that matters. A code update while a screen is live is the normal case here, and
    // an article that jumped back to the top on every publish would make the normal case feel like
    // a crash.
    val first = RecordingHost()
    val composition = article(first, null)
    composition.sendEvent(
      Event(
        i = first.scrollNode(),
        e = EventTag(1),
        q = composition.lastSentSequence,
        a = listOf(JsonPrimitive(768), JsonPrimitive(2400), JsonPrimitive(false)),
      ),
    )
    composition.frame(0L)

    val carried: StateSnapshot = composition.snapshotState()
    composition.dispose()
    assertTrue(carried.values.isNotEmpty(), "nothing was captured, so nothing can be restored")

    val second = RecordingHost()
    captured = null
    val replacement = article(second, carried)
    val restored = checkNotNull(captured)

    assertEquals(768, restored.offsetDp, "the position must come back")
    // And it must come back as a *target*, so the host actually moves there. Restoring a number the
    // host never hears about restores nothing a reader could see.
    assertEquals(768, second.ints(TARGET_DP).last())
    assertEquals(1, second.ints(SEQUENCE).last())
    replacement.dispose()
  }

  @Test
  fun theQuantumSurvivesAReplacementGuestToo() {
    // It is a constructor argument rather than mutable state, so a saver that dropped it would give
    // the replacement the default -- and the container would quietly start reporting at a
    // granularity the guest never asked for, with nothing looking wrong.
    val first = RecordingHost()
    val composition = article(first, null)
    composition.frame(0L)
    val carried: StateSnapshot = composition.snapshotState()
    composition.dispose()

    val second = RecordingHost()
    captured = null
    val replacement = article(second, carried)

    assertEquals(96, checkNotNull(captured).reportEveryDp)
    assertEquals(96, second.ints(QUANTUM_DP).last())
    replacement.dispose()
  }

  @Test
  fun aColdStartDeclaresNoTarget() {
    val (host, _) = compose { Page(ScrollState()) }
    assertEquals(listOf(0), host.ints(SEQUENCE))
  }
}

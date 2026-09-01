/*
 * Project Dogwood -- guest-side windowing, as tests.
 *
 * The claim is a cost claim, so the tests are cost tests: a list of ten thousand rows must cross
 * in proportion to what is on screen, not in proportion to the list. Everything else here exists
 * to stop that being true by accident -- a window that is right but never moves, or moves by
 * rewriting itself, would satisfy the headline number and be useless.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import dev.dogwood.protocol.ChildAdd
import dev.dogwood.protocol.ChildRemove
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.PropertySet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val ITEM_COUNT = 7
private const val WINDOW_START = 8

private fun RecordingHost.lastInt(tag: Int): Int? =
  decoded().flatMap { it.g }.filterIsInstance<PropertySet>()
    .lastOrNull { it.p.value == tag }?.v?.jsonPrimitive?.intOrNull

class WindowSelectionTest {

  @Test
  fun beforeTheFirstReportAWindowIsSeededRatherThanLeftEmpty() {
    // An empty window would leave the host nothing to measure, so it would never report a
    // viewport, so the window would stay empty. A deadlock, not an empty screen.
    assertEquals(0..15, windowFor(itemCount = 1000, firstVisible = 0, lastVisible = -1, overscan = 6))
    assertEquals(0..4, windowFor(itemCount = 5, firstVisible = 0, lastVisible = -1, overscan = 6))
  }

  @Test
  fun anEmptyListHasAnEmptyWindow() {
    assertTrue(windowFor(0, 0, -1, 6).isEmpty())
  }

  @Test
  fun theWindowIsTheViewportPlusOverscanClampedToTheList() {
    assertEquals(94..116, windowFor(1000, 100, 110, overscan = 6))
    // Clamped at the start, so a list scrolled to the top does not ask for negative indices.
    assertEquals(0..16, windowFor(1000, 0, 10, overscan = 6))
    // And at the end.
    assertEquals(984..999, windowFor(1000, 990, 999, overscan = 6))
  }

  @Test
  fun overscanZeroIsLegalAndMeansExactlyTheViewport() {
    assertEquals(100..110, windowFor(1000, 100, 110, overscan = 0))
  }
}

class LazyListTrafficTest {

  private val rows = List(10_000) { "row $it" }

  @Composable
  private fun Feed(state: DogwoodLazyListState) {
    LazyVerticalList(
      items = rows,
      state = state,
      placeholder = { Spacer(modifier = DogwoodModifier.height(64)) },
    ) { _, value ->
      Text(value)
    }
  }

  @Test
  fun tenThousandRowsCrossAsAWindow() {
    // The whole point. Before windowing this was ten thousand `Create` changes; the host's
    // `LazyColumn` then rendered about ten of them.
    lateinit var state: DogwoodLazyListState
    val (host, _) = compose {
      state = rememberDogwoodLazyListState()
      Feed(state)
    }

    val creates = host.decoded().flatMap { it.g }.filterIsInstance<Create>()
    assertTrue(
      creates.size < 60,
      "a ten-thousand-row list crossed ${creates.size} node creations; the window is sixteen " +
        "items plus a placeholder and a container",
    )
    assertEquals(10_000, host.lastInt(ITEM_COUNT), "the host must still know the true length")
    assertEquals(0, host.lastInt(WINDOW_START))
  }

  @Test
  fun theWindowFollowsTheViewportReport() {
    lateinit var state: DogwoodLazyListState
    val (host, composition) = compose {
      state = rememberDogwoodLazyListState()
      Feed(state)
    }
    val listNode = host.decoded().first().g
      .filterIsInstance<Create>()
      .first { it.w == Tags.VerticalList }
      .i

    composition.sendEvent(
      Event(
        i = listNode,
        e = EventTag(1),
        q = composition.lastSentSequence,
        a = listOf(JsonPrimitive(500), JsonPrimitive(510), JsonPrimitive(false)),
      ),
    )
    composition.frame(0L)

    assertEquals(494, host.lastInt(WINDOW_START), "the window must follow the viewport")
    assertEquals(10_000, host.lastInt(ITEM_COUNT))
  }

  @Test
  fun scrollingOneRowCostsOneRemovalAndOneInsertion() {
    // Keying each item by its index is what buys this. Without it, sliding the window by one
    // rewrites every node in it -- which would make the traffic proportional to the window on
    // every frame of a fling rather than to the movement.
    lateinit var state: DogwoodLazyListState
    val (host, composition) = compose {
      state = rememberDogwoodLazyListState()
      Feed(state)
    }
    val listNode = host.decoded().first().g
      .filterIsInstance<Create>()
      .first { it.w == Tags.VerticalList }
      .i

    fun report(first: Int, last: Int) {
      composition.sendEvent(
        Event(
          i = listNode,
          e = EventTag(1),
          q = composition.lastSentSequence,
          a = listOf(JsonPrimitive(first), JsonPrimitive(last), JsonPrimitive(true)),
        ),
      )
      composition.frame(0L)
    }

    report(100, 110)
    val settled = host.batches.size
    report(101, 111)

    val moved = host.decoded().drop(settled).flatMap { it.g }
    val removes = moved.filterIsInstance<ChildRemove>().sumOf { it.n }
    val adds = moved.filterIsInstance<ChildAdd>().size
    assertEquals(1, removes, "one row left the window; got ${moved.map { it::class.simpleName }}")
    assertEquals(1, adds, "one row entered it")
  }

  @Test
  fun thePlaceholderCrossesOnceNotOncePerHiddenRow() {
    // There is no placeholder *pool* because there is no need for one: the guest sends the
    // template once and the host repeats it. A pool is what you need when the things being reused
    // are real platform widgets.
    val (host, _) = compose {
      LazyVerticalList(
        items = rows,
        placeholder = { Spacer(modifier = DogwoodModifier.height(64)) },
      ) { _, value -> Text(value) }
    }
    val placeholderSlot = host.decoded().flatMap { it.g }
      .filterIsInstance<ChildAdd>()
      .filter { it.s == Tags.Placeholder }
    assertEquals(1, placeholderSlot.size, "the placeholder template crossed ${placeholderSlot.size} times")
  }

  @Test
  fun aShortListStillCrossesEverything() {
    // Windowing must not make short lists worse. Sixteen is more than five, so the seed window
    // covers the whole thing and the host gets every row on the first batch.
    val (host, _) = compose {
      LazyVerticalList(
        items = listOf("a", "b", "c", "d", "e"),
        placeholder = { Spacer(modifier = DogwoodModifier.height(64)) },
      ) { _, value -> Text(value) }
    }
    val texts = host.decoded().flatMap { it.g }.filterIsInstance<PropertySet>()
      .filter { it.p.value == 1 }
      .map { it.v.toString().trim('"') }
    assertTrue(listOf("a", "b", "c", "d", "e").all { it in texts }, texts.toString())
    assertEquals(5, host.lastInt(ITEM_COUNT))
  }
}

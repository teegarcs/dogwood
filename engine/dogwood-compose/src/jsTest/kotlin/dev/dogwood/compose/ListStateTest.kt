/*
 * Project Dogwood -- the first live-state holder, as tests.
 *
 * The holder's whole shape follows from one constraint: scroll offset changes every frame, and
 * Layer 4 forbids per-frame state in the guest. So the guest declares targets and reads reports,
 * and these tests pin the two halves of that and the conflict rule between them.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.StateSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The list node is the second node created: the root's content slot holds it. */
private const val TARGET_INDEX = 3
private const val TARGET_SEQUENCE = 4
private const val OBSERVED = 5

private fun RecordingHost.propertyValues(tag: Int): List<Int> =
  decoded().flatMap { it.g }
    .filterIsInstance<PropertySet>()
    .filter { it.p.value == tag }
    .mapNotNull { it.v.jsonPrimitive.intOrNull }

private fun RecordingHost.lastProperty(tag: Int): PropertySet? =
  decoded().flatMap { it.g }.filterIsInstance<PropertySet>().lastOrNull { it.p.value == tag }

class LiveStateHolderTest {

  /** Written from composition so both generations can be driven through the same call site. */
  private var captured: LazyListState? = null

  @Composable
  private fun ListWith(state: LazyListState?) {
    VerticalList(state = state) { Text("row") }
  }

  @Test
  fun aListWithoutAHolderSaysItIsNotWatching() {
    // The host cannot see guest closures, so presence has to be a property. Without it every
    // list on every screen would pay for a viewport observer nobody reads.
    val (host, _) = compose { ListWith(null) }
    assertEquals(JsonPrimitive(false), host.lastProperty(OBSERVED)?.v)
    assertEquals(listOf(0), host.propertyValues(TARGET_SEQUENCE), "no target has been declared")
  }

  @Test
  fun aHolderDeclaresItselfWatching() {
    val (host, _) = compose { ListWith(LazyListState()) }
    assertEquals(JsonPrimitive(true), host.lastProperty(OBSERVED)?.v)
  }

  @Test
  fun aScrollTargetCrossesAsAnIndexAndASequence() {
    lateinit var state: LazyListState
    val (host, composition) = compose {
      state = rememberLazyListState()
      ListWith(state)
    }

    state.scrollToItem(12)
    composition.frame(0L)

    assertEquals(12, host.lastProperty(TARGET_INDEX)?.v?.jsonPrimitive?.intOrNull)
    assertEquals(1, host.lastProperty(TARGET_SEQUENCE)?.v?.jsonPrimitive?.intOrNull)
  }

  @Test
  fun askingTwiceForTheSamePlaceIsTwoRequests() {
    // A flag would make the second tap of "back to top" change no property and cross nothing,
    // and the user who scrolled away in between would stay where they were.
    lateinit var state: LazyListState
    val (host, composition) = compose {
      state = rememberLazyListState()
      ListWith(state)
    }

    state.scrollToItem(0)
    composition.frame(0L)
    state.scrollToItem(0)
    composition.frame(0L)

    assertEquals(listOf(0, 1, 2), host.propertyValues(TARGET_SEQUENCE))
  }

  @Test
  fun theNewestTargetWinsAndAStaleOneCannotArrive() {
    // The conflict rule, and it falls out of the channel rather than being enforced: a property
    // carries only its latest value, so two targets declared in one pass cross as one.
    lateinit var state: LazyListState
    val (host, composition) = compose {
      state = rememberLazyListState()
      ListWith(state)
    }
    val batchesBefore = host.batches.size

    state.scrollToItem(40)
    state.scrollToItem(0)
    composition.frame(0L)

    assertEquals(batchesBefore + 1, host.batches.size, "one composition pass, one batch")
    assertEquals(0, host.lastProperty(TARGET_INDEX)?.v?.jsonPrimitive?.intOrNull)
    assertEquals(
      listOf(0, 2),
      host.propertyValues(TARGET_SEQUENCE),
      "both declarations counted, but only the last one crossed",
    )
  }

  @Test
  fun aViewportReportReachesTheHolder() {
    lateinit var state: LazyListState
    val (host, composition) = compose {
      state = rememberLazyListState()
      ListWith(state)
    }
    // Node 1 is the list: node 0 is the root, and the root's content slot holds it.
    val listNode = host.decoded().first().g
      .filterIsInstance<dev.dogwood.protocol.Create>()
      .first { it.w == Tags.VerticalList }
      .i

    composition.sendEvent(
      Event(
        i = listNode,
        e = EventTag(1),
        q = composition.lastSentSequence,
        a = listOf(JsonPrimitive(4), JsonPrimitive(9), JsonPrimitive(true)),
      ),
    )

    assertEquals(4, state.firstVisibleItemIndex)
    assertEquals(9, state.lastVisibleItemIndex)
    assertTrue(state.isScrollInProgress)
  }

  @Test
  fun beforeTheFirstReportTheVisibleRangeIsUnknownRatherThanZero() {
    // -1 rather than 0, so a guest can tell "the host has not told me yet" from "the first item
    // is visible". A zero here would render "showing 1-1" on every list before it laid out.
    val state = LazyListState()
    assertEquals(0, state.firstVisibleItemIndex)
    assertEquals(-1, state.lastVisibleItemIndex)
  }

  @Test
  fun aReportThatSaysNothingNewCostsNoFrame() {
    lateinit var state: LazyListState
    val (host, composition) = compose {
      state = rememberLazyListState()
      Text("first ${state.firstVisibleItemIndex}")
      ListWith(state)
    }
    val listNode = host.decoded().first().g
      .filterIsInstance<dev.dogwood.protocol.Create>()
      .first { it.w == Tags.VerticalList }
      .i

    fun report(first: Int) = composition.sendEvent(
      Event(
        i = listNode,
        e = EventTag(1),
        q = composition.lastSentSequence,
        a = listOf(JsonPrimitive(first), JsonPrimitive(first + 5), JsonPrimitive(true)),
      ),
    )

    report(4)
    composition.frame(0L)
    val batches = host.batches.size

    report(4)
    composition.frame(16L)

    assertEquals(batches, host.batches.size, "an unchanged report must produce no traffic")
  }

  /**
   * One composable, used by both generations.
   *
   * Not a convenience. `rememberSaveable` keys itself on `currentCompositeKeyHash`, which is the
   * *path* through the composition and not just the local call site, so the same `Listing()`
   * invoked from two different places is two different keys and nothing is restored. That is a
   * real constraint on what survives a code update -- a refactor that moves a call site loses its
   * state -- and a test that quietly used two paths would have been testing the wrong thing.
   */
  @Composable
  private fun Listing() {
    val holder = rememberLazyListState()
    captured = holder
    ListWith(holder)
  }

  /** One call site, so both generations produce the same composite key. */
  private fun listing(host: RecordingHost, restored: StateSnapshot?) =
    DogwoodComposition(host, dev.dogwood.protocol.HostEnvironment(), emptyMap(), restored) {
      Listing()
    }

  @Test
  fun theListPositionSurvivesAReplacementGuest() {
    // The property that matters most. Layer 4 treats a code update while a screen is live as the
    // normal case; a list that jumped to the top on every publish would make the normal case feel
    // like a crash.
    val first = RecordingHost()
    val composition = listing(first, null)
    val state = checkNotNull(captured)
    val listNode = first.decoded().first().g
      .filterIsInstance<dev.dogwood.protocol.Create>()
      .first { it.w == Tags.VerticalList }
      .i
    composition.sendEvent(
      Event(
        i = listNode,
        e = EventTag(1),
        q = composition.lastSentSequence,
        a = listOf(JsonPrimitive(7), JsonPrimitive(12), JsonPrimitive(false)),
      ),
    )
    composition.frame(0L)

    val carried: StateSnapshot = composition.snapshotState()
    composition.dispose()
    assertTrue(carried.values.isNotEmpty(), "nothing was captured, so nothing can be restored")

    // A fresh composition, as a newly delivered guest would be.
    val second = RecordingHost()
    captured = null
    val replacement = listing(second, carried)
    val restored = checkNotNull(captured)

    assertEquals(7, restored.firstVisibleItemIndex, "the position must come back")
    // And it must come back as a *target*, so the host actually scrolls there. Restoring a
    // number the host never hears about would restore nothing a user could see.
    assertEquals(7, second.lastProperty(TARGET_INDEX)?.v?.jsonPrimitive?.intOrNull)
    assertEquals(1, second.lastProperty(TARGET_SEQUENCE)?.v?.jsonPrimitive?.intOrNull)
    replacement.dispose()
  }

  @Test
  fun aColdStartDeclaresNoTarget() {
    val (host, _) = compose { ListWith(LazyListState()) }
    assertEquals(0, host.lastProperty(TARGET_SEQUENCE)?.v?.jsonPrimitive?.intOrNull)
  }
}

// ---------------------------------------------------------------------------

/**
 * Node identity, guest side.
 *
 * specs/layer-5-host.md makes two demands of identifiers, and they exist for different reasons.
 * Monotonicity is about *events*: an identifier reused inside a live composition lets an event
 * aimed at a node that is gone land on whichever node inherited the number, which is a wrong tap
 * rather than a dropped one. Stability across a reorder is about *state*, and is asserted on the
 * host where the state actually lives.
 */
class NodeIdentityGuestTest {

  @Composable
  private fun Rows(labels: List<String>) {
    Column {
      for (label in labels) {
        androidx.compose.runtime.key(label) { Text(label) }
      }
    }
  }

  @Test
  fun identifiersAreNeverReusedAfterNodesAreRemoved() {
    var labels by androidx.compose.runtime.mutableStateOf(listOf("a", "b", "c"))
    val (host, composition) = compose { Rows(labels) }

    fun createdIds() = host.decoded().flatMap { it.g }
      .filterIsInstance<dev.dogwood.protocol.Create>()
      .map { it.i.value }

    val firstGeneration = createdIds()

    // Remove everything, then create a fresh set. A recorder that restarted its counter would
    // hand these new nodes the identifiers the old ones had.
    labels = emptyList()
    composition.frame(0L)
    labels = listOf("d", "e", "f")
    composition.frame(16L)

    val all = createdIds()
    assertEquals(all.size, all.toSet().size, "an identifier was reused: $all")
    assertTrue(
      all.drop(firstGeneration.size).all { it > firstGeneration.max() },
      "identifiers must be monotonic; got $all",
    )
  }
}

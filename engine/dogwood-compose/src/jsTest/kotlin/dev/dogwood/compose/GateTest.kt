/*
 * Project Dogwood -- the Phase 1 gate conditions, as tests.
 *
 * roadmap.md names three things the vertical slice must demonstrate. Two of them are properties
 * of the guest, and both had been written for but not demonstrated:
 *
 *   - "Node identity survives list reordering."
 *   - "A state change three guest-defined wrapper layers deep crosses as a single
 *      PropertyChange (the wrapper-scoping test in Layer 4 Milestone 4)."
 *
 * The second is the one that matters most, and Layer 5 ADR-006 says why: it is the property
 * that makes developer-defined composables free. If a wrapper re-emitted its children, every
 * abstraction a developer built would cost boundary traffic, and the architecture's central
 * promise -- write ordinary Compose -- would be false.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.dogwood.protocol.ChangeBatch
import dev.dogwood.protocol.ChildAdd
import dev.dogwood.protocol.ChildMove
import dev.dogwood.protocol.ChildRemove
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.DogwoodConfiguration
import dev.dogwood.protocol.DogwoodHost
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.ModifierSet
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.WidgetTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Captures what crossed, so a test can assert on the traffic rather than on the screen. */
private class RecordingHost : DogwoodHost {
  val batches = mutableListOf<String>()
  var frameRequests = 0

  override fun sendChanges(positionalBatch: String) {
    batches += positionalBatch
  }

  override fun requestFrame() {
    frameRequests++
  }

  override fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag) = Unit
  override fun onUnknownEventNode(id: Id, tag: EventTag) = Unit
  override fun handleUncaughtException(exception: Throwable) = throw exception
  override fun close() = Unit
}

private fun compose(content: @Composable () -> Unit): Pair<RecordingHost, DogwoodComposition> {
  val host = RecordingHost()
  val composition = DogwoodComposition(host, DogwoodConfiguration(), emptyMap(), null, content)
  return host to composition
}

/**
 * Reads the batches back through the same decoder the host uses, so the test asserts on what
 * genuinely crossed rather than on an in-memory structure that never went through the encoder.
 */
private fun RecordingHost.decoded(): List<ChangeBatch> = batches.map { decodeForTest(it) }

// ---------------------------------------------------------------------------

class WrapperScopingTest {

  /** Three developer-defined wrappers, none of which the host knows anything about. */
  @Composable
  private fun OuterWrapper(content: @Composable () -> Unit) {
    MiddleWrapper { content() }
  }

  @Composable
  private fun MiddleWrapper(content: @Composable () -> Unit) {
    InnerWrapper { content() }
  }

  @Composable
  private fun InnerWrapper(content: @Composable () -> Unit) {
    content()
  }

  @Test
  fun stateChangeThreeWrappersDeepCrossesAsOnePropertyChange() {
    var label by mutableStateOf("before")
    val (host, composition) = compose {
      OuterWrapper {
        Text(label)
      }
    }

    val initial = host.decoded()
    assertEquals(1, initial.size, "initial composition must produce exactly one batch")

    label = "after"
    composition.frame(0L)

    val batches = host.decoded()
    assertEquals(2, batches.size, "a state change must produce exactly one further batch")

    val changes = batches[1].g
    assertEquals(
      1,
      changes.size,
      "a change three wrapper layers deep must cross as ONE change, not as a re-emit of the " +
        "wrappers; got ${changes.map { it::class.simpleName }}",
    )
    val change = changes.single()
    assertTrue(change is PropertySet, "expected a PropertySet, got ${change::class.simpleName}")
    assertEquals("after", change.v.toString().trim('"'))
  }

  @Test
  fun anIdleCompositionProducesNoTraffic() {
    val (host, composition) = compose { Text("static") }
    val before = host.batches.size
    composition.frame(0L)
    composition.frame(16_666_666L)
    assertEquals(before, host.batches.size, "an idle composition must produce no batches at all")
  }
}

// ---------------------------------------------------------------------------

class NodeIdentityTest {

  @Composable
  private fun Items(items: List<Int>) {
    Column {
      for (item in items) {
        key(item) { Text("item $item") }
      }
    }
  }

  @Test
  fun reorderingAListMovesNodesRatherThanRecreatingThem() {
    var items by mutableStateOf(listOf(1, 2, 3, 4))
    val (host, composition) = compose { Items(items) }

    val created = host.decoded().single().g.filterIsInstance<Create>()
    // One Column plus four Texts.
    assertEquals(5, created.size)
    val textIds = created.filter { it.w == Tags.Text }.map { it.i }
    assertEquals(4, textIds.size)

    items = listOf(4, 1, 2, 3)
    composition.frame(0L)

    val batches = host.decoded()
    assertEquals(2, batches.size)
    val changes = batches[1].g

    assertTrue(
      changes.none { it is Create },
      "a reorder must not create nodes; identity is what `key` preserves. Got: " +
        changes.map { it::class.simpleName },
    )
    assertTrue(
      changes.none { it is ChildRemove },
      "a reorder must not remove nodes. Got: ${changes.map { it::class.simpleName }}",
    )
    assertTrue(
      changes.any { it is ChildMove },
      "a reorder must cross as a ChildMove. Got: ${changes.map { it::class.simpleName }}",
    )
  }

  @Test
  fun removingRowsPurgesTheirEventHandlers() {
    var rows by mutableStateOf(3)
    val (host, composition) = compose {
      Column {
        for (index in 0 until rows) {
          key(index) {
            Row(onClick = { /* captures index */ }) { Text("row $index") }
          }
        }
      }
    }
    assertEquals(3, composition.lambdaSlotCount, "each row registers one handler")

    rows = 1
    composition.frame(0L)

    assertEquals(
      1,
      composition.lambdaSlotCount,
      "removing rows must purge their closures depth-first; without it a feed that creates and " +
        "destroys ten thousand rows retains ten thousand closures",
    )
    assertTrue(host.decoded()[1].g.any { it is ChildRemove })
  }
}

// ---------------------------------------------------------------------------

class BatchShapeTest {

  @Test
  fun oneCompositionPassProducesExactlyOneBatchWithAMonotonicSequence() {
    var count by mutableStateOf(0)
    val (host, composition) = compose { Text("count $count") }

    repeat(3) {
      count += 1
      composition.frame(0L)
    }

    val batches = host.decoded()
    assertEquals(4, batches.size, "one initial batch plus one per state change")
    assertEquals(listOf(1, 2, 3, 4), batches.map { it.q }, "sequence numbers are monotonic from 1")
  }

  @Test
  fun createIsFollowedByItsPropertiesAndThenItsAttachment() {
    val (host, _) = compose { Column { Text("hello") } }
    val changes = host.decoded().single().g

    val createText = changes.indexOfFirst { it is Create && it.w == Tags.Text }
    val setText = changes.indexOfFirst { it is PropertySet }
    val attach = changes.indexOfFirst { it is ChildAdd && it !== changes.first() }
    assertTrue(createText < setText, "a node's properties are set after it is created")
    assertTrue(
      setText < changes.indexOfLast { it is ChildAdd },
      "a node is attached only after its initial properties are set, which is why the applier " +
        "attaches on the bottom-up pass",
    )
    assertTrue(attach >= 0)
  }

  @Test
  fun modifierChainsCrossInOrder() {
    val (host, _) = compose {
      Box(modifier = DogwoodModifier.padding(8).size(48).alpha(0.5f))
    }
    val chain = host.decoded().single().g.filterIsInstance<ModifierSet>().single()
    assertEquals(
      listOf(1, 4, 5),
      chain.e.map { it.t.local },
      "order is load-bearing: padding-then-size and size-then-padding lay out differently",
    )
  }
}

// ---------------------------------------------------------------------------

/**
 * Code update while a screen is live.
 *
 * Layer 4 calls this the normal case, not an edge one, and says plainly what happens without it:
 * "any code update loses scroll position, expanded rows, and half-typed text." These assert the
 * mechanism that stops that -- capture from the outgoing guest, restore into the incoming one.
 */
class StatePreservationTest {

  @Composable
  private fun Counter() {
    val count = rememberSaveable(key = "count", stateSaver = autoSaver()) { mutableStateOf(0) }
    val label = rememberSaveable(key = "label", stateSaver = autoSaver()) { mutableStateOf("a") }
    Column {
      Text("count ${count.value}")
      Text(label.value)
      PrimaryButton("increment", onClick = { count.value += 1 })
    }
  }

  @Test
  fun savedStateSurvivesAReplacementGuest() {
    val host = RecordingHost()
    val first = DogwoodComposition(host, DogwoodConfiguration(), emptyMap(), null) { Counter() }

    // Drive the state forward the way an interaction would.
    first.sendEvent(Event(i = Id(4), e = EventTag(1), q = first.lastSentSequence))
    first.sendEvent(Event(i = Id(4), e = EventTag(1), q = first.lastSentSequence))
    first.frame(0L)

    val carried = first.snapshotState()
    first.dispose()

    assertTrue(carried.values.isNotEmpty(), "nothing was captured, so nothing can be restored")

    // A fresh composition, as a newly delivered guest would be.
    val secondHost = RecordingHost()
    val second = DogwoodComposition(secondHost, DogwoodConfiguration(), emptyMap(), carried) {
      Counter()
    }

    val texts = secondHost.decoded().single().g
      .filterIsInstance<PropertySet>()
      .map { it.v.toString().trim('"') }
    assertTrue(
      texts.any { it == "count 2" },
      "the counter should have been restored to 2; the replacement guest rendered $texts",
    )
    second.dispose()
  }

  @Test
  fun aColdStartRestoresNothing() {
    val host = RecordingHost()
    val composition = DogwoodComposition(host, DogwoodConfiguration(), emptyMap(), null) { Counter() }
    val texts = host.decoded().single().g
      .filterIsInstance<PropertySet>()
      .map { it.v.toString().trim('"') }
    assertTrue(texts.any { it == "count 0" }, "a cold start begins at the initial value")
    assertTrue(composition.snapshotState().values.isNotEmpty())
    composition.dispose()
  }
}

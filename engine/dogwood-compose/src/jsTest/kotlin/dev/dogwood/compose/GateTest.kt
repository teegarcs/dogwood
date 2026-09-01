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
import dev.dogwood.protocol.ModifierElem
import dev.dogwood.protocol.ModifierSet
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.WidgetTag
import kotlin.test.Test
import kotlinx.serialization.json.JsonPrimitive
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
  var unknownNodes = 0
    private set

  override fun onUnknownEventNode(id: Id, tag: EventTag) {
    unknownNodes++
  }
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
 * `Event.args`.
 *
 * ADR-004 gave `Event` an argument list in its first draft. Nothing exercised it until a
 * component whose signature needed one, so it was schema that had never been executed -- the
 * kind of thing that is always fine right up until it is not.
 */
class EventArgumentTest {

  @Test
  fun anEventDeliversItsArgumentsToTheGuestLambda() {
    var received: Boolean? = null
    var selected by mutableStateOf(false)
    val (host, composition) = compose {
      Chip(text = "filter", selected = selected) { nowSelected ->
        received = nowSelected
        selected = nowSelected
      }
    }
    val chip = host.decoded().single().g.filterIsInstance<Create>().single { it.w == Tags.Chip }

    composition.sendEvent(
      Event(i = chip.i, e = EventTag(1), q = composition.lastSentSequence, a = listOf(JsonPrimitive(true))),
    )
    composition.frame(0L)

    assertEquals(true, received, "the argument the host sent must reach the guest's lambda")
    val update = host.decoded()[1].g.filterIsInstance<PropertySet>()
    assertTrue(
      // A boolean, not the string "true": the wire keeps the type.
      update.any { it.v == JsonPrimitive(true) },
      "and the resulting state change must cross back as a boolean: ${update.map { it.v }}",
    )
  }

  @Test
  fun anEventForAnUnknownNodeIsReportedRatherThanThrown() {
    val (host, composition) = compose { Text("static") }
    // The user tapped a node the guest had already removed. Expected, not exceptional.
    composition.sendEvent(Event(i = Id(9999), e = EventTag(1), q = composition.lastSentSequence))
    assertEquals(1, host.unknownNodes, "a stale event is telemetry, never a crash")
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

// ---------------------------------------------------------------------------

/**
 * The Phase 2 modifier subsystem.
 *
 * The gate asks for pixel-identical output against the same chain written statically. Pixels are
 * not asserted here -- that needs screenshot testing the project does not have, and saying so is
 * better than implying otherwise. What is asserted is the protocol half: an arbitrary chain
 * crosses in order, with its arguments intact, and a chain whose argument is a deferred
 * expression crosses as a recipe rather than as a value the guest could not have built.
 */
class ModifierChainTest {

  private fun chainOf(content: @Composable () -> Unit): List<ModifierElem> {
    val (host, _) = compose(content)
    return host.decoded().single().g.filterIsInstance<ModifierSet>().first().e
  }

  @Test
  fun anArbitraryChainCrossesInOrderWithItsArguments() {
    val chain = chainOf {
      Text(
        "x",
        modifier = DogwoodModifier.padding(8).width(120).alpha(0.5f).height(40).fillMaxWidth(0.75f),
      )
    }
    assertEquals(listOf(1, 6, 5, 7, 2), chain.map { it.t.local })
    assertEquals("8", chain[0].v.toString())
    assertEquals("120", chain[1].v.toString())
    assertEquals("0.5", chain[2].v.toString())
    assertEquals("0.75", chain[4].v.toString())
  }

  @Test
  fun orderIsPreservedBecauseOrderChangesTheLayout() {
    // padding-then-size and size-then-padding are different layouts, so the chain is a sequence
    // and not a set. Two chains with the same elements in different orders must differ.
    val a = chainOf { Text("x", modifier = DogwoodModifier.padding(8).size(48)) }
    val b = chainOf { Text("x", modifier = DogwoodModifier.size(48).padding(8)) }
    assertEquals(listOf(1, 4), a.map { it.t.local })
    assertEquals(listOf(4, 1), b.map { it.t.local })
  }

  @Test
  fun aScopedModifierCrossesWithItsScopeIntact() {
    val (host, _) = compose {
      Row { Text("x", modifier = DogwoodModifier.weight(2.0f)) }
    }
    val chain = host.decoded().single().g.filterIsInstance<ModifierSet>()
      .first { it.e.any { element -> element.t.local == 3 } }
    // JSON has one number type, so 2.0f renders as `2`. The host reads it back as a float.
    assertEquals("2", chain.e.single { it.t.local == 3 }.v.toString())
    // There is no test that `weight` outside a row fails, because it cannot be written: the
    // guest's scope receivers make it a compile error. That is the Phase 2 requirement met by
    // construction rather than by diagnosis.
  }

  @Test
  fun aDeferredExpressionCrossesAsARecipe() {
    val chain = chainOf {
      Text(
        "x",
        modifier = DogwoodModifier
          .clip(Shapes.roundedCorner(12))
          .background(Colors.token("primary")),
      )
    }
    assertEquals(listOf(9, 10), chain.map { it.t.local })
    // `[factory, args...]` -- a recipe the host evaluates, not a value the guest computed.
    assertEquals("[1,12]", chain[0].v.toString())
    assertEquals("""[4,"primary"]""", chain[1].v.toString())
  }
}

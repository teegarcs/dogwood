/*
 * Project Dogwood -- the guest half of resynchronisation.
 *
 * A rejected batch leaves the host's tree intact but older than this guest believes, and every
 * later change is a diff against a tree that no longer exists on the other side. Containment
 * stopped the damage; this is the repair, and Layer 4 ADR-011 recorded its absence as the honest
 * gap in that decision.
 *
 * What the guest owes is a batch that **builds the whole tree from nothing**, because the host
 * clears before asking. These assert that, and the two counter invariants that make it safe to
 * run a second composition inside one guest.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.dogwood.protocol.ChildAdd
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.PropertySet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResynchronisationTest {

  /** A composition rebuilt the way `DogwoodGuestUiImpl.resynchronise` rebuilds one. */
  private fun rebuild(
    previous: DogwoodComposition,
    host: RecordingHost,
    content: @Composable () -> Unit,
  ): DogwoodComposition {
    val carried = previous.snapshotState()
    val resumeId = previous.nextId
    val resumeSequence = previous.lastSequence
    previous.dispose()
    return DogwoodComposition(
      host = host,
      initialConfiguration = dev.dogwood.protocol.HostEnvironment(),
      segmentVersions = emptyMap(),
      restoredState = carried,
      startId = resumeId,
      startSequence = resumeSequence,
      content = content,
    )
  }

  @Test
  fun theRebuiltBatchCreatesEveryNodeRatherThanDiffing() {
    val content: @Composable () -> Unit = {
      Column {
        Text("first")
        Text("second")
      }
    }
    val (host, composition) = compose(content = content)
    val firstBatch = host.decoded().single()
    val createdInitially = firstBatch.g.filterIsInstance<Create>().size
    assertTrue(createdInitially >= 3, "expected a column and two texts, got $createdInitially")

    host.batches.clear()
    rebuild(composition, host, content)

    val resent = host.decoded().single()
    // The whole tree, as creations. A diff would carry none, because nothing changed.
    assertEquals(
      createdInitially,
      resent.g.filterIsInstance<Create>().size,
      "a resynchronisation must rebuild the tree, not diff against one the host has thrown away",
    )
    assertTrue(
      resent.g.filterIsInstance<PropertySet>().isNotEmpty(),
      "the text properties have to be re-sent as well; the host kept none of them",
    )
    assertTrue(
      resent.g.filterIsInstance<ChildAdd>().isNotEmpty(),
      "and the structure, or the host has a bag of unparented nodes",
    )
  }

  /**
   * Identifiers must not restart.
   *
   * The host has cleared its tree, so a collision there is impossible -- but an event composed
   * against the old tree may already be in flight, and restarting at 1 would let it land on
   * whichever new node inherited its number. The counter carries across for that reason alone.
   */
  @Test
  fun identifiersDoNotRestart() {
    val content: @Composable () -> Unit = { Column { Text("only") } }
    val (host, composition) = compose(content = content)
    val firstIds = host.decoded().single().g.filterIsInstance<Create>().map { it.i.value }.toSet()

    host.batches.clear()
    rebuild(composition, host, content)

    val secondIds = host.decoded().single().g.filterIsInstance<Create>().map { it.i.value }.toSet()
    assertEquals(
      emptySet(),
      firstIds intersect secondIds,
      "a rebuilt composition reused an identifier the previous one had handed out",
    )
  }

  /**
   * Sequence numbers must not go backwards.
   *
   * `Event.q` is how a guest drops an event composed against a stale batch. A sequence that
   * restarted would make every subsequent event look stale for the life of the experience --
   * a screen that renders and ignores every tap.
   */
  @Test
  fun sequenceNumbersKeepClimbing() {
    val content: @Composable () -> Unit = { Column { Text("only") } }
    val (host, composition) = compose(content = content)
    val before = host.decoded().single().q

    host.batches.clear()
    rebuild(composition, host, content)

    assertTrue(
      host.decoded().single().q > before,
      "sequence went from $before to ${host.decoded().single().q}",
    )
  }

  /** What a user typed survives a protocol failure they did not cause. */
  @Test
  fun saveableStateSurvivesTheRebuild() {
    // Mutated through a captured lambda rather than an event, on the idiom `SaveableHolderTest`
    // uses: the point under test is that the state crosses the rebuild, and routing it through the
    // event path would be testing the event path.
    var visible = 0
    var bump: (() -> Unit)? = null
    val content: @Composable () -> Unit = {
      var count by androidx.compose.runtime.saveable.rememberSaveable(
        key = "count",
        stateSaver = androidx.compose.runtime.saveable.autoSaver(),
      ) { androidx.compose.runtime.mutableStateOf(0) }
      visible = count
      bump = { count += 1 }
      Column { Text("count $count") }
    }

    val (host, composition) = compose(content = content)
    bump!!()
    composition.frame(0L)
    assertEquals(1, visible, "the state did not change before the rebuild")

    host.batches.clear()
    rebuild(composition, host, content)

    assertEquals(
      1,
      visible,
      "the count reset; a resynchronisation must not cost a user what they had already done",
    )
  }
}

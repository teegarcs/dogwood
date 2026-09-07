/*
 * Project Dogwood -- a holder that answers, as tests.
 *
 * The three holders before this one run one way at a time: a target goes down, or a report comes
 * up, and the two are independent. A snackbar is the first thing a guest asks for **and waits on**,
 * and what comes back decides what it does next — a user who tapped *Undo* gets their row back.
 *
 * So these are mostly about correlation. A reply carries the sequence it is answering, and the
 * cases that matter are the ones where that is the only thing keeping two requests apart.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.PropertySet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** `SnackbarArea`'s property tags. */
private const val MESSAGE = 1
private const val ACTION_LABEL = 2
private const val SEQUENCE = 3
private const val WATCHING = 4

private fun RecordingHost.properties(tag: Int): List<PropertySet> =
  decoded().flatMap { it.g }.filterIsInstance<PropertySet>().filter { it.p.value == tag }

private fun RecordingHost.sequences(): List<Int> =
  properties(SEQUENCE).mapNotNull { it.v.jsonPrimitive.intOrNull }

class SnackbarHostStateTest {

  /**
   * The scope a guest would use.
   *
   * `rememberCoroutineScope`, not a test-only hook on the composition: `showSnackbar` suspends, and
   * where a real guest calls it from is inside its own composition. A test that launched from
   * somewhere else would be testing a caller nobody writes.
   */
  private var scope: CoroutineScope? = null

  @Composable
  private fun Screen(snackbars: SnackbarHostState?) {
    scope = rememberCoroutineScope()
    SnackbarArea(snackbars = snackbars) { Text("body") }
  }

  private fun ask(block: suspend () -> Unit) {
    checkNotNull(scope).launch { block() }
  }

  private fun RecordingHost.areaNode() = decoded().first().g
    .filterIsInstance<dev.dogwood.protocol.Create>()
    .first { it.w.value == dev.dogwood.protocol.widgetTag(1, 16).value }
    .i

  @Test
  fun anAreaWithoutAHolderAsksForNothing() {
    // There is no such thing as a snackbar nobody asked for.
    val (host, _) = compose { Screen(null) }
    assertTrue(host.properties(SEQUENCE).isEmpty())
    assertTrue(host.properties(WATCHING).isEmpty())
  }

  @Test
  fun aRequestCrossesAsAMessageAndASequence() {
    lateinit var snackbars: SnackbarHostState
    val (host, composition) = compose {
      snackbars = rememberSnackbarHostState()
      Screen(snackbars)
    }

    ask { snackbars.showSnackbar("Deleted", actionLabel = "Undo") }
    composition.frame(0L)

    assertEquals("Deleted", host.properties(MESSAGE).last().v.jsonPrimitive.content)
    assertEquals("Undo", host.properties(ACTION_LABEL).last().v.jsonPrimitive.content)
    assertEquals(listOf(0, 1), host.sequences())
  }

  @Test
  fun theCallerWaitsUntilTheHostAnswers() {
    lateinit var snackbars: SnackbarHostState
    val (host, composition) = compose {
      snackbars = rememberSnackbarHostState()
      Screen(snackbars)
    }

    var answer: SnackbarResult? = null
    ask { answer = snackbars.showSnackbar("Deleted", "Undo") }
    composition.frame(0L)

    // Still suspended: nothing has answered, and the guest must not guess.
    assertNull(answer)

    composition.sendEvent(
      Event(
        i = host.areaNode(),
        e = EventTag(1),
        q = composition.lastSentSequence,
        a = listOf(JsonPrimitive(1), JsonPrimitive(true)),
      ),
    )

    assertEquals(SnackbarResult.ACTION_PERFORMED, answer)
  }

  @Test
  fun aDismissalIsAnAnswerToo() {
    lateinit var snackbars: SnackbarHostState
    val (host, composition) = compose {
      snackbars = rememberSnackbarHostState()
      Screen(snackbars)
    }
    var answer: SnackbarResult? = null
    ask { answer = snackbars.showSnackbar("Deleted") }
    composition.frame(0L)

    composition.sendEvent(
      Event(
        i = host.areaNode(), e = EventTag(1), q = composition.lastSentSequence,
        a = listOf(JsonPrimitive(1), JsonPrimitive(false)),
      ),
    )

    assertEquals(SnackbarResult.DISMISSED, answer)
  }

  @Test
  fun anAnswerToASupersededRequestIsDropped() {
    // The case the sequence exists for, and the reason a reply is not just a report. A snackbar
    // dismissed by the host arrives *after* the guest replaced it, and resuming the new request
    // with the old one's answer would undo the wrong row.
    lateinit var snackbars: SnackbarHostState
    val (host, composition) = compose {
      snackbars = rememberSnackbarHostState()
      Screen(snackbars)
    }

    var second: SnackbarResult? = null
    ask { snackbars.showSnackbar("first") }
    composition.frame(0L)
    ask { second = snackbars.showSnackbar("second") }
    composition.frame(16L)

    // The host answers the *first* request, late.
    composition.sendEvent(
      Event(
        i = host.areaNode(), e = EventTag(1), q = composition.lastSentSequence,
        a = listOf(JsonPrimitive(1), JsonPrimitive(true)),
      ),
    )

    assertNull(second, "a stale answer resumed the wrong request")

    composition.sendEvent(
      Event(
        i = host.areaNode(), e = EventTag(1), q = composition.lastSentSequence,
        a = listOf(JsonPrimitive(2), JsonPrimitive(false)),
      ),
    )
    assertEquals(SnackbarResult.DISMISSED, second)
  }

  @Test
  fun aSupersededRequestIsAnsweredRatherThanLeftSuspendedForever() {
    // Its snackbar is about to be replaced on screen, so "dismissed" is what actually happened to
    // it. Leaving the coroutine suspended would leak a caller that never resumes.
    lateinit var snackbars: SnackbarHostState
    val (_, composition) = compose {
      snackbars = rememberSnackbarHostState()
      Screen(snackbars)
    }

    var first: SnackbarResult? = null
    ask { first = snackbars.showSnackbar("first") }
    composition.frame(0L)
    ask { snackbars.showSnackbar("second") }
    composition.frame(16L)

    assertEquals(SnackbarResult.DISMISSED, first)
  }

  @Test
  fun askingTwiceForTheSameMessageIsTwoRequests() {
    // A user who deletes two rows expects to be told twice.
    lateinit var snackbars: SnackbarHostState
    val (host, composition) = compose {
      snackbars = rememberSnackbarHostState()
      Screen(snackbars)
    }

    ask { snackbars.showSnackbar("Deleted") }
    composition.frame(0L)
    ask { snackbars.showSnackbar("Deleted") }
    composition.frame(16L)

    assertEquals(listOf(0, 1, 2), host.sequences())
  }
}

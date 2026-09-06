/*
 * Project Dogwood -- the first generated live-state holder, as tests.
 *
 * `LazyListState` was hand-written on both sides and its tests exercise a hand-written stub.
 * `FocusRequester` is declared with `@Holder` on the surface and everything between the holder and
 * the wire is generated (ADR-043), so these tests are checking the *generator's* output as much as
 * the holder's: what crosses, when nothing crosses, and what survives a code update.
 *
 * The host half is verified on a device, for the reason ADR-014 already records -- exercising a
 * real focus request inside a composition needs a Compose UI test harness this project does not
 * have.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.StateSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Tags 11 and 12 on `TextInput`, appended by the generator after the ten declared values. */
private const val FOCUS_REQUESTED = 11
private const val FOCUS_SEQUENCE = 12

private fun RecordingHost.properties(tag: Int): List<PropertySet> =
  decoded().flatMap { it.g }.filterIsInstance<PropertySet>().filter { it.p.value == tag }

private fun RecordingHost.sequences(): List<Int> =
  properties(FOCUS_SEQUENCE).mapNotNull { it.v.jsonPrimitive.intOrNull }

class FocusRequesterTest {

  private var captured: FocusRequester? = null

  @Composable
  private fun Field(focus: FocusRequester?) {
    TextInput(text = "", onValueChange = { _, _ -> }, focus = focus)
  }

  @Test
  fun aFieldWithoutARequesterSendsNoFocusPropertiesAtAll() {
    // Absence is the sentinel, and here it is load-bearing rather than tidy. These two tags are
    // new in this dictionary version; a client one version behind that met them on a widget
    // owning an affordance is defined to withhold the widget. Sending them unconditionally would
    // blank every text field on that client because a newer guest declined to ask for focus.
    val (host, _) = compose { Field(null) }
    assertTrue(host.properties(FOCUS_REQUESTED).isEmpty())
    assertTrue(host.properties(FOCUS_SEQUENCE).isEmpty())
  }

  @Test
  fun aRequesterThatHasNotBeenUsedCrossesAsSequenceZero() {
    // Present, so the host builds a mirror, and silent, so the mirror does nothing. Zero means
    // "nobody has asked for anything" and is the only value the host acts on by not acting.
    val (host, _) = compose { Field(FocusRequester()) }
    assertEquals(listOf(0), host.sequences())
    assertEquals(
      false,
      host.properties(FOCUS_REQUESTED).last().v.jsonPrimitive.booleanOrNull,
    )
  }

  @Test
  fun askingForFocusCrossesAsADirectionAndASequence() {
    lateinit var focus: FocusRequester
    val (host, composition) = compose {
      focus = rememberFocusRequester()
      Field(focus)
    }

    focus.requestFocus()
    composition.frame(0L)

    assertEquals(listOf(0, 1), host.sequences())
    assertEquals(true, host.properties(FOCUS_REQUESTED).last().v.jsonPrimitive.booleanOrNull)
  }

  @Test
  fun givingFocusUpIsARequestAndNotTheAbsenceOfOne() {
    lateinit var focus: FocusRequester
    val (host, composition) = compose {
      focus = rememberFocusRequester()
      Field(focus)
    }

    focus.freeFocus()
    composition.frame(0L)

    // The direction is false and the sequence advanced. Reading `requested = false` as "no
    // request" is the mistake this test exists to catch: dismissing the keyboard is something the
    // guest asked for, and a host that ignored it would leave it up.
    assertEquals(false, host.properties(FOCUS_REQUESTED).last().v.jsonPrimitive.booleanOrNull)
    assertEquals(listOf(0, 1), host.sequences())
  }

  @Test
  fun askingTwiceForFocusIsTwoRequests() {
    // The same reason `LazyListState`'s target is a counter. A user who dismissed the keyboard
    // and tapped the same "edit" control again expects the field back; with a flag the second tap
    // would change no property and cross nothing.
    lateinit var focus: FocusRequester
    val (host, composition) = compose {
      focus = rememberFocusRequester()
      Field(focus)
    }

    focus.requestFocus()
    composition.frame(0L)
    focus.requestFocus()
    composition.frame(16L)

    assertEquals(listOf(0, 1, 2), host.sequences())
  }

  @Test
  fun twoRequestsInOnePassCrossOnceWithBothCounted() {
    // The conflict rule, inherited from the channel rather than enforced on top of it: a property
    // carries only its latest value.
    lateinit var focus: FocusRequester
    val (host, composition) = compose {
      focus = rememberFocusRequester()
      Field(focus)
    }
    val batches = host.batches.size

    focus.requestFocus()
    focus.freeFocus()
    composition.frame(0L)

    assertEquals(batches + 1, host.batches.size, "one composition pass, one batch")
    assertEquals(false, host.properties(FOCUS_REQUESTED).last().v.jsonPrimitive.booleanOrNull)
    assertEquals(listOf(0, 2), host.sequences(), "both counted, only the last crossed")
  }

  /** One call site, so both generations produce the same `rememberSaveable` composite key. */
  @Composable
  private fun Form() {
    val focus = rememberFocusRequester()
    captured = focus
    Field(focus)
  }

  private fun form(host: RecordingHost, restored: StateSnapshot?) =
    DogwoodComposition(host, dev.dogwood.protocol.HostEnvironment(), emptyMap(), restored) {
      Form()
    }

  @Test
  fun aReplacementGuestDoesNotGrabTheKeyboardBack() {
    // What survives is the count, deliberately not the direction. Restoring the direction would
    // reissue the last request after a code update, so a screen the user had scrolled away from
    // would take the keyboard back seconds later for no reason they could see.
    val first = RecordingHost()
    val composition = form(first, null)
    checkNotNull(captured).requestFocus()
    composition.frame(0L)
    val carried: StateSnapshot = composition.snapshotState()
    composition.dispose()

    val second = RecordingHost()
    captured = null
    val replacement = form(second, carried)

    assertEquals(
      listOf(1),
      second.sequences(),
      "the restored requester carried its count forward without reissuing the request",
    )
    assertEquals(
      false,
      second.properties(FOCUS_REQUESTED).lastOrNull()?.v?.jsonPrimitive?.booleanOrNull,
      "a restored requester is not asking for anything",
    )
    replacement.dispose()
  }

  @Test
  fun aRestoredRequesterCanStillAskAgain() {
    // The point of carrying the count rather than resetting it: the next real request is still a
    // change, so it crosses. A restored requester that started at zero would send sequence 1
    // again -- the same value the host had already acted on -- and the request would be lost.
    val first = RecordingHost()
    val composition = form(first, null)
    checkNotNull(captured).requestFocus()
    composition.frame(0L)
    val carried: StateSnapshot = composition.snapshotState()
    composition.dispose()

    val second = RecordingHost()
    captured = null
    val restored = form(second, carried)
    checkNotNull(captured).requestFocus()
    restored.frame(0L)

    assertEquals(listOf(1, 2), second.sequences())
    assertEquals(true, second.properties(FOCUS_REQUESTED).last().v.jsonPrimitive.booleanOrNull)
    restored.dispose()
  }

  @Test
  fun aRequesterIsNotSharedBetweenFields() {
    // Two fields, one requester each: the properties are per node, so the tags say which field
    // the request is for without the protocol carrying a target identifier.
    val (host, _) = compose {
      Field(FocusRequester())
      Field(null)
    }
    assertEquals(1, host.properties(FOCUS_SEQUENCE).size)
    assertNull(host.properties(FOCUS_SEQUENCE).firstOrNull()?.takeIf { it.v == JsonPrimitive(1) })
  }
}

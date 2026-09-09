/*
 * Project Dogwood -- the guest's half of shapes seven and eight.
 *
 * These are snackbar's protocol with a value in the reply, so they inherit its two hard rules and
 * are tested on both: a reply for a superseded request is dropped, and a superseded request is
 * answered rather than left suspended forever. A guest stranded on `await()` is a screen that never
 * moves again, and it is the failure mode a picker makes easy — users open them twice.
 */
package dev.dogwood.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(DogwoodGeneratedApi::class)
class PickerStateTest {

  /**
   * `Dispatchers.Unconfined`, so a launched `show()` runs eagerly up to its `await()` and the
   * request has crossed by the time the next line asserts on it.
   *
   * The snackbar's tests compose to obtain a real `rememberCoroutineScope`, because what they are
   * testing includes the composition. These are testing the holder's protocol — which sequence
   * answers which request — so an unconfined scope is the smaller harness that still runs the same
   * suspension.
   */
  private val scope = CoroutineScope(Dispatchers.Unconfined)

  @Test
  fun aChosenDateComesBackToTheCaller() {
    val picker = DatePickerState("")
    var answer: PickerResult? = null
    val job = scope.launch { answer = picker.show("2026-03-14") }

    assertTrue(picker.requested, "the request never crossed")
    picker.report(picker.requestSequence, "2026-03-20")

    assertEquals(PickerResult.Chosen("2026-03-20"), answer)
    assertFalse(picker.requested, "the picker stayed open after answering")
  }

  @Test
  fun anEmptyAnswerIsDismissalRatherThanAValue() {
    // Empty is distinguishable from every real date, which a sentinel date would not be -- and
    // "dismissed" is a different event to a screen than "chose nothing".
    val picker = DatePickerState("")
    var answer: PickerResult? = null
    val job = scope.launch { answer = picker.show() }

    picker.report(picker.requestSequence, "")

    assertEquals(PickerResult.Dismissed, answer)
  }

  @Test
  fun aReplyForASupersededRequestIsDropped() {
    // The sequence's whole purpose: two pickers in flight must not answer each other's questions.
    val picker = TimePickerState("")
    var answer: PickerResult? = null
    val job = scope.launch { answer = picker.show("09:00") }
    val current = picker.requestSequence

    picker.report(current - 1, "23:59")
    assertEquals(null, answer, "a stale reply was delivered")

    picker.report(current, "10:30")
    assertEquals(PickerResult.Chosen("10:30"), answer)
  }

  @Test
  fun asupersededRequestIsAnsweredRatherThanStranded() {
    // The rule that keeps a guest from waiting forever. Opening a second picker while the first is
    // still open must complete the first as dismissed -- a coroutine suspended on a reply that can
    // never come is a screen that never moves again.
    val picker = DatePickerState("")
    var first: PickerResult? = null
    val firstJob = scope.launch { first = picker.show() }

    val secondJob = scope.launch { picker.show() }

    assertEquals(PickerResult.Dismissed, first)
    picker.report(picker.requestSequence, "2026-01-01")
  }

  @Test
  fun aRestoredPickerIsClosed() {
    // The worst behaviour available to this shape: a user closes a picker, a publish lands, and the
    // picker is back on their screen. The saver carries what to open on and not the request.
    val picker = DatePickerState("")
    scope.launch { picker.show("2026-03-14") }
    assertTrue(picker.requested)

    val restored = DatePickerState.Saver.restore(listOf("2026-03-14"))!!

    assertFalse(restored.requested, "a restored picker reopened itself")
    assertEquals(0, restored.requestSequence)
    assertEquals("2026-03-14", restored.initialDate)
  }
}

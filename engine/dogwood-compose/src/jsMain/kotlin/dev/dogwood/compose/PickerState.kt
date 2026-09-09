/*
 * Project Dogwood -- the guest's half of the date and time pickers.
 *
 * Shapes seven and eight, and both are snackbar's rather than scroll's: the guest asks, the user
 * decides, and the answer carries the sequence it answers. What separates them from a snackbar is
 * the reply payload — a snackbar answers with a boolean and these answer with a value.
 *
 * **Values cross as text, and that is the load-bearing decision.** A calendar date is not an
 * instant: `2026-03-14` is different milliseconds in different zones, and a guest handed an epoch
 * would have to guess a zone to name the day back — guessing wrong by one day, near midnight, for
 * the users least likely to be testing it. So a date crosses as `yyyy-MM-dd` and a time as `HH:mm`,
 * and the host decides what either *looks* like, which is the rule every host-resolved value
 * follows.
 */
@file:OptIn(DogwoodGeneratedApi::class)

package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred

/**
 * What a picker came back with.
 *
 * A sealed result rather than a nullable string, because "dismissed" and "chose nothing" would
 * otherwise be the same value — and they are not the same event to a screen deciding whether to
 * keep a form open.
 */
sealed interface PickerResult {
  /** The user chose. [value] is `yyyy-MM-dd` for a date, `HH:mm` for a time. */
  data class Chosen(val value: String) : PickerResult

  /** The user closed it without choosing. */
  data object Dismissed : PickerResult
}

/**
 * The shared machinery of both pickers.
 *
 * One class with two thin subclasses rather than two copies, because the *protocol* is identical —
 * ask, wait, answer the sequence you were asked with — and only the wire vocabulary differs. The
 * host side is genuinely two mirrors, because a calendar grid and a clock face are different
 * controls; this side has nothing to differ about.
 */
abstract class PickerState internal constructor(initialValue: String) {

  @DogwoodGeneratedApi
  var requested: Boolean by mutableStateOf(false)
    private set

  @DogwoodGeneratedApi
  var requestSequence: Int by mutableStateOf(0)
    private set

  @DogwoodGeneratedApi
  var watching: Boolean by mutableStateOf(false)
    internal set

  /** What to open on; empty means the host's idea of now, which only the host has. */
  protected var initial: String by mutableStateOf(initialValue)

  /** The request currently in flight, if any. */
  private var pending: CompletableDeferred<PickerResult>? = null

  /**
   * Asks the host to show the picker, and **waits for what the user chose**.
   *
   * Suspending, like `showSnackbar`, and for the same reason: the answer decides what the guest
   * does next. A caller that ignored it would have written a picker that changes nothing.
   */
  suspend fun show(initialValue: String = ""): PickerResult {
    watching = true
    if (initialValue.isNotEmpty()) initial = initialValue
    // A previous request still waiting is answered as dismissed rather than left suspended
    // forever — the snackbar rule (ADR-051): a superseded request must not strand its caller.
    pending?.complete(PickerResult.Dismissed)
    val answer = CompletableDeferred<PickerResult>()
    pending = answer
    requested = true
    requestSequence += 1
    return answer.await()
  }

  /**
   * Called from the host's report.
   *
   * The parameter is named by the *subclass*, because the generator emits the argument names the
   * shape table declares — `date` for one picker and `time` for the other. This shared body takes
   * the value under a neutral name and each subclass exposes the name its shape uses; a single
   * `report(sequence, value)` here compiled and then failed at the generated call site, which is
   * the seam worth stating rather than discovering twice.
   */
  @DogwoodGeneratedApi
  protected fun reportValue(sequence: Int, value: String) {
    // A reply for a request that is not the one in flight is dropped, which is what the sequence is
    // for: two pickers opened in quick succession must not answer each other's questions.
    if (sequence != requestSequence) return
    requested = false
    val answer = pending ?: return
    pending = null
    answer.complete(if (value.isEmpty()) PickerResult.Dismissed else PickerResult.Chosen(value))
  }
}

/** A date picker's state. The value it answers with is `yyyy-MM-dd`. */
class DatePickerState internal constructor(initialDate: String) : PickerState(initialDate) {
  @DogwoodGeneratedApi
  val initialDate: String get() = initial

  /** The host's answer. Named `date` because that is what the shape table calls the argument. */
  @DogwoodGeneratedApi
  fun report(sequence: Int, date: String) = reportValue(sequence, date)

  companion object {
    /**
     * Saves what to open on, and **not** the request.
     *
     * The rule every holder here shares: a restored picker must not reopen itself. A user who
     * closed a date picker and then saw a publish would find it back on their screen, which is the
     * worst behaviour in this whole table.
     */
    val Saver: Saver<DatePickerState, Any> = listSaver(
      save = { listOf(it.initialDate) },
      restore = { DatePickerState(it[0] as String) },
    )
  }
}

/** A time picker's state. The value it answers with is `HH:mm`, 24-hour. */
class TimePickerState internal constructor(initialTime: String) : PickerState(initialTime) {
  @DogwoodGeneratedApi
  val initialTime: String get() = initial

  /** The host's answer. Named `time` for the same reason [DatePickerState.report] is named `date`. */
  @DogwoodGeneratedApi
  fun report(sequence: Int, time: String) = reportValue(sequence, time)

  companion object {
    val Saver: Saver<TimePickerState, Any> = listSaver(
      save = { listOf(it.initialTime) },
      restore = { TimePickerState(it[0] as String) },
    )
  }
}

/** Remembers a date picker across recomposition and a code update, closed. */
@Composable
fun rememberDatePickerState(initialDate: String = ""): DatePickerState =
  rememberSaveable(saver = DatePickerState.Saver) { DatePickerState(initialDate) }

/** Remembers a time picker across recomposition and a code update, closed. */
@Composable
fun rememberTimePickerState(initialTime: String = ""): TimePickerState =
  rememberSaveable(saver = TimePickerState.Saver) { TimePickerState(initialTime) }

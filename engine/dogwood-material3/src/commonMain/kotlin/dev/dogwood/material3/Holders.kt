/*
 * Project Dogwood -- the host halves of the Material 3 tier's live-state mirrors.
 *
 * A generated binding builds one of these and hands the library the real state object. What the
 * mirror *does* is hand-written, and [ADR-043](../../../../../../../adrs/layer-5/ADR-043-holders-are-declared-on-the-surface.md)
 * says why: moving a pager, opening a drawer or answering with the time a user picked is the part
 * that requires taste, and it is not the part that grows without bound. The generator emits the
 * plumbing on both sides; these functions are the behaviour in the middle.
 *
 * **Named parameters, matching the shape in `LibraryHolders.kt` field for field.** The generator
 * calls these by name for a reason recorded there: a shape with four properties passed positionally
 * is two `Int` arguments away from a silent transposition, where the mirror compiles, the widget
 * moves to a sequence number, and nothing says so.
 *
 * **A request is a sequence change, never a value change.** A guest asking for 9:00 again after the
 * user moved the dial sends the same string it sent before, and an effect keyed on the value would
 * do nothing. Every mirror here keys its effect on the sequence, as every holder in this project
 * does.
 */
package dev.dogwood.material3

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import dev.dogwood.host.LocalGuestGeneration
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Drives a time picker from the guest's declared time and reports what the user settles on.
 *
 * The wire form is `HH:MM` in twenty-four-hour clock, whatever the dial displays. A locale belongs
 * to the client, not to the payload, and a picker that crossed "9:00 AM" would be a payload
 * deciding one — the same rule the date picker follows with ISO-8601 (ADR-053).
 *
 * @param requested whether the guest is asking at all. The picker's own shape carries this; a
 *   component that is always on screen simply reads it as true once a request has been made.
 * @param requestSequence raised by the guest on every request. Zero means it has never asked.
 * @param initialTime what the guest is asking for, `HH:MM`, or empty for the host's own default.
 * @param watching whether anyone is listening; reporting costs nothing when nobody is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberLibraryTimePickerState(
  requested: Boolean,
  requestSequence: Int,
  initialTime: String,
  watching: Boolean,
  report: (Int, String) -> Unit,
): TimePickerState {
  val asked = parseTime(initialTime)
  val state = rememberTimePickerState(
    initialHour = asked?.first ?: 0,
    initialMinute = asked?.second ?: 0,
    // The dial's own default. A twenty-four-hour display is a client's convention, and the wire
    // carries `HH:MM` either way, so there is nothing here for a payload to choose.
    is24Hour = false,
  )
  val generation = LocalGuestGeneration.current

  /*
   * Keyed on the sequence, and on the guest generation beside it.
   *
   * The generation is what makes a code update behave: a new payload that asks for the same time
   * its predecessor did is asking again, and without the generation in the key the effect would
   * not run. Every holder in this project keys this way.
   */
  LaunchedEffect(requestSequence, generation) {
    if (requestSequence == 0 || !requested) return@LaunchedEffect
    val (hour, minute) = asked ?: return@LaunchedEffect
    // Clamped rather than refused. A payload delivered over the air must not be able to take a
    // screen down with a number, and a picker asked for 25:99 is ordinary skew (ADR-035).
    state.hour = hour.coerceIn(0, 23)
    state.minute = minute.coerceIn(0, 59)
  }

  if (watching) {
    LaunchedEffect(state, generation) {
      snapshotFlow { formatTime(state.hour, state.minute) }
        .distinctUntilChanged()
        .collect { report(requestSequence, it) }
    }
  }
  return state
}

/** `HH:MM` to a pair, or null when the guest sent nothing usable. Total, never throwing. */
private fun parseTime(text: String): Pair<Int, Int>? {
  val parts = text.split(':')
  if (parts.size != 2) return null
  val hour = parts[0].toIntOrNull() ?: return null
  val minute = parts[1].toIntOrNull() ?: return null
  return hour to minute
}

/** Always two digits and always twenty-four hours, so the wire never carries a locale. */
private fun formatTime(hour: Int, minute: Int): String =
  "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

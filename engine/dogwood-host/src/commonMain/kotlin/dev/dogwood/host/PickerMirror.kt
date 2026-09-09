/*
 * Project Dogwood -- the host's half of the date and time pickers.
 *
 * Mirrors seven and eight. Both follow `SnackbarMirror`'s shape — a request keyed on its sequence,
 * an answer carrying the sequence it answers — and both own what a guest cannot: the platform's own
 * calendar grid and clock face, in a real dialog window.
 *
 * **Two mirrors rather than one with a mode**, because these are genuinely different controls and a
 * flag would put a conditional inside a mirror with no business branching on what the guest meant.
 * The duplication is about thirty lines; the alternative is a mirror doing two jobs badly.
 *
 * The wire vocabulary is `yyyy-MM-dd` and `HH:mm`, parsed and formatted here — the one place that
 * knows both the text form and the platform's own state objects. A guest never sees a millisecond.
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.dogwood.host

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Whether a picker is on screen, and the sequence it will answer with. */
class PickerMirror internal constructor(internal val visible: Boolean, internal val sequence: Int)

/**
 * Days since the epoch for `yyyy-MM-dd`, or null for anything else.
 *
 * Hand-rolled rather than reached for through a date library, because the calculation is exact
 * arithmetic on a proleptic Gregorian calendar and every client must agree on it to the day. A
 * platform formatter would be *locale-sensitive*, which is the one property this must not have:
 * the wire form is a calendar date, not a rendering of one.
 */
internal fun isoDateToEpochDay(text: String): Long? {
  val parts = text.split("-")
  if (parts.size != 3) return null
  val year = parts[0].toIntOrNull() ?: return null
  val month = parts[1].toIntOrNull() ?: return null
  val day = parts[2].toIntOrNull() ?: return null
  if (month !in 1..12 || day !in 1..31) return null
  // Howard Hinnant's civil-from-days, inverted; the standard integer algorithm.
  val y = if (month <= 2) year - 1 else year
  val era = (if (y >= 0) y else y - 399) / 400
  val yoe = y - era * 400
  val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
  val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
  return era.toLong() * 146097 + doe - 719468
}

/** The inverse, so a chosen date crosses back in the form it arrived. */
internal fun epochDayToIsoDate(epochDay: Long): String {
  var z = epochDay + 719468
  val era = (if (z >= 0) z else z - 146096) / 146097
  val doe = z - era * 146097
  val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
  val y = yoe + era * 400
  val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
  val mp = (5 * doy + 2) / 153
  val d = doy - (153 * mp + 2) / 5 + 1
  val m = if (mp < 10) mp + 3 else mp - 9
  val year = if (m <= 2) y + 1 else y
  return "$year-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

/**
 * Shows a date picker when asked, and answers with what the user chose.
 *
 * @param report told the sequence it is answering and the chosen date, or empty for dismissed —
 *   distinguishable from any date, which a sentinel date would not be.
 */
@Composable
fun rememberDatePickerMirror(
  requested: Boolean,
  requestSequence: Int,
  initialDate: String,
  watching: Boolean,
  report: ((Int, String) -> Unit)?,
): PickerMirror {
  val generation = LocalGuestGeneration.current
  // Keyed on the sequence, like every request in this table: asking twice is two pickers, and a
  // flag would collapse the second ask into nothing.
  var showing by remember(requestSequence, generation) { mutableStateOf(requested && requestSequence > 0) }

  if (showing && watching && report != null) {
    val state = rememberDatePickerState(
      initialSelectedDateMillis = isoDateToEpochDay(initialDate)?.let { it * 86_400_000L },
    )
    DatePickerDialog(
      onDismissRequest = {
        showing = false
        report(requestSequence, "")
      },
      confirmButton = {
        TextButton(onClick = {
          showing = false
          val chosen = state.selectedDateMillis
          // A confirm with nothing selected answers as dismissed rather than as a date: the user
          // pressed a button, but they did not choose, and a guest branching on the value must not
          // be handed one they never picked.
          report(requestSequence, chosen?.let { epochDayToIsoDate(it / 86_400_000L) } ?: "")
        }) { Text("OK") }
      },
      dismissButton = {
        TextButton(onClick = {
          showing = false
          report(requestSequence, "")
        }) { Text("Cancel") }
      },
    ) {
      DatePicker(state = state)
    }
  }
  return PickerMirror(showing, requestSequence)
}

/** Shows a time picker when asked, and answers `HH:mm` or empty for dismissed. */
@Composable
fun rememberTimePickerMirror(
  requested: Boolean,
  requestSequence: Int,
  initialTime: String,
  watching: Boolean,
  report: ((Int, String) -> Unit)?,
): PickerMirror {
  val generation = LocalGuestGeneration.current
  var showing by remember(requestSequence, generation) { mutableStateOf(requested && requestSequence > 0) }

  if (showing && watching && report != null) {
    val parts = initialTime.split(":")
    val state = rememberTimePickerState(
      initialHour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 0,
      initialMinute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0,
      // 24-hour on the wire, whatever the face shows: the guest sends the number and the host
      // decides what it looks like, which is the rule every host-resolved value follows.
      is24Hour = true,
    )
    DatePickerDialog(
      onDismissRequest = {
        showing = false
        report(requestSequence, "")
      },
      confirmButton = {
        TextButton(onClick = {
          showing = false
          report(
            requestSequence,
            "${state.hour.toString().padStart(2, '0')}:${state.minute.toString().padStart(2, '0')}",
          )
        }) { Text("OK") }
      },
      dismissButton = {
        TextButton(onClick = {
          showing = false
          report(requestSequence, "")
        }) { Text("Cancel") }
      },
    ) {
      TimePicker(state = state)
    }
  }
  return PickerMirror(showing, requestSequence)
}

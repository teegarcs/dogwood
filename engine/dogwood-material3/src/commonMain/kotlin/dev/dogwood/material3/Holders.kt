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

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSliderState
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import dev.dogwood.host.LocalGuestGeneration
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

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

// -----------------------------------------------------------------------------------------------
// Positions the user can move
//
// Every mirror below has two halves: an effect keyed on the guest's *sequence* that puts the
// control where the payload asked, and -- when anybody is watching -- a `snapshotFlow` that reports
// where the control ends up. `byUser` is the field that makes the second half worth having: a guest
// implementing "they closed it, stop offering it" cannot tell the user shutting a drawer from its
// own request landing out of position alone, and those two mean opposite things.
//
// **Which half reports is the `byUser` answer, rather than a comparison made after the fact.** The
// effect that drives the control is the only code that knows a request landed, so it reports the
// landing itself, with `byUser = false`. The flow reports everything the effect did not cause,
// which is by definition somebody's finger. Computing it instead -- comparing the position that
// arrived against the target -- was tried and is wrong in both directions: a drawer emits `closed`
// on the way to opening, which arrives *before* the request lands and reads as the user shutting a
// drawer the guest had just asked to open, and a slider whose library snaps a value to a step
// reports a number that is not the one that was asked for and reads as a drag.
//
// The flow drops its first emission for the same reason. `snapshotFlow` hands a collector the
// position the control is already in, and where something starts is not something that moved.
// -----------------------------------------------------------------------------------------------

/**
 * Asks the library's snackbar host for a snackbar, and answers with what the user did.
 *
 * The same shape and the same guest class as the design system's own snackbar (ADR-051), so a
 * payload has one idea of "ask, then find out" whichever host it is talking to. Only this half
 * knows which queue to put the request on.
 *
 * @param message what to show. Empty is not a request; a payload that raised the sequence with
 *   nothing to say gets nothing, rather than an empty bar.
 * @param actionLabel the action's label, or empty for a snackbar with no action.
 * @param sequence raised by the guest on every request. Zero means it has never asked.
 * @param watching whether anyone is listening for the answer.
 * @param dismissSequence raised when the guest takes a request back. A counter for the reason every
 *   request here is a counter: dismissing twice is two dismissals.
 */
@Composable
fun rememberLibrarySnackbarHostState(
  message: String,
  actionLabel: String,
  sequence: Int,
  watching: Boolean,
  dismissSequence: Int,
  report: (Int, Boolean) -> Unit,
): SnackbarHostState {
  val state = remember { SnackbarHostState() }
  val generation = LocalGuestGeneration.current

  LaunchedEffect(sequence, generation) {
    if (sequence == 0 || message.isEmpty()) return@LaunchedEffect
    val result = state.showSnackbar(
      message = message,
      actionLabel = actionLabel.ifEmpty { null },
    )
    // The reply carries the sequence it answers, which is what stops two requests in flight being
    // confused: the sequence that went down is the sequence that comes back.
    if (watching) report(sequence, result == SnackbarResult.ActionPerformed)
  }

  LaunchedEffect(dismissSequence, generation) {
    if (dismissSequence == 0) return@LaunchedEffect
    // The guest has already resumed its own `showSnackbar` as dismissed. This takes the bar off
    // the screen to match; the host's own answer for that sequence arrives later and the guest
    // drops it, as it drops every answer to a request it is no longer waiting on.
    state.currentSnackbarData?.dismiss()
  }

  return state
}

/**
 * Opens and closes a navigation drawer, and reports where the user leaves it.
 *
 * @param targetState `open` or `closed`. Anything else reads as closed -- the safe direction for a
 *   panel that covers the screen, and the reason the vocabulary crosses as a name.
 * @param targetSequence raised by the guest on every request. Zero means it has never asked.
 * @param watching whether anyone is listening; reporting costs nothing when nobody is.
 */
@Composable
fun rememberLibraryDrawerState(
  targetState: String,
  targetSequence: Int,
  watching: Boolean,
  report: (String, Boolean) -> Unit,
): DrawerState {
  val state = rememberDrawerState(DrawerValue.Closed)
  val generation = LocalGuestGeneration.current
  val send by rememberUpdatedState(report)
  // Where the guest's last request left the drawer. Null until it has asked for anything, which is
  // what makes a user's first move reportable on a drawer nobody has driven.
  val landed = remember(generation) { mutableStateOf<String?>(null) }

  LaunchedEffect(targetSequence, generation) {
    if (targetSequence == 0) return@LaunchedEffect
    val wanted = if (targetState == DRAWER_OPEN) DRAWER_OPEN else DRAWER_CLOSED
    // Written **before** the control moves, not after. Opening a drawer is an animation, and the
    // position flips partway through it; a mirror that recorded where the request landed only once
    // the animation finished would meet that emission with nothing recorded and report the guest's
    // own request as the user's. This was watched to happen.
    landed.value = wanted
    if (wanted == DRAWER_OPEN) state.open() else state.close()
    // Then read back rather than echoed: a drawer whose host vetoed the change did not go where it
    // was asked, and a guest told otherwise would draw a menu that is not on screen.
    val name = state.wireName()
    landed.value = name
    if (watching) send(name, false)
  }

  if (watching) {
    LaunchedEffect(state, generation) {
      snapshotFlow { state.wireName() }
        .distinctUntilChanged()
        .drop(1)
        .collect { name -> if (name != landed.value) send(name, true) }
    }
  }
  return state
}

private fun DrawerState.wireName(): String =
  if (currentValue == DrawerValue.Open) DRAWER_OPEN else DRAWER_CLOSED

private const val DRAWER_OPEN = "open"
private const val DRAWER_CLOSED = "closed"

/**
 * Sweeps a row away or puts it back, and reports **which way** the user sent it.
 *
 * The direction is the point. A row swiped one way and a row swiped the other mean different
 * things in every inbox ever built, and a guest told only that the row is gone would have to guess.
 *
 * @param targetState `startToEnd`, `endToStart`, or anything else for "put it back".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberLibrarySwipeToDismissBoxState(
  targetState: String,
  targetSequence: Int,
  watching: Boolean,
  report: (String, Boolean) -> Unit,
): SwipeToDismissBoxState {
  val state = rememberSwipeToDismissBoxState()
  val generation = LocalGuestGeneration.current
  val send by rememberUpdatedState(report)
  val landed = remember(generation) { mutableStateOf<String?>(null) }

  LaunchedEffect(targetSequence, generation) {
    if (targetSequence == 0) return@LaunchedEffect
    // Recorded before the row moves; see the drawer mirror for what happens when it is not.
    landed.value = targetState
    when (targetState) {
      SWIPE_START_TO_END -> state.dismiss(SwipeToDismissBoxValue.StartToEnd)
      SWIPE_END_TO_START -> state.dismiss(SwipeToDismissBoxValue.EndToStart)
      // An unknown direction resets rather than guessing one. A row a client cannot place stays on
      // screen; the alternative is a row that disappears for a reason nobody can name (ADR-035).
      else -> state.reset()
    }
    // Read back, because a direction the widget was told not to allow simply does not move it, and
    // a guest that heard otherwise would delete a row still on screen.
    val name = state.currentValue.wireName()
    landed.value = name
    if (watching) send(name, false)
  }

  if (watching) {
    LaunchedEffect(state, generation) {
      snapshotFlow { state.currentValue.wireName() }
        .distinctUntilChanged()
        .drop(1)
        .collect { name -> if (name != landed.value) send(name, true) }
    }
  }
  return state
}

private const val SWIPE_SETTLED = "settled"
private const val SWIPE_START_TO_END = "startToEnd"
private const val SWIPE_END_TO_START = "endToStart"

@OptIn(ExperimentalMaterial3Api::class)
private fun SwipeToDismissBoxValue.wireName(): String = when (this) {
  SwipeToDismissBoxValue.StartToEnd -> SWIPE_START_TO_END
  SwipeToDismissBoxValue.EndToStart -> SWIPE_END_TO_START
  SwipeToDismissBoxValue.Settled -> SWIPE_SETTLED
}

/**
 * Expands and collapses a search bar, and reports where the user leaves it.
 *
 * Whether the bar is open, and deliberately nothing about what is typed in it: a search field's
 * text is a versioned round trip with its own protocol (ADR-019), and a second copy riding here
 * would be a field that drops keystrokes under load.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberLibrarySearchBarState(
  targetState: String,
  targetSequence: Int,
  watching: Boolean,
  report: (String, Boolean) -> Unit,
): SearchBarState {
  val state = rememberSearchBarState()
  val generation = LocalGuestGeneration.current
  val send by rememberUpdatedState(report)
  val landed = remember(generation) { mutableStateOf<String?>(null) }

  LaunchedEffect(targetSequence, generation) {
    if (targetSequence == 0) return@LaunchedEffect
    val wanted = if (targetState == SEARCH_EXPANDED) SEARCH_EXPANDED else SEARCH_COLLAPSED
    // Before the animation, for the reason the drawer mirror records: a search bar reads as
    // expanded partway through expanding.
    landed.value = wanted
    if (wanted == SEARCH_EXPANDED) state.animateToExpanded() else state.animateToCollapsed()
    val name = state.wireName()
    landed.value = name
    if (watching) send(name, false)
  }

  if (watching) {
    LaunchedEffect(state, generation) {
      snapshotFlow { state.wireName() }
        .distinctUntilChanged()
        .drop(1)
        .collect { name -> if (name != landed.value) send(name, true) }
    }
  }
  return state
}

@OptIn(ExperimentalMaterial3Api::class)
private fun SearchBarState.wireName(): String =
  if (currentValue == SearchBarValue.Expanded) SEARCH_EXPANDED else SEARCH_COLLAPSED

private const val SEARCH_EXPANDED = "expanded"
private const val SEARCH_COLLAPSED = "collapsed"

/**
 * Drives a slider that owns its own value, and reports where the user leaves the thumb.
 *
 * The state-driven overload exists so a drag does not cross the boundary frame by frame: the
 * library settles the thumb and this reports once, where the controlled overload would echo every
 * intermediate value back to a guest that is a network away.
 *
 * @param steps discrete stops between the ends. **Clamped, not trusted**: `Slider(state)` calls
 *   `require(state.steps >= 0)`, so a payload sending a negative number would throw inside
 *   composition and take the screen down on every client that received it (ADR-035).
 * @param rangeStart the low end of the track, and [rangeEnd] the high end. An empty or inverted
 *   track is replaced by `0f..1f` rather than passed on: the library divides by the track's width
 *   to place the thumb, and a zero width puts a `NaN` into the layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberLibrarySliderState(
  targetValue: Float,
  targetSequence: Int,
  watching: Boolean,
  steps: Int,
  rangeStart: Float,
  rangeEnd: Float,
  report: (Float, Boolean) -> Unit,
): SliderState {
  val range = trackOrDefault(rangeStart, rangeEnd)
  val stops = steps.coerceIn(0, MAX_SLIDER_STEPS)
  /*
   * Seeded at the track's start, not at the target.
   *
   * The state object is remembered on the track's shape alone -- it has to be, because `steps` and
   * `valueRange` are `val` on it and the value is not. Seeding it from a target would be reading a
   * value that changes inside a `remember` that will not re-run, so the *first* target would land
   * and no later one would. Every target, including the first, goes through the effect below: one
   * code path, and the thumb's starting position is the track's start exactly as a drawer's is
   * closed and a sheet's is hidden.
   */
  val state = remember(stops, range.start, range.endInclusive) {
    SliderState(value = range.start, steps = stops, valueRange = range)
  }
  val generation = LocalGuestGeneration.current
  val send by rememberUpdatedState(report)
  // Where the guest's last request left the thumb, **after** the library snapped it to a step.
  // Recorded rather than compared against the target for exactly that reason: a slider with four
  // stops asked for 0.7 settles on 0.75, and a mirror comparing the report against 0.7 would tell
  // the guest a user had dragged it.
  val landed = remember(generation) { mutableStateOf(Float.NaN) }

  LaunchedEffect(targetSequence, generation) {
    if (targetSequence == 0) return@LaunchedEffect
    landed.value = targetValue.coerceIn(range.start, range.endInclusive)
    state.value = landed.value
    // Re-read, because the library may have snapped the value to a step.
    landed.value = state.value
    if (watching) send(state.value, false)
  }

  if (watching) {
    LaunchedEffect(state, generation) {
      snapshotFlow { state.value }
        .distinctUntilChanged()
        .drop(1)
        .collect { value -> if (value != landed.value) send(value, true) }
    }
  }
  return state
}

/** The two-thumb twin of [rememberLibrarySliderState]; the same rules, one more number. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberLibraryRangeSliderState(
  targetStart: Float,
  targetEnd: Float,
  targetSequence: Int,
  watching: Boolean,
  steps: Int,
  rangeStart: Float,
  rangeEnd: Float,
  report: (Float, Float, Boolean) -> Unit,
): RangeSliderState {
  val range = trackOrDefault(rangeStart, rangeEnd)
  val stops = steps.coerceIn(0, MAX_SLIDER_STEPS)
  // Seeded at the whole track, not at the target, for the reason the single-thumb mirror gives.
  val state = remember(stops, range.start, range.endInclusive) {
    RangeSliderState(
      activeRangeStart = range.start,
      activeRangeEnd = range.endInclusive,
      steps = stops,
      valueRange = range,
    )
  }
  val generation = LocalGuestGeneration.current
  val send by rememberUpdatedState(report)
  // Both thumbs where the guest's last request left them, after snapping; see the single-thumb
  // mirror for why this is recorded rather than compared against the target.
  val landed = remember(generation) { mutableStateOf(Float.NaN to Float.NaN) }

  LaunchedEffect(targetSequence, generation) {
    if (targetSequence == 0) return@LaunchedEffect
    val start = targetStart.coerceIn(range.start, range.endInclusive)
    landed.value = start to targetEnd.coerceIn(start, range.endInclusive)
    // The high thumb never passes the low one. The library coerces each assignment against the
    // other thumb, so the order these two run in decides the outcome when a payload sends them the
    // wrong way round; doing it explicitly is what makes that a decision rather than an accident.
    state.activeRangeStart = start
    state.activeRangeEnd = targetEnd.coerceIn(start, range.endInclusive)
    landed.value = state.activeRangeStart to state.activeRangeEnd
    if (watching) send(state.activeRangeStart, state.activeRangeEnd, false)
  }

  if (watching) {
    LaunchedEffect(state, generation) {
      snapshotFlow { state.activeRangeStart to state.activeRangeEnd }
        .distinctUntilChanged()
        .drop(1)
        .collect { thumbs ->
          if (thumbs != landed.value) send(thumbs.first, thumbs.second, true)
        }
    }
  }
  return state
}

/**
 * A payload's track, or the unit track when what arrived cannot be one.
 *
 * Empty and inverted are both refused, and `NaN` with them: the library places a thumb by dividing
 * by the track's width, so a zero or negative width is a `NaN` in the layout rather than an
 * exception anybody can trace back here.
 */
private fun trackOrDefault(start: Float, end: Float): ClosedFloatingPointRange<Float> =
  if (start.isFinite() && end.isFinite() && end > start) start..end else 0f..1f

/**
 * More stops than a screen has pixels is a payload asking for nothing anyone can see, and the
 * library allocates a tick fraction per stop. A bound rather than a refusal, for ADR-035's reason.
 */
private const val MAX_SLIDER_STEPS = 1000

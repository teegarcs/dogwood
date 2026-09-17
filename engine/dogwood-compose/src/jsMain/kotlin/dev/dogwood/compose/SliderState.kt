/*
 * Project Dogwood -- the guest's half of a slider that owns its own value.
 *
 * Material 3 ships two ways to drive a slider. The controlled one takes a `value` and an
 * `onValueChange` and is already bound as ordinary properties and an ordinary event; this is the
 * other one, where the library owns a state object and the guest mirrors it.
 *
 * The difference that matters is **who decides when a drag is finished**. A controlled slider
 * sends every intermediate value across the boundary, which is a change per frame of a drag; a
 * state-driven one reports when the value settles. Over a latent boundary that is the difference
 * between a slider and a flood, and it is why this shape reports rather than echoing.
 *
 * `steps` and the range are construction-time, exactly as a sheet's `skipPartiallyExpanded` is:
 * they change what the *gesture* does, and a guest moving the stops mid-drag would be changing the
 * rules under the user's finger. Material's own `SliderState` agrees -- both are `val` on it.
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

/** A single-thumb slider's value: what the guest asked for, and what the host last reported. */
class SliderState internal constructor(
  initialValue: Float,
  /** Discrete stops between the ends, or zero for a continuous slider. */
  @property:DogwoodGeneratedApi val steps: Int,
  /** The low end of the track. */
  @property:DogwoodGeneratedApi val rangeStart: Float,
  /** The high end of the track. */
  @property:DogwoodGeneratedApi val rangeEnd: Float,
) {

  /** Where the host last said the thumb is. The initial value until it says otherwise. */
  var currentValue: Float by mutableStateOf(initialValue)
    private set

  /** True when the last change came from the user rather than from a request of this guest's. */
  var lastChangeByUser: Boolean by mutableStateOf(false)
    private set

  @DogwoodGeneratedApi
  var targetValue: Float by mutableStateOf(initialValue)
    private set

  /** Zero means nothing has ever been asked for. A counter, so asking twice is two requests. */
  @DogwoodGeneratedApi
  var targetSequence: Int by mutableStateOf(0)
    private set

  /** Whether anyone is reading the reports. Set when a composition reads [value]. */
  @DogwoodGeneratedApi
  var watching: Boolean by mutableStateOf(false)
    internal set

  /** The thing a screen branches on. Reading it is what turns reporting on. */
  val value: Float
    get() {
      watching = true
      return currentValue
    }

  /** The track, for a guest that wants to show its ends. */
  val valueRange: FloatRange get() = FloatRange(rangeStart, rangeEnd)

  /** Moves the thumb, as though the user had. A request, so asking for the same value twice is twice. */
  fun moveTo(value: Float) {
    targetValue = value
    targetSequence += 1
  }

  /** Called from the host's report. */
  @DogwoodGeneratedApi
  fun report(value: Float, byUser: Boolean) {
    currentValue = value
    lastChangeByUser = byUser
  }

  companion object {
    /** Saves where the thumb was and how the track is shaped, and not the request counter. */
    val Saver: Saver<SliderState, Any> = listSaver(
      save = { listOf(it.currentValue, it.steps, it.rangeStart, it.rangeEnd) },
      restore = {
        SliderState(it[0] as Float, it[1] as Int, it[2] as Float, it[3] as Float)
      },
    )
  }
}

/** Remembers a slider's value across recomposition **and across a code update**. */
@Composable
fun rememberSliderState(
  initialValue: Float = 0f,
  steps: Int = 0,
  valueRange: FloatRange = FloatRange(0f, 1f),
): SliderState = rememberSaveable(saver = SliderState.Saver) {
  SliderState(initialValue, steps, valueRange.start, valueRange.end)
}

/** A two-thumb slider's selection: what the guest asked for, and what the host last reported. */
class RangeSliderState internal constructor(
  initialStart: Float,
  initialEnd: Float,
  @property:DogwoodGeneratedApi val steps: Int,
  @property:DogwoodGeneratedApi val rangeStart: Float,
  @property:DogwoodGeneratedApi val rangeEnd: Float,
) {

  /** Where the host last said the low thumb is. */
  var currentStart: Float by mutableStateOf(initialStart)
    private set

  /** Where the host last said the high thumb is. */
  var currentEnd: Float by mutableStateOf(initialEnd)
    private set

  /** True when the last change came from the user rather than from a request of this guest's. */
  var lastChangeByUser: Boolean by mutableStateOf(false)
    private set

  @DogwoodGeneratedApi
  var targetStart: Float by mutableStateOf(initialStart)
    private set

  @DogwoodGeneratedApi
  var targetEnd: Float by mutableStateOf(initialEnd)
    private set

  /** Zero means nothing has ever been asked for. A counter, so asking twice is two requests. */
  @DogwoodGeneratedApi
  var targetSequence: Int by mutableStateOf(0)
    private set

  /** Whether anyone is reading the reports. Set when a composition reads [selection]. */
  @DogwoodGeneratedApi
  var watching: Boolean by mutableStateOf(false)
    internal set

  /** The thing a screen branches on. Reading it is what turns reporting on. */
  val selection: FloatRange
    get() {
      watching = true
      return FloatRange(currentStart, currentEnd)
    }

  /** The track, for a guest that wants to show its ends. */
  val valueRange: FloatRange get() = FloatRange(rangeStart, rangeEnd)

  /** Moves both thumbs. One request, because a selection is one thing the user sees. */
  fun selectRange(selection: FloatRange) {
    targetStart = selection.start
    targetEnd = selection.end
    targetSequence += 1
  }

  /** Called from the host's report. */
  @DogwoodGeneratedApi
  fun report(start: Float, end: Float, byUser: Boolean) {
    currentStart = start
    currentEnd = end
    lastChangeByUser = byUser
  }

  companion object {
    /** Saves where the thumbs were and how the track is shaped, and not the request counter. */
    val Saver: Saver<RangeSliderState, Any> = listSaver(
      save = { listOf(it.currentStart, it.currentEnd, it.steps, it.rangeStart, it.rangeEnd) },
      restore = {
        RangeSliderState(it[0] as Float, it[1] as Float, it[2] as Int, it[3] as Float, it[4] as Float)
      },
    )
  }
}

/** Remembers a range slider's selection across recomposition **and across a code update**. */
@Composable
fun rememberRangeSliderState(
  initialSelection: FloatRange = FloatRange(0f, 1f),
  steps: Int = 0,
  valueRange: FloatRange = FloatRange(0f, 1f),
): RangeSliderState = rememberSaveable(saver = RangeSliderState.Saver) {
  RangeSliderState(
    initialSelection.start,
    initialSelection.end,
    steps,
    valueRange.start,
    valueRange.end,
  )
}

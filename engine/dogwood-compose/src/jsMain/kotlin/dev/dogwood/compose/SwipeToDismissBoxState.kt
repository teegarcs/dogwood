/*
 * Project Dogwood -- the guest's half of a swipe-to-dismiss row.
 *
 * A target plus a report of **which way the user went**, and that second half is the whole point.
 * A row swiped away to the start and a row swiped away to the end mean different things in every
 * inbox ever built -- archive one way, delete the other -- and a guest that only learned "it is
 * gone" would have to guess which action to take.
 *
 * The target half exists because a dismissal is not always the user's. A guest that has just
 * undone a delete needs to put the row back, and a guest acting on a selection needs to sweep a
 * row away without anyone touching it.
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

/**
 * Where a dismissible row can be.
 *
 * The direction is named for the *reading* direction rather than for left and right, which is what
 * Material's own vocabulary does and what a right-to-left locale needs: `START_TO_END` is the
 * swipe that begins at the edge a line of text begins at, whichever edge that is on this device.
 */
object SwipeToDismissValues {
  /** Not dismissed: the row is where it belongs. */
  const val SETTLED = "settled"

  /** Swiped away in the reading direction. */
  const val START_TO_END = "startToEnd"

  /** Swiped away against the reading direction. */
  const val END_TO_START = "endToStart"
}

/** A dismissible row's position: what the guest asked for, and what the host last reported. */
class SwipeToDismissBoxState internal constructor(initialState: String) {

  /** Where the host last said the row is. [SwipeToDismissValues.SETTLED] until it says otherwise. */
  var currentState: String by mutableStateOf(initialState)
    private set

  /** True when the last change came from the user rather than from a request of this guest's. */
  var lastChangeByUser: Boolean by mutableStateOf(false)
    private set

  @DogwoodGeneratedApi
  var targetState: String by mutableStateOf(initialState)
    private set

  /** Zero means nothing has ever been asked for. A counter, so asking twice is two requests. */
  @DogwoodGeneratedApi
  var targetSequence: Int by mutableStateOf(0)
    private set

  /** Whether anyone is reading the reports. Set when a composition reads [currentDirection]. */
  @DogwoodGeneratedApi
  var watching: Boolean by mutableStateOf(false)
    internal set

  /**
   * Which way the row went, or [SwipeToDismissValues.SETTLED] if it has not.
   *
   * The thing a screen branches on, and reading it is what turns reporting on.
   */
  val currentDirection: String
    get() {
      watching = true
      return currentState
    }

  /** True once the row has been swiped away, either way. */
  val isDismissed: Boolean get() = currentDirection != SwipeToDismissValues.SETTLED

  /** Sweeps the row away in the reading direction, as though the user had. */
  fun dismissToEnd() = declare(SwipeToDismissValues.START_TO_END)

  /** Sweeps the row away against the reading direction. */
  fun dismissToStart() = declare(SwipeToDismissValues.END_TO_START)

  /** Puts the row back: what an undo does. */
  fun reset() = declare(SwipeToDismissValues.SETTLED)

  private fun declare(state: String) {
    targetState = state
    targetSequence += 1
  }

  /** Called from the host's report. */
  @DogwoodGeneratedApi
  fun report(state: String, byUser: Boolean) {
    // An unknown direction reads as settled, which is the safe direction here as everywhere: a row
    // a guest cannot place stays on screen rather than disappearing for a reason nobody can name.
    currentState = when (state) {
      SwipeToDismissValues.START_TO_END, SwipeToDismissValues.END_TO_START -> state
      else -> SwipeToDismissValues.SETTLED
    }
    lastChangeByUser = byUser
  }

  companion object {
    /** Saves where the row was and not the request counter; see [DrawerState.Saver]. */
    val Saver: Saver<SwipeToDismissBoxState, Any> = listSaver(
      save = { listOf(it.currentState) },
      restore = { SwipeToDismissBoxState(it[0] as String) },
    )
  }
}

/** Remembers a row's dismissal across recomposition **and across a code update**. */
@Composable
fun rememberSwipeToDismissBoxState(
  initialState: String = SwipeToDismissValues.SETTLED,
): SwipeToDismissBoxState =
  rememberSaveable(saver = SwipeToDismissBoxState.Saver) { SwipeToDismissBoxState(initialState) }

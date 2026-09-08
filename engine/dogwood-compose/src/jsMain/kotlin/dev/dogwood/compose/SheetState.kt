/*
 * Project Dogwood -- the guest's half of a bottom sheet.
 *
 * The fifth holder shape, and the first whose report keeps arriving. A sheet has a position the
 * **user** can change -- dragged half open, flung shut, settled expanded -- so this declares where
 * the sheet should be and receives where it is, repeatedly. That is scroll's shape with snackbar's
 * meaning: a continuous report whose changes are decisions somebody made.
 *
 * Everything here is ordinary guest state. The host owns the drag, the animation and the platform's
 * own sheet; this owns the *intent* and the last thing it was told.
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

/** Where a sheet can be. Strings on the wire; see the shape table for why not an integer. */
object SheetValues {
  const val HIDDEN = "hidden"
  const val PARTIAL = "partial"
  const val EXPANDED = "expanded"
}

/**
 * A bottom sheet's position: what the guest asked for, and what the host last reported.
 *
 * @param skipPartiallyExpanded a sheet that is either shut or fully open, with no half stop. It is
 *   a construction-time property rather than a request because it changes what the *gesture* does,
 *   and a guest flipping it mid-drag would be changing the rules under the user's finger.
 */
class SheetState internal constructor(
  initialState: String,
  @property:DogwoodGeneratedApi val skipPartiallyExpanded: Boolean,
) {
  /** Where the host last said the sheet is. `hidden` until it says otherwise. */
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

  /**
   * Whether anyone is reading the reports.
   *
   * The host cannot see guest closures, so it cannot know whether reporting would be observed. Set
   * when a composition actually reads [currentState] -- see [isVisible] and [rememberSheetState].
   */
  @DogwoodGeneratedApi
  var watching: Boolean by mutableStateOf(false)
    internal set

  /** True unless the sheet is shut. The ordinary thing a screen branches on. */
  val isVisible: Boolean
    get() {
      watching = true
      return currentState != SheetValues.HIDDEN
    }

  fun show() = declare(if (skipPartiallyExpanded) SheetValues.EXPANDED else SheetValues.PARTIAL)

  fun expand() = declare(SheetValues.EXPANDED)

  fun hide() = declare(SheetValues.HIDDEN)

  private fun declare(state: String) {
    targetState = state
    targetSequence += 1
  }

  /** Called from the host's report. */
  @DogwoodGeneratedApi
  fun report(state: String, byUser: Boolean) {
    // An unknown state reads as hidden -- the safe direction, and the reason the vocabulary crosses
    // as a string: a client one version ahead can name a position this guest has never heard of,
    // and a sheet nobody asked for staying shut is better than one resolving to the wrong stop.
    currentState = when (state) {
      SheetValues.PARTIAL, SheetValues.EXPANDED, SheetValues.HIDDEN -> state
      else -> SheetValues.HIDDEN
    }
    lastChangeByUser = byUser
  }

  companion object {
    /**
     * Saves where the sheet was and how it behaves, and **not** the request counter.
     *
     * The same reasoning as the focus requester's: a restored holder must not re-fire a request the
     * user has since moved on from. What survives a code update is the sheet's position, restored
     * by declaring it as a fresh target -- which is how every holder here restores.
     */
    val Saver: Saver<SheetState, Any> = listSaver(
      save = { listOf(it.currentState, it.skipPartiallyExpanded) },
      restore = { SheetState(it[0] as String, it[1] as Boolean) },
    )
  }
}

/** Remembers a sheet's position across recomposition **and across a code update**. */
@Composable
fun rememberSheetState(
  initialState: String = SheetValues.HIDDEN,
  skipPartiallyExpanded: Boolean = false,
): SheetState = rememberSaveable(saver = SheetState.Saver) {
  SheetState(initialState, skipPartiallyExpanded)
}

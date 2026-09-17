/*
 * Project Dogwood -- the guest's half of a search bar's expansion.
 *
 * Two positions, collapsed and expanded, driven from either end. A guest expands the bar when the
 * user taps a "search" control somewhere else on the screen, and collapses it when a result is
 * chosen; the user expands and collapses it directly. Target down, report up, host authoritative,
 * exactly as [DrawerState].
 *
 * **What is deliberately not here is the query text.** A search field's text is a versioned round
 * trip across a latent boundary and has its own protocol ([TextFieldState], ADR-019); a second
 * copy of it riding on this holder would be a text field that drops keystrokes under load. This
 * holder owns whether the bar is *open*, and nothing about what is typed in it.
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

/** Where a search bar can be. Strings on the wire, for the reason [SheetValues] gives. */
object SearchBarValues {
  const val COLLAPSED = "collapsed"
  const val EXPANDED = "expanded"
}

/** A search bar's expansion: what the guest asked for, and what the host last reported. */
class SearchBarState internal constructor(initialState: String) {

  /** Where the host last said the bar is. The initial value until it says otherwise. */
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

  /** Whether anyone is reading the reports. Set when a composition reads [isExpanded]. */
  @DogwoodGeneratedApi
  var watching: Boolean by mutableStateOf(false)
    internal set

  /** The ordinary thing a screen branches on. Reading it is what turns reporting on. */
  val isExpanded: Boolean
    get() {
      watching = true
      return currentState == SearchBarValues.EXPANDED
    }

  fun expand() = declare(SearchBarValues.EXPANDED)

  fun collapse() = declare(SearchBarValues.COLLAPSED)

  private fun declare(state: String) {
    targetState = state
    targetSequence += 1
  }

  /** Called from the host's report. */
  @DogwoodGeneratedApi
  fun report(state: String, byUser: Boolean) {
    // Anything that is not `expanded` reads as collapsed: a bar a guest cannot place covers
    // nothing, which is the safe direction for a control that takes over the top of a screen.
    currentState =
      if (state == SearchBarValues.EXPANDED) SearchBarValues.EXPANDED else SearchBarValues.COLLAPSED
    lastChangeByUser = byUser
  }

  companion object {
    /** Saves where the bar was and not the request counter; see [DrawerState.Saver]. */
    val Saver: Saver<SearchBarState, Any> = listSaver(
      save = { listOf(it.currentState) },
      restore = { SearchBarState(it[0] as String) },
    )
  }
}

/** Remembers a search bar's expansion across recomposition **and across a code update**. */
@Composable
fun rememberSearchBarState(initialState: String = SearchBarValues.COLLAPSED): SearchBarState =
  rememberSaveable(saver = SearchBarState.Saver) { SearchBarState(initialState) }

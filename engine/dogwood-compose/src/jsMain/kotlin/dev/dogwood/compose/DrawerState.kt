/*
 * Project Dogwood -- the guest's half of a navigation drawer.
 *
 * The same shape as [SheetState] and for the same reason: a drawer has a position the **user** can
 * change -- swiped open, flung shut, tapped away on the scrim -- so the guest declares where it
 * should be and the host reports where it *is*, repeatedly. Target down, report up, host
 * authoritative ([ADR-043](../../../../../../adrs/layer-5/ADR-043-holders-are-declared-on-the-surface.md)).
 *
 * It is a separate class from `SheetState` rather than a reuse, because the two have different
 * vocabularies and the vocabulary is the part a guest writes. A sheet has three stops -- hidden,
 * partially expanded, expanded -- and a drawer has two. Folding them together would give every
 * drawer a `partial` position that means nothing and every sheet an `open` that means one of two
 * different things, and a guest would have to know which widget it was talking to before it could
 * read the answer.
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

/** Where a drawer can be. Strings on the wire, for the reason [SheetValues] gives. */
object DrawerValues {
  const val CLOSED = "closed"
  const val OPEN = "open"
}

/**
 * A navigation drawer's position: what the guest asked for, and what the host last reported.
 *
 * The host owns the drag, the scrim and the animation. This owns the *intent* and the last thing
 * it was told.
 */
class DrawerState internal constructor(initialState: String) {

  /** Where the host last said the drawer is. The initial value until it says otherwise. */
  var currentState: String by mutableStateOf(initialState)
    private set

  /**
   * True when the last change came from the user rather than from a request of this guest's.
   *
   * The field a guest needs to implement "they closed it, stop offering it" and cannot derive from
   * position alone -- a drawer that is shut because the guest shut it says nothing about what the
   * user wants.
   */
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
   * The host cannot see guest closures, so it cannot know whether reporting would be observed.
   * Set when a composition actually reads [isOpen].
   */
  @DogwoodGeneratedApi
  var watching: Boolean by mutableStateOf(false)
    internal set

  /** The ordinary thing a screen branches on. Reading it is what turns reporting on. */
  val isOpen: Boolean
    get() {
      watching = true
      return currentState == DrawerValues.OPEN
    }

  fun open() = declare(DrawerValues.OPEN)

  fun close() = declare(DrawerValues.CLOSED)

  private fun declare(state: String) {
    targetState = state
    targetSequence += 1
  }

  /** Called from the host's report. */
  @DogwoodGeneratedApi
  fun report(state: String, byUser: Boolean) {
    // Anything that is not `open` reads as closed -- the safe direction, and the reason the
    // vocabulary crosses as a string rather than a flag: a host one version ahead can name a
    // position this guest has never heard of, and a drawer nobody asked for staying shut is better
    // than one the guest believes is open over the screen.
    currentState = if (state == DrawerValues.OPEN) DrawerValues.OPEN else DrawerValues.CLOSED
    lastChangeByUser = byUser
  }

  companion object {
    /**
     * Saves where the drawer was and **not** the request counter.
     *
     * The focus requester's reasoning, which every holder here follows: a restored holder must not
     * re-fire a request the user has since moved on from. What survives a code update is the
     * position, restored by declaring it as a fresh target.
     */
    val Saver: Saver<DrawerState, Any> = listSaver(
      save = { listOf(it.currentState) },
      restore = { DrawerState(it[0] as String) },
    )
  }
}

/** Remembers a drawer's position across recomposition **and across a code update**. */
@Composable
fun rememberDrawerState(initialState: String = DrawerValues.CLOSED): DrawerState =
  rememberSaveable(saver = DrawerState.Saver) { DrawerState(initialState) }

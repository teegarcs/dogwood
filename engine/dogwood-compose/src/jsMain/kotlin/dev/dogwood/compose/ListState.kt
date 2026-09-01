/*
 * Project Dogwood -- the first live-state holder.
 *
 * roadmap.md Phase 4 names roughly thirty holder types and says to start with `LazyListState`.
 * It is the right one to start with because it is the hardest case stated plainly: **scroll offset
 * changes every frame**, and Layer 4's standing invariant is that no per-frame state lives in the
 * guest. A holder the guest owned would tick the boundary sixty times a second for as long as a
 * finger is moving.
 *
 * So the host owns the real `LazyListState` and this is a *mirror*, and the mirror is asymmetric
 * on purpose:
 *
 *   - **Reads are reports, and they are stale by design.** The host sends the visible range when
 *     it *changes by an item*, not when it changes by a pixel. A guest that renders "showing 3 to
 *     8" is correct; a guest that tries to drive a parallax effect from this is asking for
 *     per-frame state and will not get it.
 *   - **Writes are declared targets, not commands.** `scrollToItem` records where the guest wants
 *     to be and bumps a sequence number. The pair crosses as ordinary properties on the list
 *     widget, so it rides the existing change channel, arrives in order with everything else in
 *     the same composition pass, and needs no new protocol.
 *
 * The conflict rule falls out of that shape rather than being bolted on: **the host is
 * authoritative for where the list actually is, and the newest guest target wins.** A stale target
 * cannot arrive, because a property carries only its latest value — a guest that asked for item 40
 * and then item 0 in the same pass sends one property set, for 0.
 *
 * See `adrs/layer-5/ADR-014-live-state-holders.md`.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * A guest-side view of a host-owned lazy list.
 *
 * @param initialFirstVisibleItemIndex where the list should start. A non-zero value is issued as a
 *   scroll target on the first composition, which is also how a restored position is reapplied
 *   after a code update.
 */
class DogwoodLazyListState internal constructor(initialFirstVisibleItemIndex: Int = 0) {

  /**
   * The first item the host reported as visible.
   *
   * Reported, not measured: this guest has no layout, no viewport and no scroll offset. The value
   * is as fresh as the last report, which is one per item boundary crossed.
   */
  var firstVisibleItemIndex: Int by mutableStateOf(initialFirstVisibleItemIndex)
    private set

  /** The last item the host reported as visible, or -1 before the first report. */
  var lastVisibleItemIndex: Int by mutableStateOf(-1)
    private set

  /**
   * Whether the host reported a scroll in progress.
   *
   * Useful for the thing it is actually for -- suspending expensive work while a finger is down --
   * and useless for anything frame-accurate, because it arrives with the same latency as
   * everything else that crosses.
   */
  var isScrollInProgress: Boolean by mutableStateOf(false)
    private set

  internal var targetIndex: Int by mutableStateOf(initialFirstVisibleItemIndex)
    private set

  internal var targetAnimated: Boolean by mutableStateOf(false)
    private set

  /**
   * Zero means "no target has ever been declared".
   *
   * A sequence rather than a flag, because two consecutive requests for the *same* index are two
   * requests: a user who taps "back to top", scrolls away, and taps it again expects to go back.
   * With a flag the second tap would change no property and cross nothing.
   */
  internal var targetSequence: Int by mutableStateOf(0)
    private set

  init {
    // A restored position is a target like any other, so restoring needs no separate mechanism.
    if (initialFirstVisibleItemIndex != 0) targetSequence = 1
  }

  /** Declares where this list should be. The host gets there however it gets there. */
  fun scrollToItem(index: Int) = declare(index, animated = false)

  /** As [scrollToItem], but the host animates. Completion is not reported; see the ADR. */
  fun animateScrollToItem(index: Int) = declare(index, animated = true)

  private fun declare(index: Int, animated: Boolean) {
    targetIndex = index
    targetAnimated = animated
    targetSequence += 1
  }

  /** Called from the host's viewport report. */
  internal fun report(first: Int, last: Int, scrolling: Boolean) {
    // All three are snapshot state with structural equality, so a report that says nothing new
    // invalidates nothing and costs no frame.
    firstVisibleItemIndex = first
    lastVisibleItemIndex = last
    isScrollInProgress = scrolling
  }

  companion object {
    /**
     * Saves the position and nothing else.
     *
     * A holder is not a value, so what survives a code update is where the user was, not the
     * object. Restoring a position is reissuing a target, which is exactly what the constructor
     * does with a non-zero index -- so the round trip needs no special case at either end.
     */
    val Saver: Saver<DogwoodLazyListState, Int> = Saver(
      save = { it.firstVisibleItemIndex },
      restore = { DogwoodLazyListState(it) },
    )
  }
}

/**
 * Remembers a list position across recomposition **and across a code update**.
 *
 * The second half is the one that matters. Layer 4 treats a code update while a screen is live as
 * the normal case, and a list that jumped back to the top every time a developer published would
 * make that normal case feel like a crash.
 */
@Composable
fun rememberDogwoodLazyListState(
  initialFirstVisibleItemIndex: Int = 0,
): DogwoodLazyListState = rememberSaveable(saver = DogwoodLazyListState.Saver) {
  DogwoodLazyListState(initialFirstVisibleItemIndex)
}

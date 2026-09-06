/*
 * Project Dogwood -- the second live-state holder, and the first generated one.
 *
 * `LazyListState` established the shape ([ADR-014](../../../../../../../../adrs/layer-5/ADR-014-live-state-holders.md)):
 * the host owns the real object, the guest holds a mirror, **targets go down and reports come up**.
 * It was hand-written on both sides of the boundary, which is affordable once and not thirty times
 * -- the corrected coverage measurement counts roughly thirty holder types.
 *
 * This one is declared on the surface with `@Holder` and plumbed by the generator
 * ([ADR-043](../../../../../../../../adrs/layer-5/ADR-043-holders-are-declared-on-the-surface.md)).
 * What is written by hand is this class and the host-side mirror it pairs with; the two properties
 * that carry it, on both sides of the wire, are emitted.
 *
 * **Focus is a target with nothing to report, and that is a decision.** A guest can ask for the
 * keyboard and ask to give it up. It cannot ask *whether a field is focused*, because that is a
 * per-frame question -- focus moves with every tap and every keyboard dismissal -- and a guest
 * branching on it would be holding exactly the state Layer 4 forbids. A guest that needs to know a
 * field was left has an ordinary event for it.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * A guest-side request for the focus of one host-owned field.
 *
 * @param initialSequence carried so a restored requester does not re-fire on the way back. See
 *   [Saver].
 */
class FocusRequester internal constructor(initialSequence: Int = 0) {

  /** Which way the last request went. `false` is a real request -- give the focus up. */
  internal var requested: Boolean by mutableStateOf(false)
    private set

  /**
   * Zero means no request has ever been made.
   *
   * A counter rather than a flag, for the reason `LazyListState`'s is: asking twice for the same
   * thing is two requests. A user who dismissed the keyboard and tapped the same "edit" control
   * again expects the field back, and with a flag the second tap would change no property and
   * cross nothing.
   */
  internal var sequence: Int by mutableStateOf(initialSequence)
    private set

  /** Asks the host to give this field the keyboard. */
  fun requestFocus() = declare(true)

  /** Asks the host to take the keyboard away from this field. */
  fun freeFocus() = declare(false)

  private fun declare(wanted: Boolean) {
    requested = wanted
    sequence += 1
  }

  companion object {
    /**
     * Saves the sequence and nothing else.
     *
     * Deliberately **not** the direction. Restoring `requested` would reissue the last request
     * after a code update -- so a screen the user had scrolled away from would grab the keyboard
     * back, seconds later, for no reason they could see. What the sequence buys is the opposite:
     * carrying the count forward means the *next* real request is still a change, so a restored
     * requester works exactly once more than a fresh one would.
     */
    val Saver: Saver<FocusRequester, Int> = Saver(
      save = { it.sequence },
      restore = { FocusRequester(it) },
    )
  }
}

/** Remembers a focus request across recomposition and across a code update. */
@Composable
fun rememberFocusRequester(): FocusRequester =
  rememberSaveable(saver = FocusRequester.Saver) { FocusRequester() }

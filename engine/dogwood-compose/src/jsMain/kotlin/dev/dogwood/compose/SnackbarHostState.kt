/*
 * Project Dogwood -- the fourth holder shape: a request that answers.
 *
 * The three shapes already built all run one way at a time.
 * [LazyListState][dev.dogwood.compose.LazyListState] and `ScrollState` send targets down and take
 * reports up, but the two are independent -- a report is not the answer to a target. `FocusRequester`
 * sends targets and takes nothing back at all.
 *
 * A snackbar is the first thing a guest asks for **and waits on**. `showSnackbar` suspends, and what
 * it returns decides what the guest does next: a user who tapped *Undo* gets their row back, and a
 * user who let the snackbar time out does not. That is a correlated request and reply, which no
 * existing holder needed.
 *
 * It needs no new protocol. The request goes down as ordinary properties -- message, action label,
 * sequence -- exactly as a scroll target does. The reply comes up as an ordinary event carrying
 * **the sequence it is answering**, which is what turns a report into an answer. Two snackbars in
 * flight cannot be confused, because the sequence that went down is the sequence that comes back.
 *
 * The suspension is guest-side and costs the boundary nothing: a `CompletableDeferred` per request,
 * resumed when the matching reply arrives. Nothing is polled and no frame is requested.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred

/** What became of a snackbar. */
enum class SnackbarResult {
  /** It went away on its own, or the user dismissed it. */
  DISMISSED,

  /** The user tapped the action. This is the one a guest branches on. */
  ACTION_PERFORMED,
}

/**
 * A guest-side request for a host-owned snackbar.
 *
 * One at a time, deliberately: Compose's own `SnackbarHostState` queues, and a guest that could
 * enqueue without limit across a boundary would be a guest that can fill a host's queue from a
 * server. A second request while one is outstanding replaces it, which is also what a user wants —
 * the newest message is the true one.
 */
class SnackbarHostState internal constructor() {

  internal var message: String by mutableStateOf("")
    private set

  internal var actionLabel: String? by mutableStateOf(null)
    private set

  /** Zero means nothing has ever been asked for. A counter, so the same message twice is twice. */
  internal var sequence: Int by mutableStateOf(0)
    private set

  /** Presence, for the reason every holder carries it: the host cannot see guest closures. */
  internal val watching: Boolean get() = true

  private var pending: CompletableDeferred<SnackbarResult>? = null
  private var pendingSequence: Int = 0

  /**
   * Asks the host to show a snackbar, and suspends until it is gone.
   *
   * Returns [SnackbarResult.ACTION_PERFORMED] when the user tapped the action, so the caller can
   * undo what it did. A caller that ignores the result has written a notification, not a snackbar,
   * and should say so by ignoring it deliberately.
   */
  suspend fun showSnackbar(message: String, actionLabel: String? = null): SnackbarResult {
    // A previous request that is still outstanding is answered as dismissed rather than left
    // suspended forever. Its snackbar is about to be replaced on screen, so "dismissed" is what
    // actually happened to it.
    pending?.complete(SnackbarResult.DISMISSED)

    this.message = message
    this.actionLabel = actionLabel
    sequence += 1
    pendingSequence = sequence

    val answer = CompletableDeferred<SnackbarResult>()
    pending = answer
    return answer.await()
  }

  /**
   * The host's answer, carrying the sequence it is answering.
   *
   * A reply for a sequence this holder is no longer waiting on is dropped. That is not defensive
   * tidiness: a snackbar dismissed by the host arrives *after* the guest replaced it, and resuming
   * the new request with the old one's answer would undo the wrong row.
   */
  internal fun report(sequence: Int, actionPerformed: Boolean) {
    if (sequence != pendingSequence) return
    val answer = pending ?: return
    pending = null
    answer.complete(
      if (actionPerformed) SnackbarResult.ACTION_PERFORMED else SnackbarResult.DISMISSED,
    )
  }

  companion object {
    /**
     * Saves the count and nothing else.
     *
     * Not the message, and not the pending request. A snackbar that reappeared after a code update
     * would be telling a user about something that finished before the update — and a suspended
     * `showSnackbar` cannot survive one anyway, because the coroutine it suspended does not. The
     * count carries forward so the next real request is still a change.
     */
    val Saver: Saver<SnackbarHostState, Int> = Saver(
      save = { it.sequence },
      restore = { restored -> SnackbarHostState().also { it.sequence = restored } },
    )
  }
}

/** Remembers a snackbar host across recomposition and across a code update. */
@Composable
fun rememberSnackbarHostState(): SnackbarHostState =
  rememberSaveable(saver = SnackbarHostState.Saver) { SnackbarHostState() }

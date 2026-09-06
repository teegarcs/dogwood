/*
 * Project Dogwood -- a position mirror over a continuous quantity.
 *
 * `LazyListState` ([ADR-014](../../../../../../../../adrs/layer-5/ADR-014-live-state-holders.md))
 * reports the visible range **per item**, and it can, because a list has items: the smallest change
 * a guest can act on is a boundary crossing, so `distinctUntilChanged` over the index triple is the
 * throttle and the quantum at the same time.
 *
 * A scrolling container has no items. Its offset is a length that changes every frame, which is the
 * quantity Layer 4 forbids the guest to hold, and there is no natural boundary to report on. That
 * is the whole difficulty of this holder and the reason it is not `LazyListState` with different
 * words.
 *
 * **So the guest declares the quantum.** `reportEveryDp` says how far the container must move
 * before the host says anything, it crosses as an ordinary property, and it is the honest form of
 * the trade: a guest that wants a coarse "have we scrolled at all" pays almost nothing, and one
 * that wants a smoother read pays in crossings and can see exactly what it is paying. There is no
 * value of it that yields per-frame state, because the host reports on a threshold rather than on a
 * frame.
 *
 * **The two edges are exact whatever the quantum is.** A quantised offset would never equal the
 * maximum unless the scroll landed precisely on a multiple, so "am I at the bottom?" -- which is
 * what a guest actually asks, to load the next page -- would be answerable only by accident. The
 * host reports the true value at both ends and the quantised one in between. See
 * [ADR-044](../../../../../../../../adrs/layer-5/ADR-044-scroll-position-is-a-declared-quantum.md).
 *
 * Everything else is ADR-014's shape unchanged: targets go down as ordinary properties, the newest
 * one wins because a property carries only its latest value, and the sequence is a counter so that
 * asking twice for the same place is two requests.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * A target meaning "wherever the end is".
 *
 * A guest cannot compute the end itself: the maximum offset is host layout, and the guest's copy of
 * it is as stale as its last report. Sending a number would scroll to where the end *was* -- which
 * for the case this exists for, a log or a conversation that grows while you watch it, is exactly
 * the wrong place.
 */
const val SCROLL_TO_END: Int = -1

/**
 * A guest-side view of a host-owned scrolling container.
 *
 * @param reportEveryDp how far the container must move before the host reports. Larger is cheaper
 *   and coarser. It bounds traffic rather than merely throttling it: a full scroll of a container
 *   `n` density-independent pixels tall costs at most `n / reportEveryDp` crossings however fast
 *   the finger moves. The default is roughly one line of text.
 */
class ScrollState internal constructor(
  initialOffsetDp: Int = 0,
  val reportEveryDp: Int = 48,
) {

  /**
   * The offset the host last reported, in density-independent pixels.
   *
   * Reported, not measured, and quantised by [reportEveryDp] except at the two ends. A guest that
   * treats this as the container's true position will be wrong by up to one quantum in the middle
   * and right at both edges, which is the trade this holder is.
   */
  var offsetDp: Int by mutableStateOf(initialOffsetDp)
    private set

  /**
   * The largest offset the host reported.
   *
   * Three values, and they are three different things. `-1` is "the host has not told me yet", and
   * it is set here rather than sent — the host reports nothing at all until the container has been
   * measured, because `ScrollState.maxValue` reads `Int.MAX_VALUE` before the first layout and a
   * guest cannot tell that from a real measurement. `0` is "measured, and there is nothing to
   * scroll". Anything larger is a real maximum.
   */
  var maxOffsetDp: Int by mutableStateOf(-1)
    private set

  /** Whether the host reported a scroll in progress. Useful for suspending expensive work. */
  var isScrollInProgress: Boolean by mutableStateOf(false)
    private set

  /** Exact whatever the quantum is: the host reports the true value at the ends. */
  val isAtTop: Boolean get() = offsetDp <= 0

  /** Exact whatever the quantum is. False before the first report, because nothing is known yet. */
  val isAtBottom: Boolean get() = maxOffsetDp > 0 && offsetDp >= maxOffsetDp

  /**
   * Whether the end is within [withinDp] of here.
   *
   * The question a paginating guest actually asks. It is answered against a quantised offset, so it
   * becomes true up to one quantum early -- which is the right direction to be wrong in when the
   * consequence is starting a fetch.
   */
  fun isNearEnd(withinDp: Int): Boolean =
    maxOffsetDp > 0 && maxOffsetDp - offsetDp <= withinDp

  /**
   * Always true, and it crosses as a property anyway.
   *
   * Presence has to be on the wire because the host cannot see guest closures: it cannot know
   * whether a report would be read by anyone. What makes it meaningful is the *absent* case -- a
   * container composed without a holder sends nothing, the host reads false, and no observer is
   * installed. Deriving it host-side from "did any scroll property arrive" would work today and
   * break the first time a holder crossed without being read.
   */
  internal val watching: Boolean get() = true

  internal var targetDp: Int by mutableStateOf(initialOffsetDp)
    private set

  internal var targetAnimated: Boolean by mutableStateOf(false)
    private set

  /** Zero means no target has ever been declared. A counter, so asking twice is two requests. */
  internal var targetSequence: Int by mutableStateOf(0)
    private set

  init {
    // A restored position is a target like any other.
    if (initialOffsetDp != 0) targetSequence = 1
  }

  /** Declares where this container should be, in density-independent pixels from the start. */
  fun scrollTo(offsetDp: Int) = declare(offsetDp, animated = false)

  /** As [scrollTo], but the host animates. Completion is not reported; see ADR-014. */
  fun animateScrollTo(offsetDp: Int) = declare(offsetDp, animated = true)

  fun scrollToTop() = scrollTo(0)

  /** Declares [SCROLL_TO_END], so the host resolves the end against the layout it actually has. */
  fun scrollToEnd() = declare(SCROLL_TO_END, animated = false)

  fun animateScrollToEnd() = declare(SCROLL_TO_END, animated = true)

  private fun declare(offsetDp: Int, animated: Boolean) {
    targetDp = offsetDp
    targetAnimated = animated
    targetSequence += 1
  }

  /** Called from the host's scroll report. */
  internal fun report(offsetDp: Int, maxOffsetDp: Int, scrolling: Boolean) {
    // Snapshot state with structural equality, so a report that says nothing new invalidates
    // nothing. The host already suppresses those; this makes a duplicate free rather than merely
    // rare.
    this.offsetDp = offsetDp
    this.maxOffsetDp = maxOffsetDp
    this.isScrollInProgress = scrolling
  }

  companion object {
    /**
     * Saves the position and the quantum.
     *
     * The quantum too, because it is a constructor argument rather than mutable state: a restored
     * holder built with the default would quietly start reporting at a different granularity than
     * the guest asked for, and nothing would look wrong.
     *
     * The position is restored by **reissuing it as a target**, which is what the non-zero-offset
     * constructor does — so restore needs no separate path at either end, exactly as with a list.
     */
    val Saver: Saver<ScrollState, Any> = listSaver(
      save = { listOf(it.offsetDp, it.reportEveryDp) },
      restore = { ScrollState(it[0] as Int, it[1] as Int) },
    )
  }
}

/**
 * Remembers a scroll position across recomposition **and across a code update**.
 *
 * The second half is the one that matters, and it is the same argument a list makes: Layer 4 treats
 * a code update while a screen is live as the normal case, and a page that jumped back to the top
 * every time somebody published would make that normal case feel like a crash.
 */
@Composable
fun rememberScrollState(
  initialOffsetDp: Int = 0,
  reportEveryDp: Int = 48,
): ScrollState = rememberSaveable(saver = ScrollState.Saver) {
  ScrollState(initialOffsetDp, reportEveryDp)
}

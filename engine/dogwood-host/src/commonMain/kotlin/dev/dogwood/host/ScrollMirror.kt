/*
 * Project Dogwood -- the host half of the scroll holder.
 *
 * The generated binding brings five properties across and calls [rememberScrollMirror]; this is
 * what they mean, and it is where the two decisions that make this holder different from a list's
 * are actually implemented.
 *
 * **The quantum, applied.** A scrolling container's offset changes every frame, and Layer 4 forbids
 * the guest holding anything that does. A list can report per item because a list has items; this
 * has a length, so the guest declares how far it must move before the host says anything
 * ([ADR-044](../../../../../../../adrs/layer-5/ADR-044-scroll-position-is-a-declared-quantum.md)).
 *
 * **The ends are exact.** A purely quantised offset would equal the maximum only by accident, so
 * "am I at the bottom?" -- the question a paginating guest asks -- would be answerable only by
 * luck. The true value goes out at both ends and the quantised one in between.
 */
package dev.dogwood.host

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/** A target meaning "wherever the end is". Mirrors `SCROLL_TO_END` on the guest. */
private const val END = -1

/**
 * The host's scrolling container, and the guest's view of it kept in step.
 *
 * [modifierFor] is what actually makes the container scroll, and the binding attaches it whether or
 * not a guest passed a holder: a page taller than the viewport must reach its own bottom under the
 * user's finger even when nobody is watching where it is.
 *
 * The orientation is asked for here rather than held by the mirror, because it belongs to the
 * *widget* and not to the holder. A guest's `ScrollState` is a position; whether that position runs
 * down the screen or across it is a property of the container it was handed to, and a guest that
 * moved a holder between the two should not find its meaning changed underneath it.
 */
class ScrollMirror internal constructor(
  private val state: androidx.compose.foundation.ScrollState,
) {
  fun modifierFor(horizontal: Boolean): Modifier =
    if (horizontal) Modifier.horizontalScroll(state) else Modifier.verticalScroll(state)
}

@Composable
fun rememberScrollMirror(
  targetDp: Int,
  targetSequence: Int,
  targetAnimated: Boolean,
  watching: Boolean,
  reportEveryDp: Int,
  report: (Int, Int, Boolean) -> Unit = { _, _, _ -> },
): ScrollMirror {
  val state = rememberScrollState()
  val density = LocalDensity.current
  // Captured once per composition rather than read inside the effects: a density change is a
  // configuration change, which recomposes, and an effect keyed on the sequence must not restart
  // merely because the screen rotated.
  val pxPerDp = density.density

  fun toDp(px: Int): Int = (px / pxPerDp).toInt()
  fun toPx(dp: Int): Int = (dp * pxPerDp).toInt()

  val mirror = remember(state) { ScrollMirror(state) }

  if (targetSequence > 0) {
    // Keyed on the sequence, not the offset, so asking twice for the same place runs twice.
    LaunchedEffect(targetSequence) {
      // A guest cannot compute the end: the maximum is host layout and the guest's copy of it is as
      // stale as its last report. `END` asks the host to resolve it against the layout it has --
      // and to wait for one, because a target declared in a replacement guest's first batch arrives
      // while the content is still being fetched. This is the same failure a list had, found on a
      // device: `scrollTo` against a not-yet-laid-out container silently clamps to zero.
      val target = if (targetDp == END) {
        snapshotFlow { state.maxValue }.first { it > 0 }
      } else {
        toPx(targetDp)
      }
      if (targetAnimated) state.animateScrollTo(target) else state.scrollTo(target)
    }
  }

  if (watching) {
    // `rememberUpdatedState`, and it is the same defect a list's reporter had: this effect is keyed
    // on things a code update does not change, so it is not restarted when the guest is replaced --
    // and a captured report lambda would keep feeding the previous generation, holding an entire
    // interpreter alive.
    val currentReport by rememberUpdatedState(report)
    val currentQuantum by rememberUpdatedState(reportEveryDp)
    // Keyed on the generation as well as the state, and that is the fix for a defect a device
    // produced: a report is edge-triggered, and a code update hands the guest a fresh holder that
    // knows nothing while leaving the host's value exactly where it was. The host therefore had
    // nothing new to say, and the container came back reporting `offset 800dp of -1dp` -- the
    // offset right because it was saved, the maximum absent because it was only ever reported.
    // Restarting the collector re-emits the current value, which is the level a new guest needs.
    val generation = LocalGuestGeneration.current
    LaunchedEffect(state, generation) {
      snapshotFlow {
        val max = state.maxValue
        val value = state.value
        val quantum = toPx(currentQuantum.coerceAtLeast(1)).coerceAtLeast(1)
        // Exact at both ends, quantised between them. `value >= max` is the row that matters:
        // without it a guest could never learn it had reached the bottom, which is the whole
        // reason a guest watches a scroll position.
        val reported = when {
          value <= 0 -> 0
          max in 1..value -> max
          else -> (value / quantum) * quantum
        }
        Triple(toDp(reported), if (max > 0) toDp(max) else -1, state.isScrollInProgress)
      }
        .distinctUntilChanged()
        .collect { (offset, max, scrolling) -> currentReport(offset, max, scrolling) }
    }
  }

  return mirror
}

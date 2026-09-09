/*
 * Project Dogwood -- the host's half of a pager.
 *
 * The sixth mirror, and the shortest, because a page index is already discrete: there is no
 * quantum to apply and no measurement to wait for, so this is `ScrollMirror` with the throttling
 * removed and the sheet's `byUser` kept.
 *
 * What it owns is what a guest cannot: the gesture, the fling, the snapping, and Compose's own
 * `PagerState`.
 */
package dev.dogwood.host

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Drives a pager from the guest's declared page and reports where it settles.
 *
 * **Reads its holder straight off the node**, like `LazyListMirror` and unlike the four generated
 * mirrors, because a pager cannot be a generated component: Compose's pager is a lazy layout and
 * needs *per-page* access to the children, which is precisely the "indexed content lambda" the
 * generator refuses to bind (`Parser.classifyLambda`). So `Pager` joins `VerticalList` and
 * `HorizontalList` as a hand-written binding on a reserved tag, and its holder is read here rather
 * than plumbed. The shape table still carries `PagerState` — a guest writes the holder exactly as
 * it writes the other five — but the host side is hand-written on purpose, and this comment is why.
 *
 * @param pageCount how many pages the guest emitted. The host counts what it was handed, because a
 *   guest that assumed its own count would be wrong the moment a page was conditionally composed.
 */
@Composable
fun PagerMirror(
  node: WidgetView,
  state: PagerState,
  pageCount: Int,
  events: EventSink,
) {
  // Property tags follow the holder's declaration order, the same order the dictionary assigns to
  // a generated holder's properties -- so a hand-written binding and a generated one read the same
  // wire bytes, which is the property that lets a guest be written without knowing which it is.
  val targetPage = node.int(HOLDER_TARGET_PAGE, 0)
  val targetSequence = node.int(HOLDER_SEQUENCE, 0)
  val targetAnimated = node.boolean(HOLDER_ANIMATED, true)
  val watching = node.boolean(HOLDER_WATCHING, false)
  val report: ((Int, Int, Boolean) -> Unit)? = if (!watching) null else { page, count, byUser ->
    events.send(
      node,
      dev.dogwood.protocol.EventTag(HOLDER_REPORT_EVENT),
      listOf(
        kotlinx.serialization.json.JsonPrimitive(page),
        kotlinx.serialization.json.JsonPrimitive(count),
        kotlinx.serialization.json.JsonPrimitive(byUser),
      ),
    )
  }
  driveAndReport(targetPage, targetSequence, targetAnimated, watching, pageCount, report, state)
}

/** The property and event tags this hand-written binding shares with the guest's holder. */
private const val HOLDER_TARGET_PAGE = 1
private const val HOLDER_SEQUENCE = 2
private const val HOLDER_ANIMATED = 3
private const val HOLDER_WATCHING = 4
private const val HOLDER_REPORT_EVENT = 1

@Composable
private fun driveAndReport(
  targetPage: Int,
  targetSequence: Int,
  targetAnimated: Boolean,
  watching: Boolean,
  pageCount: Int,
  report: ((Int, Int, Boolean) -> Unit)?,
  state: PagerState,
) {
  val generation = LocalGuestGeneration.current

  /*
   * A request is a sequence change, never a page change -- the rule every holder here shares. A
   * guest asking to return to page 0 after the user swiped away sends the same number it sent
   * before, and a value-keyed effect would do nothing.
   */
  LaunchedEffect(targetSequence, generation) {
    if (targetSequence == 0 || pageCount == 0) return@LaunchedEffect
    // Clamped rather than refused: a payload built against more pages than this client composed is
    // ordinary skew, and clamping keeps the screen where throwing inside composition would take it
    // down on every client at once (ADR-035's rule, applied to a page index).
    val page = targetPage.coerceIn(0, pageCount - 1)
    if (targetAnimated) state.animateScrollToPage(page) else state.scrollToPage(page)
  }

  if (watching && report != null) {
    LaunchedEffect(state, generation) {
      snapshotFlow { state.settledPage }
        .distinctUntilChanged()
        .collect { page ->
          // Settled, not current: `currentPage` moves during a drag, and reporting mid-gesture
          // would tell the guest about pages the user is passing through rather than choosing.
          report(page, state.pageCount, page != targetPage)
        }
    }
  }

}

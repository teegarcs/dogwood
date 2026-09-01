/*
 * Project Dogwood -- guest-side windowing.
 *
 * The lazy containers built so far are not lazy at all *on the boundary*. `VerticalList` composes
 * every child the guest gives it, so a ten-thousand-row feed crosses ten thousand nodes -- and
 * each row in the sample is fifteen. The host's `LazyColumn` then renders about ten of them. The
 * laziness stops exactly where it matters.
 *
 * What makes real laziness possible is that the host already reports its viewport
 * ([LazyListState], `adrs/layer-5/ADR-014-live-state-holders.md`). Given that, the guest can
 * compose **only the window** and tell the host two more numbers: how many items there really are,
 * and which index the window starts at. The host renders a list of the true length and draws a
 * placeholder wherever it has no node -- so the scroll extent, the scrollbar, and every index the
 * user can fling to stay right, while the traffic is proportional to what is on screen.
 *
 * Three properties this gets from being declarative rather than a windowing protocol:
 *
 *   - **Scrolling costs a diff, not a rewrite.** Each item is `key`ed by its index, so scrolling
 *     one row produces one removal and one insertion rather than a rewritten window.
 *   - **The placeholder is one node, not a pool.** The guest composes the template once and the
 *     host repeats it for every out-of-window index. Compose's own lazy item recycling is the
 *     pool -- which is what Redwood needed an explicit one for, because it was creating real
 *     platform widgets and these are composables.
 *   - **Overscan is the guest's decision.** A viewport report is a round trip, so a window exactly
 *     the size of the screen would show placeholders on every flick. Overscan is how much latency
 *     the guest chooses to hide, and different screens want different answers.
 *
 * See `adrs/layer-5/ADR-018-lazy-layouts.md`.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

/**
 * How many items to compose before the first viewport report arrives.
 *
 * The host cannot report a viewport until it has laid one out, and it cannot lay one out until the
 * guest has sent something. This is the seed that breaks that circle, and it wants to be a little
 * more than one screenful so the first flick does not land on placeholders.
 */
internal const val INITIAL_WINDOW = 16

/**
 * The window the guest should compose, given what the host last reported.
 *
 * Never empty for a non-empty list. A window of nothing would leave the host with nothing to
 * measure, so it would never report a viewport, so the window would stay empty -- a deadlock
 * rather than an empty screen.
 */
internal fun windowFor(
  itemCount: Int,
  firstVisible: Int,
  lastVisible: Int,
  overscan: Int,
): IntRange {
  if (itemCount <= 0) return IntRange.EMPTY
  if (lastVisible < 0) return 0..minOf(INITIAL_WINDOW, itemCount) - 1
  val start = maxOf(0, firstVisible - overscan)
  val end = minOf(itemCount - 1, lastVisible + overscan)
  return if (start > end) 0..minOf(INITIAL_WINDOW, itemCount) - 1 else start..end
}

/**
 * A vertical list whose boundary cost is proportional to what is on screen.
 *
 * @param overscan how many items beyond the reported viewport to compose. Zero is legal and shows
 *   placeholders during a flick; the default hides about a round trip's worth.
 * @param placeholder composed **once** and repeated by the host wherever the guest sent no node.
 *   Give it the height a real row has, or the scrollbar will lie.
 */
@Composable
fun <T> LazyVerticalList(
  items: List<T>,
  modifier: Modifier = Modifier,
  state: LazyListState = rememberLazyListState(),
  spacingDp: Int = 0,
  contentPaddingDp: Int = 0,
  overscan: Int = 6,
  placeholder: @Composable () -> Unit,
  item: @Composable (index: Int, value: T) -> Unit,
) {
  val window = windowFor(items.size, state.firstVisibleItemIndex, state.lastVisibleItemIndex, overscan)
  ListContainerWindowed(
    tag = Tags.VerticalList,
    modifier = modifier,
    spacingDp = spacingDp,
    contentPaddingDp = contentPaddingDp,
    state = state,
    itemCount = items.size,
    window = window,
    placeholder = placeholder,
  ) {
    for (index in window) {
      // Keyed by index, so scrolling one row is one removal and one insertion rather than a
      // rewritten window. The index is the right identity here: item 500 is item 500 whichever
      // node happens to represent it.
      key(index) { item(index, items[index]) }
    }
  }
}

/** As [LazyVerticalList], horizontally. */
@Composable
fun <T> LazyHorizontalList(
  items: List<T>,
  modifier: Modifier = Modifier,
  state: LazyListState = rememberLazyListState(),
  spacingDp: Int = 0,
  contentPaddingDp: Int = 0,
  overscan: Int = 4,
  placeholder: @Composable () -> Unit,
  item: @Composable (index: Int, value: T) -> Unit,
) {
  val window = windowFor(items.size, state.firstVisibleItemIndex, state.lastVisibleItemIndex, overscan)
  ListContainerWindowed(
    tag = Tags.HorizontalList,
    modifier = modifier,
    spacingDp = spacingDp,
    contentPaddingDp = contentPaddingDp,
    state = state,
    itemCount = items.size,
    window = window,
    placeholder = placeholder,
  ) {
    for (index in window) {
      key(index) { item(index, items[index]) }
    }
  }
}

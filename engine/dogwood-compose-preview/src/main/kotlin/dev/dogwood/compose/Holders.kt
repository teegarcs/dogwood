/*
 * Project Dogwood -- the holders, for the preview path.
 *
 * A holder (ADR-014, ADR-019, ADR-043) is the answer to "who owns this piece of state when the
 * thing that draws it is across a boundary from the thing that reasons about it". A guest declares
 * a *target* -- scroll to here, focus this, show that -- and reads back a *report* the host sends
 * on a declared threshold. That two-way shape is what stops a scroll position from crossing sixty
 * times a second.
 *
 * In a preview there is no boundary, so every holder here is backed by the real Compose state it
 * was modelling all along: `ScrollState` by `androidx.compose.foundation.ScrollState`,
 * `FocusRequester` by Compose's own, `SnackbarHostState` by Material 3's. The public shape is kept
 * identical -- the same names, the same targets, the same reports -- so the payload cannot tell,
 * which is the point. **What the preview cannot show is the threshold**: `reportEveryDp` exists to
 * bound wire traffic and there is no wire, so a preview will read back every pixel and a device
 * will not.
 */
@file:Suppress("unused")

package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred

// ---------------------------------------------------------------------------------------------
// Scrolling
// ---------------------------------------------------------------------------------------------

const val SCROLL_TO_END: Int = -1

/**
 * A scrolling container's position.
 *
 * [reportEveryDp] is kept in the signature and ignored in the body, and the asymmetry is
 * deliberate: it is a *wire* budget, and a preview that honoured it would be pretending to have a
 * wire. The numbers a preview shows move continuously; the numbers a device shows step.
 */
class ScrollState internal constructor(
  initialOffsetDp: Int = 0,
  val reportEveryDp: Int = 48,
) {
  var offsetDp: Int by mutableStateOf(initialOffsetDp)
    internal set
  var maxOffsetDp: Int by mutableStateOf(-1)
    internal set
  var isScrollInProgress: Boolean by mutableStateOf(false)
    internal set

  val isAtTop: Boolean get() = offsetDp <= 0
  val isAtBottom: Boolean get() = maxOffsetDp > 0 && offsetDp >= maxOffsetDp

  fun isNearEnd(withinDp: Int): Boolean = maxOffsetDp > 0 && maxOffsetDp - offsetDp <= withinDp

  internal var targetDp: Int by mutableStateOf(initialOffsetDp)
    private set
  internal var targetAnimated: Boolean by mutableStateOf(false)
    private set
  internal var targetSequence: Int by mutableStateOf(0)
    private set

  init {
    if (initialOffsetDp != 0) targetSequence = 1
  }

  fun scrollTo(offsetDp: Int) = declare(offsetDp, animated = false)
  fun animateScrollTo(offsetDp: Int) = declare(offsetDp, animated = true)
  fun scrollToTop() = scrollTo(0)
  fun scrollToEnd() = declare(SCROLL_TO_END, animated = false)
  fun animateScrollToEnd() = declare(SCROLL_TO_END, animated = true)

  private fun declare(offsetDp: Int, animated: Boolean) {
    targetDp = offsetDp
    targetAnimated = animated
    targetSequence += 1
  }

  companion object {
    val Saver: Saver<ScrollState, Any> = listSaver(
      save = { listOf(it.offsetDp, it.reportEveryDp) },
      restore = { ScrollState(it[0] as Int, it[1] as Int) },
    )
  }
}

@Composable
fun rememberScrollState(initialOffsetDp: Int = 0, reportEveryDp: Int = 48): ScrollState =
  rememberSaveable(saver = ScrollState.Saver) { ScrollState(initialOffsetDp, reportEveryDp) }

/** A lazy list's viewport, in items rather than pixels. */
class LazyListState internal constructor(initialFirstVisibleItemIndex: Int = 0) {
  var firstVisibleItemIndex: Int by mutableStateOf(initialFirstVisibleItemIndex)
    internal set
  var lastVisibleItemIndex: Int by mutableStateOf(-1)
    internal set
  var isScrollInProgress: Boolean by mutableStateOf(false)
    internal set

  internal var targetIndex: Int by mutableStateOf(initialFirstVisibleItemIndex)
    private set
  internal var targetAnimated: Boolean by mutableStateOf(false)
    private set
  internal var targetSequence: Int by mutableStateOf(0)
    private set

  fun scrollToItem(index: Int) = declare(index, animated = false)
  fun animateScrollToItem(index: Int) = declare(index, animated = true)

  private fun declare(index: Int, animated: Boolean) {
    targetIndex = index
    targetAnimated = animated
    targetSequence += 1
  }

  companion object {
    val Saver: Saver<LazyListState, Int> = Saver(
      save = { it.firstVisibleItemIndex },
      restore = { LazyListState(it) },
    )
  }
}

@Composable
fun rememberLazyListState(initialFirstVisibleItemIndex: Int = 0): LazyListState =
  rememberSaveable(saver = LazyListState.Saver) { LazyListState(initialFirstVisibleItemIndex) }

// ---------------------------------------------------------------------------------------------
// Snackbars
// ---------------------------------------------------------------------------------------------

enum class SnackbarResult { DISMISSED, ACTION_PERFORMED }

/**
 * A holder that answers: the guest asks for a snackbar and *suspends* until the user decides.
 *
 * The preview keeps the suspension, because that is the part of the design a developer needs to
 * feel: `SnackbarArea` shows a real Material 3 snackbar and completes this with what the user did.
 */
class SnackbarHostState internal constructor() {
  internal var message: String by mutableStateOf("")
    private set
  internal var actionLabel: String? by mutableStateOf(null)
    private set
  internal var sequence: Int by mutableStateOf(0)
    private set

  private var pending: CompletableDeferred<SnackbarResult>? = null
  private var pendingSequence: Int = 0

  fun dismiss() {
    val outstanding = pending ?: return
    pending = null
    outstanding.complete(SnackbarResult.DISMISSED)
  }

  suspend fun showSnackbar(message: String, actionLabel: String? = null): SnackbarResult {
    pending?.complete(SnackbarResult.DISMISSED)
    this.message = message
    this.actionLabel = actionLabel
    sequence += 1
    pendingSequence = sequence
    val answer = CompletableDeferred<SnackbarResult>()
    pending = answer
    return answer.await()
  }

  internal fun report(sequence: Int, actionPerformed: Boolean) {
    if (sequence != pendingSequence) return
    val answer = pending ?: return
    pending = null
    answer.complete(if (actionPerformed) SnackbarResult.ACTION_PERFORMED else SnackbarResult.DISMISSED)
  }

  companion object {
    val Saver: Saver<SnackbarHostState, Int> = Saver(
      save = { it.sequence },
      restore = { restored -> SnackbarHostState().also { it.sequence = restored } },
    )
  }
}

@Composable
fun rememberSnackbarHostState(): SnackbarHostState =
  rememberSaveable(saver = SnackbarHostState.Saver) { SnackbarHostState() }

// ---------------------------------------------------------------------------------------------
// Text input
// ---------------------------------------------------------------------------------------------

/**
 * The buffer the *host* owns.
 *
 * ADR-019: a controlled text field round-trips every keystroke, so the host keeps the text and
 * stamps every edit with a count the guest echoes back. The preview keeps the same two fields,
 * and the round trip it is guarding against is a millimetre long.
 */
class TextFieldState internal constructor(
  initialText: String = "",
  initialAcknowledged: Int = 0,
  internal val sensitive: Boolean = false,
) {
  var text: String by mutableStateOf(initialText)
    private set

  internal var acknowledged: Int by mutableStateOf(initialAcknowledged)
    private set

  fun set(value: String) {
    text = value
  }

  fun clear() = set("")

  internal fun onHostEdit(value: String, editCount: Int) {
    text = value
    acknowledged = editCount
  }

  companion object {
    val Saver: Saver<TextFieldState, Any> = listSaver(
      save = { listOf(if (it.sensitive) "" else it.text, it.acknowledged, it.sensitive) },
      restore = {
        TextFieldState(
          initialText = it[0] as String,
          initialAcknowledged = it[1] as Int,
          sensitive = it.getOrNull(2) as? Boolean ?: false,
        )
      },
    )
  }
}

@Composable
fun rememberTextFieldState(initialText: String = "", sensitive: Boolean = false): TextFieldState =
  rememberSaveable(saver = TextFieldState.Saver) { TextFieldState(initialText, sensitive = sensitive) }

object Keyboards {
  const val TEXT = "text"
  const val NUMBER = "number"
  const val PHONE = "phone"
  const val EMAIL = "email"
  const val PASSWORD = "password"
  const val DECIMAL = "decimal"
}

// ---------------------------------------------------------------------------------------------
// Focus
// ---------------------------------------------------------------------------------------------

/**
 * A level-triggered focus request: what the guest *wants*, not an imperative it fires once.
 *
 * "Give the keyboard up" is a request in its own right and not the absence of one, which is why
 * there are two calls and one sequence number rather than one call and a boolean.
 */
class FocusRequester internal constructor(initialSequence: Int = 0) {
  internal var requested: Boolean by mutableStateOf(false)
    private set
  internal var sequence: Int by mutableStateOf(initialSequence)
    private set

  fun requestFocus() = declare(true)
  fun freeFocus() = declare(false)

  private fun declare(wanted: Boolean) {
    requested = wanted
    sequence += 1
  }

  companion object {
    val Saver: Saver<FocusRequester, Int> = Saver(save = { it.sequence }, restore = { FocusRequester(it) })
  }
}

@Composable
fun rememberFocusRequester(): FocusRequester =
  rememberSaveable(saver = FocusRequester.Saver) { FocusRequester() }

// ---------------------------------------------------------------------------------------------
// Pager, sheet and pickers
//
// Present so that the vocabulary compiles whole, and thin because the catalogue does not drive
// them. Each keeps the target-and-report shape; what a preview does with the target is named at
// the widget that reads it (`Primitives.kt`, `DesignSystem.kt`).
// ---------------------------------------------------------------------------------------------

class PagerState internal constructor(initialPage: Int = 0) {
  var currentPage: Int by mutableStateOf(initialPage)
    internal set
  var pageCount: Int by mutableStateOf(0)
    internal set
  var lastChangeByUser: Boolean by mutableStateOf(false)
    internal set

  internal var targetPage: Int by mutableStateOf(initialPage)
    private set
  internal var targetSequence: Int by mutableStateOf(0)
    private set
  internal var targetAnimated: Boolean by mutableStateOf(true)
    private set

  val page: Int get() = currentPage

  fun animateScrollToPage(page: Int) = declare(page, animated = true)
  fun scrollToPage(page: Int) = declare(page, animated = false)

  internal fun report(page: Int, pageCount: Int, byUser: Boolean) {
    currentPage = page
    this.pageCount = pageCount
    lastChangeByUser = byUser
  }

  private fun declare(page: Int, animated: Boolean) {
    targetPage = page
    targetAnimated = animated
    targetSequence += 1
  }

  companion object {
    val Saver: Saver<PagerState, Any> = listSaver(
      save = { listOf(it.currentPage) },
      restore = { PagerState(it[0] as Int) },
    )
  }
}

@Composable
fun rememberPagerState(initialPage: Int = 0): PagerState =
  rememberSaveable(saver = PagerState.Saver) { PagerState(initialPage) }

object SheetValues {
  const val HIDDEN = "hidden"
  const val PARTIAL = "partial"
  const val EXPANDED = "expanded"
}

class SheetState internal constructor(
  initialState: String = SheetValues.HIDDEN,
  internal val skipPartiallyExpanded: Boolean = false,
) {
  var currentState: String by mutableStateOf(initialState)
    internal set
  var lastChangeByUser: Boolean by mutableStateOf(false)
    internal set

  internal var targetState: String by mutableStateOf(initialState)
    private set
  internal var targetSequence: Int by mutableStateOf(0)
    private set

  val isVisible: Boolean get() = currentState != SheetValues.HIDDEN

  fun show() = declare(if (skipPartiallyExpanded) SheetValues.EXPANDED else SheetValues.PARTIAL)
  fun expand() = declare(SheetValues.EXPANDED)
  fun hide() = declare(SheetValues.HIDDEN)

  internal fun report(state: String, byUser: Boolean) {
    currentState = state
    lastChangeByUser = byUser
  }

  private fun declare(state: String) {
    targetState = state
    targetSequence += 1
  }

  companion object {
    val Saver: Saver<SheetState, Any> = listSaver(
      save = { listOf(it.currentState, it.skipPartiallyExpanded) },
      restore = { SheetState(it[0] as String, it[1] as Boolean) },
    )
  }
}

@Composable
fun rememberSheetState(
  initialState: String = SheetValues.HIDDEN,
  skipPartiallyExpanded: Boolean = false,
): SheetState = rememberSaveable(saver = SheetState.Saver) { SheetState(initialState, skipPartiallyExpanded) }

sealed interface PickerResult {
  data class Chosen(val value: String) : PickerResult
  data object Dismissed : PickerResult
}

abstract class PickerState internal constructor(initialValue: String) {
  var requested: Boolean by mutableStateOf(false)
    private set
  var requestSequence: Int by mutableStateOf(0)
    private set

  protected var initial: String by mutableStateOf(initialValue)

  private var pending: CompletableDeferred<PickerResult>? = null

  suspend fun show(initialValue: String = ""): PickerResult {
    if (initialValue.isNotEmpty()) initial = initialValue
    pending?.complete(PickerResult.Dismissed)
    val answer = CompletableDeferred<PickerResult>()
    pending = answer
    requested = true
    requestSequence += 1
    return answer.await()
  }

  protected fun reportValue(sequence: Int, value: String) {
    if (sequence != requestSequence) return
    requested = false
    val answer = pending ?: return
    pending = null
    answer.complete(if (value.isEmpty()) PickerResult.Dismissed else PickerResult.Chosen(value))
  }
}

class DatePickerState internal constructor(initialDate: String) : PickerState(initialDate) {
  val initialDate: String get() = initial
  fun report(sequence: Int, date: String) = reportValue(sequence, date)

  companion object {
    val Saver: Saver<DatePickerState, Any> = listSaver(
      save = { listOf(it.initialDate) },
      restore = { DatePickerState(it[0] as String) },
    )
  }
}

class TimePickerState internal constructor(initialTime: String) : PickerState(initialTime) {
  val initialTime: String get() = initial
  fun report(sequence: Int, time: String) = reportValue(sequence, time)

  companion object {
    val Saver: Saver<TimePickerState, Any> = listSaver(
      save = { listOf(it.initialTime) },
      restore = { TimePickerState(it[0] as String) },
    )
  }
}

@Composable
fun rememberDatePickerState(initialDate: String = ""): DatePickerState =
  rememberSaveable(saver = DatePickerState.Saver) { DatePickerState(initialDate) }

@Composable
fun rememberTimePickerState(initialTime: String = ""): TimePickerState =
  rememberSaveable(saver = TimePickerState.Saver) { TimePickerState(initialTime) }

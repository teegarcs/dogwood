/*
 * Project Dogwood -- the host's half of a bottom sheet.
 *
 * The fifth holder mirror. What it owns is the part a guest cannot: Material's own
 * `SheetState`, the drag, the animation, and the platform window the sheet lives in. What it
 * reports is where that ended up, and **who moved it** -- which is the field a guest needs to
 * implement "remember that they dismissed it" and cannot derive from position alone.
 *
 * The shape is `ScrollMirror`'s (a declared target, a continuous report) with `SnackbarMirror`'s
 * meaning (the report is the user's decision), which is why it borrows both of their rules: the
 * target fires on a *sequence* change rather than a value change, and reporting is keyed on the
 * guest generation so a replacement guest is told where the sheet is without it having moved.
 */
// Material's sheet API is experimental upstream, and the opt-in is file-level because the mapping
// function needs it too. That is a real coupling worth stating: a Compose Multiplatform upgrade
// that changes `SheetValue` or `rememberModalBottomSheetState` breaks this mirror and nothing else,
// which is the containment the mirror pattern is for.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.dogwood.host

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The wire vocabulary, mapped both ways in one place so the two directions cannot drift. */
private const val HIDDEN = "hidden"
private const val PARTIAL = "partial"
private const val EXPANDED = "expanded"

private fun SheetValue.wireName(): String = when (this) {
  SheetValue.Expanded -> EXPANDED
  SheetValue.PartiallyExpanded -> PARTIAL
  SheetValue.Hidden -> HIDDEN
  else -> HIDDEN
}

/**
 * What the guest asked for and what the platform is doing, as one value the binding draws.
 *
 * A value rather than a composable that draws, because that is the convention the generator emits
 * against: a holder parameter becomes `rememberXMirror(...)` and is *passed* to the binding. It
 * also puts the split in the right place — this owns the state and the effects, and the binding
 * owns the window.
 */
@OptIn(ExperimentalMaterial3Api::class)
class SheetMirror internal constructor(
  val sheetState: androidx.compose.material3.SheetState,
  /** Whether the sheet should be on screen at all; see [rememberSheetMirror]. */
  val onScreen: Boolean,
  internal val dismissed: () -> Unit,
)

/**
 * Drives the platform sheet from the guest's declared target and reports where the user leaves it.
 *
 * @param report told the sheet's position and whether the **user** put it there. Null when no guest
 *   is watching, which is the presence property doing its job: a sheet nobody observes costs no
 *   traffic at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSheetMirror(
  // Named for the holder's own fields, because that is what the generator emits: a holder property
  // declares `field = "targetState"` and the call site says `targetState = …`. Renaming either
  // half here would compile on one side and not the other.
  targetState: String,
  targetSequence: Int,
  watching: Boolean,
  skipPartiallyExpanded: Boolean,
  report: ((String, Boolean) -> Unit)?,
): SheetMirror {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
  val generation = LocalGuestGeneration.current

  /*
   * Whether the sheet is on screen is host state derived from the guest's target, and it has to be
   * *state* rather than a computed value: `ModalBottomSheet` animates itself away, and a window
   * that vanished the instant the target said `hidden` would cut that animation off.
   */
  var onScreen by remember(generation) { mutableStateOf(targetState != HIDDEN && targetSequence > 0) }

  /*
   * A request is a sequence change, never a value change -- the rule every holder here shares.
   * Asking to expand a sheet the user just dragged shut is a real request; a value-keyed effect
   * would see the same string and do nothing.
   */
  LaunchedEffect(targetSequence, generation) {
    if (targetSequence == 0) return@LaunchedEffect
    when (targetState) {
      HIDDEN -> {
        if (onScreen) sheetState.hide()
        onScreen = false
      }
      EXPANDED -> {
        onScreen = true
        sheetState.expand()
      }
      // An unknown target reads as "show", deliberately: a client one dictionary version behind,
      // asked for a stop it does not have, should still open the sheet -- the guest's evident
      // intent was to show something.
      else -> {
        onScreen = true
        if (skipPartiallyExpanded) sheetState.expand() else sheetState.partialExpand()
      }
    }
  }

  /*
   * The report, and the `byUser` half is why it watches the platform state rather than the target.
   * A settle that matches what the guest asked for is the guest's own request landing; anything
   * else is the user -- and a guest cannot tell those apart from position alone.
   */
  if (watching && report != null) {
    LaunchedEffect(sheetState, generation) {
      snapshotFlow { sheetState.currentValue }
        .distinctUntilChanged()
        .collect { value ->
          val name = value.wireName()
          report(name, name != targetState)
        }
    }
  }

  return SheetMirror(
    sheetState = sheetState,
    onScreen = onScreen,
    dismissed = {
      // The user dismissed it: the scrim, a drag, or the back gesture. Reported as the user's doing
      // *before* the window goes, because afterwards there is nothing left to observe.
      onScreen = false
      if (watching && report != null) report(HIDDEN, true)
    },
  )
}

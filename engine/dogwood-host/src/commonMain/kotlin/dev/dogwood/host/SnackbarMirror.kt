/*
 * Project Dogwood -- the host half of the snackbar holder.
 *
 * The fourth holder shape, and the first whose report is an **answer**. A scroll report is an
 * observation the guest did not ask for; this one is the result of a specific request, and it
 * carries the sequence it is answering so two requests in flight cannot be confused.
 *
 * What the host owns is the queue, the timing and the dismissal, because all three are per-frame
 * or per-gesture and none of them can cross a boundary sixty times a second. What the guest owns is
 * the decision that follows: undo, or do not.
 */
package dev.dogwood.host

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue

/** The host's snackbar queue, and the guest's view of it kept in step. */
class SnackbarMirror internal constructor(val hostState: SnackbarHostState)

/**
 * Shows what the guest asked for, and answers.
 *
 * Keyed on the sequence, so asking twice for the same message is two snackbars — a user who
 * deletes two rows expects to be told twice, and with a flag the second would change no property
 * and show nothing.
 *
 * @param report called with the sequence being answered and whether the action was tapped. The
 *   sequence is what makes this a reply rather than an observation.
 */
@Composable
fun rememberSnackbarMirror(
  message: String,
  actionLabel: String,
  sequence: Int,
  watching: Boolean,
  report: (Int, Boolean) -> Unit = { _, _ -> },
  /** The guest taking its request back. Zero means it never has; each dismissal is a new number. */
  dismissSequence: Int = 0,
): SnackbarMirror {
  val hostState = remember { SnackbarHostState() }
  val mirror = remember(hostState) { SnackbarMirror(hostState) }
  val currentReport by rememberUpdatedState(report)

  if (dismissSequence > 0) {
    LaunchedEffect(dismissSequence) {
      // Whatever is showing, not the request by sequence: the guest has already resumed its own
      // caller as dismissed, and the only job left is to take the snackbar off the screen.
      hostState.currentSnackbarData?.dismiss()
    }
  }

  if (watching && sequence > 0) {
    LaunchedEffect(sequence) {
      val outcome = hostState.showSnackbar(
        message = message,
        actionLabel = actionLabel.takeIf { it.isNotEmpty() },
        // Indefinite would be a guest holding the screen hostage from a server. A snackbar the
        // user never dismisses answers itself.
        duration = if (actionLabel.isEmpty()) SnackbarDuration.Short else SnackbarDuration.Long,
      )
      // Answered with the sequence that was asked, not the current one: by the time this resumes
      // the guest may have asked again, and resuming the new request with the old one's answer
      // would undo the wrong row.
      currentReport(sequence, outcome == SnackbarResult.ActionPerformed)
    }
  }

  return mirror
}

/*
 * Project Dogwood -- the host half of the focus holder.
 *
 * The generated binding brings the two properties across and calls [rememberFocusMirror]; this is
 * what they mean. The split is the same one the generator draws everywhere else
 * ([ADR-011](../../../../../../../adrs/layer-5/ADR-011-generator-emits-the-bridge.md)): the
 * plumbing is emitted, and the part that requires taste -- what "give this field the keyboard"
 * actually does on a platform -- is written by hand, once.
 */
package dev.dogwood.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager

/**
 * The host's answer to a guest's focus request.
 *
 * [modifier] is attached whether or not anybody ever asks. That is deliberate: a requester that
 * appeared only once a request had been made would be attached in the same frame the request is
 * acted on, and `FocusRequester.requestFocus()` throws when its node is not yet attached. One
 * always-attached modifier node per text field is a cheaper answer than a race.
 */
class FocusMirror internal constructor(internal val requester: FocusRequester) {
  val modifier: Modifier = Modifier.focusRequester(requester)
}

/**
 * Acts on a guest's declared focus target.
 *
 * Keyed on the sequence, not on the direction, so asking twice for the same thing runs twice --
 * the user who dismissed the keyboard and tapped "edit" again expects the field back. A sequence
 * of zero means no request has ever been made and nothing happens at all.
 *
 * **Taking focus away is not the same call as asking for it.** `FocusRequester.freeFocus()`
 * releases *captured* focus, which is a different mechanism; clearing focus is the focus manager's
 * job. Getting this wrong is silent -- the keyboard simply stays up -- which is why it is written
 * down here rather than left to whoever writes the next holder.
 *
 * A request that cannot be honoured is dropped rather than thrown. Compose signals an unattached
 * or unfocusable target by throwing, and this request came from a payload delivered over the air:
 * an exception here would take the screen down on every client at once, which is the failure
 * [ADR-035](../../../../../../../adrs/layer-5/ADR-035-hostile-property-values.md) exists to
 * prevent. A dropped focus request degrades to a keyboard that did not open.
 */
@Composable
fun rememberFocusMirror(requested: Boolean, sequence: Int): FocusMirror {
  val requester = remember { FocusRequester() }
  val mirror = remember(requester) { FocusMirror(requester) }
  val focusManager = LocalFocusManager.current
  // Read here rather than inside the effect: a composition local is a composition-time lookup, and
  // the effect's body runs after the composition that launched it has finished.
  val skew = LocalSkewReport.current

  if (sequence > 0) {
    LaunchedEffect(sequence) {
      try {
        if (requested) requester.requestFocus() else focusManager.clearFocus()
      } catch (rejected: IllegalStateException) {
        skew.rejectedFocusRequests += rejected.message ?: "focus request rejected"
      }
    }
  }

  return mirror
}

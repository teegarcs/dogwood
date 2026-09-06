/*
 * Project Dogwood -- the host half of the focus holder, in a real composition.
 *
 * `FocusRequesterTest` on the guest pins what crosses the boundary. This pins what the host does
 * with it, which is the half
 * [ADR-043](../../../../../../../adrs/layer-5/ADR-043-holders-are-declared-on-the-surface.md) is
 * actually about: a sequence that changes takes the focus, a sequence that does not is inert, and
 * `requested = false` is a request in its own right rather than the absence of one.
 *
 * It was demonstrated on an emulator and read out of `dumpsys`. That is evidence for a record, and
 * it is not a gate — nothing re-runs it. These are the same claims as assertions, on every build.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.protocol.decodePositional
import dev.dogwood.protocol.widgetTag
import kotlin.test.Test
import kotlin.test.assertTrue

private val TEXT_INPUT = widgetTag(1, 13).value

/** `focusRequested` and `focusSequence`, appended by the generator after the ten declared values. */
private const val REQUESTED = 11
private const val SEQUENCE = 12

@OptIn(ExperimentalTestApi::class)
class FocusMirrorTest {

  /**
   * One text field, with an optional focus request on it.
   *
   * The field is found by its **content description**, not by its text. `TextInputImpl` supplies
   * the label as `contentDescription` and clears the semantics of the visible label composable —
   * because Material 3 draws a label without folding it into the field's own semantics on iOS while
   * Android's does merge it, so a field labelled only by the composable reaches VoiceOver anonymous
   * and Android announces it twice. That fix is what makes `onNodeWithText` the wrong finder here.
   */
  private fun tree(requested: Boolean? = null, sequence: Int = 0): HostTree = HostTree().also {
    val focus = if (requested == null) "" else ",[1,1,$REQUESTED,$requested],[1,1,$SEQUENCE,$sequence]"
    it.apply(
      decodePositional("""[1,[[0,1,$TEXT_INPUT],[1,1,1,""],[1,1,3,"Card number"]$focus,[3,0,1,1,0]]]"""),
    )
  }

  /** The field, found the way the accessibility layer finds it. */
  private fun androidx.compose.ui.test.ComposeUiTest.field() =
    onNodeWithContentDescription("Card number")

  private fun show(tree: HostTree, body: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
    runComposeUiTest {
      setContent {
        Box(Modifier.size(300.dp)) {
          DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew)
        }
      }
      body()
    }

  @Test
  fun aFieldNobodyAskedAboutIsNotFocused() {
    // The control. Without it, every assertion below could be satisfied by a field that is always
    // focused because it is the only focusable thing on screen.
    show(tree()) {
      field().assertIsDisplayed()
      field().assertIsNotFocused()
    }
  }

  @Test
  fun aRequesterThatHasNotBeenUsedIsInert() {
    // Sequence zero means "nobody has asked for anything", and the host acts on it by not acting.
    // The properties are on the wire — a holder was passed — so this is the case that separates
    // "a holder exists" from "a request was made".
    show(tree(requested = true, sequence = 0)) {
      field().assertIsNotFocused()
    }
  }

  @Test
  fun aGuestRequestTakesTheFocus() {
    show(tree(requested = true, sequence = 1)) {
      waitForIdle()
      field().assertIsFocused()
    }
  }

  @Test
  fun givingFocusUpIsARequestAndNotTheAbsenceOfOne() {
    // The mistake this exists to catch: reading `requested = false` as "nothing was asked" leaves
    // the keyboard up. It is also a different call — `FocusRequester.freeFocus()` releases
    // *captured* focus and would not do this — and getting that wrong is silent.
    val tree = tree(requested = true, sequence = 1)
    show(tree) {
      waitForIdle()
      field().assertIsFocused()

      tree.apply(decodePositional("[2,[[1,1,$REQUESTED,false],[1,1,$SEQUENCE,2]]]"))
      waitForIdle()

      field().assertIsNotFocused()
    }
  }

  @Test
  fun askingTwiceForFocusActsTwice() {
    // The reason the target is a counter rather than a flag. A user who dismissed the keyboard and
    // tapped the same control again expects the field back — and with a flag the second tap would
    // change no property, cross nothing, and do nothing.
    val tree = tree(requested = true, sequence = 1)
    show(tree) {
      waitForIdle()
      field().assertIsFocused()

      tree.apply(decodePositional("[2,[[1,1,$REQUESTED,false],[1,1,$SEQUENCE,2]]]"))
      waitForIdle()
      field().assertIsNotFocused()

      // The same request as the first one, with a new number on it.
      tree.apply(decodePositional("[3,[[1,1,$REQUESTED,true],[1,1,$SEQUENCE,3]]]"))
      waitForIdle()
      field().assertIsFocused()
    }
  }

  @Test
  fun aRepeatedSequenceDoesNotReFire() {
    // The other half: the target is level-triggered on the *sequence*, so a property batch that
    // says the same thing again must not act again. Without this the mirror would re-take the
    // focus on every recomposition, which is a keyboard that cannot be dismissed.
    val tree = tree(requested = true, sequence = 1)
    show(tree) {
      waitForIdle()
      field().assertIsFocused()

      tree.apply(decodePositional("[2,[[1,1,$REQUESTED,false],[1,1,$SEQUENCE,2]]]"))
      waitForIdle()
      field().assertIsNotFocused()

      // An unrelated property changes; the focus sequence does not.
      tree.apply(decodePositional("""[3,[[1,1,1,"4242"]]]"""))
      waitForIdle()

      field().assertIsNotFocused()
    }
  }

  @Test
  fun aRefusedRequestIsReportedRatherThanThrown() {
    // A request arriving for a field that cannot take focus must not take the screen down: the
    // payload is delivered over the air without a store review, and an exception inside composition
    // lands on every client at once (ADR-035). A disabled field is the reachable case.
    val tree = HostTree().also {
      it.apply(
        decodePositional(
          """[1,[[0,1,$TEXT_INPUT],[1,1,1,""],[1,1,3,"Card number"],[1,1,5,false],""" +
            """[1,1,$REQUESTED,true],[1,1,$SEQUENCE,1],[3,0,1,1,0]]]""",
        ),
      )
    }
    show(tree) {
      waitForIdle()
      // The screen survives, which is the claim. Whether the platform honoured the request is the
      // platform's business; whether it took the experience down with it is Dogwood's.
      field().assertIsDisplayed()
    }
  }

  @Test
  fun aReplacementGuestDoesNotTakeTheKeyboardBack() {
    // The pair from the device run, as an assertion. The guest's saver carries the request *count*
    // and deliberately not the direction, so a restored requester is not asking for anything — and
    // a screen the user had moved on from does not grab the keyboard seconds later.
    //
    // Here that shows up as: a new generation, with the field's last request having been "give it
    // up", must not re-fire the earlier "take it".
    val tree = tree(requested = true, sequence = 1)
    runComposeUiTest {
      var generation by mutableStateOf("first")
      setContent {
        Box(Modifier.size(300.dp)) {
          DogwoodTree(tree, EventSink { _, _, _ -> }, evaluatorKey = generation, skew = tree.skew)
        }
      }
      waitForIdle()
      field().assertIsFocused()

      tree.apply(decodePositional("[2,[[1,1,$REQUESTED,false],[1,1,$SEQUENCE,2]]]"))
      waitForIdle()
      field().assertIsNotFocused()

      generation = "second"
      waitForIdle()

      field().assertIsNotFocused()
    }
  }

  @Test
  fun nothingAboutFocusIsRecordedAsSkewOnTheOrdinaryPath() {
    val tree = tree(requested = true, sequence = 1)
    show(tree) { waitForIdle() }
    assertTrue(
      tree.skew.rejectedFocusRequests.isEmpty(),
      "an ordinary request was reported as refused: ${tree.skew.rejectedFocusRequests}",
    )
  }
}

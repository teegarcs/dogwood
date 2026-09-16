/*
 * Project Dogwood -- the host half of the holder that answers.
 *
 * The guest tests pin the correlation: a reply carries the sequence it answers, and a stale one is
 * dropped. These pin what the host does — that a request actually shows a snackbar, that the
 * answer reports the outcome the user chose, and that asking twice shows twice.
 *
 * Runs on the Java Virtual Machine, an iOS simulator and a real browser, because it is in
 * `renderTest`.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.decodePositional
import dev.dogwood.protocol.widgetTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * How long an outcome may take to arrive.
 *
 * Compose's default is one second, which is a development machine's second. These waits are on
 * outcomes that take several frames -- a held target that waits for a list to grow, an animated
 * scroll settling, a guest being answered -- and a two-processor continuous-integration runner
 * under load does not have the same second. `aTargetForAnItemThatDoesNotExistYetWaits...` timed out
 * there while passing everywhere else, which is the shape of a threshold rather than of a defect.
 *
 * Generous rather than tuned: a wait that ends when the outcome arrives costs nothing extra by
 * being allowed to wait longer, and a test that fails for want of a second teaches a team to rerun
 * the build rather than to read it.
 */
private const val WAIT = 10_000L

private val SNACKBAR_AREA = widgetTag(1, 16).value
private val TEXT = DogwoodDictionary.Text.value

/** One reply, as the guest would receive it. */
private data class Answer(val sequence: Int, val actionPerformed: Boolean)

@OptIn(ExperimentalTestApi::class)
class SnackbarMirrorTest {

  private val answers = mutableListOf<Answer>()

  private val sink = EventSink { _, tag, args ->
    if (tag == EventTag(1)) {
      answers += Answer(
        args[0].jsonPrimitive.intOrNull ?: -1,
        args[1].jsonPrimitive.content.toBoolean(),
      )
    }
  }

  private fun tree(
    message: String,
    actionLabel: String = "",
    sequence: Int = 1,
    watching: Boolean = true,
  ) = HostTree().also {
    it.apply(
      decodePositional(
        """[1,[[0,1,$SNACKBAR_AREA],[1,1,1,"$message"],[1,1,2,"$actionLabel"],""" +
          """[1,1,3,$sequence],[1,1,4,$watching],[3,0,1,1,0],""" +
          """[0,2,$TEXT],[1,2,1,"body"],[3,1,1,2,0]]]""",
      ),
    )
  }

  private fun show(tree: HostTree, body: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
    runComposeUiTest {
      setContent {
        Box(Modifier.size(400.dp)) { DogwoodTree(tree, sink, skew = tree.skew) }
      }
      body()
    }

  @Test
  fun theContentRendersWithNoRequest() = run {
    // The control. A `SnackbarArea` is a container first; nothing about it should depend on a
    // snackbar having been asked for.
    show(tree("", sequence = 0)) {
      onNodeWithText("body").assertIsDisplayed()
      waitForIdle()
      assertTrue(answers.isEmpty(), "answered without being asked: $answers")
    }
  }

  @Test
  fun aRequestShowsTheMessage() = run {
    show(tree("Deleted")) {
      waitUntil("the snackbar never appeared", timeoutMillis = WAIT) {
        onAllNodesWithText("Deleted").fetchSemanticsNodes().isNotEmpty()
      }
      onNodeWithText("Deleted").assertIsDisplayed()
    }
  }

  @Test
  fun tappingTheActionAnswersWithTheActionPerformed() = run {
    // The whole reason a snackbar is not a notification: what the user did decides what the guest
    // does next, and the guest is waiting to be told.
    show(tree("Deleted", actionLabel = "Undo")) {
      waitUntil("the action never appeared", timeoutMillis = WAIT) {
        onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty()
      }
      onNodeWithText("Undo").performClick()
      waitUntil("the guest was never answered", timeoutMillis = WAIT) { answers.isNotEmpty() }

      assertEquals(Answer(sequence = 1, actionPerformed = true), answers.single())
    }
  }

  @Test
  fun theAnswerCarriesTheSequenceItIsAnswering() = run {
    // Not the current sequence: by the time a snackbar resolves the guest may have asked again,
    // and answering the new request with the old one's outcome would undo the wrong row.
    show(tree("Deleted", actionLabel = "Undo", sequence = 7)) {
      waitUntil("the action never appeared", timeoutMillis = WAIT) {
        onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty()
      }
      onNodeWithText("Undo").performClick()
      waitUntil("the guest was never answered", timeoutMillis = WAIT) { answers.isNotEmpty() }

      assertEquals(7, answers.single().sequence)
    }
  }

  @Test
  fun anAreaNobodyIsWatchingShowsNothing() = run {
    show(tree("Deleted", sequence = 1, watching = false)) {
      onNodeWithText("body").assertIsDisplayed()
      waitForIdle()
      assertEquals(0, onAllNodesWithText("Deleted").fetchSemanticsNodes().size)
    }
  }

  @Test
  fun aDismissTakesTheSnackbarOffTheScreen() = run {
    // Property five is the holder's DismissSequence, appended to the shape on 2026-09-15. A shown
    // snackbar, then the guest taking it back: the observable consequence is the message gone.
    //
    // **Within one second of virtual time, with the clock held.** The first version of this test
    // let the harness auto-advance and waited for the message to leave -- and passed with the
    // dismiss branch deleted, because a `Long` snackbar leaves on its own after ten seconds and
    // the auto-advancing clock reached them at once. A control that passes without its subject
    // is the vacuity this repository keeps rediscovering; holding the clock is what makes "gone"
    // mean "dismissed" rather than "expired".
    val tree = tree("Deleted", actionLabel = "Undo")
    show(tree) {
      waitUntil("the snackbar never appeared", timeoutMillis = WAIT) {
        onAllNodesWithText("Deleted").fetchSemanticsNodes().isNotEmpty()
      }
      mainClock.autoAdvance = false
      tree.apply(decodePositional("[2,[[1,1,5,1]]]"))
      mainClock.advanceTimeBy(1_000)
      assertTrue(
        onAllNodesWithText("Deleted").fetchSemanticsNodes().isEmpty(),
        "the snackbar is still on screen a second after the guest dismissed it",
      )
      // And the guest is told, with the sequence it asked under, so a caller that did not resume
      // itself locally would still resume.
      mainClock.advanceTimeBy(1_000)
      assertTrue(answers.any { it.sequence == 1 && !it.actionPerformed }, "no dismissal answer: $answers")
    }
  }
}

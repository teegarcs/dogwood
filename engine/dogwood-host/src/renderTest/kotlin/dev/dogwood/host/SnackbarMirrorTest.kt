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
      waitUntil("the snackbar never appeared") {
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
      waitUntil("the action never appeared") {
        onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty()
      }
      onNodeWithText("Undo").performClick()
      waitUntil("the guest was never answered") { answers.isNotEmpty() }

      assertEquals(Answer(sequence = 1, actionPerformed = true), answers.single())
    }
  }

  @Test
  fun theAnswerCarriesTheSequenceItIsAnswering() = run {
    // Not the current sequence: by the time a snackbar resolves the guest may have asked again,
    // and answering the new request with the old one's outcome would undo the wrong row.
    show(tree("Deleted", actionLabel = "Undo", sequence = 7)) {
      waitUntil("the action never appeared") {
        onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty()
      }
      onNodeWithText("Undo").performClick()
      waitUntil("the guest was never answered") { answers.isNotEmpty() }

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
}

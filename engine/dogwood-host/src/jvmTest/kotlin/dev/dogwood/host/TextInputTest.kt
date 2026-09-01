/*
 * Project Dogwood -- text input, in a real composition.
 *
 * Two claims, and the second is the one the specification has been making since the first coverage
 * measurement. Masks, limits and counters never round trip. And the host is authoritative for the
 * text, so a guest value answering an edit the user has already overtaken is discarded rather than
 * applied — because applying it would undo their typing.
 */
package dev.dogwood.host

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextMaskTest {

  @Test
  fun aCardNumberIsGroupedAsItIsTyped() {
    val mask = "#### #### #### ####"
    assertEquals("4242", applyMask("4242", mask))
    assertEquals("4242 4", applyMask("42424", mask))
    assertEquals("4242 4242 4242 4242", applyMask("4242424242424242", mask))
  }

  @Test
  fun aTrailingLiteralIsNotEmittedBeforeTheCharacterThatFollowsIt() {
    // "4242 " with the caret after a space the user never typed means the next backspace deletes
    // nothing visible. Every masked field gets this wrong at least once.
    assertEquals("4242", applyMask("4242", "#### ####"))
  }

  @Test
  fun theRawValueIsWhatTheGuestSees() {
    assertEquals("4242424242424242", stripMask("4242 4242 4242 4242"))
    // Coarser than matching the mask position by position, deliberately: a paste, an autofill and
    // an input method rewriting a whole word all arrive with formatting of their own.
    assertEquals("4242424242424242", stripMask("4242-4242-4242-4242"))
    assertEquals("4242424242424242", stripMask("4242424242424242"))
  }

  @Test
  fun capacityComesFromTheMaskWhenNoLimitIsGiven() {
    assertEquals(16, maskCapacity("#### #### #### ####"))
    assertEquals(6, maskCapacity("AA-####"))
    assertEquals(0, maskCapacity("---"))
  }

  @Test
  fun theOffsetMappingPutsTheCaretWhereTheUserPutIt() {
    // The regression test for a bug a device found and this harness could not: with the mask
    // applied to the field's *value*, the caret arithmetic was the binding's and the input
    // method's idea of the cursor drifted from the field's within a few keystrokes -- typing
    // sixteen digits produced transposed ones. The mask is a visual transformation now, and this
    // pins the mapping it hands back to Compose.
    val mask = "#### #### #### ####"
    val transformed = MaskTransformation(mask)
      .filter(androidx.compose.ui.text.AnnotatedString("4242424242"))
    assertEquals("4242 4242 42", transformed.text.text)

    val offsets = transformed.offsetMapping
    // Four raw digits sit before the group separator, so the caret goes after them and before it.
    assertEquals(4, offsets.originalToTransformed(4))
    // Five raw digits: past the separator.
    assertEquals(6, offsets.originalToTransformed(5))
    assertEquals(0, offsets.originalToTransformed(0))

    // And back. The separator position maps to the four digits that precede it.
    assertEquals(4, offsets.transformedToOriginal(4))
    assertEquals(4, offsets.transformedToOriginal(5))
    assertEquals(5, offsets.transformedToOriginal(6))
  }

  @Test
  fun aMaskCanOnlyHoldWhatItsSlotsAccept() {
    // Typing a letter into a card field should do nothing, not vanish silently from the display
    // while staying in the value the guest reads.
    assertEquals("4242", filterForMask("4a2b4c2d", "#### ####"))
    assertEquals("abcd", filterForMask("a1b2c3d4", "AAAA"))
    assertEquals("a1b2", filterForMask("a1b2-!", "AA-##"))
  }

  @Test
  fun aMaskedFieldRoundTripsWhateverTheUserTyped() {
    val mask = "#### #### #### ####"
    val typed = "4242424242424242"
    assertEquals(typed, stripMask(applyMask(typed, mask)))
  }
}

class TextInputHostTest {

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun theHostAppliesTheMaskAndReportsTheRawValue() {
    var reported: Pair<String, Int>? = null
    runComposeUiTest {
      setContent {
        TextInputImpl(
          text = "",
          version = 0,
          label = "Card",
          placeholder = null,
          enabled = true,
          singleLine = true,
          maxLength = -1,
          mask = "#### #### #### ####",
          keyboard = "number",
          showCounter = false,
          modifier = androidx.compose.ui.Modifier,
          onValueChange = { value, count -> reported = value to count },
        )
      }
      onNode(hasSetTextAction()).performTextInput("42424242")
      waitForIdle()
      // The user sees groups; the guest is told digits. A guest validating a card number never
      // has to strip anything, and changing the mask cannot change what it sees.
      onNodeWithText("4242 4242").assertIsDisplayed()
    }
    assertEquals("42424242", reported?.first)
    assertTrue((reported?.second ?: 0) > 0, "every edit must carry a count")
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aStaleGuestValueIsDiscardedRatherThanUndoingTheUsersTyping() {
    // The conflict rule. The guest is answering the state before the user typed; applying its
    // value would erase what they just wrote.
    var guestText by mutableStateOf("")
    var guestVersion by mutableStateOf(0)
    runComposeUiTest {
      setContent {
        TextInputImpl(
          text = guestText,
          version = guestVersion,
          label = null,
          placeholder = null,
          enabled = true,
          singleLine = true,
          maxLength = -1,
          mask = null,
          keyboard = null,
          showCounter = false,
          modifier = androidx.compose.ui.Modifier,
          onValueChange = { _, _ -> },
        )
      }
      onNode(hasSetTextAction()).performTextInput("hello")
      waitForIdle()
      onNodeWithText("hello").assertIsDisplayed()

      // A guest value stamped with the count from before the typing.
      guestText = "from the guest"
      guestVersion = 0
      androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
      waitForIdle()

      onNodeWithText("hello").assertIsDisplayed()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun anUpToDateGuestValueIsAdopted() {
    // The other half, and the reason the rule is a version rather than a flat "host always wins":
    // clearing a field, or filling it from a saved address, has to work.
    var guestText by mutableStateOf("")
    var guestVersion by mutableStateOf(0)
    runComposeUiTest {
      setContent {
        TextInputImpl(
          text = guestText,
          version = guestVersion,
          label = null,
          placeholder = null,
          enabled = true,
          singleLine = true,
          maxLength = -1,
          mask = null,
          keyboard = null,
          showCounter = false,
          modifier = androidx.compose.ui.Modifier,
          onValueChange = { _, count -> guestVersion = count },
        )
      }
      onNode(hasSetTextAction()).performTextInput("typed")
      waitForIdle()

      // The guest acknowledged that edit, then set its own value.
      guestText = "from the guest"
      androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
      waitForIdle()

      onNodeWithText("from the guest").assertIsDisplayed()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun theLengthLimitIsEnforcedWhereTheTypingIs() {
    var reported = ""
    runComposeUiTest {
      setContent {
        TextInputImpl(
          text = "",
          version = 0,
          label = null,
          placeholder = null,
          enabled = true,
          singleLine = true,
          maxLength = 5,
          mask = null,
          keyboard = null,
          showCounter = true,
          modifier = androidx.compose.ui.Modifier,
          onValueChange = { value, _ -> reported = value },
        )
      }
      onNode(hasSetTextAction()).performTextInput("abcdefghij")
      waitForIdle()
      // The counter is drawn by the host too. A guest-computed one would be a crossing per
      // keystroke, which is the thing this whole subsystem exists to avoid.
      onNodeWithText("5/5").assertIsDisplayed()
    }
    assertEquals("abcde", reported)
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun clearingIsAnEditLikeAnyOther() {
    var reported: String? = null
    runComposeUiTest {
      setContent {
        TextInputImpl(
          text = "",
          version = 0,
          label = null,
          placeholder = null,
          enabled = true,
          singleLine = true,
          maxLength = -1,
          mask = null,
          keyboard = null,
          showCounter = false,
          modifier = androidx.compose.ui.Modifier,
          onValueChange = { value, _ -> reported = value },
        )
      }
      onNode(hasSetTextAction()).performTextInput("abc")
      waitForIdle()
      onNode(hasSetTextAction()).performTextClearance()
      waitForIdle()
    }
    assertEquals("", reported)
  }
}

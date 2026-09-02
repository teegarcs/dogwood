/*
 * Project Dogwood -- text input, guest side.
 *
 * The guest's job here is small and exact: hold a mirror of the host's text, remember which edit
 * it last saw, and stamp everything it sends with that number. Everything that could stutter --
 * the caret, the input method, the mask -- is on the other side on purpose.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.StateSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val TEXT = 1
private const val VERSION = 2

private fun RecordingHost.last(tag: Int): PropertySet? =
  decoded().flatMap { it.g }.filterIsInstance<PropertySet>().lastOrNull { it.p.value == tag }

class TextFieldStateTest {

  private var captured: TextFieldState? = null

  @Composable
  private fun Field() {
    val state = rememberTextFieldState()
    captured = state
    TextField(state = state, label = TextValue("Search"))
  }

  private fun field(host: RecordingHost, restored: StateSnapshot?) =
    DogwoodComposition(host, HostEnvironment(), emptyMap(), restored) { Field() }

  @Test
  fun theGuestSendsTheTextItLastAcknowledged() {
    val host = RecordingHost()
    val composition = field(host, null)
    val state = checkNotNull(captured)
    val node = host.decoded().first().g
      .filterIsInstance<dev.dogwood.protocol.Create>()
      .first { it.w == Tags.TextInput }
      .i

    assertEquals(0, host.last(VERSION)?.v?.jsonPrimitive?.intOrNull, "nothing has been edited yet")

    // The host reports an edit: the raw text, and the count it stamped.
    composition.sendEvent(
      Event(
        i = node,
        e = EventTag(1),
        q = composition.lastSentSequence,
        a = listOf(JsonPrimitive("hel"), JsonPrimitive(3)),
      ),
    )
    composition.frame(0L)

    assertEquals("hel", state.text)
    assertEquals(3, host.last(VERSION)?.v?.jsonPrimitive?.intOrNull, "the guest echoes the count it saw")
    composition.dispose()
  }

  @Test
  fun aProgrammaticSetKeepsTheAcknowledgedCount() {
    // Deliberate. The count says which edit this value is answering, and a guest clearing a field
    // is answering the last edit it saw -- not claiming to be newer than the user.
    val host = RecordingHost()
    val composition = field(host, null)
    val state = checkNotNull(captured)
    val node = host.decoded().first().g
      .filterIsInstance<dev.dogwood.protocol.Create>()
      .first { it.w == Tags.TextInput }
      .i

    composition.sendEvent(
      Event(
        i = node,
        e = EventTag(1),
        q = composition.lastSentSequence,
        a = listOf(JsonPrimitive("hello"), JsonPrimitive(5)),
      ),
    )
    composition.frame(0L)

    state.clear()
    composition.frame(16L)

    assertEquals("", host.last(TEXT)?.v?.toString()?.trim('"'))
    assertEquals(5, host.last(VERSION)?.v?.jsonPrimitive?.intOrNull)
    composition.dispose()
  }

  @Test
  fun theTextAndTheCountBothSurviveACodeUpdate() {
    // Both, and the count is the one that is easy to forget. The host's edit count survives a code
    // update because its binding keeps the same composition group, so a guest that came back
    // stamped zero would have every value it sent discarded as stale -- silently, for the life of
    // the screen.
    val first = RecordingHost()
    val composition = field(first, null)
    val node = first.decoded().first().g
      .filterIsInstance<dev.dogwood.protocol.Create>()
      .first { it.w == Tags.TextInput }
      .i
    composition.sendEvent(
      Event(
        i = node,
        e = EventTag(1),
        q = composition.lastSentSequence,
        a = listOf(JsonPrimitive("half typed"), JsonPrimitive(9)),
      ),
    )
    composition.frame(0L)
    val carried = composition.snapshotState()
    composition.dispose()

    captured = null
    val second = RecordingHost()
    val replacement = field(second, carried)
    val restored = checkNotNull(captured)

    assertEquals("half typed", restored.text, "a half-typed field must not empty itself on publish")
    assertEquals(9, second.last(VERSION)?.v?.jsonPrimitive?.intOrNull, "the edit count must come back too")
    replacement.dispose()
  }

  @Test
  fun aDeclaredMaskCrossesOnceAndNotPerKeystroke() {
    // The mask is a declaration, not a conversation. A card field that asked the guest where to
    // put the spaces would be the per-keystroke crossing the Layer 4 invariant forbids.
    val (host, _) = compose {
      val state = rememberTextFieldState()
      TextField(state = state, mask = "#### #### #### ####", keyboard = Keyboards.NUMBER)
    }
    val masks = host.decoded().flatMap { it.g }.filterIsInstance<PropertySet>()
      .filter { it.p.value == 8 }
    assertEquals(1, masks.size)
    assertEquals("#### #### #### ####", masks.single().v.toString().trim('"'))
  }

  @Test
  fun anUnsetOptionalSendsNothing() {
    val (host, _) = compose {
      TextField(state = rememberTextFieldState())
    }
    val sentTags = host.decoded().flatMap { it.g }.filterIsInstance<PropertySet>().map { it.p.value }
    assertTrue(3 !in sentTags, "no label was given, so none should cross")
    assertTrue(8 !in sentTags, "no mask was given, so none should cross")
  }
}

/*
 * What a snapshot is allowed to remember.
 *
 * Saved state used to live microseconds inside a code update and never leave the process, so
 * saving a field's text cost nothing. Persisting a snapshot across process death makes it user
 * data at rest -- measured, for a masked card-number field, as the digits in plain text -- and
 * that is a different thing to be storing. Nothing in the host can decide which fields are too
 * sensitive to survive a process; only the code that declared them can.
 */
class SensitiveFieldTest {

  @Test
  fun anOrdinaryFieldKeepsItsTextAcrossASnapshot() {
    // The control, and the behaviour that must not regress: losing a half-typed address is the
    // whole failure this mechanism exists to prevent.
    val state = TextFieldState("10 Downing Street", 4)
    @Suppress("UNCHECKED_CAST")
    val saved = with(TextFieldState.Saver) { SaverScopeStub.save(state) } as List<Any?>
    assertEquals("10 Downing Street", saved[0])
    assertEquals(4, saved[1])
  }

  @Test
  fun aSensitiveFieldSavesItsShapeButNotItsContents() {
    val state = TextFieldState("4242424242424242", 4, sensitive = true)
    @Suppress("UNCHECKED_CAST")
    val saved = with(TextFieldState.Saver) { SaverScopeStub.save(state) } as List<Any?>
    assertEquals("", saved[0], "a sensitive field's text must never reach a snapshot")
    // The acknowledged count still survives, and it has to: a field restored stamped zero would
    // silently discard everything the user typed next, for the life of the screen.
    assertEquals(4, saved[1])
    assertEquals(true, saved[2])

    val restored = TextFieldState.Saver.restore(saved)!!
    assertEquals("", restored.text)
    assertEquals(4, restored.acknowledged)
    assertTrue(restored.sensitive, "and it comes back still marked sensitive")
  }

  @Test
  fun aSnapshotWrittenBeforeTheFlagExistedStillRestores() {
    // Two entries, not three. An older guest had no sensitive fields to protect, so the absence
    // reads as false rather than as a corrupt entry.
    val restored = TextFieldState.Saver.restore(listOf("kept", 2))!!
    assertEquals("kept", restored.text)
    assertEquals(2, restored.acknowledged)
    assertTrue(!restored.sensitive)
  }
}

/** `Saver.save` needs a `SaverScope`; nothing in these savers consults it. */
private object SaverScopeStub : androidx.compose.runtime.saveable.SaverScope {
  override fun canBeSaved(value: Any): Boolean = true
}

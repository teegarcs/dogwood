/*
 * Project Dogwood -- text input.
 *
 * The one live-state holder that cannot use [DogwoodLazyListState]'s pattern, and Layer 5 has said
 * so since the first coverage measurement: "Do not attempt a naive controlled `TextField`."
 *
 * The reason is the Layer 4 invariant. A controlled text field asks the guest what the text should
 * be after every keystroke, which is a boundary crossing per character with a composition on the
 * far side of it. At sixty words a minute that is five crossings a second, each one racing the
 * next keystroke -- and when one loses, the caret jumps, a character is swallowed, or the input
 * method's composing region is torn apart mid-word. Every framework that has tried this has the
 * same bug report.
 *
 * So the **host is authoritative for the text**, and the guest holds a version-stamped mirror.
 * That is Redwood's shape -- its `TextFieldState` carries a `userEditCount` and its host binding
 * discards stale guest updates outright -- with the conflict rule made explicit here:
 *
 *   - The host counts user edits. Every edit event carries the text **and** the count.
 *   - The guest records both, so it always knows which edit it is answering.
 *   - When the guest sends text back, it sends the count it last acknowledged.
 *   - **The host discards a guest value stamped older than its own count**, because the user has
 *     typed since and the user wins.
 *
 * A programmatic set from the guest -- clearing a field, filling one from a saved address -- is
 * therefore only honoured when the guest is up to date, which is exactly when it should be.
 *
 * **Masks, length limits and counters never round trip at all.** A card-number field that asked
 * the guest where to put the spaces would be the per-keystroke crossing again, wearing a hat. The
 * guest *declares* the mask and the limit; the host applies them as the user types.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The guest's view of a host-owned text field.
 *
 * [text] is always the **raw** value -- digits, not "4242 4242 4242 4242". Masking is a display
 * transform the host applies, so guest code that validates a card number never has to strip
 * anything, and a change to the mask cannot change what the guest sees.
 */
class DogwoodTextFieldState internal constructor(
  initialText: String = "",
  initialAcknowledged: Int = 0,
) {
  var text: String by mutableStateOf(initialText)
    private set

  /**
   * The host edit count this guest has seen.
   *
   * Sent back with every value the guest writes, and the whole conflict rule rests on it: a value
   * stamped older than the host's own count is answering a keystroke the user has already
   * overtaken.
   */
  internal var acknowledged: Int by mutableStateOf(initialAcknowledged)
    private set

  /**
   * Sets the text from guest code.
   *
   * Honoured only if the guest is up to date with the host's edits, which is the point rather than
   * a limitation: an assignment computed from text the user has already changed is stale by
   * definition, and applying it would undo their typing.
   */
  fun set(value: String) {
    text = value
  }

  fun clear() = set("")

  /** Called when the host reports an edit. */
  internal fun onHostEdit(value: String, editCount: Int) {
    text = value
    acknowledged = editCount
  }

  companion object {
    /**
     * Saves the text **and** the acknowledged count.
     *
     * Both, because the host's edit count survives a code update -- its binding keeps the same
     * composition group -- so a guest that came back stamped zero would have every value it sent
     * discarded as stale, silently, for the life of the screen.
     */
    val Saver: Saver<DogwoodTextFieldState, Any> = listSaver(
      save = { listOf(it.text, it.acknowledged) },
      restore = { DogwoodTextFieldState(it[0] as String, it[1] as Int) },
    )
  }
}

@Composable
fun rememberDogwoodTextFieldState(initialText: String = ""): DogwoodTextFieldState =
  rememberSaveable(saver = DogwoodTextFieldState.Saver) { DogwoodTextFieldState(initialText) }

/** Which keyboard the host should offer. Named rather than an ordinal, like every other token. */
object Keyboards {
  const val TEXT = "text"
  const val NUMBER = "number"
  const val PHONE = "phone"
  const val EMAIL = "email"
  const val PASSWORD = "password"
  const val DECIMAL = "decimal"
}

/**
 * A text field whose state the host owns.
 *
 * @param mask a display pattern -- `#` takes a digit, `A` takes a letter, anything else is a
 *   literal the host inserts. `"#### #### #### ####"` renders a card number. The guest never sees
 *   the spaces.
 * @param maxLength counted in raw characters, enforced host-side. -1 for no limit.
 * @param showCounter draws "12/50" beneath the field, computed by the host. A guest-computed
 *   counter would be a crossing per keystroke.
 */
@Composable
fun TextField(
  state: DogwoodTextFieldState,
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  label: String? = null,
  placeholder: String? = null,
  enabled: Boolean = true,
  singleLine: Boolean = true,
  maxLength: Int = -1,
  mask: String? = null,
  keyboard: String? = null,
  showCounter: Boolean = false,
) {
  TextInput(
    text = state.text,
    version = state.acknowledged,
    modifier = modifier,
    label = label,
    placeholder = placeholder,
    enabled = enabled,
    singleLine = singleLine,
    maxLength = maxLength,
    mask = mask,
    keyboard = keyboard,
    showCounter = showCounter,
    onValueChange = { value, editCount -> state.onHostEdit(value, editCount) },
  )
}

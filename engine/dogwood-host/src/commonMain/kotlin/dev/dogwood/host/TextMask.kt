/*
 * Project Dogwood -- display masks, applied where the user is typing.
 *
 * The roadmap is explicit that masks belong to this subsystem and that "per-keystroke guest round
 * trips are forbidden by the Layer 4 invariant". A card-number field that asked the guest where to
 * put the spaces would be exactly that crossing, wearing a hat.
 *
 * So a mask is a *declaration* the guest makes once and the host applies continuously. The guest's
 * value stays raw, which also means changing the mask cannot change what guest validation sees.
 */
package dev.dogwood.host

/** `#` takes a digit, `A` takes a letter; anything else is a literal the host inserts. */
private const val DIGIT_SLOT = '#'
private const val LETTER_SLOT = 'A'

/** How many characters [mask] can hold. */
fun maskCapacity(mask: String): Int = mask.count { it == DIGIT_SLOT || it == LETTER_SLOT }

/**
 * Renders [raw] through [mask].
 *
 * Trailing literals are **not** emitted ahead of the character that follows them: a card field
 * showing "4242 " before the fifth digit is typed puts the caret after a space the user did not
 * type, and every backspace then deletes nothing visible.
 */
fun applyMask(raw: String, mask: String): String {
  if (mask.isEmpty()) return raw
  val builder = StringBuilder()
  var index = 0
  for (slot in mask) {
    if (index >= raw.length) break
    when (slot) {
      DIGIT_SLOT -> {
        val next = raw.getOrNull(index) ?: break
        if (!next.isDigit()) {
          index++
          continue
        }
        builder.append(next)
        index++
      }
      LETTER_SLOT -> {
        val next = raw.getOrNull(index) ?: break
        if (!next.isLetter()) {
          index++
          continue
        }
        builder.append(next)
        index++
      }
      else -> builder.append(slot)
    }
  }
  return builder.toString()
}

/**
 * Recovers the raw value from formatted text.
 *
 * Everything that is not a letter or a digit is a literal the mask put there. Coarser than
 * matching the mask position by position, deliberately: the user can paste, autofill can arrive
 * with its own formatting, and an input method can rewrite a whole word at once. A
 * position-matching reader breaks on all three; this one keeps whatever the user actually meant.
 */
fun stripMask(display: String): String = display.filter { it.isLetterOrDigit() }

/** Keeps only the characters [mask]'s slots can actually hold. */
fun filterForMask(raw: String, mask: String): String {
  val digits = mask.any { it == DIGIT_SLOT }
  val letters = mask.any { it == LETTER_SLOT }
  return when {
    digits && letters -> raw.filter { it.isLetterOrDigit() }
    digits -> raw.filter { it.isDigit() }
    letters -> raw.filter { it.isLetter() }
    else -> raw
  }
}

/**
 * Renders [mask] over the field's own text, leaving the value raw.
 *
 * The first implementation set the field's `value` to the masked string and stripped it back on
 * every edit. It worked in tests and produced transposed digits on a device: with a display
 * transform applied to the value itself, the caret arithmetic is the binding's problem, and the
 * input method's idea of where the cursor is drifts from the field's within a few keystrokes.
 *
 * A `VisualTransformation` is the shape Compose provides for exactly this. The field's text stays
 * raw -- so the guest's value and the field's value are the same string, with no conversion in the
 * hot path at all -- and the offset mapping hands the caret arithmetic back to Compose, which
 * knows what the input method is doing and this binding does not.
 */
class MaskTransformation(private val mask: String) :
  androidx.compose.ui.text.input.VisualTransformation {

  private fun isSlot(index: Int) =
    index < mask.length && (mask[index] == DIGIT_SLOT || mask[index] == LETTER_SLOT)

  override fun filter(
    text: androidx.compose.ui.text.AnnotatedString,
  ): androidx.compose.ui.text.input.TransformedText {
    val shown = applyMask(text.text, mask)
    val mapping = object : androidx.compose.ui.text.input.OffsetMapping {
      /** How far into the rendered string the caret sits after [offset] raw characters. */
      override fun originalToTransformed(offset: Int): Int {
        var placed = 0
        var index = 0
        while (index < mask.length && placed < offset) {
          if (isSlot(index)) placed++
          index++
        }
        return index.coerceAtMost(shown.length)
      }

      /** How many raw characters precede [offset] rendered ones. */
      override fun transformedToOriginal(offset: Int): Int {
        var placed = 0
        for (index in 0 until offset.coerceAtMost(mask.length)) if (isSlot(index)) placed++
        return placed.coerceAtMost(text.text.length)
      }
    }
    return androidx.compose.ui.text.input.TransformedText(
      androidx.compose.ui.text.AnnotatedString(shown),
      mapping,
    )
  }
}

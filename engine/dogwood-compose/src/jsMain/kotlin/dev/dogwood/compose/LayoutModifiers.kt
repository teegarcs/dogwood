/*
 * Project Dogwood -- the primitive tier's second growth, guest side.
 *
 * The first tier had five widgets and thirteen modifiers, and it was enough to prove the protocol
 * and not enough to build with: a guest could not make a `Box` tappable, draw a border, push a
 * node sideways, space a row out, or set a label bold. Every one of those needed a registered
 * component, and a registered component needs an application release -- which is the treadmill
 * the whole architecture exists to end. A "new component type without a release" is only ever a
 * *composition* of primitives the installed host already draws, so the primitive vocabulary is the
 * lever, and this file is most of the second turn of it.
 *
 * Nothing here is new machinery. Every modifier is a tag and a value on the existing chain;
 * `clickable` reports through the per-element event slot animation completions already use; the
 * text and layout names are strings the host resolves and, when it cannot, reports. A host one
 * layout version behind ignores all of it and draws the node without -- except that a payload
 * using any of it declares layout version 2 and such a host refuses it before it starts
 * ([ADR-061](../../../../../../adrs/layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md)),
 * which is what keeps "ignored" from meaning "a screen nothing can tap".
 */
@file:OptIn(dev.dogwood.compose.DogwoodGeneratedApi::class)

package dev.dogwood.compose

import dev.dogwood.protocol.ModifierTags
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

// ---------------------------------------------------------------------------------------------
// Names the host resolves
//
// Each is a closed set in Compose, and each crosses as a *name* rather than an ordinal. A name
// survives a reordered declaration, reads as itself in a transcript, and lets a client that has
// never heard of it say so in the skew report; an ordinal resolves silently to whichever entry
// happens to sit at that index. The bytes are a few characters per property.
// ---------------------------------------------------------------------------------------------

/**
 * How a row or column distributes its children along its main axis.
 *
 * Compose's own vocabulary and Compose's own names. The single wire form is a string, so
 * `spacedBy(8)` is `"spacedBy:8"` and a host that has never heard of `SpaceEvenly` gets its own
 * default rather than a crash.
 */
class Arrangement private constructor(internal val wire: String) {
  override fun equals(other: Any?): Boolean = other is Arrangement && other.wire == wire
  override fun hashCode(): Int = wire.hashCode()
  override fun toString(): String = "Arrangement($wire)"

  companion object {
    /** Children packed toward the start of the axis: the top of a column, the start of a row. */
    val Start: Arrangement = Arrangement("start")
    val Center: Arrangement = Arrangement("center")
    /** Children packed toward the end: the bottom of a column, the end of a row. */
    val End: Arrangement = Arrangement("end")
    val SpaceBetween: Arrangement = Arrangement("spaceBetween")
    val SpaceAround: Arrangement = Arrangement("spaceAround")
    val SpaceEvenly: Arrangement = Arrangement("spaceEvenly")

    /** Compose's `Top` and `Bottom` are `Start` and `End` on a vertical axis; named for readers. */
    val Top: Arrangement get() = Start
    val Bottom: Arrangement get() = End

    /** A fixed gap between neighbours, in density-independent pixels. Negative is clamped host-side. */
    fun spacedBy(dp: Int): Arrangement = Arrangement("spacedBy:$dp")
  }
}

/** A font weight the host resolves; the four names every type ramp has, or a numeric weight. */
class FontWeight private constructor(internal val wire: String) {
  override fun equals(other: Any?): Boolean = other is FontWeight && other.wire == wire
  override fun hashCode(): Int = wire.hashCode()

  companion object {
    val Normal: FontWeight = FontWeight("normal")
    val Medium: FontWeight = FontWeight("medium")
    val SemiBold: FontWeight = FontWeight("semibold")
    val Bold: FontWeight = FontWeight("bold")

    /** A numeric weight, 100..900. Out of range is clamped host-side and reported. */
    fun of(weight: Int): FontWeight = FontWeight(weight.toString())
  }
}

enum class TextAlign(internal val wire: String) { Start("start"), Center("center"), End("end"), Justify("justify") }

enum class TextOverflow(internal val wire: String) { Clip("clip"), Ellipsis("ellipsis"), Visible("visible") }

enum class TextDecoration(internal val wire: String) { Underline("underline"), LineThrough("lineThrough") }

// ---------------------------------------------------------------------------------------------
// Modifiers
// ---------------------------------------------------------------------------------------------

/**
 * Makes any node tappable.
 *
 * The handler never crosses. What crosses is `enabled`, as the element's value, and the handler
 * sits in the chain's per-element callback slot -- the one animation completions use -- so the
 * host reports a tap on `ELEMENT_EVENT_BASE + index` and `applyModifier` routes it here. No
 * allocated identifier, nothing new on the wire, and it works on a `Box`, a `Text` or a registered
 * component alike, which `Row(onClick)` never did.
 *
 * `enabled` is a property rather than a reason to omit the element, because a disabled control
 * still occupies its slot and still reads as a control to a screen reader.
 */
fun Modifier.clickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
  then(ModifierTags.CLICKABLE, JsonPrimitive(enabled), onClick)

/** A border of [widthDp] in [color] -- a recipe, so a token follows the palette. */
fun Modifier.border(widthDp: Int, color: Color): Modifier =
  then(ModifierTags.BORDER, JsonArray(listOf(JsonPrimitive(widthDp), color.json)))

/** Moves the node without affecting its siblings' layout, as Compose's `offset` does. */
fun Modifier.offset(xDp: Int = 0, yDp: Int = 0): Modifier =
  then(ModifierTags.OFFSET, JsonArray(listOf(JsonPrimitive(xDp), JsonPrimitive(yDp))))

fun Modifier.fillMaxHeight(fraction: Float = 1.0f): Modifier =
  then(ModifierTags.FILL_MAX_HEIGHT, JsonPrimitive(fraction))

fun Modifier.fillMaxSize(fraction: Float = 1.0f): Modifier =
  then(ModifierTags.FILL_MAX_SIZE, JsonPrimitive(fraction))

/** Per-side padding. One wire form for the three Compose spellings; the host reads four numbers. */
fun Modifier.padding(start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0): Modifier =
  then(
    ModifierTags.PADDING_SIDES,
    JsonArray(listOf(JsonPrimitive(start), JsonPrimitive(top), JsonPrimitive(end), JsonPrimitive(bottom))),
  )

/** Symmetric padding, spelled as Compose spells it. Crosses as the four-sided form. */
fun Modifier.padding(horizontal: Int, vertical: Int): Modifier =
  padding(start = horizontal, top = vertical, end = horizontal, bottom = vertical)

/** A drop shadow of [elevationDp]. Negative is clamped host-side, because Compose throws on it. */
fun Modifier.shadow(elevationDp: Int): Modifier =
  then(ModifierTags.SHADOW, JsonPrimitive(elevationDp))

/** Width over height. Zero or negative is clamped host-side, because Compose throws on it. */
fun Modifier.aspectRatio(ratio: Float): Modifier =
  then(ModifierTags.ASPECT_RATIO, JsonPrimitive(ratio))

/**
 * What a screen reader says for this node. Draws nothing.
 *
 * The one accessibility seam a guest-composed component needs: a tappable `Box` with an icon in
 * it has no text of its own, and without this it reaches TalkBack and VoiceOver as an unnamed
 * control -- exactly the defect the accessibility drill found on the card-number field.
 */
fun Modifier.contentDescription(text: String): Modifier =
  then(ModifierTags.CONTENT_DESCRIPTION, JsonPrimitive(text))

/** A tag a test or a drill can find the node by. Semantics only; draws nothing. */
fun Modifier.testTag(tag: String): Modifier =
  then(ModifierTags.TEST_TAG, JsonPrimitive(tag))

fun Modifier.wrapContentWidth(): Modifier = then(ModifierTags.WRAP_CONTENT_WIDTH, JsonPrimitive(true))
fun Modifier.wrapContentHeight(): Modifier = then(ModifierTags.WRAP_CONTENT_HEIGHT, JsonPrimitive(true))

/** A minimum size applied only when nothing else constrains the node. -1 leaves a side alone. */
fun Modifier.defaultMinSize(minWidthDp: Int = -1, minHeightDp: Int = -1): Modifier =
  then(ModifierTags.DEFAULT_MIN_SIZE, JsonArray(listOf(JsonPrimitive(minWidthDp), JsonPrimitive(minHeightDp))))

/** Width bounds. -1 leaves a bound unconstrained, as `Dp.Unspecified` does in Compose. */
fun Modifier.widthIn(minDp: Int = -1, maxDp: Int = -1): Modifier =
  then(ModifierTags.WIDTH_IN, JsonArray(listOf(JsonPrimitive(minDp), JsonPrimitive(maxDp))))

/** Height bounds. -1 leaves a bound unconstrained. */
fun Modifier.heightIn(minDp: Int = -1, maxDp: Int = -1): Modifier =
  then(ModifierTags.HEIGHT_IN, JsonArray(listOf(JsonPrimitive(minDp), JsonPrimitive(maxDp))))

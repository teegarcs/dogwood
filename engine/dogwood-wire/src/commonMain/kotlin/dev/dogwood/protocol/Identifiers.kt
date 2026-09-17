/*
 * Project Dogwood -- the shared identifier spaces, declared once each.
 *
 * These numbers are the protocol. A deferred expression is `[factory, args...]` and a modifier
 * element is `[tag, value]`, so a factory identifier or a modifier tag that means one thing in the
 * guest and another in the host does not fail: it produces a plausible wrong answer. A price
 * formats as a currency code, a padding becomes a rotation.
 *
 * They used to be declared separately on each side -- the factory space in one guest object and
 * three `private` host blocks, the modifier space in one of each -- with nothing comparing any of
 * them. That is the arrangement that let identifier 13 be skipped on both sides with no record
 * anywhere of what withdrew it, which is exactly how the next factory would have collided with a
 * retired one. `Bindings.kt` already carries the lesson for widget tags: "a tag collision does not
 * fail to render; it renders the wrong widget." The reasoning was never extended here.
 *
 * **Identifiers are permanent.** Append; never renumber, never reuse a withdrawn one.
 */
package dev.dogwood.protocol

/**
 * Deferred-expression factories: the first element of `[factory, args...]`.
 *
 * A guest names an intent it cannot resolve -- a colour token, a currency amount, an animation
 * target -- and the host resolves it against the environment it is drawing in.
 */
object ExpressionFactories {
  const val ROUNDED_CORNER = 1
  const val CIRCLE = 2
  const val COLOR_ARGB = 3
  const val COLOR_TOKEN = 4

  // Text the guest cannot produce. The pinned QuickJS ships no ECMA-402 `Intl`, so a guest has no
  // locale-aware number, currency or date formatting at all -- not a slow one, none.
  const val TEXT_NUMBER = 5
  const val TEXT_CURRENCY = 6
  const val TEXT_PERCENT = 7
  const val TEXT_DATE = 8
  const val TEXT_TIME = 9
  const val TEXT_DATE_TIME = 10
  const val TEXT_RELATIVE_TIME = 11

  // Values that change with the host's clock. `ANIMATED_NUMBER` is ADR-020's declared target;
  // the other two extend the same idea to colour and to motion that repeats.
  const val ANIMATED_NUMBER = 12

  /**
   * **13 is withdrawn and must never be reused.**
   *
   * It was skipped on both sides during Phase 4 with no record of why, which is the state that
   * makes a collision inevitable rather than unlikely: the next factory takes the lowest free
   * number, and nothing remembers that this one was not free. Recorded here so that a payload
   * built before whatever used to hold it cannot be read as something else now.
   */
  const val WITHDRAWN_13 = 13

  const val ANIMATED_COLOR = 14
  const val OSCILLATE = 15
  const val TEXT_PLURAL = 16

  /**
   * A rounded shape whose four corners differ: `[17, topStart, topEnd, bottomEnd, bottomStart]`.
   *
   * Beside [ROUNDED_CORNER] rather than replacing it, because a payload that sends one radius
   * still means one radius and a client that predates this reads the old form unchanged. The
   * order is Compose's own (`RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)`), so
   * nobody has to remember a second convention.
   */
  const val ROUNDED_CORNER_EACH = 17
}

/** Modifier element tags: the first element of `[tag, value]` inside a modifier chain. */
object ModifierTags {
  const val PADDING = 1
  const val FILL_MAX_WIDTH = 2
  const val WEIGHT = 3
  const val SIZE = 4
  const val ALPHA = 5
  const val WIDTH = 6
  const val HEIGHT = 7
  const val ALIGN = 8
  const val CLIP = 9
  const val BACKGROUND = 10
  const val ROTATE = 11
  const val SCALE = 12

  /*
   * The primitive tier's second growth (layout segment version 2). Appended, never renumbered: a
   * client one version behind reaches `else -> modifier` for every tag below and draws the node
   * without it, which is the containment rule for a cosmetic modifier -- and every one of these is
   * cosmetic except `CLICKABLE`, whose absence on an old client is contained a different way: a
   * payload that uses any tag here declares layout version 2, and a client at version 1 refuses it
   * before it starts (ADR-061) rather than drawing a screen nothing can tap.
   */

  /** Value: `enabled`. The handler is guest-side, on the event tag derived from the element index. */
  const val CLICKABLE = 13

  /** Value: `[widthDp, colour recipe]`. */
  const val BORDER = 14

  /** Value: `[xDp, yDp]`. */
  const val OFFSET = 15
  const val FILL_MAX_HEIGHT = 16
  const val FILL_MAX_SIZE = 17

  /** Value: `[startDp, topDp, endDp, bottomDp]`. The symmetric forms are this with repeats. */
  const val PADDING_SIDES = 18
  const val SHADOW = 19
  const val ASPECT_RATIO = 20

  /** Value: the description. Semantics only; draws nothing. */
  const val CONTENT_DESCRIPTION = 21

  /** Value: the tag. Semantics only, for tests and drills that need to find a node. */
  const val TEST_TAG = 22
  const val WRAP_CONTENT_WIDTH = 23
  const val WRAP_CONTENT_HEIGHT = 24

  /** Value: `[minWidthDp, minHeightDp]`; -1 leaves a side unconstrained. */
  const val DEFAULT_MIN_SIZE = 25

  /** Value: `[minDp, maxDp]`; -1 leaves a bound unconstrained. */
  const val WIDTH_IN = 26
  const val HEIGHT_IN = 27

  /**
   * Value: `[enabled, roleName]`. `CLICKABLE` with a semantics role; the handler rides the same
   * per-element slot. A separate tag rather than a widened `CLICKABLE`, so a payload that names no
   * role keeps sending the form a version-2 host already reads.
   */
  const val CLICKABLE_ROLE = 28

  /** Value: `[widthDp, colourRecipe, shapeRecipe]`. `BORDER` with a shape. */
  const val BORDER_SHAPE = 29

  /**
   * Value: `[start, top, end, bottom]`, each a number or an animated-number recipe. `PADDING_SIDES`
   * with targets: one element, so all four sides retarget together and one completion fires.
   */
  const val PADDING_SIDES_ANIMATED = 30
}

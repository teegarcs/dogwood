/*
 * Project Dogwood -- what this client did not understand.
 *
 * A payload ships months apart from the host that renders it, so a guest built against a newer
 * dictionary is the expected case, not the exceptional one. The specification's rule everywhere is
 * the same: an unknown thing renders as a documented fallback rather than throwing, **and it is
 * reported**. The first half was implemented in several places; the second was implemented in one,
 * and nobody could read it.
 *
 * This is the one place. Everything a client failed to recognise while rendering lands here, and a
 * host can ship it as telemetry -- which is how a team learns that a design system update has
 * reached payloads before it reached devices.
 */
package dev.dogwood.host

import androidx.compose.runtime.staticCompositionLocalOf

/** One experience's accumulated skew. Not snapshot state: reading it must not drive composition. */
class SkewReport {
  /*
   * These are plain sets, not snapshot state, and that is deliberate rather than an oversight.
   *
   * Most of them are written *during composition* -- an unknown colour token is recorded by the
   * binding that failed to resolve it, and a withheld widget by the guard that declined to draw it.
   * Writing snapshot state during composition is not allowed and would invalidate the very
   * composition doing the writing.
   *
   * The consequence is that this report must be **sampled, not observed**. A composable that reads
   * it sees whatever was there when that composition began, so an entry recorded during the same
   * pass appears only after some later recomposition. That is fine for what this is -- telemetry a
   * host collects and sends -- and wrong for anything that wants to react to it. Poll it.
   */
  /** Widget tags this client's dictionary does not carry. Each rendered as a placeholder node. */
  val unknownWidgetTags = mutableSetOf<Int>()

  /** Deferred-expression factories this client does not implement. */
  val unknownExpressionFactories = mutableSetOf<Int>()

  /** Colour token names absent from this client's palette. Rendered `Color.Unspecified`. */
  val unknownColorTokens = mutableSetOf<String>()

  /** Text style names absent from this client's typography. Rendered as body text. */
  val unknownTextStyles = mutableSetOf<String>()

  /** Icon names absent from this client's icon set. Rendered as the set's fallback icon. */
  val unknownIcons = mutableSetOf<String>()

  /** Enter/exit transition names this client does not implement. Rendered as a fade. */
  val unknownTransitions = mutableSetOf<String>()

  /** Number patterns the payload supplied that this platform would not accept. */
  val rejectedNumberPatterns = mutableSetOf<String>()

  /** Plural categories this locale uses that the payload carried no template for. */
  val untranslatedPlurals = mutableSetOf<String>()

  /** Routes a guest asked for that this client does not handle. The host stayed where it was. */
  val unknownRoutes = mutableSetOf<String>()

  /**
   * Widget tags replaced by an inert placeholder because they arrived carrying a property this
   * client could not interpret, on a widget that owns an affordance.
   *
   * The most serious entry in this report. Everything else here degraded something; this one
   * refused to draw a control, because drawing it might have offered the user an action the
   * payload was trying to withhold.
   */
  val withheldWidgets = mutableSetOf<Int>()

  /**
   * Batches this client refused because it could not decode them, with the reason.
   *
   * Distinct from every other entry here in what it costs. The rest name something the client did
   * not recognise inside a batch it understood, and degraded around it. This names a batch whose
   * *grammar* disagreed -- a tuple of the wrong length, a kind from a newer protocol -- where
   * there is nothing to degrade to, because the changes in a batch are ordered and interdependent
   * and applying half of one leaves a tree the guest never composed.
   */
  val rejectedBatches = mutableSetOf<String>()

  /**
   * Image requests refused because the host does not allow that origin.
   *
   * Reported for the same reason every other entry here is: the visible symptom is a blank space,
   * and a blank space with no record is indistinguishable from a slow network, a broken content
   * delivery network, or a guest bug. A team that has widened its data allow list and forgotten its
   * image one should be able to see that rather than deduce it.
   */
  val refusedImages = mutableSetOf<String>()

  /**
   * Property values the client had to clamp to keep Compose from throwing.
   *
   * Distinct from every other entry here, which record something the client did not *recognise*.
   * These are values it recognised perfectly and could not use: a negative padding, a `maxLines` of
   * zero, a weight of zero. Compose rejects them rather than clamping, and the rejection lands
   * inside composition -- so an off-by-one in a payload delivered over the air takes down the
   * screen on every client that receives it, together.
   *
   * Clamping keeps the screen. Reporting is what keeps the clamp from becoming a silent
   * difference between what the payload asked for and what the user sees.
   */
  val clampedValues = mutableSetOf<String>()


  val isEmpty: Boolean
    get() = unknownWidgetTags.isEmpty() && unknownExpressionFactories.isEmpty() &&
      unknownColorTokens.isEmpty() && unknownTextStyles.isEmpty() && unknownIcons.isEmpty() &&
      unknownTransitions.isEmpty() && rejectedNumberPatterns.isEmpty() &&
      untranslatedPlurals.isEmpty() && unknownRoutes.isEmpty() && withheldWidgets.isEmpty() &&
      rejectedBatches.isEmpty() && refusedImages.isEmpty() && clampedValues.isEmpty()

  override fun toString(): String = buildString {
    append("SkewReport(")
    if (unknownWidgetTags.isNotEmpty()) append("widgets=$unknownWidgetTags ")
    if (unknownExpressionFactories.isNotEmpty()) append("factories=$unknownExpressionFactories ")
    if (unknownColorTokens.isNotEmpty()) append("colours=$unknownColorTokens ")
    if (unknownTextStyles.isNotEmpty()) append("styles=$unknownTextStyles ")
    if (unknownIcons.isNotEmpty()) append("icons=$unknownIcons ")
    if (unknownTransitions.isNotEmpty()) append("transitions=$unknownTransitions ")
    if (rejectedNumberPatterns.isNotEmpty()) append("patterns=$rejectedNumberPatterns ")
    if (untranslatedPlurals.isNotEmpty()) append("plurals=$untranslatedPlurals ")
    if (unknownRoutes.isNotEmpty()) append("routes=$unknownRoutes ")
    if (withheldWidgets.isNotEmpty()) append("withheld=$withheldWidgets ")
    if (rejectedBatches.isNotEmpty()) append("rejectedBatches=$rejectedBatches ")
    if (refusedImages.isNotEmpty()) append("refusedImages=$refusedImages ")
    if (clampedValues.isNotEmpty()) append("clamped=$clampedValues")
    append(")")
  }
}

/** The report the current experience is filling in. */
val LocalSkewReport = staticCompositionLocalOf { SkewReport() }

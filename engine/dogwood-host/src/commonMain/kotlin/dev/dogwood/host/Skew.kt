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

  val isEmpty: Boolean
    get() = unknownWidgetTags.isEmpty() && unknownExpressionFactories.isEmpty() &&
      unknownColorTokens.isEmpty() && unknownTextStyles.isEmpty() && unknownIcons.isEmpty() &&
      unknownTransitions.isEmpty() && rejectedNumberPatterns.isEmpty() &&
      untranslatedPlurals.isEmpty() && unknownRoutes.isEmpty() && withheldWidgets.isEmpty()

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
    if (withheldWidgets.isNotEmpty()) append("withheld=$withheldWidgets")
    append(")")
  }
}

/** The report the current experience is filling in. */
val LocalSkewReport = staticCompositionLocalOf { SkewReport() }

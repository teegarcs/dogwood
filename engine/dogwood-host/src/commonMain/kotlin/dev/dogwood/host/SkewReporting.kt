/*
 * Project Dogwood -- getting the skew report out of the process.
 *
 * `SkewReport` records everything a client did not understand while rendering, because every
 * degradation rule in this architecture is **silent by design**: an unknown icon becomes the
 * fallback glyph, an unknown text style becomes body text, a value Compose would throw on is
 * clamped. A screen full of those looks fine. Without something draining this report, a team learns
 * that a design-system update reached payloads before it reached devices from a support ticket.
 *
 * The report has always existed and nothing has ever shipped it anywhere. This is the seam that
 * makes it shippable, and it is deliberately the smallest thing that could be:
 *
 *   - **Dogwood stores; the host reports.** There is no built-in transport, no batching policy, no
 *     retry, no sampling rate. A product has a telemetry system already and it is not this one's
 *     business to have opinions about it.
 *   - **Draining is explicit.** Nothing here polls, schedules or hooks a lifecycle. The host calls
 *     [SkewDrain.drain] when it wants to -- on background, on a timer, at the end of an experience --
 *     and gets what is new since the last call.
 *   - **New since last time, not everything.** The sets in `SkewReport` accumulate for the life of
 *     an experience, so a reporter that sent the whole report on every drain would send the same
 *     entries forever. That is the difference between telemetry and noise.
 */
package dev.dogwood.host

import dev.dogwood.protocol.WidgetTag

/**
 * What kind of thing a client did not understand.
 *
 * An enumeration rather than a free string, because the consumer of this is a metric name in
 * somebody's telemetry system and a typo there is a dashboard that quietly stops counting.
 */
enum class SkewKind {
  /** A widget tag this dictionary does not carry. Rendered as an inert placeholder. */
  UNKNOWN_WIDGET,

  /** A deferred-expression factory this client does not implement. */
  UNKNOWN_EXPRESSION_FACTORY,

  /** A colour token absent from this client's palette. */
  UNKNOWN_COLOR_TOKEN,

  /** A text style absent from this client's typography. */
  UNKNOWN_TEXT_STYLE,

  /** An icon absent from this client's icon set. */
  UNKNOWN_ICON,

  /** An enter/exit transition name this client does not implement. */
  UNKNOWN_TRANSITION,

  /** An enumeration entry name absent from this client's surface. Read as the parameter's default. */
  UNKNOWN_ENUM_VALUE,

  /** A number pattern this platform would not accept. */
  REJECTED_NUMBER_PATTERN,

  /** A plural category this locale uses that the payload carried no template for. */
  UNTRANSLATED_PLURAL,

  /** A route the guest asked for that this client does not handle. */
  UNKNOWN_ROUTE,

  /**
   * A widget replaced by an inert placeholder because it owns an affordance and arrived carrying a
   * property this client could not read.
   *
   * The most serious kind here. Everything else degraded something; this refused to draw a control,
   * because drawing it might have offered the user an action the payload was trying to withhold.
   */
  WITHHELD_WIDGET,

  /**
   * A batch refused because its grammar disagreed.
   *
   * Distinct from every other kind in what it costs: the rest name something unrecognised *inside*
   * a batch that was understood. This names one where there was nothing to degrade to.
   */
  REJECTED_BATCH,

  /** An image request refused because the host does not allow that origin. */
  REFUSED_IMAGE,

  /** A value the client recognised perfectly and had to clamp to keep Compose from throwing. */
  CLAMPED_VALUE,

  /** A focus request the platform refused. */
  REJECTED_FOCUS_REQUEST,
}

/**
 * One thing a client did not understand.
 *
 * [value] is a name wherever a name exists — an icon, a token, a route — and the widget kinds
 * resolve their tag to a dictionary name when this client knows one, falling back to the number
 * when it does not. That fallback is the *normal* case for [SkewKind.UNKNOWN_WIDGET]: the whole
 * point is that the tag came from a dictionary this client has never seen.
 */
data class SkewEntry(val kind: SkewKind, val value: String)

/**
 * Where skew goes. Implement this with whatever a product already uses.
 *
 * Called with entries that are **new since the last drain**, never with an empty list. It is called
 * on whichever thread called [SkewDrain.drain], which is the host's choice; nothing here moves work
 * between threads on a host's behalf.
 */
fun interface DogwoodSkewReporter {
  fun report(entries: List<SkewEntry>)
}

/** Everything currently in this report, flattened. Accumulated, so it grows over an experience. */
fun SkewReport.entries(): List<SkewEntry> = buildList {
  fun add(kind: SkewKind, values: Iterable<String>) {
    for (value in values) add(SkewEntry(kind, value))
  }
  // Widget tags go out as `DogwoodDictionary` names them, which is a name for anything this client
  // carries and `Unknown#<tag>` for anything it does not. Both are the right answer for their case:
  // a withheld widget is one this client knows and refused to draw, so its name is the useful
  // thing; an unknown one comes from a dictionary this client has never seen, so the number is all
  // there is and the string says so.
  add(SkewKind.UNKNOWN_WIDGET, unknownWidgetTags.map { DogwoodDictionary.name(WidgetTag(it)) })
  add(SkewKind.WITHHELD_WIDGET, withheldWidgets.map { DogwoodDictionary.name(WidgetTag(it)) })
  add(SkewKind.UNKNOWN_EXPRESSION_FACTORY, unknownExpressionFactories.map { it.toString() })
  add(SkewKind.UNKNOWN_COLOR_TOKEN, unknownColorTokens)
  add(SkewKind.UNKNOWN_TEXT_STYLE, unknownTextStyles)
  add(SkewKind.UNKNOWN_ICON, unknownIcons)
  add(SkewKind.UNKNOWN_TRANSITION, unknownTransitions)
  add(SkewKind.UNKNOWN_ENUM_VALUE, unknownEnumValues)
  add(SkewKind.REJECTED_NUMBER_PATTERN, rejectedNumberPatterns)
  add(SkewKind.UNTRANSLATED_PLURAL, untranslatedPlurals)
  add(SkewKind.UNKNOWN_ROUTE, unknownRoutes)
  add(SkewKind.REJECTED_BATCH, rejectedBatches)
  add(SkewKind.REFUSED_IMAGE, refusedImages)
  add(SkewKind.CLAMPED_VALUE, clampedValues)
  add(SkewKind.REJECTED_FOCUS_REQUEST, rejectedFocusRequests)
}


/**
 * Reads a [SkewReport] and hands a reporter what is new.
 *
 * One per report. Keeping the already-reported set here rather than inside `SkewReport` is
 * deliberate: two reporters — a metrics pipeline and a debug overlay, say — each want their own
 * idea of "new", and a report that remembered what it had emitted could only serve one of them.
 *
 * **Drain on the thread that composes.** `SkewReport`'s sets are plain mutable sets, written during
 * composition (`Skew.kt` records why they are not snapshot state), so reading them from another
 * thread is a data race. A host that wants to report from a background thread should drain on the
 * user-interface thread and hand the resulting list over — the list is a copy and is safe to move.
 */
class SkewDrain(private val report: SkewReport) {

  private val reported = mutableSetOf<SkewEntry>()

  /**
   * Everything recorded since the last call. Empty when nothing new happened.
   *
   * Idempotent in the sense that matters: calling it twice with nothing in between returns an empty
   * list the second time, so a host may drain as often as it likes without inflating its own counts.
   */
  fun drain(): List<SkewEntry> {
    val fresh = report.entries().filterNot { it in reported }
    reported += fresh
    return fresh
  }

  /** Drains and reports, if there is anything to report. Returns what was sent. */
  fun drainTo(reporter: DogwoodSkewReporter): List<SkewEntry> {
    val fresh = drain()
    if (fresh.isNotEmpty()) reporter.report(fresh)
    return fresh
  }

  /** Forgets what has been reported, so the next drain returns everything again. */
  fun reset() = reported.clear()

  /**
   * Whether this drain belongs to [candidate].
   *
   * A code update gives an experience a **new** `SkewReport`, and a drain carried across that
   * boundary would treat the new report's first entries as already sent — losing exactly the skew a
   * replacement payload introduced, which is the case a team most needs to see. A host keeping one
   * drain per screen checks this before reusing it.
   */
  fun isFor(candidate: SkewReport): Boolean = candidate === report
}

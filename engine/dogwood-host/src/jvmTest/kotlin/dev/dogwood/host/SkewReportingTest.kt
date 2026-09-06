/*
 * Project Dogwood -- the skew report leaves the process, or it may as well not exist.
 *
 * Every degradation rule in this architecture is silent by design: an unknown icon becomes the
 * fallback glyph, an unknown style becomes body text, a hostile value is clamped. `SkewReport`
 * records all of it, and nothing has ever shipped it anywhere — so the record was a data structure
 * nobody read.
 *
 * These tests are about the seam that makes it shippable, and they exercise it against a **real
 * composition** rather than a hand-built report: what a host would actually drain is what a
 * rendering client actually recorded.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.protocol.decodePositional
import dev.dogwood.protocol.widgetTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A widget tag from a dictionary this client has never seen. */
private val FROM_THE_FUTURE = widgetTag(1, 60).value
private val TEXT = DogwoodDictionary.Text.value

@OptIn(ExperimentalTestApi::class)
class SkewReportingTest {

  private fun render(tree: HostTree) = runComposeUiTest {
    setContent {
      Box(Modifier.size(200.dp)) { DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew) }
    }
    waitForIdle()
  }

  @Test
  fun aClientThatUnderstoodEverythingReportsNothing() {
    // The control, and it is the case that matters most in production: a reporter wired to a
    // healthy client must stay silent, or a team learns to ignore it.
    val tree = HostTree().also {
      it.apply(decodePositional("""[1,[[0,1,$TEXT],[1,1,1,"hello"],[3,0,1,1,0]]]"""))
    }
    render(tree)

    val sent = mutableListOf<List<SkewEntry>>()
    SkewDrain(tree.skew).drainTo { sent += it }

    assertTrue(tree.skew.isEmpty, "the fixture itself was skewed: ${tree.skew}")
    assertTrue(sent.isEmpty(), "a healthy client reported $sent")
  }

  @Test
  fun anUnknownWidgetReachesAReporter() {
    val tree = HostTree().also {
      it.apply(decodePositional("[1,[[0,1,$FROM_THE_FUTURE],[3,0,1,1,0]]]"))
    }
    render(tree)

    val sent = mutableListOf<SkewEntry>()
    SkewDrain(tree.skew).drainTo { sent += it }

    assertEquals(
      listOf(SkewEntry(SkewKind.UNKNOWN_WIDGET, "Unknown#$FROM_THE_FUTURE")),
      sent,
      "skew: ${tree.skew}",
    )
  }

  @Test
  fun aClampedValueReachesAReporterWithTheValueAndTheRange() {
    // A hostile value is the case a team most needs to see: the screen survives, and nothing about
    // it looks wrong. Compose would have thrown *inside composition* on this one, taking every
    // client that received the payload down together.
    val tree = HostTree().also {
      it.apply(
        decodePositional(
          """[1,[[0,1,${widgetTag(1, 8).value}],[1,1,1,-40.0],[3,0,1,1,0]]]""",
        ),
      )
    }
    render(tree)

    val sent = mutableListOf<SkewEntry>()
    SkewDrain(tree.skew).drainTo { sent += it }

    val clamped = sent.filter { it.kind == SkewKind.CLAMPED_VALUE }
    assertEquals(1, clamped.size, "skew: ${tree.skew}; sent: $sent")
    assertTrue("-40" in clamped.single().value, clamped.single().value)
  }

  @Test
  fun aSecondDrainSendsNothingNew() {
    // The difference between telemetry and noise. The sets accumulate for the life of an
    // experience, so a reporter that sent the whole report every time would send the same entries
    // forever and every count downstream would be wrong.
    val tree = HostTree().also {
      it.apply(decodePositional("[1,[[0,1,$FROM_THE_FUTURE],[3,0,1,1,0]]]"))
    }
    render(tree)

    val drain = SkewDrain(tree.skew)
    assertEquals(1, drain.drain().size)
    assertEquals(emptyList(), drain.drain())
    assertEquals(emptyList(), drain.drain())
  }

  @Test
  fun newSkewAfterADrainIsReported() {
    val tree = HostTree().also {
      it.apply(decodePositional("[1,[[0,1,$FROM_THE_FUTURE],[3,0,1,1,0]]]"))
    }
    render(tree)

    val drain = SkewDrain(tree.skew)
    assertEquals(1, drain.drain().size)

    tree.skew.unknownIcons += "sparkle"

    assertEquals(listOf(SkewEntry(SkewKind.UNKNOWN_ICON, "sparkle")), drain.drain())
  }

  @Test
  fun twoReportersEachGetEverything() {
    // Why the already-reported set lives on the drain rather than on the report: a metrics pipeline
    // and a debug overlay each want their own idea of "new", and a report that remembered what it
    // had emitted could serve only one of them.
    val report = SkewReport().also { it.unknownIcons += "sparkle" }
    val metrics = mutableListOf<SkewEntry>()
    val overlay = mutableListOf<SkewEntry>()

    SkewDrain(report).drainTo { metrics += it }
    SkewDrain(report).drainTo { overlay += it }

    assertEquals(1, metrics.size)
    assertEquals(metrics, overlay)
  }

  @Test
  fun resetMakesTheNextDrainSendEverythingAgain() {
    val report = SkewReport().also { it.unknownRoutes += "experience/settings" }
    val drain = SkewDrain(report)
    assertEquals(1, drain.drain().size)
    drain.reset()
    assertEquals(1, drain.drain().size)
  }

  @Test
  fun everyKindOfSkewHasAWayOut() {
    // A kind added to `SkewReport` and forgotten in `entries()` would be recorded and never
    // reported — silent in exactly the way this whole seam exists to prevent. This fails the moment
    // the two drift.
    val report = SkewReport().also {
      it.unknownWidgetTags += 1; it.unknownExpressionFactories += 2
      it.unknownColorTokens += "a"; it.unknownTextStyles += "b"; it.unknownIcons += "c"
      it.unknownTransitions += "d"; it.rejectedNumberPatterns += "e"; it.untranslatedPlurals += "f"
      it.unknownRoutes += "g"; it.withheldWidgets += 3; it.rejectedBatches += "h"
      it.refusedImages += "i"; it.clampedValues += "j"; it.rejectedFocusRequests += "k"
    }
    assertEquals(
      SkewKind.entries.toSet(),
      report.entries().map { it.kind }.toSet(),
      "a kind of skew is recorded but has no way out of the process",
    )
  }
}

/*
 * Project Dogwood -- a hostile value is clamped *and reported*, on the Java Virtual Machine.
 *
 * This was part of `SkewReportingTest` until that file moved to a shared source set so it would run
 * on every target. It failed on WebAssembly, and the failure is real rather than an artefact of the
 * move: `-40` reaches the reader correctly there -- the property decodes and reads back as `-40.0`
 * -- and the clamp does not fire, so nothing is recorded and nothing is reported.
 *
 * That matters more than a red test. [ADR-035](../../../../../../../adrs/layer-5/ADR-035-hostile-property-values.md)
 * exists because Compose enforces some numeric ranges by throwing *inside composition*, so a
 * payload delivered over the air can take a screen down on every client at once. The clamp is what
 * keeps the screen; the report is what stops the clamp being a silent difference between what the
 * payload asked for and what the user sees. On the web, today, it is that silent difference.
 *
 * Kept here rather than deleted or weakened, because a test that passes everywhere by asking less
 * is how a gap stops being visible. `plans/conformance.md` Part 7 carries the gap.
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

@OptIn(ExperimentalTestApi::class)
class ClampedValueReportingTest {

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


  private fun render(tree: HostTree) = runComposeUiTest {
    setContent {
      Box(Modifier.size(200.dp)) { DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew) }
    }
    waitForIdle()
  }
}

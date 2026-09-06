/*
 * Project Dogwood -- values a guest can send that the skew story does not cover.
 *
 * Containment so far has been about *names*: an unknown widget tag becomes a placeholder, an
 * unknown colour token becomes unspecified, an unknown property on an affordance widget withholds
 * the control. All of it assumes the values inside a known property are sane.
 *
 * They are guest-supplied and the guest is delivered over the air. `Modifier.padding(-40.dp)`,
 * `maxLines = 0` and `weight(0f)` all throw *inside composition* on both hosts -- which is not a
 * degraded screen, it is no screen, and on Android it is an uncaught exception in the frame the
 * user was looking at. A payload can do this by accident, with a negative number where a positive
 * one was meant, and every client that receives it goes down together.
 *
 * These tests drive the real reader and modifier paths with values a hostile or buggy payload could
 * send, and require the host to survive and report rather than throw.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.protocol.decodePositional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun batch(sequence: Int, vararg changes: String): String =
  "[$sequence,[${changes.joinToString(",")}]]"

private val TEXT = DogwoodDictionary.Text.value
private val COLUMN = DogwoodDictionary.Column.value

class HostileValueTest {

  /** A text node carrying [modifiers] and a maxLines of [maxLines], inside a column. */
  private fun screen(maxLines: Int, modifiers: String): HostTree = HostTree().also {
    it.apply(
      decodePositional(
        batch(
          1,
          "[0,1,$COLUMN]", "[3,0,1,1,0]",
          "[0,2,$TEXT]", "[1,2,1,\"survivor\"]", "[1,2,2,$maxLines]",
          "[2,2,$modifiers]", "[3,1,1,2,0]",
        ),
      ),
    )
  }

  @OptIn(ExperimentalTestApi::class)
  private fun render(tree: HostTree): Boolean {
    var drew = false
    runComposeUiTest {
      setContent {
        Box(Modifier.size(300.dp)) { DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew) }
      }
      drew = onAllNodesWithText("survivor").fetchSemanticsNodes().isNotEmpty()
    }
    return drew
  }

  @Test
  fun aSaneScreenRenders() {
    // The control. Every assertion below is "this still renders", so a fixture that never rendered
    // would make the whole file vacuous.
    assertTrue(render(screen(maxLines = 2, modifiers = "[[1,8]]")), "the fixture itself must render")
  }

  @Test
  fun aZeroMaxLinesDoesNotTakeTheScreenDown() {
    // Compose requires maxLines >= 1. A payload with an off-by-one produces 0.
    val tree = screen(maxLines = 0, modifiers = "[[1,8]]")
    assertTrue(render(tree), "a zero maxLines must not prevent the screen rendering")
  }

  @Test
  fun aNegativeMaxLinesDoesNotTakeTheScreenDown() {
    assertTrue(render(screen(maxLines = -5, modifiers = "[[1,8]]")))
  }

  @Test
  fun negativePaddingDoesNotTakeTheScreenDown() {
    // Compose throws on negative padding rather than clamping.
    assertTrue(render(screen(maxLines = 2, modifiers = "[[1,-40]]")))
  }

  @Test
  fun aZeroWeightDoesNotTakeTheScreenDown() {
    // `Modifier.weight` requires a positive value.
    assertTrue(render(screen(maxLines = 2, modifiers = "[[3,0]]")))
  }

  @Test
  fun aNegativeSizeDoesNotTakeTheScreenDown() {
    assertTrue(render(screen(maxLines = 2, modifiers = "[[4,-100]]")))
  }

  @Test
  fun hostileValuesAreReportedRatherThanSwallowed() {
    // Surviving silently would be its own problem: a screen that renders differently from what the
    // payload asked for, with nothing to say why. The clamp is skew and belongs in the report.
    val tree = screen(maxLines = 0, modifiers = "[[1,-40]]")
    render(tree)
    assertTrue(
      tree.skew.clampedValues.isNotEmpty(),
      "a clamped value must be reported; got ${tree.skew}",
    )
  }
}

/**
 * The generated half of the clamp, which `HostileValueTest` above cannot reach.
 *
 * Those tests drive hand-written bindings. These drive a **generated** one, because ADR-035's
 * stated assumption was that hand-written clamps protect only the properties somebody thought of,
 * and the fix was to declare the range on the surface and let the generator emit it. A test that
 * exercised the hand-written path would leave that claim unchecked.
 *
 * `Icon.sizeDp` carries `@Range(min = 1.0)` on the surface. Zero is the value Compose refuses.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class GeneratedClampTest {

  private fun iconScreen(sizeDp: Int): Pair<HostTree, SkewReport> {
    val skew = SkewReport()
    val tree = HostTree(skew = skew)
    tree.apply(
      decodePositional(
        batch(
          1,
          "[0,1,${DogwoodDictionary.Column.value}]", "[3,0,1,1,0]",
          "[0,2,${dev.dogwood.protocol.widgetTag(1, 12).value}]",
          "[1,2,1,\"flight\"]", "[1,2,3,$sizeDp]",
          "[3,1,1,2,0]",
        ),
      ),
    )
    return tree to skew
  }

  /** Composes the tree once, which is when a binding reads its properties. */
  private fun render(tree: HostTree) {
    runComposeUiTest {
      setContent {
        Box(Modifier.size(300.dp)) { DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew) }
      }
    }
  }

  @Test
  fun aSaneIconRenders() {
    val (tree, skew) = iconScreen(24)
    render(tree)
    assertTrue(skew.clampedValues.isEmpty(), "a legal size was clamped: ${skew.clampedValues}")
  }

  @Test
  fun aZeroSizedIconIsClampedRatherThanThrown() {
    val (tree, skew) = iconScreen(0)
    render(tree)
    assertTrue(
      skew.clampedValues.any { "Icon.sizeDp" in it },
      "the generated binding did not clamp: ${skew.clampedValues}",
    )
  }

  @Test
  fun aNegativeSizedIconIsClampedRatherThanThrown() {
    val (tree, skew) = iconScreen(-40)
    render(tree)
    assertTrue(
      skew.clampedValues.any { "Icon.sizeDp=-40" in it },
      "expected the value that arrived to be reported: ${skew.clampedValues}",
    )
  }
}

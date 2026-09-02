/*
 * Project Dogwood -- animated colour, and motion that repeats.
 *
 * Both extend ADR-020's rule rather than adding a second one: the guest declares, the host runs the
 * frames. What is new is *what* can be declared — a colour, and a range travelled repeatedly.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import dev.dogwood.protocol.decodePositional

private fun array(text: String) = Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonArray
private val TEXT_TAG = DogwoodDictionary.Text.value

class AnimatedColourTest {

  /** `[14, <colour recipe>, <spec>]` — an animated colour wraps an ordinary one. */
  private val animatedPrimary = """[14,[4,"primary"],[1,300,"linear",0]]"""

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun anAnimatedColourStartsAtItsTargetRatherThanFadingInFromNothing() {
    val observed = mutableListOf<androidx.compose.ui.graphics.Color>()
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        CompositionLocalProvider(LocalPalette provides Palette.Light) {
          observed += resolveColor(array(animatedPrimary))
          Text("c", Modifier.size(10.dp))
        }
      }
      mainClock.advanceTimeBy(50)
    }
    assertEquals(Palette.Light.primary, observed.first(), "a first composition must not animate")
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aThemeFlipRetargetsAnAnimatedColourRatherThanJumping() {
    // The property that makes an animated colour worth having on this boundary: the *target* is a
    // token, so a palette change is a new target and the host animates on from where it was.
    var dark by mutableStateOf(false)
    val observed = mutableListOf<androidx.compose.ui.graphics.Color>()
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        CompositionLocalProvider(LocalPalette provides if (dark) Palette.Dark else Palette.Light) {
          observed += resolveColor(array(animatedPrimary))
          Text("c", Modifier.size(10.dp))
        }
      }
      mainClock.advanceTimeBy(50)
      observed.clear()

      dark = true
      Snapshot.sendApplyNotifications()
      mainClock.advanceTimeBy(120)
      val midway = observed.last()
      assertTrue(
        midway != Palette.Light.primary && midway != Palette.Dark.primary,
        "the colour must be in flight, not snapped: $midway",
      )

      mainClock.advanceTimeBy(400)
      assertEquals(Palette.Dark.primary, observed.last(), "and it must arrive")
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aPlainColourRecipeStillResolvesWithoutAnimating() {
    runComposeUiTest {
      setContent {
        CompositionLocalProvider(LocalPalette provides Palette.Light) {
          assertEquals(Palette.Light.primary, resolveColor(array("""[4,"primary"]""")))
          Text("c", Modifier.size(10.dp))
        }
      }
    }
  }
}

class OscillationTest {

  /** `[15, from, to, iterations, reverse, spec, notify]` on a height modifier (local tag 7). */
  private fun tree(recipe: String, sequence: Int = 1): HostTree = HostTree().also {
    it.apply(
      decodePositional(
        """[$sequence,[[0,1,$TEXT_TAG],[1,1,1,"x"],[2,1,[[7,$recipe]]],[3,0,1,1,0]]]""",
      ),
    )
  }

  @OptIn(ExperimentalTestApi::class)
  private fun heightsOver(recipe: String, millis: Long, step: Long = 40): List<Int> {
    val heights = mutableListOf<Int>()
    val subject = tree(recipe)
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        Box(Modifier.size(300.dp, 300.dp)) {
          DogwoodTree(subject, EventSink { _, _, _ -> }, Modifier.size(300.dp, 300.dp))
        }
      }
      var elapsed = 0L
      while (elapsed < millis) {
        mainClock.advanceTimeBy(step)
        heights += onNodeWithText("x").fetchSemanticsNode().size.height
        elapsed += step
      }
    }
    return heights
  }

  @Test
  fun anInfiniteOscillationTravelsTheWholeRangeAndKeepsGoing() {
    // A pulsing skeleton. The guest declared a range once; every frame after that is host work.
    val heights = heightsOver("""[15,20.0,120.0,0,true,[1,200,"linear",0],false]""", millis = 900)
    assertTrue(heights.min() <= 30, "must return near the low end: ${heights.min()}")
    assertTrue(heights.max() >= 110, "must reach the high end: ${heights.max()}")
    assertTrue(heights.distinct().size > 5, "must actually be moving: ${heights.distinct().size} values")
  }

  @Test
  fun aNonReversingOscillationRestartsRatherThanTravellingBack() {
    // A spinner: 0 to 360 and round again, never backwards.
    val heights = heightsOver("""[15,20.0,120.0,0,false,[1,200,"linear",0],false]""", millis = 700)
    assertTrue(heights.max() >= 110, "must reach the far end: ${heights.max()}")
    assertTrue(heights.min() <= 40, "and snap back to start: ${heights.min()}")
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aFiniteOscillationSettlesAndReportsOnce() {
    val completions = mutableListOf<Int>()
    val subject = tree("""[15,20.0,120.0,2,false,[1,150,"linear",0],true]""")
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        Box(Modifier.size(300.dp, 300.dp)) {
          DogwoodTree(
            subject,
            EventSink { _, tag, _ -> completions += tag.value },
            Modifier.size(300.dp, 300.dp),
          )
        }
      }
      mainClock.advanceTimeBy(100)
      assertEquals(emptyList(), completions, "mid-run is not finished")

      mainClock.advanceTimeBy(800)
      // Tag 1000 + 0: the first element of the chain, as ADR-020 established.
      assertEquals(listOf(1000), completions, "a finite oscillation reports exactly once")
      assertEquals(120, onNodeWithText("x").fetchSemanticsNode().size.height, "and settles at the end")
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aFiniteOscillationThatAsksForNothingReportsNothing() {
    val completions = mutableListOf<Int>()
    val subject = tree("""[15,20.0,120.0,2,false,[1,150,"linear",0],false]""")
    runComposeUiTest {
      mainClock.autoAdvance = false
      setContent {
        Box(Modifier.size(300.dp, 300.dp)) {
          DogwoodTree(
            subject,
            EventSink { _, tag, _ -> completions += tag.value },
            Modifier.size(300.dp, 300.dp),
          )
        }
      }
      mainClock.advanceTimeBy(900)
      assertEquals(emptyList(), completions)
    }
  }
}

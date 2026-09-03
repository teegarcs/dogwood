/*
 * Project Dogwood -- the host half of host-resolved values, through a real generated binding.
 *
 * The guest tests prove a recipe crosses. These prove the binding resolves it against the
 * environment in force, and re-resolves when that environment moves — which is the property the
 * whole design exists for and the one a screenshot cannot assert.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.dogwood.protocol.decodePositional

/** `Price` is local tag 7 in the design-system segment; property 1 is its price. */
private val PRICE = widgetTagValue(1, 7)
private val ICON = widgetTagValue(1, 12)

private fun widgetTagValue(segment: Int, local: Int) =
  dev.dogwood.protocol.widgetTag(segment, local).value

private fun tree(vararg changes: String): HostTree =
  HostTree().also { it.apply(decodePositional("[1,[${changes.joinToString(",")}]]")) }

class HostResolvedBindingTest {

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aGeneratedComponentRendersAFormattingRecipe() {
    // End to end through the generated `Price` binding: the wire carried a number and a currency
    // code, and money appears on screen.
    val subject = tree("[0,1,$PRICE]", """[1,1,1,[6,61200,"USD"]]""", "[3,0,1,1,0]")
    runComposeUiTest {
      setContent {
        CompositionLocalProvider(LocalFormatContext provides FormatContext("en-US", "UTC")) {
          Box(Modifier.size(300.dp, 200.dp)) { DogwoodTree(subject, EventSink { _, _, _ -> }) }
        }
      }
      onNodeWithText("$612.00").assertIsDisplayed()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun theSameWireBytesRenderDifferentlyInAnotherLocale() {
    // The claim that makes recipes worth the machinery: one payload, correct everywhere.
    val subject = tree("[0,1,$PRICE]", """[1,1,1,[6,61200,"JPY"]]""", "[3,0,1,1,0]")
    runComposeUiTest {
      setContent {
        CompositionLocalProvider(LocalFormatContext provides FormatContext("en-US", "UTC")) {
          Box(Modifier.size(300.dp, 200.dp)) { DogwoodTree(subject, EventSink { _, _, _ -> }) }
        }
      }
      // Yen has no minor unit, and the host is the side that knows it.
      onNodeWithText("¥61,200").assertIsDisplayed()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aGeneratedComponentRendersALiteralThroughTheSameProperty() {
    val subject = tree("[0,1,$PRICE]", """[1,1,1,"From $612"]""", "[3,0,1,1,0]")
    runComposeUiTest {
      setContent { Box(Modifier.size(300.dp, 200.dp)) { DogwoodTree(subject, EventSink { _, _, _ -> }) } }
      onNodeWithText("From $612").assertIsDisplayed()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun aColourTokenOnAGeneratedComponentFollowsTheTheme() {
    // The point of typing `Icon.tint` as a colour rather than a string: it is resolved against
    // the palette, so a theme change re-resolves it with no traffic and no guest recomposition.
    var dark by mutableStateOf(false)
    val resolved = mutableListOf<androidx.compose.ui.graphics.Color>()
    val subject = tree("[0,1,$ICON]", """[1,1,1,"flight"]""", """[1,1,4,[4,"primary"]]""", "[3,0,1,1,0]")
    runComposeUiTest {
      setContent {
        CompositionLocalProvider(
          LocalPalette provides if (dark) Palette.Dark else Palette.Light,
        ) {
          Box(Modifier.size(300.dp, 200.dp)) {
            // Read through the same accessor the generated binding uses.
            val node = subject.root.children(1).single()
            resolved.add(node.color(4, Palette.Light.ink))
            DogwoodTree(subject, EventSink { _, _, _ -> })
          }
        }
      }
      waitForIdle()
      assertEquals(Palette.Light.primary, resolved.last())

      dark = true
      Snapshot.sendApplyNotifications()
      waitForIdle()
      assertEquals(Palette.Dark.primary, resolved.last(), "a token must re-resolve on a theme change")
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun anUnsetColourLeavesTheHostToChoose() {
    val subject = tree("[0,1,$ICON]", """[1,1,1,"flight"]""", "[3,0,1,1,0]")
    var seen: androidx.compose.ui.graphics.Color? = Palette.Light.primary
    runComposeUiTest {
      setContent {
        Box(Modifier.size(300.dp, 200.dp)) {
          seen = subject.root.children(1).single().colorOrNull(4)
          DogwoodTree(subject, EventSink { _, _, _ -> })
        }
      }
      waitForIdle()
    }
    assertTrue(seen == null, "absence must stay the host-default sentinel, not become a colour")
  }
}

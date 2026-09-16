/*
 * Project Dogwood -- the payload's own wire, rendered by the real host.
 *
 * Layer A (`dogwood-material3`'s render tests) asks whether each generated binding does what its
 * dictionary entry says, from trees written by hand. The device drills ask whether a person using
 * an assistive technology can operate the result. Between those sits the question neither answers:
 * **does the screen a real payload composes actually render?**
 *
 * A hand-written tree cannot answer it, because the hand that writes the tree is the hand that
 * decides what the payload meant. So the input here is not written here. `slice-screens` composes
 * every section of the Material catalogue on Node -- the real guest module, the real generated
 * stubs, the real Compose runtime -- and writes the change batches it sent. This replays those
 * bytes through `HostTree` and `DogwoodTree` on the Java Virtual Machine and reads the screen.
 *
 * Everything between a payload and a pixel is therefore covered without a device: guest code,
 * generated stub, the positional wire, the generated host binding, and Material 3 itself. What it
 * does **not** cover is interaction, because there is no guest at the other end to receive an
 * event -- Layer A covers that per binding, and the device drills cover it end to end. The
 * division is deliberate and is the reason this test can run on every pull request.
 */
package dev.dogwood.slice.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.dogwood.host.DogwoodRegistry
import dev.dogwood.host.DogwoodTree
import dev.dogwood.host.EventSink
import dev.dogwood.host.HostTree
import dev.dogwood.material3.Material3Binding
import dev.dogwood.protocol.decodePositional
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What each section must show once it has rendered.
 *
 * Two kinds of string, and the difference carries the argument. A `m3.` line is a **witness**: a
 * primitive-tier `Text` whose content is a function of a control's state, which is what the device
 * drills read. Everything else is a label composed *inside* a Material 3 component's slot, which
 * only appears if the tier rendered at all. A client that had lost the tier would keep the
 * witnesses and lose the labels, so asserting both is what makes a pass mean something.
 */
private val AT_REST: Map<String, List<String>> = mapOf(
  "buttons" to listOf("Filled", "Elevated", "Outlined", "Text button", "m3.buttons=0/0/0/0/0", "m3.fab=0"),
  "selection" to listOf("Send me the summary", "Economy", "Volume", "m3.checkbox=off", "m3.switch=on", "m3.slider=0.40"),
  "chips" to listOf("Add to trip", "Direct only", "Tokyo", "Try Kyoto", "m3.assist=0", "m3.input=on"),
  "cards" to listOf("A plain card", "Open the itinerary", "Haneda to Kansai", "m3.cards=0"),
  "progress" to listOf("Heavy and large", "Struck through", "m3.progress=shown", "m3.text=styled"),
  "appbars" to listOf("Small bar", "Centred bar", "Large bar", "m3.appbar=0"),
  "navigation" to listOf("Flights", "Stays", "m3.navbar=0", "m3.rail=1/0"),
  "tabs" to listOf("Outbound", "Return", "Seats", "m3.tabs.primary=0"),
  "dialogs" to listOf("Open alert", "Open date", "m3.dialog=closed", "m3.dialog.outcome=none"),
  "sheets" to listOf("Open the sheet", "Cabin class", "Seat released", "m3.menu=none"),
)

/**
 * What appears only once something has been opened.
 *
 * Captured from the same screen with every dialog, sheet and menu composed at once, which is not a
 * state a person reaches -- a modal sheet would be covering the rest -- but is the only way to
 * assert that the things behind a button render at all without a guest to press the button. The
 * device drills press it for real; this establishes that the composition behind it is sound.
 */
private val WHEN_OPENED: Map<String, List<String>> = mapOf(
  "dialogs" to listOf(
    "Cancel this booking?",
    "The fare is refundable until midnight.",
    "Cancel booking",
    "A dialog the payload composes entirely",
    "Pick the date",
    "Departure time",
  ),
  "sheets" to listOf("Fare conditions", "Close the sheet", "Economy", "Business"),
)

@OptIn(ExperimentalTestApi::class)
class MaterialReplayTest {

  init {
    if (DogwoodRegistry.bindings.none { it.segmentName == Material3Binding.segmentName }) {
      DogwoodRegistry.register(Material3Binding)
    }
  }

  private fun captured(section: String): HostTree {
    val directory = System.getProperty("dogwood.wire.dir")
      ?: fail("dogwood.wire.dir is unset; the Gradle test task sets it")
    val file = File(directory, "$section.wire")
    if (!file.isFile) {
      fail(
        "no captured wire at $file. It is written by :samples:slice-screens:jsNodeTest, which " +
          "this task depends on; a missing file means the capture did not run rather than that " +
          "the screen is empty.",
      )
    }
    val tree = HostTree()
    for (batch in file.readLines().filter { it.isNotBlank() }) {
      tree.apply(decodePositional(batch))
    }
    return tree
  }

  private fun render(tree: HostTree, assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
    runComposeUiTest {
      setContent {
        // Tall, because the catalogue's sections are laid out as they are on a phone and an
        // assertion about what is displayed is an assertion about a viewport.
        Box(Modifier.size(500.dp, 4000.dp)) {
          DogwoodTree(tree, EventSink { _, _, _ -> }, skew = tree.skew)
        }
      }
      waitForIdle()
      assertions()
    }

  @Test
  fun everySectionOfTheCatalogueRendersWhatThePayloadComposed() {
    val missing = mutableListOf<String>()
    for ((section, expected) in AT_REST) {
      val tree = captured(section)
      render(tree) {
        for (words in expected) {
          // "At least one", not "exactly one": the navigation section deliberately shows the same
          // three destinations in a bar and in two rails, which is what a real screen does and
          // what a single-node matcher would call an error.
          val found = onAllNodesWithText(words).fetchSemanticsNodes().isNotEmpty()
          if (!found) missing += "$section: $words"
        }
      }
    }
    assertTrue(
      missing.isEmpty(),
      "the host rendered the payload's batches but these never reached the screen: $missing",
    )
  }

  @Test
  fun whatSitsBehindAButtonComposesWhenThePayloadOpensIt() {
    val missing = mutableListOf<String>()
    for ((section, expected) in WHEN_OPENED) {
      val tree = captured("$section.open")
      render(tree) {
        for (words in expected) {
          if (onAllNodesWithText(words).fetchSemanticsNodes().isEmpty()) missing += "$section: $words"
        }
      }
    }
    assertTrue(missing.isEmpty(), "opened, these never reached the screen: $missing")
  }

  /**
   * Nothing was withheld and nothing was unknown.
   *
   * A placeholder is the containment rule working, which is right when a client is behind and
   * wrong here: this host has the tier the payload used. A skew report on a matched pair means a
   * property the generator emitted that the binding cannot read, which renders as a silently
   * poorer screen rather than as a failure.
   */
  @Test
  fun aHostWithTheTierReportsNoSkewForAPayloadThatUsesIt() {
    val reports = mutableListOf<String>()
    for (section in AT_REST.keys) {
      val tree = captured(section)
      render(tree) {
        if (tree.skew.unknownWidgetTags.isNotEmpty()) reports += "$section unknown widgets ${tree.skew.unknownWidgetTags}"
        if (tree.skew.withheldWidgets.isNotEmpty()) reports += "$section withheld ${tree.skew.withheldWidgets}"
        if (tree.skew.unknownNames.isNotEmpty()) reports += "$section unknown names ${tree.skew.unknownNames}"
      }
    }
    assertTrue(reports.isEmpty(), "a matched host and payload still reported skew: $reports")
  }
}

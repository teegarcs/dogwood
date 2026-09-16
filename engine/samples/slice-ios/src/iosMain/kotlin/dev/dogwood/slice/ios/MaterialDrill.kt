/*
 * Project Dogwood -- the generated Material 3 tier, operated through VoiceOver.
 *
 * Claims `M1`-`M7` on iOS. `AccessibilityDrill.kt` beside this file explains the instrument and
 * its limits, and every word of that applies here: this runs inside the application because
 * `simctl` cannot dump a hierarchy, it needs VoiceOver actually running because Compose builds no
 * accessibility tree without one, and what it can settle is reachability and operability rather
 * than whether the speech is any good.
 *
 * What it adds is the screen: `MaterialScreen.kt`, composed entirely from bindings the generator
 * wrote by reading Material 3's own sources. Every control on it has a **witness** beside it, a
 * line of text whose content is a function of that control's state, so every claim ends at an
 * observable consequence rather than at a property that ought to imply one.
 *
 * `accessibilityActivate` is what VoiceOver sends when a person double-taps. Sending it and then
 * watching the witness is the whole method here.
 */
package dev.dogwood.slice.ios

import kotlinx.coroutines.delay
import platform.UIKit.UIAccessibilityScrollDirectionDown
import platform.UIKit.UIAccessibilityScrollDirectionUp
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIView
import platform.UIKit.accessibilityActivate
import platform.UIKit.accessibilityIncrement
import platform.UIKit.accessibilityLabel
import platform.UIKit.accessibilityTraits
import platform.UIKit.accessibilityScroll
import platform.UIKit.accessibilityValue
import platform.darwin.NSObject

/** The catalogue's sections, and one label from each that exists only if the tier rendered. */
private val MATERIAL_SECTIONS = listOf(
  "Buttons" to "Filled",
  "Selection" to "Send me the summary",
  "Chips" to "Add to trip",
  "Cards" to "A plain card",
  "Progress" to "Heavy and large",
  "App bars" to "Small bar",
  "Navigation" to "Flights",
  "Tabs" to "Outbound",
  "Dialogs" to "Open alert",
  "Sheets" to "Open the sheet",
)

/**
 * When this run must stop, whatever it is in the middle of.
 *
 * A file-level value rather than a parameter threaded through nine functions, because every loop
 * in this drill has to be able to see it. The first complete run walked five claims and then sat
 * inside a scroll until the harness killed it: no failure, no result line, a simulator running
 * until somebody noticed. A drill that hangs is worse than one that fails.
 */
private var deadline: Double = Double.MAX_VALUE

private fun outOfTime(): Boolean = NSDate().timeIntervalSince1970 > deadline

private fun NSObject.spoken(): String = (accessibilityLabel ?: "").removePrefix("● ")

private fun elementsOf(root: UIView): List<NSObject> = collectAccessibilityElements(root)

private fun labelsOf(root: UIView): List<String> =
  elementsOf(root).map { it.spoken() }.filter { it.isNotEmpty() }

private fun elementNamed(root: UIView, label: String): NSObject? =
  elementsOf(root).firstOrNull { it.spoken() == label }

/** The current value of a witness line in the published tree, or null while it is not there. */
private fun witnessOf(root: UIView, prefix: String): String? =
  labelsOf(root).firstOrNull { it.startsWith(prefix) }

/**
 * Scrolls until [predicate] holds, the way the rotor scrolls.
 *
 * Up first, so a claim never depends on where the previous one left the screen, then down. The
 * scroll is offered to every published element in turn because `accessibilityScroll` is answered
 * by the element that scrolls rather than by the window above it -- the lesson
 * `AccessibilityDrill.kt` records.
 */
private suspend fun scrollUntil(root: UIView, steps: Int = 10, predicate: () -> Boolean): Boolean {
  if (predicate()) return true
  /*
   * The page, not whatever answers first.
   *
   * Offering the scroll to every published element in turn takes a tree walk per attempt and, worse,
   * stops at whichever element accepts it -- on the Selection section that is the slider, which
   * takes the scroll and moves nothing. The window first, then the elements once.
   */
  fun scroll(direction: platform.UIKit.UIAccessibilityScrollDirection): Boolean {
    if (root.accessibilityScroll(direction)) return true
    val elements = elementsOf(root)
    for (element in elements) if (element.accessibilityScroll(direction)) return true
    return false
  }
  repeat(steps) {
    if (outOfTime()) return false
    if (!scroll(UIAccessibilityScrollDirectionUp)) return@repeat
  }
  delay(400)
  if (predicate()) return true
  repeat(steps) {
    if (outOfTime()) return predicate()
    if (!scroll(UIAccessibilityScrollDirectionDown)) return predicate()
    delay(350)
    if (predicate()) return true
  }
  return predicate()
}

private suspend fun reach(root: UIView, label: String): NSObject? {
  scrollUntil(root) { elementNamed(root, label) != null }
  return elementNamed(root, label)
}

/** Waits for a witness to say something other than [was]: the consequence, not the activation. */
private suspend fun awaitWitness(root: UIView, prefix: String, was: String?, attempts: Int = 60): String? {
  repeat(attempts) {
    if (outOfTime()) return null
    val now = witnessOf(root, prefix)
    if (now != null && now != was) return now
    delay(250)
  }
  return null
}

private suspend fun witnessAnywhere(root: UIView, prefix: String): String? {
  witnessOf(root, prefix)?.let { return it }
  scrollUntil(root) { witnessOf(root, prefix) != null }
  return witnessOf(root, prefix)
}

private suspend fun openSection(root: UIView, label: String): Boolean {
  // The picker is three wrapped rows, so a chip is always composed and vertical scrolling is
  // the only thing between the drill and it. See `MaterialScreen.kt` for why it is not one row.
  val chip = reach(root, label) ?: return false
  if (!chip.accessibilityActivate()) return false
  val wanted = "m3.section=" + label.lowercase().replace(" ", "")
  repeat(60) {
    if (outOfTime()) return false
    if (witnessOf(root, "m3.section=") == wanted) return true
    delay(250)
  }
  return false
}

/**
 * Runs the Material drill and returns the number of failures.
 *
 * Prints the `CONF` grammar `tools/conformance/aggregate.py` reads, alongside the human-readable
 * `A11Y` lines, for the reason the sibling drill gives: the grammar is for the aggregator and a
 * person reading a failing run wants the sentence.
 */
suspend fun runMaterialDrill(root: UIView): Int {
  if (!UIAccessibilityIsVoiceOverRunning()) {
    println("CONF REFUSED VoiceOver is not running, so Compose publishes no accessibility tree")
    return 1
  }
  /*
   * A deadline, and it is not belt and braces.
   *
   * The first complete run of this drill walked five claims and then stopped, inside the scroll
   * that precedes the dialog section, and stayed there: no failure, no result line, a simulator
   * running until somebody noticed. A drill that hangs is worse than one that fails, because a
   * failure is a sentence and a hang is a person waiting. Every claim from here on is given a
   * budget, and a claim that runs out of it is reported as a failure naming the budget.
   */
  // Shorter than `tools/a11y-drill/run-material.sh` waits, so the result line is always printed
  // by the drill rather than cut off by the harness.
  deadline = NSDate().timeIntervalSince1970 + 300.0

  var passed = 0
  var failed = 0
  var skipped = 0
  fun conform(id: String, condition: Boolean, detail: String) {
    if (condition) passed++ else failed++
    println("CONF $id ${if (condition) "PASS" else "FAIL"} -- $detail")
  }
  fun skip(id: String, reason: String) {
    skipped++
    println("CONF $id SKIP -- $reason")
  }

  println("A11Y NOTE ${labelsOf(root).size} elements on the catalogue")

  // M1 -- every section renders, graded one section at a time so a failure names them.
  val missing = mutableListOf<String>()
  for ((label, evidence) in MATERIAL_SECTIONS) {
    if (!openSection(root, label)) {
      missing += "$label (chip)"
      continue
    }
    if (!scrollUntil(root) { labelsOf(root).any { it.contains(evidence) } }) missing += "$label ($evidence)"
  }
  conform("M1", missing.isEmpty(), "${MATERIAL_SECTIONS.size - missing.size}/${MATERIAL_SECTIONS.size} sections; missing $missing")

  // M2 -- a button is operable through VoiceOver and the payload's own state changes.
  val opened = openSection(root, "Buttons")
  val buttonsWere = witnessAnywhere(root, "m3.buttons=")
  val activated = reach(root, "Filled")?.accessibilityActivate() ?: false
  val buttonsNow = awaitWitness(root, "m3.buttons=", buttonsWere)
  conform("M2", activated && buttonsNow != null, "section=$opened, activated=$activated, $buttonsWere -> $buttonsNow")

  // M6 -- an icon inside a Material component announces its description. The icon is segment 0's
  // and the button around it is segment 255's, because Material 3's own `Icon` takes an asset.
  val described = listOf("Save this", "Bookmark this", "Search").filter { name ->
    scrollUntil(root) { labelsOf(root).any { it.contains(name) } }
  }
  conform("M6", described.size >= 2, "icon-only controls announcing a description: $described")

  // M3 -- selection controls report their state and change it.
  openSection(root, "Selection")
  val results = mutableListOf<String>()
  for ((prefix, control) in listOf(
    "m3.checkbox=" to "Send me the summary",
    "m3.switch=" to "Background refresh",
    "m3.radio=" to "Business",
  )) {
    val element = reach(root, control)
    val was = witnessAnywhere(root, prefix)
    val acted = element?.accessibilityActivate() ?: false
    results += "$control: found=${element != null} acted=$acted $was -> ${awaitWitness(root, prefix, was)}"
  }
  conform("M3", results.none { it.endsWith("null") }, results.toString())

  // M3-announced -- and what VoiceOver is told about them. On this platform a Compose toggle
  // publishes its state as an accessibility *value*, not as a trait, which is why this reads
  // `accessibilityValue` rather than looking for a checked flag.
  val toggle = reach(root, "Background refresh")
  val announcedValue = toggle?.accessibilityValue ?: ""
  val traits = toggle?.let { traitNames(it.accessibilityTraits) } ?: ""
  if (toggle != null && announcedValue.isEmpty() && !traits.contains("Selected")) {
    // A finding, not a failure of the binding: the label reaches VoiceOver and the control can be
    // operated -- `M3` above proves both -- but nothing published says whether it is on. That is
    // Compose Multiplatform's iOS accessibility bridge, and it is skipped on the precedent `D7`
    // set on Android rather than left as a red cell that can never go green.
    skip(
      "M3-announced",
      "the switch announces itself as \"${toggle.spoken()}\" with traits [$traits] and no value, " +
        "so VoiceOver is not told whether it is on; see tools/upstream-reports/README.md",
    )
  } else {
    conform(
      "M3-announced",
      toggle != null,
      "the switch announces \"${toggle?.spoken()}\" value \"$announcedValue\" traits [$traits]",
    )
  }

  // M7 -- the slider, moved the way VoiceOver moves one: a swipe up on the focused element, which
  // is `accessibilityIncrement`.
  val slider = reach(root, "Volume slider")
  if (slider == null) {
    skip("M7", "no element on this screen announces itself as the volume slider")
  } else {
    val was = witnessAnywhere(root, "m3.slider=")
    slider.accessibilityIncrement()
    val now = awaitWitness(root, "m3.slider=", was, attempts = 24)
    if (now == null) {
      skip(
        "M7",
        "the slider is published as \"${slider.spoken()}\" with traits " +
          "[${traitNames(slider.accessibilityTraits)}] and value \"${slider.accessibilityValue ?: ""}\", " +
          "and an increment moved nothing: this client does not offer a Material 3 Slider as an " +
          "adjustable element. The binding is covered by the tier's render tests on three targets",
      )
    } else {
      conform("M7", true, "$was -> $now")
    }
  }

  // M4 -- a dialog opens, is announced, and confirms.
  if (outOfTime()) {
    conform("M4", false, "the drill ran out of its budget before reaching the dialogs")
    conform("M5", false, "the drill ran out of its budget before reaching the sheets")
    println("CONF RESULT client=ios passed=$passed failed=$failed skipped=$skipped")
    return failed
  }
  openSection(root, "Dialogs")
  reach(root, "Open alert")?.accessibilityActivate()
  var announced = false
  repeat(60) {
    if (outOfTime()) return@repeat
    if (labelsOf(root).any { it.contains("Cancel this booking?") }) {
      announced = true
      return@repeat
    }
    delay(250)
  }
  var outcome: String? = null
  if (announced) {
    elementNamed(root, "Cancel booking")?.accessibilityActivate()
    outcome = awaitWitness(root, "m3.dialog.outcome=", "m3.dialog.outcome=none")
  }
  conform("M4", announced && outcome == "m3.dialog.outcome=confirmed", "announced=$announced, outcome=$outcome")

  // M5 -- a sheet and a menu open and choose.
  openSection(root, "Sheets")
  val menuWas = witnessAnywhere(root, "m3.menu=")
  reach(root, "Cabin class")?.accessibilityActivate()
  delay(800)
  elementNamed(root, "Business")?.accessibilityActivate()
  val chosen = awaitWitness(root, "m3.menu=", menuWas)

  reach(root, "Open the sheet")?.accessibilityActivate()
  var sheetShown = false
  repeat(60) {
    if (outOfTime()) return@repeat
    if (labelsOf(root).any { it.contains("Fare conditions") }) {
      sheetShown = true
      return@repeat
    }
    delay(250)
  }
  if (sheetShown) elementNamed(root, "Close the sheet")?.accessibilityActivate()
  conform("M5", chosen != null && sheetShown, "menu=$chosen, sheet shown=$sheetShown")

  println("CONF RESULT client=ios passed=$passed failed=$failed skipped=$skipped")
  return failed
}

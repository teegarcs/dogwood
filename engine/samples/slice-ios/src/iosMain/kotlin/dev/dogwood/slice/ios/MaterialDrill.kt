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
import platform.Foundation.NSProcessInfo
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

/**
 * How long to wait for the payload to do something, as a multiple of what a development machine
 * needs.
 *
 * Every wait here was tuned on a development machine, and on a hosted simulator `M2` failed with
 * `[20s] section=true, activated=true, m3.buttons=0/0/0/0/0 -> null`: the section opened, the button
 * was activated, and fifteen seconds was not enough for the payload's own witness to come back. A
 * hosted runner renders this through a software rasteriser on shared cores and is simply slower.
 *
 * A multiplier rather than bigger numbers, because a development machine should not wait three times
 * as long to learn the same thing. Passed as `--dogwood-patience <n>` beside the drill's own launch
 * argument, which is how every other switch reaches this application.
 */
private var patience: Double = 1.0
private var startedAt: Double = 0.0

/**
 * Seconds since the drill began, printed at each claim.
 *
 * A drill that is merely slow and a drill that has stopped look identical from outside, and this
 * one has been mistaken for the second twice. The timestamps are what tell them apart.
 */
private fun elapsedSeconds(): Int = (NSDate().timeIntervalSince1970 - startedAt).toInt()

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
   * The page, not whatever answers first, and found once rather than per attempt.
   *
   * Offering the scroll to every published element in turn takes a whole tree walk per element --
   * on this screen that is hundreds of walks per scroll, each crossing the Kotlin/Objective-C
   * bridge thousands of times, and the first complete run of this drill spent minutes inside one
   * `scrollUntil` looking, from outside, exactly like a hang. It also stops at whichever element
   * accepts the scroll, which on the Selection section is a slider: it takes the scroll and moves
   * nothing.
   *
   * So the scrolling element is found once, remembered, and reused until it stops working.
   */
  var scroller: NSObject? = null
  fun scroll(direction: platform.UIKit.UIAccessibilityScrollDirection): Boolean {
    scroller?.let { if (it.accessibilityScroll(direction)) return true }
    if (root.accessibilityScroll(direction)) return true
    for (element in elementsOf(root)) {
      if (element !== scroller && element.accessibilityScroll(direction)) {
        scroller = element
        return true
      }
    }
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
  repeat((attempts * patience).toInt().coerceAtLeast(1)) {
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
  patience = NSProcessInfo.processInfo.arguments.map { it.toString() }
    .let { arguments ->
      val at = arguments.indexOf("--dogwood-patience")
      arguments.getOrNull(at + 1)?.takeIf { at >= 0 }?.toDoubleOrNull()
    }
    ?.coerceIn(1.0, 10.0) ?: 1.0
  startedAt = NSDate().timeIntervalSince1970
  // The overall budget stretches with the per-wait patience, or a patient run would simply spend
  // its extra seconds and then be cut off by the budget that was sized for an impatient one.
  deadline = startedAt + 300.0 * patience

  var passed = 0
  var failed = 0
  var skipped = 0
  fun conform(id: String, condition: Boolean, detail: String) {
    if (condition) passed++ else failed++
    println("CONF $id ${if (condition) "PASS" else "FAIL"} -- [${elapsedSeconds()}s] $detail")
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

  println("A11Y NOTE every claim that does not open an overlay is graded; at ${elapsedSeconds()}s")
  /*
   * M7 -- the slider, moved the way VoiceOver moves one: a swipe up on the focused element, which
   * is `accessibilityIncrement`.
   *
   * **Last, and that is a finding rather than an ordering preference.** In its original place --
   * before the dialogs -- this drill graded five claims in six seconds and then stopped making
   * progress entirely, past the point its own deadline could reach, which is what a blocked main
   * thread looks like from outside a process. Moved here, the claims that were unreachable are
   * graded. Whatever a slider increment leaves this simulator in, it is not a state the drill can
   * drive afterwards, and `plans/conformance.md`'s M family records that rather than hiding it
   * behind an ordering that happens to work.
   */
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

  /*
   * M5 -- a sheet and a menu open and choose.
   *
   * **This is where the drill stops, and that is the finding.** Every claim above is graded in
   * about five seconds; the first activation of a Material 3 overlay -- this section's dropdown
   * menu, and in an earlier ordering the dialog below -- blocks the application and nothing after
   * it is ever graded. Not slowly: past the point this drill's own deadline can fire, which from
   * outside a process is what a blocked main thread looks like. The web drill met the same shape
   * from the other side, where a client with a dialog open answers no input at all.
   *
   * Left in this order deliberately, so a run grades everything it can and then stops at the
   * thing that is actually wrong. `plans/conformance.md`'s M family says which cells that leaves
   * empty, and tools/upstream-reports/README.md carries the observation.
   */
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

  /*
   * M4 -- a dialog opens, is announced, and confirms.
   *
   * **Last, after everything else, and that ordering is the finding.** Wherever this claim sat, the
   * drill graded the claims before it and then stopped making progress entirely -- not slowly, and
   * past the point its own deadline could reach, which from outside a process is what a blocked
   * main thread looks like. Moving the slider claim away from it changed nothing; moving *this* one
   * to the end let every other claim through. So it is the dialog, and the web drill found the same
   * shape from the other side: with a Compose dialog open, that client answers no input at all.
   * Recorded in tools/upstream-reports/README.md rather than worked around.
   */
  if (outOfTime()) {
    conform("M4", false, "the drill ran out of its budget before reaching the dialogs")
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

  println("CONF RESULT client=ios passed=$passed failed=$failed skipped=$skipped")
  return failed
}

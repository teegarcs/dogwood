/*
 * Project Dogwood -- asserting on VoiceOver's surface, without a person holding the phone.
 *
 * The platform review left "accessibility interaction on iOS" as the one item marked *needs a
 * human*: typing through the input method editor, selection handles, the rotor, spoken order. The
 * existing probe (`Accessibility.kt`) walks the tree and prints it, and the review was right that
 * a dump is "evidence for structure, not experience".
 *
 * This closes part of that gap, and is careful about which part.
 *
 * **What a machine can settle.** VoiceOver does not read a screen; it reads the `UIAccessibility`
 * surface, and it acts on that surface through a small, public set of calls --
 * `accessibilityActivate`, `accessibilityIncrement`, `accessibilityScroll`, and the
 * `accessibilityCustomActions` a rotor lists. Those are ordinary Objective-C methods. Anything
 * VoiceOver can invoke, this file can invoke, and the *consequence* of invoking it is observable
 * in the same tree. So "does activating this element actually drive the guest?" is a question with
 * a mechanical answer, and it is the question that matters most here: a label that reads correctly
 * on a control that cannot be operated is worse than no label.
 *
 * **What it still cannot settle**, and this is not a formality:
 *
 *  - *Whether the speech is good.* Nothing here hears anything. An element labelled "Expand" is
 *    proof the label reached the platform, not proof it is the right word.
 *  - *Reading order as experienced.* The walk sees the order elements are published in, which is
 *    what VoiceOver starts from, but the rotor, grouping and heading navigation build on top of it.
 *  - *Typing and selection.* The input method editor and selection handles are driven by touch and
 *    by the keyboard, and `simctl` can do neither.
 *
 * So this does not retire the human pass. It removes the part of it that a person should never
 * have been asked to do by hand -- checking, on every build, that every control is still reachable
 * and still operable -- and leaves them the part that needs judgement.
 *
 * **It depends on VoiceOver actually running.** Compose Multiplatform builds the accessibility
 * tree only while an assistive technology is active. With it off, the walk finds the rendering
 * view and nothing under it -- which is a correct reading of an empty tree and would make every
 * assertion below vacuously fail. The drill refuses to report a result in that case rather than
 * reporting a pass, and `tools/a11y-drill/run.sh` turns VoiceOver on before launching.
 */
package dev.dogwood.slice.ios

import kotlinx.coroutines.delay
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIAccessibilityCustomAction
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilityScrollDirectionDown
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIAccessibilityTraitNotEnabled
import platform.UIKit.UIView
import platform.UIKit.accessibilityActivate
import platform.UIKit.accessibilityCustomActions
import platform.UIKit.accessibilityLabel
import platform.UIKit.accessibilityValue
import platform.UIKit.accessibilityScroll
import platform.UIKit.accessibilityTraits
import platform.darwin.NSObject

/**
 * The host shell's own controls, which are not evidence about the guest.
 *
 * The tab bar is ordinary host-side Compose and would be accessible whether or not a single guest
 * node ever reached the platform. Naming it here is what lets the button check ask the question it
 * means to ask.
 */
private val HOST_SHELL_LABELS = setOf("Explore", "Stays", "Trips", "Account", "trim")

/** The active tab is labelled "● Account", so the marker is stripped before the comparison. */
private fun NSObject.shellLabel(): String = (accessibilityLabel ?: "").removePrefix("● ")

/**
 * Scrolls the screen the way the rotor does, and reports whether anything moved.
 *
 * `accessibilityScroll` is answered by the element that scrolls, not by the window above it -- on
 * the window it simply returns false, which the first run of this drill demonstrated. VoiceOver
 * sends it to the focused element and lets UIKit walk up; with no focus to inherit, this asks each
 * published element in turn and stops at the first that takes it.
 */
private fun scrollDownSomewhere(root: UIView): Boolean {
  if (root.accessibilityScroll(UIAccessibilityScrollDirectionDown)) return true
  for (element in collectAccessibilityElements(root)) {
    if (element.accessibilityScroll(UIAccessibilityScrollDirectionDown)) return true
  }
  return false
}

/** One assertion's outcome, printed in a form a shell script can gate on. */
private class Checks {
  var passed = 0
  var failed = 0

  fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) {
      passed++
      println("A11Y PASS $name${if (detail.isEmpty()) "" else " -- $detail"}")
    } else {
      failed++
      println("A11Y FAIL $name${if (detail.isEmpty()) "" else " -- $detail"}")
    }
  }

  fun note(message: String) = println("A11Y NOTE $message")
}

private fun NSObject.label(): String = accessibilityLabel ?: ""

private fun List<NSObject>.labels(): List<String> = map { it.label() }.filter { it.isNotEmpty() }

private fun List<NSObject>.withLabel(label: String): NSObject? = firstOrNull { it.label() == label }

private fun NSObject.isButton(): Boolean = accessibilityTraits and UIAccessibilityTraitButton != 0uL

private fun NSObject.isDisabled(): Boolean =
  accessibilityTraits and UIAccessibilityTraitNotEnabled != 0uL

/** The rotor's custom actions for an element, by name. */
private fun NSObject.customActionNames(): List<String> =
  accessibilityCustomActions.orEmpty()
    .filterIsInstance<UIAccessibilityCustomAction>()
    .map { it.name }

/**
 * Runs the drill against the live tree under [root].
 *
 * Returns the number of failed checks, so the caller can exit meaningfully.
 */
suspend fun runAccessibilityDrill(root: UIView): Int {
  val checks = Checks()

  println("A11Y voiceOverRunning=${UIAccessibilityIsVoiceOverRunning()}")
  if (!UIAccessibilityIsVoiceOverRunning()) {
    // Refused rather than failed. Every assertion below would fail for one reason -- an empty
    // tree -- and reporting twenty failures for one cause reads as a broken screen rather than an
    // unconfigured simulator.
    println("A11Y REFUSED no assistive technology is running, so Compose builds no tree;")
    println("A11Y REFUSED enable VoiceOver first (tools/a11y-drill/run.sh does this)")
    return -1
  }

  val elements = collectAccessibilityElements(root)
  checks.note("${elements.size} accessibility elements")
  for (element in elements.take(40)) {
    val value = element.accessibilityValue?.let { " value=\"$it\"" } ?: ""
    println("A11Y ELEMENT \"${element.label()}\"$value${traitNames(element.accessibilityTraits)}")
  }

  // An element with neither a label nor a value is announced by VoiceOver as its trait alone --
  // "text field", with no indication of which one. It is reachable, focusable, and anonymous.
  val anonymous = elements.filter { it.label().isEmpty() && it.accessibilityValue.isNullOrEmpty() }
  checks.check(
    "every element announces something",
    anonymous.isEmpty(),
    "${anonymous.size} anonymous:" + anonymous.joinToString("") { traitNames(it.accessibilityTraits) },
  )

  // ------------------------------------------------------------------------------------------
  // 1. Structure: guest-composed text reached the platform's accessibility layer.
  // ------------------------------------------------------------------------------------------
  //
  // These strings are written in the guest, cross the wire as properties, and are rendered by
  // host bindings. Finding them here is end-to-end evidence that a node composed inside the
  // sandbox is readable by a screen reader with no Dogwood-specific accessibility code.
  val labels = elements.labels()
  checks.check(
    "guest text is exposed to the accessibility layer",
    labels.any { it.contains("Diagnostics") },
    "looked for \"Diagnostics\" among ${labels.size} labels",
  )

  // ------------------------------------------------------------------------------------------
  // 2. Controls are exposed AS controls, not as text.
  // ------------------------------------------------------------------------------------------
  //
  // A button that reaches VoiceOver without the button trait is announced as a label. It reads
  // correctly and cannot be operated, which is the failure mode this check exists for.
  val buttons = elements.filter { it.isButton() }
  checks.check(
    "at least one element carries the button trait",
    buttons.isNotEmpty(),
    "${buttons.size} buttons: ${buttons.labels().take(6)}",
  )

  // ------------------------------------------------------------------------------------------
  // 3. The interaction itself: a VoiceOver activation drives the guest.
  // ------------------------------------------------------------------------------------------
  //
  // This is the check the review wanted a person for, and the one a dump can never make. The
  // sample's Expand button relabels itself when tapped, so its own label is the observable
  // consequence: activate through the accessibility layer, and the tree must come back changed.
  //
  // The activation goes to the host binding, the binding sends an event across the Zipline
  // boundary, the guest recomposes, a batch comes back, the host applies it, and Compose rebuilds
  // the accessibility tree. Every one of those steps has to work for the label to change.
  // The control is below the fold, and iOS publishes only what is on screen -- so reaching it is
  // itself part of the test. This is what a screen reader user does: scroll, then look again.
  var reached = elements
  var scrolls = 0
  while (reached.withLabel("Expand") == null && scrolls < 6) {
    if (!scrollDownSomewhere(root)) break
    scrolls++
    delay(600)
    reached = collectAccessibilityElements(root)
  }
  checks.check(
    "the screen scrolls through the accessibility layer",
    scrolls > 0,
    "$scrolls scrolls to reach the control",
  )

  // Guest-composed controls, as opposed to the host's own tab bar. This is the distinction the
  // first run of this drill exposed: the only buttons on screen belonged to the host shell, and a
  // check that counted buttons without asking whose they were would have passed on that.
  val guestButtons = reached.filter { it.isButton() && it.shellLabel() !in HOST_SHELL_LABELS }
  checks.check(
    "a guest-composed control carries the button trait",
    guestButtons.isNotEmpty(),
    "guest buttons: ${guestButtons.labels().take(6)}",
  )

  val expand = reached.withLabel("Expand")
  if (expand == null) {
    checks.check("the sample's Expand button is reachable", false, "labels: ${reached.labels().take(12)}")
  } else {
    checks.check("the sample's Expand button is reachable", true)
    checks.check("Expand is exposed as a button", expand.isButton())

    val activated = expand.accessibilityActivate()
    checks.check("accessibilityActivate() was accepted", activated)

    // The round trip crosses to the guest thread and back, so it cannot be observed synchronously.
    var relabelled = false
    repeat(40) {
      delay(100)
      if (collectAccessibilityElements(root).labels().any { it == "Collapse" }) {
        relabelled = true
        return@repeat
      }
    }
    checks.check(
      "activating through VoiceOver drove the guest and changed the tree",
      relabelled,
      "Expand -> Collapse",
    )

    // And back, so the drill leaves the screen as it found it and so the reverse direction is
    // exercised too -- a control that can be switched on and not off is half-broken.
    collectAccessibilityElements(root).withLabel("Collapse")?.let { collapse ->
      collapse.accessibilityActivate()
      var restored = false
      repeat(40) {
        delay(100)
        if (collectAccessibilityElements(root).labels().any { it == "Expand" }) {
          restored = true
          return@repeat
        }
      }
      checks.check("and back again", restored, "Collapse -> Expand")
    }
  }

  // ------------------------------------------------------------------------------------------
  // 4. Disabled controls say so.
  // ------------------------------------------------------------------------------------------
  //
  // ADR-031 withholds a widget whose affordance this client cannot read, on the grounds that a
  // control lying about what it will do is the worst outcome. The accessibility layer has the
  // same failure available to it: a disabled button with no `notEnabled` trait is announced as
  // operable. Recorded rather than asserted -- the sample may legitimately have no disabled
  // control on screen -- but printed, so its absence is visible rather than assumed.
  val disabled = elements.filter { it.isDisabled() }
  checks.note("${disabled.size} elements carry the notEnabled trait: ${disabled.labels().take(4)}")

  // ------------------------------------------------------------------------------------------
  // 5. Rotor actions, and scrolling.
  // ------------------------------------------------------------------------------------------
  val withCustomActions = elements.filter { it.customActionNames().isNotEmpty() }
  checks.note(
    "${withCustomActions.size} elements publish rotor actions" +
      withCustomActions.joinToString("") { " ${it.label()}=${it.customActionNames()}" },
  )

  println("A11Y RESULT passed=${checks.passed} failed=${checks.failed}")
  return checks.failed
}

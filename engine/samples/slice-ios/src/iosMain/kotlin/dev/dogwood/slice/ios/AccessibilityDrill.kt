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

/**
 * One assertion's outcome, in the shared conformance grammar.
 *
 * Every client emits `CONF <id> PASS|FAIL|SKIP`, so one aggregator reads all four runs and
 * generates the matrix in `plans/conformance.md`. The claim identifiers are the catalogue's, which
 * is what makes `D4` here and `D4` on Android and web the same promise rather than three
 * coincidentally similar tests. The human-readable `A11Y` lines are kept alongside, because the
 * `CONF` grammar is for the aggregator and a person reading a failing run wants the sentence.
 */
private class Checks {
  var passed = 0
  var failed = 0
  var skipped = 0

  fun check(id: String, name: String, condition: Boolean, detail: String = "") {
    val suffix = if (detail.isEmpty()) "" else " -- $detail"
    if (condition) {
      passed++
      println("CONF $id PASS$suffix")
      println("A11Y PASS $name$suffix")
    } else {
      failed++
      println("CONF $id FAIL$suffix")
      println("A11Y FAIL $name$suffix")
    }
  }

  fun skip(id: String, reason: String) {
    skipped++
    println("CONF $id SKIP -- $reason")
    println("A11Y SKIP $id -- $reason")
  }

  fun note(message: String) = println("A11Y NOTE $message")
}

private fun NSObject.label(): String = accessibilityLabel ?: ""

private fun List<NSObject>.labels(): List<String> = map { it.label() }.filter { it.isNotEmpty() }

/**
 * How an element looks when the thing wrong with it is that it has no label.
 *
 * [labels] drops the empty ones, which is right when reporting what *was* found and exactly wrong
 * when reporting what is missing: a claim about anonymous elements failed with the detail `1: []`,
 * naming a count and then throwing away every clue about which element it was. This prints what is
 * left when the label is gone -- the value, the traits, and the class -- so a failure says
 * something.
 */
private fun NSObject.describe(): String {
  val label = label()
  val value = (accessibilityValue ?: "").take(24)
  val traits = accessibilityTraits
  return buildString {
    append(if (label.isEmpty()) "<no label>" else "\"$label\"")
    if (value.isNotEmpty()) append(" value=\"$value\"")
    append(" traits=0x${traits.toString(16)}")
    append(" ${this@describe::class.simpleName}")
  }
}

/**
 * Where an element sits in the walk, named by the labelled elements around it.
 *
 * An element with no label cannot be named by its own label, which is the whole problem. Its
 * neighbours can name it: VoiceOver reads this list in order, so "after Expand" locates it on the
 * screen as precisely as a coordinate would and survives a layout change that a coordinate would
 * not.
 */
private fun List<NSObject>.locate(element: NSObject): String {
  val at = indexOfFirst { it === element }
  if (at < 0) return "not in the walk"
  val before = take(at).lastOrNull { it.label().isNotEmpty() }?.label() ?: "start"
  val after = drop(at + 1).firstOrNull { it.label().isNotEmpty() }?.label() ?: "end"
  return "#$at between \"$before\" and \"$after\""
}

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
    "D2",
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
    "D1",
    "guest text is exposed to the accessibility layer",
    labels.any { it.contains("Diagnostics") },
    "looked for \"Diagnostics\" among ${labels.size} labels",
  )

  // ------------------------------------------------------------------------------------------
  // 1b. The host's services reached the guest -- conformance group J.
  // ------------------------------------------------------------------------------------------
  //
  // Graded here rather than in a drill of its own, and the reason is the walk: this screen is the
  // Diagnostics screen and these labels are already in hand. A separate drill would be a second
  // copy of the accessibility walker and a second launch of the application to assert on strings
  // this one has already collected.
  //
  // They are graded at all because the capability was never graded anywhere. It has existed on this
  // platform since Phase 4 and the only client where a machine checked it was the web, which had
  // none of it until 2026-09-07 -- an inversion `plans/conformance.md` had to write down. This is
  // half of closing it.
  //
  // The clock is the one with an observable value: a millisecond count the guest could not have
  // invented, and which reads `host clock unavailable` when no clock crossed.
  checks.check(
    "J1",
    "the services this host wired reached the guest",
    labels.any { it.startsWith("host clock ") && it.last().isDigit() } &&
      labels.any { it.startsWith("time zone ") && it.contains("/") },
    labels.filter { it.startsWith("host clock") || it.startsWith("time zone") }.toString(),
  )

  // What a guest branches on to decide what it may use. An empty map renders as `unreported`, and a
  // client that reports nothing is a client every guest must assume is empty.
  val revision = labels.firstOrNull { it.startsWith("surface revision ") }
  checks.check(
    "J3",
    "the dictionary versions this client implements reached the guest",
    revision != null && !revision.contains("unreported"),
    revision ?: "no surface revision line on screen",
  )

  // ------------------------------------------------------------------------------------------
  // 2. Controls are exposed AS controls, not as text.
  // ------------------------------------------------------------------------------------------
  //
  // A button that reaches VoiceOver without the button trait is announced as a label. It reads
  // correctly and cannot be operated, which is the failure mode this check exists for.
  val buttons = elements.filter { it.isButton() }
  checks.check(
    "D3-any",
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
    "D5",
    "the screen scrolls through the accessibility layer",
    scrolls > 0,
    "$scrolls scrolls to reach the control",
  )

  // Guest-composed controls, as opposed to the host's own tab bar. This is the distinction the
  // first run of this drill exposed: the only buttons on screen belonged to the host shell, and a
  // check that counted buttons without asking whose they were would have passed on that.
  val guestButtons = reached.filter { it.isButton() && it.shellLabel() !in HOST_SHELL_LABELS }
  checks.check(
    "D3",
    "a guest-composed control carries the button trait",
    guestButtons.isNotEmpty(),
    "guest buttons: ${guestButtons.labels().take(6)}",
  )

  val expand = reached.withLabel("Expand")
  if (expand == null) {
    checks.check("D4-reachable", "the sample's Expand button is reachable", false, "labels: ${reached.labels().take(12)}")
  } else {
    checks.check("D4-reachable", "the sample's Expand button is reachable", true)
    checks.check("D4-exposed", "Expand is exposed as a button", expand.isButton())

    val activated = expand.accessibilityActivate()
    checks.check("D4-accepted", "accessibilityActivate() was accepted", activated)

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
      "D4",
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
      checks.check("D4-reverse", "and back again", restored, "Collapse -> Expand")
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
  // The whole walk, printed only when something in it has no label. A claim that fails on an
  // element with *no label* is a claim whose message cannot name its subject, so the tree is the
  // only way to tell "this control lost its label" from "this is some other element entirely" --
  // and a green run does not need to say any of it.
  if (reached.any { it.label().isEmpty() }) {
    checks.note("walk: " + reached.mapIndexed { at, it -> "$at ${it.describe()}" }.joinToString(" | "))
  }

  // Look again before reporting.
  //
  // iOS publishes only what is on screen, and the scroll loop above stops at the *first* frame in
  // which its target is reachable -- so whatever comes next sits at the viewport edge, published
  // with its traits and, if the part of it carrying the name is clipped, without its label. That is
  // a property of where the walk stopped, not of the control.
  //
  // It is not hypothetical, and it is the reason this second look exists. Adding an unrelated
  // section to the sample screen moved where the loop stops, and this claim went red on a button
  // that had not changed: `1: [<no label> traits=0x101]`, sitting immediately after the button the
  // loop had been searching for. The finding was a hypothesis about a control and was really an
  // observation about a viewport.
  //
  // So an anonymous disabled control is confirmed by bringing it further into view and looking
  // again. If it is still anonymous, it is reported -- and now the report means what it says.
  var disabled = reached.filter { it.isDisabled() }
  var lookedAgain = false
  if (disabled.any { it.label().isEmpty() } && scrolls < 6) {
    if (scrollDownSomewhere(root)) {
      lookedAgain = true
      delay(600)
      reached = collectAccessibilityElements(root)
      disabled = reached.filter { it.isDisabled() }
    }
  }

  val confirmation = if (lookedAgain) " (after a second look)" else ""
  if (disabled.isEmpty()) {
    // Not silence: the sample carries a deliberately disabled control for exactly this claim, so
    // finding none here is a difference from Android worth seeing rather than a blank.
    checks.skip("D7", "no element on this screen carries the notEnabled trait$confirmation")
  } else {
    checks.check("D7", "a disabled control is announced as disabled", disabled.all { it.label().isNotEmpty() },
      "${disabled.size}$confirmation: ${disabled.map { "${it.describe()} ${reached.locate(it)}" }.take(4)}")
  }

  // ------------------------------------------------------------------------------------------
  // 5. Rotor actions, and scrolling.
  // ------------------------------------------------------------------------------------------
  val withCustomActions = elements.filter { it.customActionNames().isNotEmpty() }
  checks.note(
    "${withCustomActions.size} elements publish rotor actions" +
      withCustomActions.joinToString("") { " ${it.label()}=${it.customActionNames()}" },
  )

  println("CONF RESULT client=ios passed=${checks.passed} failed=${checks.failed} skipped=${checks.skipped}")
  println("A11Y RESULT passed=${checks.passed} failed=${checks.failed}")
  return checks.failed
}

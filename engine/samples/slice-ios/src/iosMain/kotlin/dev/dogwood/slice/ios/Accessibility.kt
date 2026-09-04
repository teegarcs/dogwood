/*
 * Project Dogwood -- the accessibility probe.
 *
 * roadmap.md Phase 6 step 1 asserts that "VoiceOver, the input method editor, and text selection
 * work with no Dogwood-specific code -- they should, because Compose Multiplatform owns the layout
 * tree." That is a claim about somebody else's code, so this prints the evidence rather than
 * restating the argument: it walks the `UIView` hierarchy the way an assistive technology does and
 * reports what is exposed to it.
 *
 * **What it can and cannot settle.** It reads exactly the surface `UIAccessibility` publishes --
 * the elements, their labels, their values and their traits. That is the surface VoiceOver reads,
 * so an element appearing here with a label taken from a guest-composed `Text` is direct evidence
 * that a guest-authored node reached the platform's accessibility layer without a line of
 * Dogwood-specific code. It is *not* evidence that the spoken experience is good: order, grouping,
 * rotor behaviour and whether the resulting speech makes sense need a person with the screen
 * reader on. See the Phase 6 report.
 *
 * Compose Multiplatform builds this tree only while an assistive technology is running, which is
 * a performance decision on its side, not an availability one -- see
 * `UIAccessibilityIsVoiceOverRunning` below. On a simulator with VoiceOver off the walk finds the
 * rendering view and no elements under it, and says so, which is a correct reading rather than a
 * failure.
 */
package dev.dogwood.slice.ios

import platform.UIKit.UIAccessibilityIsSwitchControlRunning
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilityTraitButton
import platform.UIKit.UIAccessibilityTraitHeader
import platform.UIKit.UIAccessibilityTraitImage
import platform.UIKit.UIAccessibilityTraitLink
import platform.UIKit.UIAccessibilityTraitSelected
import platform.UIKit.UIAccessibilityTraitStaticText
import platform.UIKit.UIView
import platform.UIKit.accessibilityElementAtIndex
import platform.UIKit.accessibilityElementCount
import platform.UIKit.accessibilityElements
import platform.UIKit.accessibilityLabel
import platform.UIKit.accessibilityTraits
import platform.UIKit.accessibilityValue
import platform.UIKit.isAccessibilityElement
import platform.Foundation.hash
import platform.darwin.NSObject

/**
 * The traits this probe names.
 *
 * `UIAccessibilityTraits` is a bit field, and the ones below are the ones a screen built out of
 * text, buttons, images and headings actually sets. An unnamed bit is reported as a number rather
 * than dropped.
 */
private val NAMED_TRAITS = listOf(
  UIAccessibilityTraitButton to "button",
  UIAccessibilityTraitStaticText to "staticText",
  UIAccessibilityTraitHeader to "header",
  UIAccessibilityTraitImage to "image",
  UIAccessibilityTraitLink to "link",
  UIAccessibilityTraitSelected to "selected",
)

internal fun traitNames(traits: ULong): String {
  if (traits == 0uL) return ""
  val named = NAMED_TRAITS.filter { (bit, _) -> traits and bit != 0uL }.map { it.second }
  val remainder = traits and NAMED_TRAITS.fold(0uL) { acc, (bit, _) -> acc or bit }.inv()
  val all = named + if (remainder != 0uL) listOf("0x${remainder.toString(16)}") else emptyList()
  return " traits=${all.joinToString("|")}"
}

/**
 * The children an assistive technology would walk into.
 *
 * `accessibilityElements` and the `accessibilityElementCount`/`accessibilityElementAtIndex` pair
 * are both declared by UIKit as categories on `NSObject`, so they reach Compose Multiplatform's
 * own element objects and not only the `UIView`s above them. That matters here: the interesting
 * part of the tree -- everything the guest composed -- hangs off a single non-view element that a
 * `subviews` walk cannot see into. Falling back to `subviews` keeps the plain UIKit part of the
 * hierarchy visible.
 */
internal fun childrenOf(obj: NSObject): List<Any?> {
  // A `UIView` is asked for its own subviews first, and only for its published
  // `accessibilityElements` when it has none. Asking a `UIView` for
  // `accessibilityElementCount()` while VoiceOver is running makes UIKit compute a whole
  // accessibility hierarchy for it; on `UIWindow` that never returned, which is why this
  // distinction exists rather than one uniform call.
  if (obj is UIView) {
    val subviews = obj.subviews
    if (subviews.isNotEmpty()) return subviews
    return obj.accessibilityElements.orEmpty()
  }
  // Compose Multiplatform's own elements are not views. `accessibilityElements` and the
  // `accessibilityElementCount`/`accessibilityElementAtIndex` pair are declared by UIKit as
  // categories on `NSObject`, so they reach them -- which is the whole point: everything the
  // guest composed hangs off a single non-view element that a `subviews` walk cannot see into.
  obj.accessibilityElements?.takeIf { it.isNotEmpty() }?.let { return it }
  val count = obj.accessibilityElementCount()
  if (count > 0) return (0 until count).map { obj.accessibilityElementAtIndex(it) }
  return emptyList()
}

/**
 * A hard cap on the walk.
 *
 * The first version of this probe had none and never returned: an accessibility container may
 * legitimately publish an element that publishes it back, and a lazy list republishes elements as
 * it scrolls, so a depth limit alone does not bound the traversal. Printing as it goes and
 * stopping at a fixed count is both bounded and useful -- the first couple of hundred nodes of a
 * screen are the screen.
 */
internal const val MAX_NODES = 250

private fun describe(node: Any?, depth: Int, seen: MutableSet<Long>, sink: (String) -> Unit) {
  val obj = node as? NSObject ?: return
  if (depth > 24 || seen.size >= MAX_NODES) return
  // Identity by Objective-C hash: two Kotlin wrappers may stand for the same underlying object.
  if (!seen.add(obj.hash().toLong())) return
  val indent = "  ".repeat(depth)
  val label = obj.accessibilityLabel
  val value = obj.accessibilityValue
  sink(
    "$indent${obj::class.simpleName ?: "element"}" +
      (if (obj.isAccessibilityElement()) " isElement" else "") +
      (label?.let { " label=\"$it\"" } ?: "") +
      (value?.let { " value=\"$it\"" } ?: "") +
      traitNames(obj.accessibilityTraits),
  )
  childrenOf(obj).forEach { describe(it, depth + 1, seen, sink) }
}

/**
 * Prints the accessibility tree under [root].
 *
 * Written to standard output so `xcrun simctl launch --console-pty` picks it up, which is the only
 * channel a simulator-installed application without an Xcode project has.
 */
fun dumpAccessibilityTree(root: UIView) {
  println(
    "dogwood-a11y: voiceOverRunning=${UIAccessibilityIsVoiceOverRunning()} " +
      "switchControlRunning=${UIAccessibilityIsSwitchControlRunning()}",
  )
  var count = 0
  describe(root, 0, mutableSetOf()) {
    count++
    println("dogwood-a11y: $it")
  }
  println("dogwood-a11y: $count nodes (cap $MAX_NODES)")
}

/**
 * Every accessibility element under [root], in the order the walk reaches them.
 *
 * The same traversal [dumpAccessibilityTree] prints, returning the objects instead of describing
 * them, so that a test can assert on the surface a screen reader reads rather than on a string
 * rendering of it. Only objects that answer `isAccessibilityElement` are returned: containers
 * publish children and are walked through, but VoiceOver never stops on them.
 */
internal fun collectAccessibilityElements(root: UIView): List<NSObject> {
  val found = mutableListOf<NSObject>()
  val seen = mutableSetOf<Long>()

  fun walk(node: Any?, depth: Int) {
    val obj = node as? NSObject ?: return
    if (depth > 24 || seen.size >= MAX_NODES) return
    if (!seen.add(obj.hash().toLong())) return
    if (obj.isAccessibilityElement()) found += obj
    childrenOf(obj).forEach { walk(it, depth + 1) }
  }

  walk(root, 0)
  return found
}

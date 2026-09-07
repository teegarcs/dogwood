/*
 * Project Dogwood -- skew containment on iOS, read off the screen rather than off a log.
 *
 * Section 6 of the technical specification makes three claims about a client meeting a payload
 * built against a **newer** dictionary than its own. They are conformance claims A2, A3 and A4,
 * and until this file they had been run end to end on Android only -- `tools/skew-drill` -- while
 * the shared unit tests covered the rules and no client but one had ever met a real skewed
 * payload.
 *
 * The two-build shape is the same and is the whole point: the host bindings are compiled into the
 * installed application, the guest payload is fetched at run time, so serving a payload built at
 * version N+1 to an application still at N is the only honest way to produce the condition.
 * `tools/skew-drill/run-ios.sh` arranges it; this is what reads the result.
 *
 * **It reads the accessibility tree, not the host's account of itself.** The claims are about a
 * *screen*: a placeholder that occupies its slot, a widget that still renders, a widget that is
 * genuinely absent. A host log line saying "withheld" is the host agreeing with itself. The
 * accessibility tree is the outcome, and it is the same surface `AccessibilityDrill.kt` asserts on
 * for the same reason -- `xcrun simctl` cannot tap, type or dump a view hierarchy the way
 * `uiautomator` can, so this is iOS's equivalent of the Android drill's hierarchy dump.
 *
 * **Which means VoiceOver must be running.** Compose Multiplatform builds its accessibility tree
 * only while an assistive technology is active, so with it off the walk finds the rendering view
 * and nothing under it -- and every claim below would fail for one uninteresting reason. The drill
 * refuses rather than reports in that case, exactly as the accessibility one does.
 */
package dev.dogwood.slice.ios

import dev.dogwood.host.SkewReport
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIView
import platform.UIKit.accessibilityFrame
import platform.UIKit.accessibilityLabel

/** The markers `tools/skew-drill/skew.py` composes into the guest's Diagnostics screen. */
private const val BEFORE = "SKEW-BEFORE"
private const val AFTER = "SKEW-AFTER"
private const val BADGE = "SKEW-BADGE"
private const val PAY = "SKEW-PAY"

/**
 * Runs the drill and prints the `CONF` grammar `tools/conformance/aggregate.py` reads.
 *
 * @param root the key window, walked the way an assistive technology walks it.
 * @param skew the live experience's report, **sampled** by the caller rather than observed.
 *   `SkewReport` is plain sets written during composition, so nothing invalidates when an entry
 *   lands; the Android drill learned that the expensive way, with `withheld=` appearing only after
 *   an unrelated redraw.
 * @return the number of failed claims, so the caller can print a sentinel the script waits for.
 */
@OptIn(ExperimentalForeignApi::class)
fun runSkewDrill(root: UIView, skew: SkewReport?): Int {
  if (!UIAccessibilityIsVoiceOverRunning()) {
    println("SKEW REFUSED VoiceOver is not running, so Compose publishes no accessibility tree")
    return -1
  }

  // Label to the top of its frame, in points. The first occurrence wins: a lazy container may
  // republish an element as it scrolls, and the first is the one laid out where the walk found it.
  val tops = LinkedHashMap<String, Double>()
  for (element in collectAccessibilityElements(root)) {
    val label = element.accessibilityLabel ?: continue
    if (!label.startsWith("SKEW-")) continue
    if (label in tops) continue
    tops[label] = element.accessibilityFrame.useContents { origin.y }
  }
  for ((label, top) in tops) println("SKEW element $label y=$top")

  var passed = 0
  var failed = 0
  fun conform(claim: String, ok: Boolean, detail: String) {
    if (ok) passed++ else failed++
    println("CONF $claim ${if (ok) "PASS" else "FAIL"} -- $detail")
  }

  // The control, and it is not decoration: without it a screen that never loaded reads as three
  // passes, because every claim below is satisfied by absence.
  conform(
    "A2-control",
    BEFORE in tops && AFTER in tops,
    "the skewed screen rendered: ${tops.keys}",
  )

  // A2 -- an unknown widget tag becomes a placeholder, and the sibling after it keeps its place.
  // The placeholder draws nothing, so the evidence is geometric: the marker after it must sit
  // below the one before it. Had the create been skipped rather than placeheld, every later index
  // in that slot would have shifted by one.
  val before = tops[BEFORE]
  val after = tops[AFTER]
  conform(
    "A2",
    before != null && after != null && after > before,
    "$BEFORE at y=$before, $AFTER at y=$after" +
      if (before != null && after != null && after > before) {
        " -- the placeholder occupies its slot (gap ${after - before} points)"
      } else {
        ""
      },
  )

  // A3 -- an unknown property on a widget that owns no affordance is ignored, and it still renders.
  conform(
    "A3",
    BADGE in tops,
    if (BADGE in tops) {
      "the badge carrying an unknown property rendered"
    } else {
      "the badge is missing; visible markers: ${tops.keys}"
    },
  )

  // A4 -- an unknown property on a widget that *owns* an affordance withholds the widget entirely.
  // One of the things the payload might have been saying is "this is disabled", and this client
  // cannot read it. See ADR-031.
  conform(
    "A4",
    PAY !in tops,
    if (PAY !in tops) {
      "the button is absent from the accessibility tree"
    } else {
      "the button rendered while carrying an unreadable affordance-bearing property"
    },
  )

  // And it is reported, not merely survived. Containment nobody can see teaches no team that its
  // payloads have moved ahead of its devices, which is the whole purpose of `SkewReport`.
  val report = skew?.toString() ?: "no experience to read"
  conform("A4-reported", skew != null && skew.withheldWidgets.isNotEmpty(), report)

  println("CONF RESULT client=ios passed=$passed failed=$failed skipped=0")
  return failed
}

/*
 * Project Dogwood -- conformance claim B3 on iOS: a too-new payload is refused before it runs.
 *
 * The other half of `SkewDrill.kt`. Both are run by a script that serves this binary a payload built
 * against a dictionary it does not have; they differ in whether that payload *says so* in its
 * manifest. `SkewDrill.kt` grades what happens when it does not -- a placeholder holding its slot,
 * a badge that still renders, a button withheld, claims A2/A3/A4. This grades what happens when it
 * does: the client reads the declaration out of the manifest's **signed** metadata and refuses.
 *
 * **What "before it runs" means on this platform, precisely, because it is not what it means on the
 * web.** The web host fetches and verifies the manifest itself, so it refuses without ever creating
 * a Worker, and claim `B3` there asserts `workerCreated=false` -- nothing of the payload executes at
 * all. Zipline exposes no manifest-only fetch to a mobile client: `ZiplineLoader.loadOnce` fetches,
 * verifies and evaluates the modules in one call, and `fetchManifestFromNetwork` and
 * `LoadedManifest` are `internal` (checked against zipline-loader 1.27.0). So the mobile check runs
 * after module evaluation and before `start`: no entry point is called, no service is bound, nothing
 * composes, and the QuickJS instance is closed. That is a weaker guarantee than the web's, and it is
 * written down rather than blurred.
 *
 * **Read off the screen, and VoiceOver must be running.** Compose Multiplatform builds its
 * accessibility tree only while an assistive technology is active; with it off, the walk finds the
 * rendering view and nothing under it, and every claim below would fail for one uninteresting
 * reason. This refuses rather than reports in that case, exactly as its two siblings do.
 */
package dev.dogwood.slice.ios

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIView
import platform.UIKit.accessibilityLabel

/**
 * Runs the drill and prints the `CONF` grammar `tools/conformance/aggregate.py` reads.
 *
 * @param root the key window, walked the way an assistive technology walks it.
 * @return the number of failed claims, so the caller can print a sentinel the script waits for.
 */
@OptIn(ExperimentalForeignApi::class)
fun runPreflightDrill(root: UIView): Int {
  if (!UIAccessibilityIsVoiceOverRunning()) {
    println("PREFLIGHT REFUSED VoiceOver is not running, so Compose publishes no accessibility tree")
    return -1
  }

  val labels = collectAccessibilityElements(root).mapNotNull { it.accessibilityLabel }
  for (label in labels.filter { it.startsWith("SKEW-") || "refused:" in it }) {
    println("PREFLIGHT element $label")
  }

  var passed = 0
  var failed = 0
  fun conform(claim: String, ok: Boolean, detail: String) {
    if (ok) passed++ else failed++
    println("CONF $claim ${if (ok) "PASS" else "FAIL"} -- $detail")
  }

  // The sample renders whatever the shell last decided, so the refusal is a label on the screen
  // rather than a line in the console. A console line saying "refused" is the host agreeing with
  // itself; this is what a person looking at the device would see.
  val refusal = labels.firstOrNull { "refused:" in it }.orEmpty()
  val markers = labels.filter { it.startsWith("SKEW-") }

  // The control, and it is what makes the two assertions below mean anything: every one of them is
  // satisfied by an application that failed to launch or showed a blank screen, so first prove the
  // shell is alive and has decided something. The note reads "starting…" until it has.
  val alive = labels.any { "refused:" in it || "loaded version" in it || "failed:" in it }
  conform("B3-control", alive, "the shell reported a decision: ${refusal.ifEmpty { labels.take(6).toString() }}")

  // B3 -- the client refuses, and names both versions. "This client is behind" sends nobody
  // anywhere; "wants 15, implements 14" is the difference between a diagnosis and a shrug.
  val numbers = Regex("""wants (\d+), this client implements (\d+)""").find(refusal)
  val wanted = numbers?.groupValues?.get(1)?.toIntOrNull()
  val have = numbers?.groupValues?.get(2)?.toIntOrNull()
  conform(
    "B3",
    wanted != null && have != null && wanted > have,
    refusal.ifEmpty { "nothing on screen mentions a refusal; visible: ${labels.take(8)}" },
  )

  // And nothing of the payload reached the screen. The skewed guest publishes four `SKEW-` markers
  // on the Diagnostics screen; a refusal that still showed them would be a refusal in name only.
  conform(
    "B3-absent",
    markers.isEmpty(),
    if (markers.isEmpty()) {
      "no widget from the refused payload is on screen"
    } else {
      "the refused payload rendered anyway: $markers"
    },
  )

  println("CONF RESULT client=ios passed=$passed failed=$failed skipped=0")
  return failed
}

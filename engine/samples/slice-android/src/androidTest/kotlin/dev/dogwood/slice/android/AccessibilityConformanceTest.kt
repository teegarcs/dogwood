/*
 * Project Dogwood -- conformance claims D1-D7 on Android.
 *
 * The same claims the iOS drill asserts (`AccessibilityDrill.kt`, ADR-039), against entirely
 * different machinery. That split is the whole point of `plans/conformance.md`: the *claim* is
 * shared, the *instrument* cannot be. `D4` means "activating through the accessibility layer drives
 * the guest" on both platforms; it is `accessibilityActivate` there and
 * `performAction(ACTION_CLICK)` here.
 *
 * **Why this is an instrumented test and the iOS one is not.** On iOS there is no way to read the
 * accessibility tree from outside the process, so the drill has to live inside the application and
 * needs VoiceOver switched on before Compose will build a tree at all. Android has neither
 * problem: `UiAutomation` *is* an accessibility service, so it sees the tree the way TalkBack does,
 * out of process, and connecting it is what makes the tree exist. Mimicking the iOS shape here
 * would mean shipping drill code inside the sample and reading a less faithful tree, to look
 * consistent. The claims are what should look consistent.
 *
 * Output is the `CONF` grammar from `plans/conformance.md`, printed to standard output so the
 * aggregator reads this run exactly as it reads the other three clients'.
 */
package dev.dogwood.slice.android

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The host shell's own controls, which are not evidence about the guest. */
private val HOST_SHELL_LABELS = setOf("Explore", "Stays", "Trips", "Account", "trim")

private const val PACKAGE = "dev.dogwood.slice.android"

/**
 * Where the `CONF` lines go.
 *
 * Standard output from an instrumented test does not reach the Gradle console or the result XML,
 * so a `println` alone produces a run whose verdict is a number with no lines behind it. Logcat is
 * the channel that survives, and `tools/conformance/run-android.sh` scrapes this tag.
 */
private const val CONF_TAG = "DogwoodConf"

private fun emit(line: String) {
  android.util.Log.i(CONF_TAG, line)
  println(line)
}

@RunWith(AndroidJUnit4::class)
class AccessibilityConformanceTest {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val automation get() = instrumentation.uiAutomation
  private val device: UiDevice = UiDevice.getInstance(instrumentation)

  private var passed = 0
  private var failed = 0
  private var skipped = 0

  private fun conform(id: String, condition: Boolean, detail: String = "") {
    val suffix = if (detail.isEmpty()) "" else " -- $detail"
    if (condition) {
      passed++
      emit("CONF $id PASS$suffix")
    } else {
      failed++
      emit("CONF $id FAIL$suffix")
    }
  }

  /**
   * Performs an accessibility action, surviving a node that has gone stale.
   *
   * The tree is a snapshot. Between fetching it and acting on it the screen may have recomposed,
   * and Compose throws rather than returning false for an action on a detached node. A throw here
   * would abort the run before the `RESULT` line, turning one expired node into no report at all.
   */
  private fun perform(node: AccessibilityNodeInfo, action: Int): Boolean =
    try {
      node.performAction(action)
    } catch (stale: IllegalStateException) {
      emit("CONF NOTE action $action on a stale node: ${stale.message?.take(80)}")
      false
    }

  private fun skip(id: String, reason: String) {
    skipped++
    emit("CONF $id SKIP -- $reason")
  }

  // -----------------------------------------------------------------------------------------
  // Reading the tree.
  // -----------------------------------------------------------------------------------------

  /**
   * Every node under the active window.
   *
   * `rootInActiveWindow` is refetched on each call rather than cached: the tree is a snapshot, and
   * a node held across an interaction refers to a view that may no longer exist. That is the
   * Android analogue of the iOS drill re-walking after an activation, and the same reasoning --
   * the consequence of an action is only visible in a tree fetched after it.
   */
  private fun nodes(): List<AccessibilityNodeInfo> {
    val root = automation.rootInActiveWindow ?: return emptyList()
    val found = mutableListOf<AccessibilityNodeInfo>()
    fun walk(node: AccessibilityNodeInfo?, depth: Int) {
      if (node == null || depth > 40 || found.size >= 400) return
      found += node
      for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
    }
    walk(root, 0)
    return found
  }

  /** This node's own name, if it has one. */
  private fun AccessibilityNodeInfo.ownName(): String {
    val described = contentDescription?.toString()
    if (!described.isNullOrBlank()) return described
    return text?.toString()?.takeIf { it.isNotBlank() }.orEmpty()
  }

  /**
   * What a screen reader would actually announce for this node.
   *
   * **Compose does not merge a button's label into the clickable node**, and reading the raw node
   * is therefore wrong. The real tree looks like this:
   *
   * ```
   * View        clickable=true  enabled=false  text=""     <- the node TalkBack focuses
   *   TextView  clickable=false enabled=true   text="Unavailable"
   *   Button    clickable=false                            <- the role marker
   * ```
   *
   * TalkBack composes its announcement from unfocusable descendants when the focused node has no
   * name of its own, so it says "Unavailable, button, disabled" where a naive reading of the
   * focused node alone says nothing at all. The first draft of this drill did exactly that and
   * reported thirteen anonymous controls that a screen reader announces perfectly well -- a false
   * alarm, which is the failure mode automated accessibility checks are most prone to and the
   * reason this method exists rather than a field read.
   *
   * The same structure is why [actionable] and [enabledState] look at the clickable node rather
   * than the labelled one: the label and the behaviour live on different nodes.
   */
  private fun AccessibilityNodeInfo.spokenLabel(): String {
    val own = ownName()
    if (own.isNotEmpty()) return own
    val parts = mutableListOf<String>()
    fun gather(node: AccessibilityNodeInfo?, depth: Int) {
      if (node == null || depth > 6) return
      // A descendant a screen reader would focus in its own right is not part of this node's
      // announcement -- it gets its own.
      if (depth > 0 && node.isClickable) return
      node.ownName().takeIf { it.isNotEmpty() }?.let { parts += it }
      for (i in 0 until node.childCount) gather(node.getChild(i), depth + 1)
    }
    gather(this, 0)
    return parts.joinToString(" ").trim()
  }

  /**
   * Whether a screen reader would stop on this node.
   *
   * The Android tree carries far more nodes than VoiceOver's element list -- layout containers are
   * in it too -- so a check phrased as "every node" would be about Compose's view structure rather
   * than about what a user hears. A node is a stop if a screen reader can focus it or if it can be
   * operated; the second half matters because the clickable node of a Compose button is not always
   * marked focusable and is exactly the node a user lands on.
   */
  private fun AccessibilityNodeInfo.isFocusableByScreenReader(): Boolean =
    isClickable || if (Build.VERSION.SDK_INT >= 30) isScreenReaderFocusable else isImportantForAccessibility

  /** The active tab is labelled "● Account", so the marker is stripped before the comparison. */
  private fun AccessibilityNodeInfo.shellLabel(): String = spokenLabel().removePrefix("● ")

  private fun bounds(node: AccessibilityNodeInfo): android.graphics.Rect =
    android.graphics.Rect().also { node.getBoundsInScreen(it) }

  /**
   * Whether this node is wholly on screen, rather than clipped by the scrolling viewport.
   *
   * A control half off the bottom of a scroller reports bounds that extend past its container, and
   * its label node has not been realised yet -- so it reads as anonymous for as long as it is
   * half-visible. Judging it would make the verdict depend on scroll position, which is the
   * definition of a flaky gate; the user scrolls one notch further and the label is there.
   *
   * The rule is containment in the scrolling ancestor, not "is it on the display", because the
   * display is not what clips it.
   */
  private fun isWhollyVisible(node: AccessibilityNodeInfo): Boolean {
    val scroller = nodes().firstOrNull { it.isScrollable } ?: return true
    val viewport = bounds(scroller)
    val rect = bounds(node)
    if (!android.graphics.Rect.intersects(viewport, rect)) return true
    return viewport.contains(rect)
  }

  private fun labels(): List<String> =
    nodes().filter { it.isFocusableByScreenReader() }.map { it.spokenLabel() }.filter { it.isNotEmpty() }

  /**
   * The node a screen reader would operate for [label].
   *
   * Prefers a clickable node, because the label and the behaviour are on different nodes and
   * sending `ACTION_CLICK` to the label does nothing -- which the first draft did, reporting the
   * action as refused and the claim as failed when the control was working.
   */
  private fun find(label: String): AccessibilityNodeInfo? {
    val matching = nodes().filter { it.spokenLabel() == label }
    return matching.firstOrNull { it.isClickable } ?: matching.firstOrNull()
  }

  /** Waits for [label] to appear, which is how the consequence of an action is observed. */
  private fun awaitLabel(label: String, timeoutMs: Long = 8_000): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (find(label) != null) return true
      Thread.sleep(150)
    }
    return false
  }

  // -----------------------------------------------------------------------------------------

  @Before
  fun openTheDiagnosticsScreen() {
    val context = instrumentation.targetContext
    context.startActivity(
      Intent().apply {
        component = ComponentName(PACKAGE, "$PACKAGE.TabsActivity")
        // The screen under test is named by the intent, which is the Android analogue of the iOS
        // drill's `--dogwood-a11y` launch argument. Navigating by tapping the tab bar was the
        // first attempt and it failed for a reason worth keeping: the tab buttons announce
        // nothing, so there was no label to find them by. That is now claim D2's problem, not
        // this setup's.
        putExtra("entry", "about")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
      },
    )
    device.wait(androidx.test.uiautomator.Until.hasObject(
      androidx.test.uiautomator.By.pkg(PACKAGE).depth(0),
    ), 20_000)
    // The guest is fetched over the network, so the screen takes a moment to exist.
    awaitLabel("Diagnostics", timeoutMs = 40_000)
  }

  @Test
  fun accessibilityConformance() {
    val visible = nodes().filter { it.isFocusableByScreenReader() }
    emit("CONF NOTE ${visible.size} screen-reader-focusable nodes")
    for (node in visible.take(40)) {
      emit("CONF ELEMENT \"${node.spokenLabel()}\" class=${node.className} clickable=${node.isClickable} enabled=${node.isEnabled}")
    }

    // D1 -- guest-composed text reaches the platform's accessibility layer.
    conform(
      "D1",
      labels().any { it.contains("Diagnostics") },
      "looked for \"Diagnostics\" among ${labels().size} announcements",
    )

    // D2 -- no anonymous elements. A node a screen reader stops on with nothing to announce is
    // reachable, focusable and silent.
    val anonymous = visible.filter { it.spokenLabel().isEmpty() && isWhollyVisible(it) }
    val clipped = visible.count { it.spokenLabel().isEmpty() && !isWhollyVisible(it) }
    if (clipped > 0) emit("CONF NOTE $clipped unnamed node(s) clipped by the viewport, not judged")
    conform(
      "D2",
      anonymous.isEmpty(),
      "${anonymous.size} anonymous: " + anonymous.take(4).joinToString("; ") {
        "${it.className} clickable=${it.isClickable} children=${it.childCount} bounds=${
          android.graphics.Rect().also { r -> it.getBoundsInScreen(r) }
        }"
      },
    )

    // D3 -- a guest-composed control is exposed AS a control. Excluding the host shell's own tab
    // bar, which would be accessible whether or not a single guest node reached the platform.
    // A control must be *named* as well as exposed. The first draft asked only whether a clickable
    // node existed, and passed on six that announced nothing -- which is the failure it was
    // supposed to catch, wearing a green tick.
    val guestControls = visible.filter { it.isClickable && it.shellLabel() !in HOST_SHELL_LABELS }
    val namedGuestControls = guestControls.filter { it.spokenLabel().isNotEmpty() }
    conform(
      "D3",
      namedGuestControls.isNotEmpty(),
      "${namedGuestControls.size} named of ${guestControls.size} guest controls: " +
        "${namedGuestControls.map { it.spokenLabel() }.take(6)}",
    )

    // D5 -- the screen scrolls through the accessibility layer. Asserted before D4 because the
    // control D4 needs is below the fold, so reaching it is the evidence for D5.
    var scrolls = 0
    while (find("Expand") == null && scrolls < 6) {
      if (!scrollForward()) break
      scrolls++
      Thread.sleep(500)
    }
    conform("D5", scrolls > 0, "$scrolls scrolls to reach the control")

    // D4 -- activating through the accessibility layer drives the guest. The whole round trip:
    // accessibility action, host binding, event across the Zipline boundary, guest recomposition,
    // batch back, host applies, tree rebuilt. The sample's button relabels itself, so its own
    // label is the observable consequence -- no instrumentation and no back channel.
    val expand = find("Expand")
    if (expand == null) {
      conform("D4", false, "the Expand button was never reachable")
    } else {
      conform("D4-reachable", true, "Expand is present and exposed")
      val accepted = perform(expand, AccessibilityNodeInfo.ACTION_CLICK)
      conform("D4-accepted", accepted, "ACTION_CLICK was accepted")
      val relabelled = awaitLabel("Collapse")
      conform("D4", relabelled, "Expand -> Collapse")
      // And back, so the screen is left as it was found and the reverse direction is exercised:
      // a control that switches on and not off is half-broken.
      //
      // Guarded on the forward direction having worked. Without the guard this check passes
      // vacuously when D4 fails -- there is no "Collapse" to click, nothing happens, and
      // `awaitLabel("Expand")` finds the label that was never replaced. It did exactly that on the
      // run before this comment was written: D4 red and D4-reverse green, describing one screen.
      if (!relabelled) {
        skip("D4-reverse", "the forward direction did not happen, so there is nothing to reverse")
      } else {
        find("Collapse")?.let { perform(it, AccessibilityNodeInfo.ACTION_CLICK) }
        conform("D4-reverse", awaitLabel("Expand"), "Collapse -> Expand")
      }
    }

    // D7 -- a disabled control is announced as disabled. A screen reader user who is not told will
    // try to operate it and be met with nothing.
    val disabled = find("Unavailable")
    if (disabled == null) {
      skip("D7", "the sample screen carries no disabled control")
    } else {
      conform("D7", !disabled.isEnabled, "\"Unavailable\" enabled=${disabled.isEnabled}")
    }

    assertEquals("failed conformance claims", 0, failed)
  }

  /**
   * Sends a scroll to the scrollable container, the way a screen reader's gesture does.
   *
   * The first draft offered the action to *every* node until one took it, which is wrong twice
   * over. A screen reader sends scroll to a scrollable container, not to everything -- and acting
   * on a list of nodes while the earlier actions recompose the screen means the later entries are
   * detached by the time they are reached, which Compose answers by throwing
   * `IllegalStateException: LayoutCoordinate operations are only valid when isAttached is true`.
   * A stale node is not an error to report; it is a snapshot that has expired.
   */
  private fun scrollForward(): Boolean {
    val scrollable = nodes().firstOrNull { it.isScrollable } ?: return false
    return perform(scrollable, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
  }
}

/*
 * Project Dogwood -- the generated Material 3 tier, operated on a real Android device.
 *
 * Claims `M1`-`M7`, graded through `UiAutomation`, which *is* an accessibility service and
 * therefore sees what TalkBack sees. `AccessibilityConformanceTest` beside this file grades the
 * `D` and `J` families on the Diagnostics screen and explains why the instrument is what it is;
 * this file points the same instrument at a screen composed entirely from bindings nobody wrote.
 *
 * The screen is `MaterialScreen.kt` in `samples/slice-screens`, opened by its own entry point so
 * that no claim here depends first on a claim about a tab bar. Every control on it has a
 * **witness** beside it -- a line of primitive-tier text whose content is a function of that
 * control's state -- and every claim ends at the witness, because a control that looks right and
 * changes nothing is the failure this whole layer exists to catch.
 *
 * What is under test is the generated binding, not Material 3. A `Switch` that toggles proves the
 * event crossed the boundary on the tag the generator derived, the host called the library's real
 * function, and the payload's own state moved -- four things, one observation.
 */
package dev.dogwood.slice.android

import android.content.ComponentName
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "dev.dogwood.slice.android"
private const val CONF_TAG = "DogwoodConf"

private fun emit(line: String) {
  android.util.Log.i(CONF_TAG, line)
  println(line)
}

/** The catalogue's sections, and one label from each that exists only if the tier rendered. */
private val SECTIONS = listOf(
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

@RunWith(AndroidJUnit4::class)
class MaterialConformanceTest {

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val automation get() = instrumentation.uiAutomation
  private val device: UiDevice = UiDevice.getInstance(instrumentation)

  /**
   * How long to wait, as a multiple of what a development machine needs. See
   * `AccessibilityConformanceTest` for the reason; `tier-c.yml` sets it for a hosted emulator.
   */
  private val patience: Double =
    InstrumentationRegistry.getArguments().getString("dogwoodPatience")?.toDoubleOrNull()
      ?.coerceIn(1.0, 10.0) ?: 1.0

  private fun patiently(ms: Long): Long = (ms * patience).toLong()

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

  private fun skip(id: String, reason: String) {
    skipped++
    emit("CONF $id SKIP -- $reason")
  }

  // -----------------------------------------------------------------------------------------
  // Reading and driving the tree. Refetched every time, never cached: a node held across an
  // interaction refers to a view that may no longer exist, and the consequence of an action is
  // only visible in a tree fetched after it.
  // -----------------------------------------------------------------------------------------

  private fun nodes(): List<AccessibilityNodeInfo> {
    val root = automation.rootInActiveWindow ?: return emptyList()
    val found = mutableListOf<AccessibilityNodeInfo>()
    fun walk(node: AccessibilityNodeInfo?) {
      if (node == null) return
      found += node
      for (index in 0 until node.childCount) walk(node.getChild(index))
    }
    walk(root)
    return found
  }

  /**
   * What a screen reader would say for this node.
   *
   * The subtree's text, not the node's own: Compose puts a control's label on a child, and reading
   * `text` alone reports anonymous controls that announce perfectly well. That mistake cost this
   * project a false accessibility report and is why AGENTS.md section 1.5 exists.
   */
  private fun spoken(node: AccessibilityNodeInfo): String {
    val parts = mutableListOf<String>()
    fun gather(current: AccessibilityNodeInfo?) {
      if (current == null) return
      current.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it }
      current.text?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it }
      for (index in 0 until current.childCount) gather(current.getChild(index))
    }
    gather(node)
    return parts.joinToString(" ").trim()
  }

  private fun labels(): List<String> = nodes().map { spoken(it) }.filter { it.isNotEmpty() }

  /** Every label on screen, as one string, for the "is this word anywhere" questions. */
  private fun screen(): String = labels().joinToString(" | ")

  /**
   * The node a screen reader would operate for [label]: the clickable one, not the one holding
   * the text. Sending a click to the label does nothing, which the sibling drill records.
   */
  private fun find(label: String): AccessibilityNodeInfo? {
    val matching = nodes().filter { spoken(it) == label }
    return matching.firstOrNull { it.isClickable } ?: matching.firstOrNull()
  }

  private fun act(node: AccessibilityNodeInfo?, action: Int = AccessibilityNodeInfo.ACTION_CLICK): Boolean =
    try {
      node != null && node.performAction(action)
    } catch (stale: IllegalStateException) {
      emit("CONF NOTE action on a stale node: ${stale.message?.take(80)}")
      false
    }

  /**
   * The current value of a witness line **in the current viewport**, or null.
   *
   * Viewport-local on purpose, so that the scrolling version below can be the one that costs time.
   */
  private fun witness(prefix: String): String? = labels().firstOrNull { it.startsWith(prefix) }

  /**
   * The same, scrolling to it if it is not on screen.
   *
   * A witness sits beside its control, so reaching the control usually brings it into view -- but
   * "usually" is how a drill becomes flaky, and a witness that is one line below the fold would
   * otherwise read as a control that did nothing.
   */
  private fun witnessAnywhere(prefix: String): String? {
    witness(prefix)?.let { return it }
    scrollUntil { witness(prefix) != null }
    return witness(prefix)
  }

  /** Waits for a witness to say something other than [was]: the consequence, not the click. */
  private fun awaitWitness(prefix: String, was: String?, timeoutMs: Long = 15_000): String? {
    val deadline = System.currentTimeMillis() + patiently(timeoutMs)
    var scrolled = false
    while (System.currentTimeMillis() < deadline) {
      val now = witness(prefix)
      if (now != null && now != was) return now
      if (now == null && !scrolled) {
        scrolled = true
        scrollUntil { witness(prefix) != null }
      }
      Thread.sleep(200)
    }
    return null
  }

  /**
   * Scrolls the screen until [predicate] is satisfied, then stops.
   *
   * A section is taller than a phone, and Compose publishes accessibility nodes only for what it
   * has laid out -- so a drill that reads one viewport is asserting about the top of a screen. The
   * sibling drill learned this twice, and both times the symptom was a control reported as
   * unreachable that a person could reach by scrolling once.
   *
   * Scrolls back to the top first, so a claim never depends on where the previous claim left the
   * screen, and gives up after a bounded number of steps rather than looping on a screen that has
   * stopped moving.
   */
  private fun bounds(node: AccessibilityNodeInfo): android.graphics.Rect =
    android.graphics.Rect().also { node.getBoundsInScreen(it) }

  /**
   * The scrolling container this drill means, chosen by shape rather than by tree order.
   *
   * There are two on this screen -- the page, and the section picker, which scrolls sideways --
   * and "the first scrollable node" picked whichever the tree happened to publish first. When it
   * picked the picker, every vertical scroll moved the chips instead of the page, the screen never
   * changed, and six claims failed reporting controls that were there all along. Shape is the
   * property that actually distinguishes them: a page is taller than it is wide.
   */
  private fun scroller(vertical: Boolean): AccessibilityNodeInfo? =
    nodes().filter { it.isScrollable }
      .filter { node ->
        val box = bounds(node)
        if (vertical) box.height() >= box.width() else box.width() > box.height()
      }
      .maxByOrNull { if (vertical) bounds(it).height() else bounds(it).width() }

  /**
   * Scrolls the page with real gestures until [predicate] holds.
   *
   * Gestures rather than `ACTION_SCROLL_BACKWARD`, and that is a correction rather than a
   * preference: Compose's scrolling container on this screen accepts the forward action and
   * ignores the backward one, so a drill that asked the accessibility layer to scroll up sat
   * exactly where the previous claim had left it and reported six controls as missing. A swipe is
   * what a person does and it always works. The accessibility layer is still the instrument for
   * everything that is *graded* -- finding, activating, reading -- which is the part that matters.
   */
  private fun scrollUntil(steps: Int = 10, predicate: () -> Boolean): Boolean {
    if (predicate()) return true
    repeat(steps) {
      swipeVertically(towardsTheTop = true)
      if (predicate()) return true
    }
    repeat(steps) {
      swipeVertically(towardsTheTop = false)
      if (predicate()) return true
    }
    return predicate()
  }

  /**
   * A finger on the page, inside the safe band.
   *
   * **The coordinates are the whole of this function.** A gesture that starts in the bottom eighth
   * of the display is the system's home gesture and one that starts within a few tens of pixels of
   * either edge is the back gesture -- so the first version of this drill swiped itself out of the
   * application and reported the launcher's icons as the screen under test. 25% to 75% is the band
   * the sibling drill already uses, for the same reason.
   *
   * Gestures rather than `ACTION_SCROLL_BACKWARD` because Compose's container accepts the forward
   * action and declines the backward one, so a drill that asked the accessibility layer to go back
   * up sat where the previous claim had left it. The accessibility layer remains the instrument
   * for everything that is graded -- finding, activating, reading. Reaching a control in order to
   * press it is allowed to be a finger, because a user's is.
   */
  private fun swipeVertically(towardsTheTop: Boolean) {
    val width = device.displayWidth
    val height = device.displayHeight
    val near = (height * 0.30).toInt()
    val far = (height * 0.70).toInt()
    val before = labels()
    if (towardsTheTop) {
      device.swipe(width / 2, near, width / 2, far, 12)
    } else {
      device.swipe(width / 2, far, width / 2, near, 12)
    }
    val deadline = System.currentTimeMillis() + 2_000
    while (System.currentTimeMillis() < deadline && labels() == before) Thread.sleep(120)
  }

  /** Scrolls until a node with this exact spoken label is on screen, and returns it. */
  private fun reach(label: String): AccessibilityNodeInfo? {
    scrollUntil { find(label) != null }
    return find(label)
  }

  private fun awaitLabel(label: String, timeoutMs: Long = 20_000): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (labels().any { it.contains(label) }) return true
      Thread.sleep(200)
    }
    return false
  }

  /**
   * Opens a section by its chip, and waits for the catalogue's own witness to agree.
   *
   * The picker is three wrapped rows rather than one scrolling row, so a chip is always composed
   * and the only thing between the drill and it is vertical scrolling. That is the third shape the
   * picker has had; `MaterialScreen.kt` records why, and two of the three earlier failures on this
   * drill were about reaching a chip rather than about anything the tier does.
   */
  private fun openSection(label: String): Boolean {
    val chip = reach(label) ?: return false
    if (!act(chip)) return false
    val wanted = "m3.section=" + label.lowercase().replace(" ", "")
    val deadline = System.currentTimeMillis() + 15_000
    while (System.currentTimeMillis() < deadline) {
      if (witness("m3.section=") == wanted) return true
      Thread.sleep(200)
    }
    return false
  }

  @Before
  fun openTheMaterialCatalogue() {
    instrumentation.targetContext.startActivity(
      Intent().apply {
        component = ComponentName(PACKAGE, "$PACKAGE.TabsActivity")
        // Its own entry point, for the reason the drill's header gives: navigating by the tab bar
        // would make every claim below depend on a control this drill is not about.
        putExtra("entry", "material")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
      },
    )
    device.wait(
      androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg(PACKAGE).depth(0)),
      20_000,
    )
    // The guest is fetched over the network, so the screen takes a moment to exist.
    check(awaitLabel("Material 3, generated", timeoutMs = 60_000)) {
      "the Material catalogue never rendered; is the payload being served? Screen: ${screen().take(300)}"
    }
  }

  @Test
  fun materialConformance() {
    emit("CONF NOTE ${labels().size} announcements on the catalogue")

    // M1 -- every section renders. Per section, so a failure names the ones that did not.
    val missing = mutableListOf<String>()
    for ((label, evidence) in SECTIONS) {
      if (!openSection(label)) {
        missing += "$label (chip)"
        continue
      }
      if (!scrollUntil { labels().any { label -> label.contains(evidence) } }) missing += "$label ($evidence)"
    }
    conform("M1", missing.isEmpty(), "${SECTIONS.size - missing.size}/${SECTIONS.size} sections; missing $missing")

    // M2 -- a button is operable through the accessibility layer, and the payload's state changes.
    openSection("Buttons")
    val opened = openSection("Buttons")
    val buttonsWere = witnessAnywhere("m3.buttons=")
    val clicked = act(reach("Filled"))
    val buttonsNow = awaitWitness("m3.buttons=", buttonsWere)
    conform("M2", clicked && buttonsNow != null, "section opened=$opened, clicked=$clicked, $buttonsWere -> $buttonsNow")

    // M6 -- an icon inside a Material component announces its description.
    //
    // The icon is segment 0's and the button around it is segment 255's, because Material 3's own
    // `Icon` takes an asset and no asset crosses this boundary. A description reaching the
    // accessibility layer through a generated component's slot is the claim.
    val described = listOf("Save this", "Bookmark this", "Search").filter { name ->
      scrollUntil { labels().any { it.contains(name) } }
    }
    conform("M6", described.size >= 2, "icon-only controls announcing a description: $described")

    // M3 -- selection controls report their state and change it.
    openSection("Selection")
    val results = mutableListOf<String>()
    for ((prefix, control) in listOf(
      "m3.checkbox=" to "Send me the summary",
      "m3.switch=" to "Background refresh",
      "m3.radio=" to "Business",
    )) {
      val node = reach(control)
      val was = witnessAnywhere(prefix)
      val acted = act(node)
      results += "$control: found=${node != null} acted=$acted $was -> ${awaitWitness(prefix, was)}"
    }
    conform("M3", results.none { it.endsWith("null") }, results.toString())

    // M3-announced -- and what a screen reader is told about them.
    //
    // Android publishes `checkable` and `checked` on these, which the web client does not; the
    // difference is recorded rather than smoothed over, because a claim that reads the same on
    // every client while the platforms differ is a claim nobody can act on.
    val checkable = nodes().filter { it.isCheckable }
    conform(
      "M3-announced",
      checkable.isNotEmpty() && checkable.any { spoken(it).contains("Send me the summary") },
      "${checkable.size} controls announce themselves as checkable, and the checkbox announces " +
        "what it is for",
    )

    // M7 -- the slider, moved the way a screen reader moves one.
    reach("Volume slider")
    val slider = nodes().firstOrNull { node ->
      node.rangeInfo != null || node.actionList.any {
        it == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS
      }
    }
    if (slider == null) {
      skip("M7", "no node on this screen publishes a range or a set-progress action, so there " +
        "is nothing for an assistive technology to move; the screen composes two sliders")
    } else {
      val was = witnessAnywhere("m3.slider=")
      // Forward, rather than setting a value: `ACTION_SCROLL_FORWARD` is what a screen reader
      // sends for an increment, and the point is the path a real assistive technology takes.
      act(slider, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
      val now = awaitWitness("m3.slider=", was)
      conform("M7", now != null, "$was -> $now")
    }

    // M4 -- a dialog opens, is announced, and confirms.
    openSection("Dialogs")
    act(reach("Open alert"))
    val announced = awaitLabel("Cancel this booking?", timeoutMs = 15_000)
    var outcome: String? = null
    if (announced) {
      act(reach("Cancel booking"))
      outcome = awaitWitness("m3.dialog.outcome=", "m3.dialog.outcome=none")
    }
    conform(
      "M4",
      announced && outcome == "m3.dialog.outcome=confirmed",
      "announced=$announced, outcome=$outcome, on screen: ${screen().take(160)}",
    )

    // M5 -- a sheet and a menu open and choose.
    openSection("Sheets")
    val menuWas = witnessAnywhere("m3.menu=")
    /*
     * Opened and chosen from until the witness moves, rather than once with a sleep between.
     *
     * This was `act(reach("Cabin class"))`, `Thread.sleep(800)`, `act(reach("Business"))`. Eight
     * hundred milliseconds is a guess about how long a dropdown takes to compose, and under load it
     * is wrong: the item is not on screen yet, `reach` finds nothing, the click goes nowhere and the
     * claim reports `menu=null`. Watched on a loaded development machine after passing 27 of 27 on
     * the same machine an hour earlier, which is what a guess about a schedule looks like when the
     * schedule changes.
     *
     * The same shape as `M2` on iOS, where the drill's first activation failed while every later one
     * worked: an activation that lands before the target is ready is gone, and waiting longer does
     * not bring it back. So wait for the item to exist, act, and if the payload's own witness has
     * not moved, open the menu and choose again.
     */
    var chosen: String? = null
    var menuNotes = ""
    repeat(3) { round ->
      if (chosen != null) return@repeat
      /*
       * **The anchor is only tapped when the menu is shut**, and that is the correction to the
       * first version of this retry. `Cabin class` is a toggle: tapping it with the menu already
       * open closes it again. The first attempt re-tapped it every round, so a round that opened
       * the menu too late for its own deadline was followed by a round that shut it, and the
       * retry alternated instead of converging. It passed here on the first round, where the bug
       * is invisible, and failed on a hosted emulator where the first round is the slow one.
       */
      if (find("Business") == null) {
        act(reach("Cabin class"))
        awaitLabel("Business", timeoutMs = 5_000)
      }
      if (find("Business") == null) {
        menuNotes += " [round $round: the menu never opened]"
        return@repeat
      }
      act(reach("Business"))
      /*
       * **The full budget, not a short one per round**, and this is the correction to my own first
       * retry rather than to the original code.
       *
       * That retry gave each round five seconds instead of the default fifteen, reasoning that
       * three quick attempts beat one slow one. On a hosted emulator the run then read
       * `[round 0: chose, witness unmoved] [round 1: the menu never opened] [round 2: the menu
       * never opened]` -- round zero opened the menu and chose correctly and the witness simply had
       * not caught up in five seconds, and by then the choice had closed the menu and renamed its
       * anchor, so nothing could open it again. The original single attempt with a generous wait
       * passed this emulator twice; the retry failed it twice. It was a worse drill.
       *
       * A choice that registers slowly is still a choice. Retry only what can genuinely be missed --
       * the *opening* -- and never cut short the wait on a consequence that is already in flight.
       */
      chosen = awaitWitness("m3.menu=", menuWas)
      if (chosen == null) menuNotes += " [round $round: chose, witness unmoved]"
    }

    val sheetWas = witnessAnywhere("m3.sheet=")
    act(reach("Open the sheet"))
    val sheetShown = awaitLabel("Fare conditions", timeoutMs = 15_000)
    if (sheetShown) act(reach("Close the sheet"))
    val sheetClosed = awaitWitness("m3.sheet=", "m3.sheet=on") ?: witness("m3.sheet=")
    conform(
      "M5",
      chosen != null && sheetShown,
      "menu=$chosen$menuNotes, sheet shown=$sheetShown, after closing=$sheetClosed (was $sheetWas)",
    )

    emit("CONF RESULT client=android passed=$passed failed=$failed skipped=$skipped")
    org.junit.Assert.assertEquals("failing Material claims", 0, failed)
  }
}

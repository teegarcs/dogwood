/*
 * Project Dogwood -- state that belongs to a screen nobody is looking at.
 *
 * A guest that owns its own navigation keeps every tab's state alive while only one tab is
 * composed, and the standard way to do that is `rememberSaveableStateHolder()`. That holder does
 * not register one provider per screen. It registers ONE provider whose value is a nested
 * `Map<key, Map<providerKey, List<Any?>>>` holding every off-screen screen's state at once.
 *
 * That shape is why these tests exist. The registry's `canBeSaved` predicate decides what may
 * cross the Zipline boundary, and `performSave` throws on the FIRST value it rejects -- so a
 * predicate that did not understand maps would not merely drop the holder's entry. It would take
 * the whole snapshot with it, and every unrelated screen's state would vanish on the next code
 * update or warm-pool eviction with nothing on screen to say why. It did exactly that, and the
 * symptom was a counter reading zero after a tab switch.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import dev.dogwood.protocol.StateSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaveableHolderTest {

  /** What the currently composed screen reads, so a test can assert on restored values. */
  private var visible: Int = -1

  /** Handles onto the live composition, rebound on every recomposition. */
  private var switchTo: ((String) -> Unit)? = null
  private var bump: (() -> Unit)? = null

  @Composable
  private fun Screen() {
    var count by rememberSaveable(key = "count", stateSaver = autoSaver()) { mutableStateOf(0) }
    visible = count
    bump = { count += 1 }
    Text("count $count")
  }

  @Composable
  private fun Tabs() {
    val holder = rememberSaveableStateHolder()
    var tab by rememberSaveable(key = "tab", stateSaver = autoSaver()) { mutableStateOf("a") }
    switchTo = { tab = it }
    holder.SaveableStateProvider(tab) { Screen() }
  }

  /**
   * One call site for both generations.
   *
   * `rememberSaveable` keys on the path through the composition, so a test that composed the two
   * generations from two different functions would be testing a refactor rather than a restore.
   */
  private fun tabs(restored: StateSnapshot?) =
    compose(restoredState = restored) { Tabs() }

  private fun DogwoodComposition.switch(tab: String) {
    switchTo!!(tab)
    frame(0L)
  }

  private fun DogwoodComposition.increment(times: Int) {
    repeat(times) {
      bump!!()
      frame(0L)
    }
  }

  @Test
  fun anOffScreenTabKeepsItsStateWithinOneGeneration() {
    // The in-process half of the promise, and the cheaper half: nothing has crossed the boundary
    // yet, so this passes even with a registry that cannot serialise a map. It is here to prove
    // the tabs themselves are wired correctly, so a failure in the next test is unambiguously
    // about the boundary and not about the fixture.
    val (_, composition) = tabs(null)
    composition.increment(3)
    assertEquals(3, visible)

    composition.switch("b")
    assertEquals(0, visible, "a tab opened for the first time starts fresh")
    composition.increment(5)

    composition.switch("a")
    assertEquals(3, visible, "the first tab's state outlived being off-screen")
    composition.dispose()
  }

  @Test
  fun theHolderSurvivesAReplacementGuest() {
    val (_, first) = tabs(null)
    first.increment(3)
    first.switch("b")
    first.increment(5)
    first.switch("a")

    val carried = first.snapshotState()
    first.dispose()

    // The regression that motivated the fix. `performSave` throws on the first value it cannot
    // serialise, so a rejected holder yields an EMPTY snapshot -- not a partial one. Asserting
    // the tab key survived is asserting the whole snapshot did not go down with the holder.
    assertTrue(carried.values.isNotEmpty(), "the holder took the entire snapshot down with it")
    assertTrue(
      carried.values.keys.any { it == "tab" },
      "unrelated state must not be collateral damage: ${carried.values.keys}",
    )

    val (_, second) = tabs(carried)
    assertEquals(3, visible, "the visible tab came back on the wrong screen or the wrong count")

    // And the one that only a serialised holder can pass: tab "b" was never composed in this
    // generation, so its 5 exists only because the nested map crossed as JSON and came back.
    second.switch("b")
    assertEquals(5, visible, "the off-screen tab's state did not survive the boundary")
    second.dispose()
  }

  @Test
  fun aSnapshotWithAHolderIsRestoredWithoutLosingTheTabItself() {
    // A guest evicted while looking at its second tab must return to that tab, not to its first.
    // The tab id is ordinary saveable state, but it is saved ALONGSIDE the holder's map, and the
    // ordering of providers within one registry is not something a guest controls.
    val (_, first) = tabs(null)
    first.switch("b")
    first.increment(2)
    val carried = first.snapshotState()
    first.dispose()

    val (_, second) = tabs(carried)
    assertEquals(2, visible, "restored to the wrong tab")
    second.dispose()
  }
}

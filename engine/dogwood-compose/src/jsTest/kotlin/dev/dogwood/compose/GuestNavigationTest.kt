/*
 * Project Dogwood -- Path A, as tests: navigation the guest owns.
 *
 * The claim being pinned is a cost claim, so these are cost tests. When a product's screens are
 * one surface, navigating between them should be a recomposition and nothing more -- no teardown,
 * no cold start, no delivery work, and a change batch proportional to what actually changed on
 * screen rather than to the size of the screen.
 *
 * The contrast is Path B, where each surface is its own runtime: correct when a second team or a
 * hard isolation boundary is real, and a full cold start per switch when it is not.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.PropertySet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A shell shaped like the sample's: shared state above the navigation, screens below it. */
private class Harness {
  var tab by mutableStateOf("one")

  @Composable
  fun Shell() {
    // Hoisted above the `when`, so every screen sees it -- the shape Path A exists for.
    var shared by rememberSaveable(key = "shared", stateSaver = autoSaver()) { mutableStateOf(0) }
    val screens = rememberSaveableStateHolder()
    Column {
      Text("shared $shared")
      PrimaryButton(label = "add", onClick = { shared += 1 })
      screens.SaveableStateProvider(tab) {
        when (tab) {
          "two" -> ScreenTwo()
          else -> ScreenOne()
        }
      }
    }
  }

  @Composable
  private fun ScreenOne() {
    var typed by rememberSaveable(key = "typed", stateSaver = autoSaver()) { mutableStateOf("") }
    Column {
      Text("one:$typed")
      PrimaryButton(label = "type", onClick = { typed += "x" })
    }
  }

  @Composable
  private fun ScreenTwo() {
    Text("two")
  }
}

private fun RecordingHost.texts(): List<String> =
  decoded().flatMap { it.g }.filterIsInstance<PropertySet>()
    .filter { it.p.value == 1 }
    .map { it.v.toString().trim('"') }

class GuestOwnedNavigationTest {

  @Test
  fun switchingScreensCostsOneBatch() {
    // The headline. Path B pays a QuickJS cold start here -- Phase 0 measured ~127 ms p50 to first
    // composition on a development machine. Path A pays one batch.
    val harness = Harness()
    val (host, composition) = compose { harness.Shell() }
    val before = host.batches.size

    harness.tab = "two"
    composition.frame(0L)

    assertEquals(before + 1, host.batches.size, "a screen change must be one batch")
    assertTrue("two" in host.texts(), "and the new screen must be on it: ${host.texts()}")
  }

  @Test
  fun aSwitchCrossesTheDifferenceNotTheScreen() {
    // The shell's own nodes are untouched by navigation, so they must not re-cross. If they did,
    // every switch would cost the whole screen and the cheap path would not be cheap.
    val harness = Harness()
    val (host, composition) = compose { harness.Shell() }
    val settled = host.batches.size

    harness.tab = "two"
    composition.frame(0L)

    val changes = host.decoded().drop(settled).flatMap { it.g }
    val creates = changes.filterIsInstance<Create>()
    assertTrue(creates.size <= 2, "only the arriving screen should be created; got ${creates.size}")
    assertTrue(
      changes.none { it is PropertySet && it.v.toString().contains("shared") },
      "the unchanged shell must not re-cross: ${changes.map { it::class.simpleName }}",
    )
  }

  @Test
  fun stateHoistedAboveNavigationIsSharedByEveryScreen() {
    // Across separate experiences this is two heaps and a host service between them. Here it is
    // a variable.
    val harness = Harness()
    val (host, composition) = compose { harness.Shell() }
    val button = host.decoded().first().g.filterIsInstance<Create>()
      .first { it.w == Tags.PrimaryButton }.i

    composition.sendEvent(
      dev.dogwood.protocol.Event(i = button, e = Tags.OnClick, q = composition.lastSentSequence),
    )
    composition.frame(0L)
    assertTrue("shared 1" in host.texts())

    harness.tab = "two"
    composition.frame(16L)
    harness.tab = "one"
    composition.frame(32L)

    assertTrue(
      host.texts().last { it.startsWith("shared") } == "shared 1",
      "shared state must survive navigation: ${host.texts()}",
    )
  }

  @Test
  fun perScreenStateSurvivesALeaveAndReturn() {
    // `SaveableStateHolder` is `androidx.compose.runtime.saveable`, not Dogwood. A guest gets
    // this for free, which is why Path A needs no navigation subsystem: Compose already has one.
    val harness = Harness()
    val (host, composition) = compose { harness.Shell() }
    val typeButton = host.decoded().first().g.filterIsInstance<Create>()
      .filter { it.w == Tags.PrimaryButton }[1].i

    composition.sendEvent(
      dev.dogwood.protocol.Event(i = typeButton, e = Tags.OnClick, q = composition.lastSentSequence),
    )
    composition.frame(0L)
    assertTrue("one:x" in host.texts())

    harness.tab = "two"
    composition.frame(16L)
    harness.tab = "one"
    composition.frame(32L)

    assertTrue(
      host.texts().last { it.startsWith("one:") } == "one:x",
      "a screen's own state must come back with it: ${host.texts()}",
    )
  }

  @Test
  fun navigatingDoesNotAskTheHostForAnythingButFrames() {
    // No service call, no navigation event, no reload. The host is not involved in Path A's
    // navigation at all -- which is exactly why it costs nothing.
    val harness = Harness()
    val (host, composition) = compose { harness.Shell() }
    val unknownBefore = host.unknownNodes

    harness.tab = "two"
    composition.frame(0L)
    harness.tab = "one"
    composition.frame(16L)

    assertEquals(unknownBefore, host.unknownNodes, "navigation must not produce stray events")
  }
}

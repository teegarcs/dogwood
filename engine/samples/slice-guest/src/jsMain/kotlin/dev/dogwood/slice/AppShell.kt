/*
 * Project Dogwood -- Path A: one experience, many screens, navigation owned by the guest.
 *
 * The reference for the common case, and the point is how little there is to it. There is no
 * Dogwood navigation machinery here because none is needed: the tab bar is guest Compose, the
 * screen choice is a `when`, and switching is a recomposition. One QuickJS runtime, one
 * composition, one payload.
 *
 * Three things this buys that Path B (an experience per surface) cannot, and they are the reason
 * to reach for this shape first:
 *
 *   - **State is shared for free.** `saved` below is hoisted above the `when`, so every screen
 *     sees the same value and it survives navigation. Across separate experiences the same thing
 *     is two heaps and a host service between them.
 *   - **Switching costs nothing at runtime.** No teardown, no cold start, no delivery work -- one
 *     recomposition, and the guest test in `AppShellTest` pins it at exactly one change batch.
 *   - **Per-screen state survives too, using ordinary Compose.** `SaveableStateHolder` is
 *     `androidx.compose.runtime.saveable`, not Dogwood: it keeps each screen's `rememberSaveable`
 *     state while that screen is off the composition, so a scroll position and a half-typed filter
 *     are still there when the user comes back. Nothing in this file knows it is running inside a
 *     sandbox.
 *
 * Compare `samples/slice-android`'s host-native tab bar, which is Path B: an experience per tab,
 * isolated by construction, and worth its cost only when a second team, a second release cadence,
 * or a hard isolation requirement is actually present.
 */
package dev.dogwood.slice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import dev.dogwood.compose.Chip
import dev.dogwood.compose.Column
import dev.dogwood.compose.Divider
import dev.dogwood.compose.HorizontalList
import dev.dogwood.compose.Modifier
import dev.dogwood.compose.Row
import dev.dogwood.compose.Text
import dev.dogwood.compose.TextValue
import dev.dogwood.compose.fillMaxWidth
import dev.dogwood.compose.padding
import dev.dogwood.compose.services
import dev.dogwood.compose.size
import dev.dogwood.compose.Spacer

private val TABS = listOf("explore" to "Explore", "feed" to "Feed", "about" to "Diagnostics")

@Composable
fun AppShell(params: ExploreParams) {
  // Saveable, so the tab the user was on survives a code update as well as a recomposition.
  var tab by rememberSaveable(key = "tab", stateSaver = autoSaver()) { mutableStateOf("explore") }

  // Shared state, hoisted above the navigation. This is the whole of Path A's advantage: it is
  // ordinary Compose state in one composition, so every screen below sees the same value with no
  // protocol, no service, and no host involvement.
  var saved by rememberSaveable(key = "sharedSaved", stateSaver = autoSaver()) { mutableStateOf(0) }

  // Ordinary Compose, from `androidx.compose.runtime.saveable`. It retains each screen's own
  // `rememberSaveable` state while that screen is not composed, which is what makes a tab switch
  // feel like a tab switch rather than a reload.
  val screenState = rememberSaveableStateHolder()
  val host = services()

  Column(modifier = Modifier.fillMaxWidth()) {
    Row(modifier = Modifier.fillMaxWidth().padding(8)) {
      Text("Path A · one experience", style = "labelLarge")
      Spacer(modifier = Modifier.size(8))
      // Visible from every tab, because there is only one composition.
      Text("· $saved saved", style = "labelSmall")
    }

    HorizontalList(spacingDp = 8, contentPaddingDp = 8) {
      for ((id, label) in TABS) {
        Chip(
          text = label,
          selected = id == tab,
          onSelectedChange = { chosen ->
            if (chosen) {
              tab = id
              host.track("app.tab", mapOf("tab" to id))
            }
          },
        )
      }
    }

    Divider(modifier = Modifier.fillMaxWidth())

    // The navigation. A `when`, and nothing else.
    screenState.SaveableStateProvider(tab) {
      when (tab) {
        "feed" -> FeedScreen()
        "about" -> AboutScreen()
        else -> ExploreScreen(params, onSaved = { saved += 1 })
      }
    }
  }
}

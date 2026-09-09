/*
 * Project Dogwood -- the vertical slice's entry point.
 *
 * The manifest's `mainFunction` names this. Its whole job is to bind one `DogwoodGuestUi` under a
 * name the host takes, and to declare which experiences this payload offers: the entry-point
 * contract of ADR-004 section 2.5 in its Phase 4 form
 * (adrs/layer-5/ADR-013-host-services-and-entry-points.md).
 *
 * Two entry points rather than one, because one would not demonstrate the contract. A host names
 * the experience it wants and hands it launch parameters; a name this payload does not offer is
 * reported to the host with the names it does, rather than rendering nothing.
 */
package dev.dogwood.slice

import app.cash.zipline.Zipline
import dev.dogwood.compose.DogwoodGuest
import dev.dogwood.protocol.DogwoodGuestUi
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.Serializable

private val zipline by lazy { Zipline.get() }

/*
 * `ExploreParams` and `exploreParams` moved to `slice-screens`: a launch parameter is part of
 * what a screen needs, not part of how a payload was delivered, and leaving them here kept the
 * screens from being shared with any other entry point.
 */

@OptIn(ExperimentalJsExport::class)
@JsExport
fun main() {
  zipline.bind<DogwoodGuestUi>(
    name = "dogwood.guest",
    // State is declared with `rememberSaveable` inside each screen, so it survives a code update.
    instance = DogwoodGuest(
      "explore" to { params -> ExploreScreen(exploreParams(params)) },
      "about" to { _ -> AboutScreen() },
      // Ten thousand rows, of which about twenty ever cross the boundary.
      "feed" to { _ -> FeedScreen() },
      // Path A: one experience whose tab bar and navigation are guest Compose.
      "app" to { params -> AppShell(exploreParams(params)) },
      // A payload that fails on purpose, so that the two mechanisms built for a bad publish
      // -- a readable crash (ADR-059) and the crash-loop quarantine (ADR-049) -- can be graded
      // against a real failure instead of a simulated one. See `CrashScreen.kt`.
      "crash" to { _ -> CrashScreen() },
    ),
  )
}

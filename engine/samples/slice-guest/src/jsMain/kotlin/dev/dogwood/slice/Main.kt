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

/**
 * What the host may hand this payload at launch.
 *
 * Raw data, decoded here, because the host cannot construct guest types — it ships months apart
 * from this code and has never seen this class. Unknown keys are ignored on purpose: a host that
 * learns to send a new parameter must not break a payload that predates it.
 */
@Serializable
data class ExploreParams(
  val city: String = "Tokyo",
  val country: String = "Japan",
  /**
   * Where this client's data lives.
   *
   * The guest cannot know this. `10.0.2.2` on an Android emulator and `localhost` on a desktop
   * are the same machine reached by different names, and only the host knows which it is. A
   * payload that hard-coded either would work on exactly one of them.
   */
  val apiBaseUrl: String = "",
)

private val launchJson = Json { ignoreUnknownKeys = true }

internal fun exploreParams(raw: JsonElement): ExploreParams =
  runCatching { launchJson.decodeFromJsonElement(ExploreParams.serializer(), raw) }
    .getOrElse { ExploreParams() }

@OptIn(ExperimentalJsExport::class)
@JsExport
fun main() {
  zipline.bind<DogwoodGuestUi>(
    name = "dogwood.guest",
    // State is declared with `rememberSaveable` inside each screen, so it survives a code update.
    instance = DogwoodGuest(
      "explore" to { params -> ExploreScreen(exploreParams(params)) },
      "about" to { _ -> AboutScreen() },
    ),
  )
}

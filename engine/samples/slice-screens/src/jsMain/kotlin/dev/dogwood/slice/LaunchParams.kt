/*
 * Project Dogwood -- what the host may hand this payload at launch.
 *
 * It lived in `slice-guest`'s `Main.kt`, which was the Zipline entry point, so the screens that
 * *use* it could not be shared with any other entry point. It belongs with the screens: a launch
 * parameter is part of what a screen needs, not part of how a payload was delivered.
 */
package dev.dogwood.slice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Raw data, decoded by the guest, because the host cannot construct guest types — it ships months
 * apart from this code and has never seen this class. Unknown keys are ignored on purpose: a host
 * that learns to send a new parameter must not break a payload that predates it.
 */
@Serializable
data class ExploreParams(
  val city: String = "Tokyo",
  val country: String = "Japan",
  /**
   * Where this client's data lives.
   *
   * The guest cannot know this. `10.0.2.2` on an Android emulator and `localhost` on a desktop are
   * the same machine reached by different names, and only the host knows which it is. A payload
   * that hard-coded either would work on exactly one of them.
   */
  val apiBaseUrl: String = "",
)

private val launchJson = Json { ignoreUnknownKeys = true }

fun exploreParams(raw: JsonElement): ExploreParams =
  runCatching { launchJson.decodeFromJsonElement(ExploreParams.serializer(), raw) }
    .getOrElse { ExploreParams() }

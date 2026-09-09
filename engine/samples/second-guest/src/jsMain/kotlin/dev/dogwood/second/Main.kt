/*
 * Project Dogwood -- the second team's payload.
 *
 * One screen, one marker, and nothing else. What it exists to demonstrate is entirely on the host
 * side: two payloads, published on their own schedules with their own release identities, running
 * side by side in one application. See `docs/multi-team.md`.
 *
 * The marker is load-bearing. `two-payloads` reads it off the render transcript to prove that *this*
 * payload composed, rather than that a payload did -- and with two live guests in one process, "a
 * screen appeared" is not a sentence that identifies which one.
 */
package dev.dogwood.second

import app.cash.zipline.Zipline
import dev.dogwood.compose.Column
import dev.dogwood.compose.DogwoodGuest
import dev.dogwood.compose.Modifier
import dev.dogwood.compose.Text
import dev.dogwood.compose.fillMaxWidth
import dev.dogwood.compose.padding
import dev.dogwood.protocol.DogwoodGuestUi
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/** What the host looks for to know this payload, specifically, rendered. */
const val SECOND_MARKER: String = "SECOND-PAYLOAD"

private val zipline by lazy { Zipline.get() }

@OptIn(ExperimentalJsExport::class)
@JsExport
fun main() {
  zipline.bind<DogwoodGuestUi>(
    name = "dogwood.guest",
    instance = DogwoodGuest(
      "second" to { _ ->
        Column(modifier = Modifier.fillMaxWidth().padding(16)) {
          Text("The second team's screen", style = "titleMedium")
          Text(SECOND_MARKER, style = "bodyMedium")
        }
      },
    ),
  )
}

/*
 * Project Dogwood -- the vertical slice's entry point.
 *
 * The manifest's `mainFunction` names this. Its whole job is to bind one `DogwoodGuestUi` under
 * a name the host takes: the minimal entry-point contract of ADR-004 section 2.5.
 */
package dev.dogwood.slice

import app.cash.zipline.Zipline
import dev.dogwood.compose.DogwoodGuest
import dev.dogwood.protocol.DogwoodGuestUi
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

private val zipline by lazy { Zipline.get() }

@OptIn(ExperimentalJsExport::class)
@JsExport
fun main() {
  zipline.bind<DogwoodGuestUi>(
    name = "dogwood.guest",
    // State is declared with `rememberSaveable` inside the screen, so it survives a code update.
    instance = DogwoodGuest { ExploreScreen() },
  )
}

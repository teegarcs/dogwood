/*
 * Umbra's payload: one screen, and the entry point that binds it.
 *
 * Everything here is what a product's guest module contains and nothing more: a `main` that names
 * the screens. The screen itself lives in `guest/screens/`, a source directory this module and
 * `:web-guest` both compile, because a product ships ONE set of screens to two transports -- Zipline
 * on mobile, a Web Worker on the web -- and the screens must not know which. The imports there tell
 * the adoption story: `dev.dogwood.compose.*` resolves from a published artifact, and
 * `UmbraBanner`/`UmbraStepper` from stubs the Dogwood plugin generated out of Umbra's own surface.
 */
package dev.umbra.guest

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
    instance = DogwoodGuest(
      "home" to { _ -> HomeScreen() },
    ),
  )
}

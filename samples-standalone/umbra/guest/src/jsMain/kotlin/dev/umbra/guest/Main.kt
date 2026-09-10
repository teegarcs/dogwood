/*
 * Umbra's payload: one screen, and the entry point that binds it.
 *
 * Everything here is what a product's guest module contains and nothing more: screens written
 * against the product's own components plus Dogwood's, and a `main` that names them. The imports
 * tell the adoption story -- `dev.dogwood.compose.*` resolves from a published artifact, and
 * `UmbraBanner`/`UmbraStepper` from stubs the Dogwood plugin generated out of Umbra's own surface.
 */
package dev.umbra.guest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.saveable.rememberSaveable
import app.cash.zipline.Zipline
import dev.dogwood.compose.DogwoodGuest
import dev.dogwood.compose.Modifier
import dev.dogwood.compose.PrimaryButton
import dev.dogwood.compose.Text
import dev.dogwood.compose.TextValue
import dev.dogwood.compose.VerticalList
import dev.dogwood.compose.fillMaxWidth
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

/**
 * The screen the standalone check reads. Its markers are load-bearing: `UMBRA-ALIVE` proves a
 * composition ran, the stepper value proves an event crossed and came back, and the banner proves
 * a component that exists nowhere in the Dogwood repository rendered through a generated binding.
 */
@Composable
private fun HomeScreen() {
  // Saveable for the same reason every screen's state is: a code update while this is open is the
  // normal case, and the count surviving one is what that means concretely.
  var count by rememberSaveable(key = "count", stateSaver = autoSaver()) { mutableStateOf(3) }

  VerticalList(modifier = Modifier.fillMaxWidth(), spacingDp = 8, contentPaddingDp = 16) {
    UmbraBanner(message = TextValue("a product's own banner"), tone = "calm")
    // The marker lives in a `Text`, deliberately: the engine's Text binding details the literal it
    // is about to draw into the render transcript, so the check can demand the exact string. The
    // Umbra bindings above and below prove themselves by their names appearing as lines.
    Text("UMBRA-ALIVE: a payload built outside the Dogwood repository")
    UmbraStepper(value = count, onChange = { count = it })
    // Bound directly to the design system's own composable -- no wrapper exists for this one, and
    // the standalone check requires it in the render transcript for exactly that reason.
    UmbraChip(label = "direct")
    PrimaryButton(
      label = TextValue("UMBRA-BUMP"),
      modifier = Modifier.fillMaxWidth(),
      onClick = { count += 1 },
    )
  }
}

/*
 * Umbra's one screen, shared by both payload builds.
 *
 * Compiled into `:guest` (Zipline, for the mobile and desktop hosts) and into `:web-guest` (a Web
 * Worker, for the page) from this one directory. Neither build is allowed to have a screen the
 * other lacks: if this file and one of the two `main`s ever disagree, one platform is running
 * different product code, which is the thing sharing the directory makes impossible by accident.
 */
package dev.umbra.guest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.saveable.rememberSaveable
import dev.dogwood.compose.Arrangement
import dev.dogwood.compose.Box
import dev.dogwood.compose.Color
import dev.dogwood.compose.FontWeight
import dev.dogwood.compose.Modifier
import dev.dogwood.compose.PrimaryButton
import dev.dogwood.compose.Row
import dev.dogwood.compose.Text
import dev.dogwood.compose.TextValue
import dev.dogwood.compose.VerticalAlignment
import dev.dogwood.compose.VerticalList
import dev.dogwood.compose.border
import dev.dogwood.compose.clickable
import dev.dogwood.compose.fillMaxWidth
import dev.dogwood.compose.padding
import dev.dogwood.compose.size

/**
 * The screen the standalone check reads. Its markers are load-bearing: `UMBRA-ALIVE` proves a
 * composition ran, the stepper value proves an event crossed and came back, and the banner proves
 * a component that exists nowhere in the Dogwood repository rendered through a generated binding.
 */
@Composable
internal fun HomeScreen() {
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
    // An enumeration declared on Umbra's surface and decoded on the host into Umbra's OWN
    // `UmbraTone` -- `@Implementation` on the enumeration -- so the design system's badge is
    // called with the type it was written against and no wrapper exists anywhere.
    UmbraBadge(label = "badge", tone = UmbraTone.Loud)
    // A component that is nobody's: composed here, in the payload, from the primitive tier. No
    // surface entry, no tag, no release. This is the shape most of a design system's compositional
    // components take once the primitives are rich enough (ADR-069), and it is what the standalone
    // check means by "a payload built outside the repository uses the primitive tier".
    UmbraPill(label = "composed in the payload", onClick = { count += 1 })
    PrimaryButton(
      label = TextValue("UMBRA-BUMP"),
      modifier = Modifier.fillMaxWidth(),
      onClick = { count += 1 },
    )
  }
}

/**
 * A pill nobody registered. `clickable`, `border`, per-side `padding`, `spacedBy` and `fontWeight`
 * are all segment-0 primitives, so this composable ships in the payload and updates with it.
 */
@Composable
private fun UmbraPill(label: String, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .border(1, Color(0xFF5C6BC0))
      .padding(horizontal = 10, vertical = 4)
      .clickable(onClick = onClick),
    horizontalArrangement = Arrangement.spacedBy(6),
    verticalAlignment = VerticalAlignment.CenterVertically,
  ) {
    Box(modifier = Modifier.size(8))
    Text(label, fontWeight = FontWeight.Medium)
  }
}

/*
 * Project Dogwood -- the contract-free path: guest logic over the injected environment.
 *
 * Two legitimate ways to make an environment-dependent decision. A design-system token carries a
 * contract -- the name must mean something on the client -- and costs nothing when the theme
 * flips. Guest logic over the environment carries no contract at all, and costs one small message
 * when it does. These tests pin the second path end to end: the decision is real Kotlin, the wire
 * carries only its result, and a theme change re-runs it for exactly one batch.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.PropertySet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val COLOR_PROPERTY = 5
private const val LIGHT_INK = 0xFF223344L
private const val DARK_INK = 0xFFDDEEFFL

class GuestLogicColourTest {

  /** The whole pattern: ordinary Kotlin, reading the injected environment, sending a literal. */
  @Composable
  private fun Swatch() {
    Text(
      "swatch",
      color = if (isSystemInDarkTheme()) Color(DARK_INK) else Color(LIGHT_INK),
    )
  }

  private fun RecordingHost.colours(): List<Long> =
    decoded().flatMap { it.g }.filterIsInstance<PropertySet>()
      .filter { it.p.value == COLOR_PROPERTY }
      .map { (it.v as JsonArray).jsonArray[1].jsonPrimitive.longOrNull ?: -1L }

  @Test
  fun theFirstFrameIsAlreadyInTheRightTheme() {
    // The environment is present before the first composition, not shortly after it -- so guest
    // logic never paints a wrong-theme frame on launch.
    val (dark, _) = compose(configuration = HostEnvironment(darkMode = true)) { Swatch() }
    assertEquals(listOf(DARK_INK), dark.colours())

    val (light, _) = compose(configuration = HostEnvironment(darkMode = false)) { Swatch() }
    assertEquals(listOf(LIGHT_INK), light.colours())
  }

  @Test
  fun aThemeFlipReRunsTheLogicForExactlyOneBatch() {
    // The cost of the contract-free path, measured rather than described: one message, carrying
    // the re-decided literal, when the environment moves. Not one per frame, not a re-send of the
    // screen.
    val (host, composition) = compose(configuration = HostEnvironment(darkMode = false)) { Swatch() }
    val before = host.batches.size

    composition.updateConfiguration(HostEnvironment(darkMode = true))
    composition.frame(0L)

    assertEquals(before + 1, host.batches.size, "a theme flip must cost exactly one batch")
    assertEquals(listOf(LIGHT_INK, DARK_INK), host.colours(), "and carry the re-decided literal")
  }

  @Test
  fun theLiteralCrossesAsALiteralNotAsLogic() {
    // Nothing resembling the `if` goes over the wire. The host receives a finished decision --
    // factory 3, one number -- and never learns how it was made. That is the line this project
    // holds: data crosses, logic does not.
    val (host, _) = compose(configuration = HostEnvironment(darkMode = true)) { Swatch() }
    val recipe = host.decoded().flatMap { it.g }.filterIsInstance<PropertySet>()
      .first { it.p.value == COLOR_PROPERTY }.v as JsonArray
    assertEquals(3, recipe[0].jsonPrimitive.intOrNull, "the literal-colour factory")
    assertEquals(2, recipe.size, "a factory and a value; no conditions, no branches")
  }

  @Test
  fun anUncolouredTextStillSendsNothing() {
    val (host, _) = compose { Text("plain") }
    val tags = host.decoded().flatMap { it.g }.filterIsInstance<PropertySet>().map { it.p.value }
    assertTrue(COLOR_PROPERTY !in tags, "absence stays the host-default sentinel")
  }

  @Test
  fun guestLogicAndTokensMixFreelyOnOneScreen() {
    // The two paths are not modes; they are per-value choices. A screen can token its brand
    // colour and hand-pick its chart colours in the same composition.
    val (host, _) = compose(configuration = HostEnvironment(darkMode = false)) {
      Text("brand", color = Color.token("primary"))
      Text("chart", color = Color(LIGHT_INK))
    }
    val factories = host.decoded().flatMap { it.g }.filterIsInstance<PropertySet>()
      .filter { it.p.value == COLOR_PROPERTY }
      .map { (it.v as JsonArray)[0].jsonPrimitive.intOrNull }
    assertEquals(listOf(4, 3), factories, "a token and a literal, side by side")
  }
}

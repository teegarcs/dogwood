/*
 * Project Dogwood -- the theme document, and what a bad one may not do.
 *
 * A theme arrives from outside the process, so the standard applies: wrong-looking beats crashed,
 * and silently wrong beats neither. The tests are mostly about the failure half, because the
 * success half is one parse — it is the degrade rules that keep a brand rollout from becoming an
 * outage.
 */
package dev.dogwood.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.dogwood.protocol.decodePositional

private const val OCEAN = """
{
  "name": "ocean",
  "light": { "primary": "#006494", "brandGold": "#FFD700" },
  "dark":  { "primary": "#64B5DE" },
  "typography": { "titleLarge": { "sizeSp": 26, "weight": 700 } }
}
"""

class ThemeParseTest {

  @Test
  fun aDocumentRestylesWhatItNamesAndNothingElse() {
    val theme = Theme.fromJson(OCEAN)
    assertEquals(Color(0xFF006494), theme.light.primary)
    assertEquals(Color(0xFF64B5DE), theme.dark.primary)
    // Field-level fallback: everything the document did not mention keeps the built-in value.
    assertEquals(Palette.Light.ink, theme.light.ink)
    assertEquals(Palette.Dark.canvas, theme.dark.canvas)
  }

  @Test
  fun namesBeyondTheCoreThirteenStillResolve() {
    // The vocabulary is not capped by the class: a brand accent the built-in palette never heard
    // of resolves through the same token path the guest already uses.
    val theme = Theme.fromJson(OCEAN)
    assertEquals(Color(0xFFFFD700), theme.light.token("brandGold"))
    assertEquals(null, theme.light.token("brandSilver"), "an undeclared name is still unknown")
  }

  @Test
  fun aBadColourFallsBackByFieldAndIsReported() {
    val problems = mutableListOf<String>()
    val theme = Theme.fromJson(
      """{ "light": { "primary": "teal-ish", "ink": "#111111" } }""",
      onProblem = problems::add,
    )
    // The broken field falls back; the good field in the same document still lands.
    assertEquals(Palette.Light.primary, theme.light.primary)
    assertEquals(Color(0xFF111111), theme.light.ink)
    assertTrue(problems.single().contains("primary"), problems.toString())
  }

  @Test
  fun garbageFallsBackWholesaleAndIsReported() {
    val problems = mutableListOf<String>()
    val theme = Theme.fromJson("not even json {", onProblem = problems::add)
    assertEquals(Theme.Default.name, theme.name)
    assertTrue(problems.isNotEmpty())
  }

  @Test
  fun colourFormsAreExactlyTwo() {
    assertEquals(Color(0xFF0770E3), Theme.parseColor("#0770E3"))
    assertEquals(Color(0x800770E3), Theme.parseColor("#800770E3"))
    assertEquals(null, Theme.parseColor("#FFF"), "shorthand is a guess, and this parser does not guess")
    assertEquals(null, Theme.parseColor("blue"))
  }

  @Test
  fun typographyOverridesApplyOverTheBaseAndTouchOnlyWhatTheyName() {
    val base = Typography(
      displayLarge = TextStyle(fontSize = 40.sp), titleLarge = TextStyle(fontSize = 22.sp),
      titleMedium = TextStyle(fontSize = 16.sp), titleSmall = TextStyle(fontSize = 14.sp),
      bodyLarge = TextStyle(fontSize = 16.sp), bodyMedium = TextStyle(fontSize = 14.sp),
      bodySmall = TextStyle(fontSize = 12.sp), labelLarge = TextStyle(fontSize = 14.sp),
      labelMedium = TextStyle(fontSize = 12.sp), labelSmall = TextStyle(fontSize = 11.sp),
    )
    val themed = Theme.fromJson(OCEAN).applyTo(base)
    assertEquals(26.sp, themed.titleLarge.fontSize, "the named style changes")
    assertEquals(700, themed.titleLarge.fontWeight?.weight)
    assertEquals(16.sp, themed.bodyLarge.fontSize, "an unnamed style does not")
  }
}

class ThemeSwapTest {

  private val ICON = dev.dogwood.protocol.widgetTag(1, 12).value

  /** A generated component whose tint is the token "primary", exactly as a payload sends it. */
  private fun iconTree(): HostTree = HostTree().also {
    it.apply(
      decodePositional("""[1,[[0,1,$ICON],[1,1,1,"flight"],[1,1,4,[4,"primary"]],[3,0,1,1,0]]]"""),
    )
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun swappingTheThemeMidSessionRestylesAPayloadThatNeverChanged() {
    // The whole point of the document: the wire bytes are identical before and after, because
    // the guest only ever said "primary". There is no channel on which this change *could* reach
    // the guest -- the swap is entirely host-side.
    var theme by mutableStateOf(Theme.Default)
    val resolved = mutableListOf<Color>()
    val tree = iconTree()
    runComposeUiTest {
      setContent {
        DogwoodEnvironment(Modifier.size(300.dp), darkMode = false, theme = theme) {
          Box(Modifier.size(200.dp)) {
            resolved.add(tree.root.children(1).single().color(4, Color.Black))
            DogwoodTree(tree, EventSink { _, _, _ -> })
          }
        }
      }
      waitForIdle()
      assertEquals(Palette.Light.primary, resolved.last(), "the built-in brand before the swap")

      theme = Theme.fromJson(OCEAN)
      Snapshot.sendApplyNotifications()
      waitForIdle()
      assertEquals(Color(0xFF006494), resolved.last(), "the document's brand after it")
    }
  }
}

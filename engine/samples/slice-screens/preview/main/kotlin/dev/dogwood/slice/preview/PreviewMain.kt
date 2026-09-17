/*
 * Project Dogwood -- the payload's screens, in a window, with nothing between them and Compose.
 *
 * `plans/close-the-backlog.md` Group 4, step 1: `./gradlew :samples:slice-screens:preview
 * -Pscreen=material` opens the Material catalogue in a Compose Desktop window "rendered by real
 * Compose on the Java Virtual Machine with no protocol, no wire and no host tree between the
 * payload's source and the screen".
 *
 * **What is actually running.** The file `MaterialScreen.kt` compiled into this window is the same
 * file, byte for byte, that `:samples:slice-guest` compiles to JavaScript and ships inside a signed
 * payload. It is compiled twice from one source set, exactly as Layer 1 section 3 describes: the
 * deployment compilation links `dogwood-compose`, whose `Column` records a wire operation, and this
 * compilation links `dogwood-compose-preview`, whose `Column` calls
 * `androidx.compose.foundation.layout.Column`. Nothing is translated at run time and nothing is
 * serialised. There is one Compose runtime in this process and the payload is inside it.
 *
 * **What this window cannot show you, and it is the more important half.** One runtime means no
 * protocol, no batching and no thread hop, so the two failure modes that cost the most on a device
 * are invisible here by construction:
 *
 * - **A payload that crosses the boundary every frame.** An animation driven from guest state, a
 *   scroll position read on every pixel, a list that re-sends its whole content -- each of those is
 *   free in this window and is the difference between a smooth screen and an unusable one on a
 *   device. The guest check (`:samples:slice-screens:dogwoodGuestCheck`) catches the shapes it can
 *   name; a preview catches none of them.
 * - **Skew.** `LocalSegmentVersions` here reports what the preview back end implements, which is
 *   the newest of everything. A client that predates a component a payload uses will withhold or
 *   degrade it, and no arrangement of this window can produce that client.
 *
 * Both need a device and a real client. `tools/skew-drill/` and the pre-flight drills are where
 * they are graded.
 *
 * **Headless.** `-Pheadless` composes the chosen screen into an off-screen Skia surface, renders
 * one frame, and exits non-zero if composition throws. It exists because a window cannot be
 * asserted on in continuous integration, and because a preview delegate that is wrong is usually
 * wrong by throwing.
 */
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.dogwood.slice.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.dogwood.compose.LocalHostEnvironment
import dev.dogwood.compose.LocalHostServices
import dev.dogwood.compose.LocalLaunchParams
import dev.dogwood.compose.LocalSegmentVersions
import dev.dogwood.compose.PreviewHostServices
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.SERVICES_SEGMENT
import dev.dogwood.protocol.SERVICES_VERSION
import dev.dogwood.slice.AboutScreen
import dev.dogwood.slice.MATERIAL_SECTIONS
import dev.dogwood.slice.MaterialScreen
import dev.dogwood.slice.MaterialSection
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.system.exitProcess

/** The screens this preview can open, and the argument that picks one. */
private val SCREENS: Map<String, @Composable () -> Unit> = mapOf(
  "material" to { MaterialScreen() },
  "about" to { AboutScreen() },
)

/**
 * What `-Pheadless` composes, which is deliberately more than the window shows.
 *
 * `MaterialScreen` composes one section at a time -- that is the catalogue's first design rule and
 * the reason its controls stay reachable. A gate that only ever composed the section a chip happens
 * to be on would grade one tenth of the tier, so the headless pass composes **every** section with
 * `openEverything`, the same lever `MaterialScreenCoverageTest` pulls on the deployment path and
 * for the same reason: a dialog nobody opened is a delegate nobody checked.
 */
private val HEADLESS_SCREENS: Map<String, @Composable () -> Unit> = mapOf(
  "material" to { EveryMaterialSection() },
)

@Composable
private fun EveryMaterialSection() {
  dev.dogwood.compose.Column {
    // The screen itself first, chrome and all. Leaving it out was measured rather than assumed:
    // with only the sections composed, a deliberately broken `SectionHeader` delegate was caught
    // by the About screen's pass and **not** by this one, because the catalogue's own header, chip
    // rows and witness line are in `MaterialScreen` and in none of its sections.
    MaterialScreen()
    for ((id, _) in MATERIAL_SECTIONS) {
      MaterialSection(id, openEverything = true)
    }
  }
}

private const val WIDTH_DP = 460
private const val HEIGHT_DP = 900

fun main(args: Array<String>) {
  val requested = argument(args, "screen") ?: System.getProperty("dogwood.preview.screen") ?: "material"
  val screen = SCREENS[requested] ?: run {
    System.err.println(
      "unknown screen '$requested'. Known screens: ${SCREENS.keys.sorted().joinToString(", ")}",
    )
    exitProcess(2)
  }
  val dark = (argument(args, "dark") ?: System.getProperty("dogwood.preview.dark")) == "true"
  val headless = args.contains("--headless") || System.getProperty("dogwood.preview.headless") == "true"

  if (headless) {
    val out = argument(args, "out") ?: System.getProperty("dogwood.preview.out")
    composeOnce(requested, HEADLESS_SCREENS[requested] ?: screen, screen, dark, out)
    return
  }

  application {
    Window(
      onCloseRequest = ::exitApplication,
      title = "Dogwood preview — $requested",
      state = rememberWindowState(size = DpSize(WIDTH_DP.dp, HEIGHT_DP.dp)),
    ) {
      PreviewRoot(dark, screen)
    }
  }
}

/**
 * The gate: compose the screen with no window, render one frame, and fail loudly.
 *
 * A preview delegate that forwards to the wrong function, drops a required argument or resolves a
 * token that does not exist shows up here as an exception out of composition, and this exits
 * non-zero on it rather than printing a warning nobody reads. **Watched to fail on 2026-09-16**: a
 * delegate made to throw took both screens' passes down with the message in the output, and the
 * first arrangement of this gate -- which composed the catalogue's sections but not the catalogue
 * screen itself -- did not catch it, which is why `EveryMaterialSection` composes both.
 *
 * A frame is rendered as well as composed, so anything Compose defers to measurement or drawing
 * runs here too. That catches less than it sounds like it should: a delegate that passes a
 * *negative* size was tried and did not fail, because Compose coerces it rather than throwing. The
 * gate's reach is a crash, not a wrong picture; a wrong picture needs an eye or a screenshot.
 */
private fun composeOnce(
  name: String,
  gate: @Composable () -> Unit,
  asShown: @Composable () -> Unit,
  dark: Boolean,
  out: String?,
) {
  try {
    frame(dark, gate, encode = false)
    // `-Pout=<path>` writes a picture, and it writes the screen **as the window shows it** rather
    // than the exhaustive pass above -- the exhaustive one stacks four dialogs on one another,
    // which is a state no user reaches and a misleading thing to put in a manual. This is how the
    // window was looked at without opening one: a preview whose only evidence is "it did not
    // throw" has not been seen by anybody.
    if (out != null) {
      java.io.File(out).writeBytes(frame(dark, asShown, encode = true)!!)
      println("preview: wrote $out")
    }
  } catch (failure: Throwable) {
    System.err.println("preview: composing '$name' failed")
    failure.printStackTrace()
    exitProcess(1)
  }
  println("preview: composed and rendered '$name' with no protocol, no wire and no host tree")
}

/**
 * Everything the host tells a payload, answered by the preview.
 *
 * The environment is a phone-shaped viewport so the catalogue's wrapped chip rows lay out the way
 * they do on a device; the dictionary says the preview back end's own versions, which is always
 * the newest; the launch parameters name this as a preview, because a payload that branches on
 * them should be able to see that it is in one.
 */
@Composable
private fun PreviewRoot(dark: Boolean, screen: @Composable () -> Unit) {
  val environment = HostEnvironment(
    density = 2f,
    fontScale = 1f,
    darkMode = dark,
    viewportWidthDp = WIDTH_DP,
    viewportHeightDp = HEIGHT_DP,
    locale = java.util.Locale.getDefault().toLanguageTag(),
  )
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    Surface(modifier = Modifier.fillMaxSize()) {
      CompositionLocalProvider(
        LocalHostEnvironment provides environment,
        LocalHostServices provides PreviewHostServices.create(),
        LocalSegmentVersions provides mapOf(SERVICES_SEGMENT to SERVICES_VERSION),
        LocalLaunchParams provides buildJsonObject { put("source", "preview") },
      ) {
        screen()
      }
    }
  }
}

/**
 * One off-screen frame. Encoded before the scene is closed, because the image is the scene's.
 */
private fun frame(dark: Boolean, content: @Composable () -> Unit, encode: Boolean): ByteArray? {
  val scene = ImageComposeScene(width = WIDTH_DP * 2, height = HEIGHT_DP * 2, density = Density(2f)) {
    PreviewRoot(dark, content)
  }
  return try {
    val image = scene.render()
    if (!encode) {
      null
    } else {
      (image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG) ?: error("the frame could not be encoded"))
        .bytes
    }
  } finally {
    scene.close()
  }
}

private fun argument(args: Array<String>, name: String): String? =
  args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')

/*
 * The floor: Compose Multiplatform's runtime, foundation and user-interface layers, and nothing
 * else. This is the smallest page any Compose Multiplatform host can be, so if it is already too
 * heavy, nothing a Dogwood host adds can rescue it.
 */
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.browser.window
import kotlinx.browser.document

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main() {
  ComposeViewport(document.body!!) {
    Box(Modifier.fillMaxSize())
    ReportFirstFrame()
  }
}

/*
 * Startup, reported from inside the composition -- for whoever measures it properly.
 *
 * Page weight is a proxy for the thing anyone actually cares about: how long a user stares at
 * nothing. The mark is taken on the first frame Compose produces, which is the first moment the
 * page is not blank, and it counts everything before it -- fetching the WebAssembly, compiling it,
 * Skiko's startup, and the first composition.
 *
 * **Nothing in this harness reads it.** Doing so needs a real browser with a graphics context, and
 * `measure.sh` deliberately reports only bytes; see the "What is NOT measured, and why" section of
 * README.md, which also records the two approaches that produced convincingly wrong numbers. Serve
 * the distribution, load it, and read `#dogwood-first-frame`.
 */
@Composable
private fun ReportFirstFrame() {
  LaunchedEffect(Unit) {
    withFrameNanos { }
    val element = document.createElement("div")
    element.setAttribute("id", "dogwood-first-frame")
    element.textContent = window.performance.now().toInt().toString()
    document.body!!.appendChild(element)
  }
}

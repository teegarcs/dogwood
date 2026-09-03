/*
 * The floor plus Material 3, which is what a Dogwood host actually links: the design-system
 * bindings in `dogwood-host` are implemented in terms of `MaterialTheme`, `Text`, `Button` and
 * their relatives. The gap between this and `:floor` is the price of that decision.
 */
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.browser.window
import kotlinx.browser.document

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main() {
  ComposeViewport(document.body!!) {
    MaterialTheme { Surface { Text("weight") } }
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

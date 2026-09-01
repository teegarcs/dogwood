/*
 * Project Dogwood -- the payload's second entry point.
 *
 * A diagnostics screen, and the reason it exists is that it is the only way to *see* the host
 * service surface. Every fact on it is something the guest could not have worked out for itself:
 * which services this client offers, which revision of the surface it speaks, what time zone it is
 * in, which flags are set, and what it was launched with.
 *
 * It is also the second entry point, which is what makes the entry-point contract demonstrable
 * rather than described: one payload, two experiences, and the host chooses.
 */
package dev.dogwood.slice

import androidx.compose.runtime.Composable
import dev.dogwood.compose.Modifier
import dev.dogwood.compose.LocalHostEnvironment
import dev.dogwood.compose.LocalLaunchParams
import dev.dogwood.compose.LocalSegmentVersions
import dev.dogwood.compose.Divider
import dev.dogwood.compose.SectionHeader
import dev.dogwood.compose.Text
import dev.dogwood.compose.VerticalList
import dev.dogwood.compose.fillMaxWidth
import dev.dogwood.compose.Formats
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.dogwood.compose.Animations
import dev.dogwood.compose.Box
import dev.dogwood.compose.Color
import dev.dogwood.compose.Keyboards
import dev.dogwood.compose.PrimaryButton
import dev.dogwood.compose.animate
import dev.dogwood.compose.animateDp
import dev.dogwood.compose.alpha
import dev.dogwood.compose.background
import dev.dogwood.compose.height
import dev.dogwood.compose.TextField
import dev.dogwood.compose.rememberTextFieldState
import dev.dogwood.compose.services
import dev.dogwood.protocol.SERVICES_SEGMENT
import dev.dogwood.protocol.widthClass

@Composable
fun AboutScreen() {
  val host = services()
  val environment = LocalHostEnvironment.current
  val segments = LocalSegmentVersions.current
  val launch = LocalLaunchParams.current

  VerticalList(
    modifier = Modifier.fillMaxWidth(),
    spacingDp = 8,
    contentPaddingDp = 16,
  ) {
    SectionHeader(
      title = "Diagnostics",
      description = "Everything on this screen came from the host. None of it is knowable inside the sandbox.",
    )

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(title = "Services", description = null)
    Text(
      if (host.available.isEmpty()) {
        "this client offers no services at all"
      } else {
        host.available.sorted().joinToString(", ")
      },
    )
    Text("surface revision ${host.version} (${segments[SERVICES_SEGMENT] ?: "unreported"})")
    // The clock is the clearest case of a service that is not about capability. QuickJS has
    // Date.now(); it has no time zone, because the pinned build ships no Intl.
    Text("host clock ${host.nowEpochMillis() ?: "unavailable"}")
    Text("time zone ${host.clock?.timeZoneId() ?: "unavailable"}")

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(
      title = "Formatted by the host",
      description = "The sandbox has no Intl. Every line below crossed as a number.",
    )
    val now = host.nowEpochMillis() ?: 0L
    Text(Formats.currency(61200, "USD"))
    Text(Formats.currency(61200, "JPY"))
    Text(Formats.number(1234567.891))
    Text(Formats.percent(0.075, maximumFractionDigits = 1))
    Text(Formats.dateTime(now))
    Text(Formats.relativeTime(now - 3 * 86_400_000L, now))

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(
      title = "Text input",
      description = "The mask, the limit and the counter are all applied host-side, where the typing is.",
    )
    val card = rememberTextFieldState()
    TextField(
      state = card,
      modifier = Modifier.fillMaxWidth(),
      label = "Card number",
      mask = "#### #### #### ####",
      keyboard = Keyboards.NUMBER,
      showCounter = true,
    )
    // The guest sees digits. It never sees the spaces, so changing the mask cannot change what
    // validation reads.
    Text("guest sees: \"${card.text}\"")

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(
      title = "Animation",
      description = "One property crossed when the target changed. Every frame after that is host work.",
    )
    var expanded by remember { mutableStateOf(false) }
    var arrivals by remember { mutableStateOf(0) }
    PrimaryButton(
      label = if (expanded) "Collapse" else "Expand",
      modifier = Modifier.fillMaxWidth(),
      onClick = { expanded = !expanded },
    )
    Box(
      modifier = Modifier
        .fillMaxWidth()
        // Two animated arguments in one chain. Interrupt it mid-flight -- tap twice quickly --
        // and it retargets from wherever it is rather than restarting, because that is what
        // Compose's own `animateFloatAsState` does and this protocol declares targets rather than
        // starting animations.
        .height(animateDp(if (expanded) 160 else 24, Animations.spring(damping = "mediumBouncy")))
        .alpha(
          animate(
            if (expanded) 1f else 0.35f,
            Animations.tween(durationMs = 250),
            // A completion, not a retarget: an interrupted animation never reports one.
            onFinished = { arrivals += 1 },
          ),
        )
        .background(Color.token("primaryContainer")),
    )
    Text("arrived $arrivals times")

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(title = "Feature flags", description = null)
    if (host.flags.isEmpty()) {
      Text("none set")
    } else {
      for ((name, value) in host.flags.entries.sortedBy { it.key }) {
        Text("$name = $value")
      }
    }

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(title = "Launch", description = null)
    Text(launch.toString())

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(title = "Dictionary", description = null)
    for ((segment, version) in segments.entries.sortedBy { it.key }) {
      Text("$segment v$version")
    }

    Divider(modifier = Modifier.fillMaxWidth())

    SectionHeader(title = "Environment", description = null)
    Text("${environment.viewportWidthDp}×${environment.viewportHeightDp}dp, ${environment.widthClass}")
    Text("${if (environment.darkMode) "dark" else "light"}, ${environment.locale}, text ×${environment.fontScale}")
  }
}

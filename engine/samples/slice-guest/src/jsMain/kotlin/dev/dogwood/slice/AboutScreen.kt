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
import dev.dogwood.compose.DogwoodModifier
import dev.dogwood.compose.LocalDogwoodConfiguration
import dev.dogwood.compose.LocalDogwoodLaunch
import dev.dogwood.compose.LocalDogwoodSegments
import dev.dogwood.compose.Divider
import dev.dogwood.compose.SectionHeader
import dev.dogwood.compose.Text
import dev.dogwood.compose.VerticalList
import dev.dogwood.compose.fillMaxWidth
import dev.dogwood.compose.services
import dev.dogwood.protocol.SERVICES_SEGMENT
import dev.dogwood.protocol.widthClass

@Composable
fun AboutScreen() {
  val host = services()
  val environment = LocalDogwoodConfiguration.current
  val segments = LocalDogwoodSegments.current
  val launch = LocalDogwoodLaunch.current

  VerticalList(
    modifier = DogwoodModifier.fillMaxWidth(),
    spacingDp = 8,
    contentPaddingDp = 16,
  ) {
    SectionHeader(
      title = "Diagnostics",
      description = "Everything on this screen came from the host. None of it is knowable inside the sandbox.",
    )

    Divider(modifier = DogwoodModifier.fillMaxWidth())

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

    Divider(modifier = DogwoodModifier.fillMaxWidth())

    SectionHeader(title = "Feature flags", description = null)
    if (host.flags.isEmpty()) {
      Text("none set")
    } else {
      for ((name, value) in host.flags.entries.sortedBy { it.key }) {
        Text("$name = $value")
      }
    }

    Divider(modifier = DogwoodModifier.fillMaxWidth())

    SectionHeader(title = "Launch", description = null)
    Text(launch.toString())

    Divider(modifier = DogwoodModifier.fillMaxWidth())

    SectionHeader(title = "Dictionary", description = null)
    for ((segment, version) in segments.entries.sortedBy { it.key }) {
      Text("$segment v$version")
    }

    Divider(modifier = DogwoodModifier.fillMaxWidth())

    SectionHeader(title = "Environment", description = null)
    Text("${environment.viewportWidthDp}×${environment.viewportHeightDp}dp, ${environment.widthClass}")
    Text("${if (environment.darkMode) "dark" else "light"}, ${environment.locale}, text ×${environment.fontScale}")
  }
}

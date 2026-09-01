/*
 * Project Dogwood -- the icon dictionary.
 *
 * `specs/layer-5-host.md` bespoke subsystem 8 and
 * [ADR-005](../../../../../../adrs/layer-5/ADR-005-corrected-coverage-and-bespoke-subsystem-list.md)
 * name this precisely: "an icon dictionary for the enumerable `Icons.*` `val` properties". The
 * reason it needs one at all is that `Icon` takes a required `Painter`, `ImageBitmap` or
 * `ImageVector`, and the sandbox has no filesystem, no network, and no stable host resource
 * identifiers -- integer resource identifiers change across host builds, and the payload ships
 * months apart from the host. `Bindings.kt` said so and left `Icon` out for exactly that reason.
 *
 * The dictionary is the same shape as the colour palette one layer down: **the guest names an
 * intent and the host resolves it**. Which is also why the set is the host's to choose. A
 * product's icons are its own; the Material set below is a default, not a requirement.
 */
package dev.dogwood.host

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icons a client knows, by name.
 *
 * @param fallback drawn when the guest names an icon this client has never heard of. Not null and
 *   not a throw: a payload built against a newer dictionary must degrade visibly rather than
 *   crash or render a hole, which is the same rule an unknown widget tag and an unknown colour
 *   token both follow.
 */
class IconSet(
  private val icons: Map<String, ImageVector>,
  val fallback: ImageVector = Icons.Filled.Warning,
) {
  val names: Set<String> get() = icons.keys

  operator fun get(name: String): ImageVector? = icons[name]

  companion object {
    /** A small default set. Replace it; a product's icons are its own. */
    val Default = IconSet(
      mapOf(
        "arrowBack" to Icons.Filled.ArrowBack,
        "bookmark" to Icons.Filled.Bookmark,
        "bookmarkBorder" to Icons.Filled.BookmarkBorder,
        "check" to Icons.Filled.Check,
        "close" to Icons.Filled.Close,
        "filter" to Icons.Filled.FilterList,
        "flight" to Icons.Filled.Flight,
        "hotel" to Icons.Filled.Hotel,
        "info" to Icons.Filled.Info,
        "location" to Icons.Filled.LocationOn,
        "search" to Icons.Filled.Search,
        "star" to Icons.Filled.Star,
      ),
    )
  }
}

/** The icon set in force. Static: a client's icon set does not change while a screen is open. */
val LocalIconSet = staticCompositionLocalOf { IconSet.Default }

/** Names a guest asked for that this client does not carry. Telemetry, and the skew signal. */
val LocalUnknownIcons = staticCompositionLocalOf<MutableSet<String>?> { null }

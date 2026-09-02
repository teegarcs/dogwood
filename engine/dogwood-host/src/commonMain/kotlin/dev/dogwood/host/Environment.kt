/*
 * Project Dogwood -- the host environment subsystem.
 *
 * roadmap.md Phase 4 names this first and calls it the cheapest, "and everything else assumes
 * it". Everything else assumes it because a guest composition cannot see the device: it has no
 * display metrics, no resources, no system settings, and QuickJS ships no ECMA-402 `Intl`. Every
 * host fact a composition may read has to arrive through this one path or not at all.
 *
 * There are two halves, and they are deliberately separate:
 *
 *   - **Deriving** the environment from Compose Multiplatform's own ambient values, which is
 *     what [rememberHostEnvironment] and [DogwoodEnvironment] do. This is common code, so
 *     it is the same derivation on Android, desktop, Web and iOS.
 *   - **Delivering** it to a running guest, which is `DogwoodExperience.updateConfiguration` and
 *     `DogwoodSession.updateConfiguration`. Delivery is a boundary crossing and therefore has a
 *     dispatcher; derivation is composition and therefore does not.
 *
 * See `adrs/layer-5/ADR-012-host-environment-subsystem.md`.
 */
package dev.dogwood.host

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.LayoutDirection
import dev.dogwood.protocol.HostEnvironment

/**
 * Mounts an experience's environment: measures the slot it is given, resolves the palette, and
 * hands the derived configuration to [content].
 *
 * **Place this exactly around the space the experience occupies**, not around the whole window.
 * The viewport it reports is the slot it measures, so a wrapper that also contains application
 * chrome reports a viewport the guest does not actually have, and the guest will lay out for
 * room that is not there.
 *
 * The measurement comes from `BoxWithConstraints` rather than from screen metrics on purpose.
 * Screen metrics are wrong in every case that matters -- split screen, a foldable's inner
 * display, a resizable desktop window, a tablet with the experience in a side pane -- and they
 * are wrong silently.
 *
 * @param darkMode overridable so an application with its own theme switch can drive Dogwood from
 *   it rather than from the system setting, which is the common case in products that offer one.
 * @param windowInsets which insets the experience is expected to respect. The default assumes it
 *   is mounted full-bleed and must avoid the system bars itself. A host that has already inset
 *   the slot -- a scaffold, a top application bar, the sample's own status banner -- must say so
 *   here, because Compose's inset *consumption* travels through the modifier chain and is not
 *   visible to a composition read. Getting this wrong double-counts: the guest pads for a status
 *   bar the host has already padded for, and the screen gains a band of dead space.
 */
@Composable
fun DogwoodEnvironment(
  modifier: Modifier = Modifier,
  darkMode: Boolean = isSystemInDarkTheme(),
  windowInsets: WindowInsets = WindowInsets.safeDrawing,
  /**
   * The look in force. A value, so it can come from anywhere -- the compiled-in default, the
   * product's own configuration channel, or a document fetched from the payload origin
   * (`ThemeDelivery.kt`) -- and so swapping it mid-session repaints exactly as a dark-mode flip
   * does: the readers recompose, and nothing crosses to the guest, because the guest only ever
   * named the tokens.
   */
  theme: Theme = Theme.Default,
  content: @Composable (HostEnvironment) -> Unit,
) {
  BoxWithConstraints(modifier) {
    val configuration = rememberHostEnvironment(
      viewportWidthDp = maxWidth.value.toInt(),
      viewportHeightDp = maxHeight.value.toInt(),
      darkMode = darkMode,
      windowInsets = windowInsets,
    )
    val base = materialTypography()
    val typography = androidx.compose.runtime.remember(theme, base) { theme.applyTo(base) }
    CompositionLocalProvider(
      LocalPalette provides theme.palette(darkMode),
      LocalTypography provides typography,
    ) {
      content(configuration)
    }
  }
}

/**
 * Derives the configuration from the ambient environment, recomposing when any part of it moves.
 *
 * Every field is read from a Compose Multiplatform ambient rather than from a platform
 * application programming interface, which is what keeps this file common:
 *
 *   - `density` and `fontScale` come from `LocalDensity`. Font scale is separate from density
 *     because a user who enlarges text has not enlarged everything, and a guest that conflates
 *     the two produces layouts that break for exactly the users who most need them not to.
 *   - `layoutDirectionRtl` comes from `LocalLayoutDirection`. It crosses as a boolean rather than
 *     as an enumeration ordinal so that adding a third layout direction, if one ever exists,
 *     is not a silent renumbering of the wire format.
 *   - `safeArea*` come from [windowInsets], converted to density-independent pixels at the
 *     boundary. The guest has no density it can trust to do that conversion itself.
 *   - `locale` comes from `androidx.compose.ui.text.intl.Locale.current`. The guest needs the tag
 *     rather than a formatted string because the pinned QuickJS has no `Intl`, so guest-side
 *     locale-aware formatting has no built-in primitive and must be host-served or tag-driven.
 *
 * The result is `remember`ed on its own contents so that an unrelated recomposition of the
 * caller does not manufacture a new configuration value. That matters: a new value is a boundary
 * crossing and a guest recomposition, and an equal-but-not-identical one would buy both for
 * nothing.
 */
@Composable
fun rememberHostEnvironment(
  viewportWidthDp: Int,
  viewportHeightDp: Int,
  darkMode: Boolean = isSystemInDarkTheme(),
  windowInsets: WindowInsets = WindowInsets.safeDrawing,
): HostEnvironment {
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val locale = Locale.current.toLanguageTag()
  val safeAreaTopDp = with(density) { windowInsets.getTop(density).toDp().value.toInt() }
  val safeAreaBottomDp = with(density) { windowInsets.getBottom(density).toDp().value.toInt() }

  return remember(
    density.density,
    density.fontScale,
    layoutDirection,
    locale,
    darkMode,
    viewportWidthDp,
    viewportHeightDp,
    safeAreaTopDp,
    safeAreaBottomDp,
  ) {
    HostEnvironment(
      density = density.density,
      fontScale = density.fontScale,
      darkMode = darkMode,
      layoutDirectionRtl = layoutDirection == LayoutDirection.Rtl,
      viewportWidthDp = viewportWidthDp,
      viewportHeightDp = viewportHeightDp,
      safeAreaTopDp = safeAreaTopDp,
      safeAreaBottomDp = safeAreaBottomDp,
      locale = locale,
    )
  }
}

/*
 * Project Dogwood -- the registered design system's tokens.
 *
 * Spacing and corner-radius values are Skyscanner Backpack's, read from its published Compose
 * tokens (`BpkSpacing.kt`, `BpkBorderRadius.kt`, Apache 2.0). Backpack is the design system this
 * slice is audited and modelled against, because it is public, actively released, built on
 * atomic design, and ships real Jetpack Compose components -- so the bindability audit is against
 * signatures somebody actually maintains rather than ones invented to be convenient.
 *
 * The colours are NOT Backpack's. Its colour tokens are generated and not vendored here, so
 * these are ours, chosen to sit sensibly with its spacing. Saying so matters: a reader should
 * never have to guess which parts of this are borrowed and which are invented.
 *
 * Spacing and radius are constants because they do not vary with the environment. Colour is a
 * *value* rather than a constant, because dark mode is a host fact that changes at runtime --
 * see [Palette] and the host-environment subsystem in
 * `adrs/layer-5/ADR-012-host-environment-subsystem.md`.
 */
package dev.dogwood.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Backpack's spacing scale. */
object Spacing {
  val None = 0.dp
  val Xs = 2.dp
  val Sm = 4.dp
  val Md = 8.dp
  val Base = 16.dp
  val Lg = 24.dp
  val Xl = 32.dp
  val Xxl = 40.dp
}

/** Backpack's corner-radius scale. */
object Radius {
  val Xs = 4.dp
  val Sm = 8.dp
  val Md = 12.dp
  val Lg = 24.dp
  val Full = 100.dp
}

/**
 * The host's named colours. Ours, not Backpack's.
 *
 * A class rather than an object, because there is more than one of them: the same token name
 * resolves to a different colour in dark mode. This is the reason a guest may not send a literal
 * colour for anything themed -- it would have to know which palette is in force, and it cannot,
 * because the palette is a host fact that can change while the guest is running.
 */
class Palette(
  val name: String,
  val ink: Color,
  val inkSecondary: Color,
  val canvas: Color,
  val canvasContrast: Color,
  val primary: Color,
  val primaryContainer: Color,
  val onPrimary: Color,
  val line: Color,
  val success: Color,
  val successContainer: Color,
  val warning: Color,
  val warningContainer: Color,
  val star: Color,
  /**
   * Tokens beyond the core thirteen.
   *
   * A theme document may define names this class has no property for -- a brand accent, a chart
   * ramp -- and a custom design system may resolve its own vocabulary. They ride here, resolved
   * by [token] exactly as the named slots are, so "the core names are typed and the rest are
   * data" is a fact about this class rather than a limit on the vocabulary.
   */
  val extras: Map<String, Color> = emptyMap(),
) {
  /**
   * Resolves a token by the name the guest sent, or null when this client has never heard of it.
   *
   * Null rather than a throw: a guest built against a newer dictionary may name a token this
   * client's palette does not carry, and skew must degrade rather than crash. The caller decides
   * the fallback.
   */
  fun token(name: String): Color? = when (name) {
    "ink" -> ink
    "inkSecondary" -> inkSecondary
    "canvas" -> canvas
    "canvasContrast" -> canvasContrast
    "primary" -> primary
    "primaryContainer" -> primaryContainer
    "onPrimary" -> onPrimary
    "line" -> line
    "success" -> success
    "successContainer" -> successContainer
    "warning" -> warning
    "warningContainer" -> warningContainer
    "star" -> star
    else -> extras[name]
  }

  companion object {
    val Light = Palette(
      name = "light",
      ink = Color(0xFF111236),
      inkSecondary = Color(0xFF5F6067),
      canvas = Color(0xFFFFFFFF),
      canvasContrast = Color(0xFFF1F2F8),
      primary = Color(0xFF0770E3),
      primaryContainer = Color(0xFFE6F0FC),
      onPrimary = Color(0xFFFFFFFF),
      line = Color(0xFFDDDDE5),
      success = Color(0xFF0C7D63),
      successContainer = Color(0xFFE0F5F1),
      warning = Color(0xFFB35C00),
      warningContainer = Color(0xFFFDF2E4),
      star = Color(0xFFFF9400),
    )

    /**
     * The same token names, resolved for a dark surface.
     *
     * Not an inversion: `primary` is lightened rather than flipped, because a mid-blue that
     * carries white text on white fails contrast against near-black.
     */
    val Dark = Palette(
      name = "dark",
      ink = Color(0xFFF3F3F7),
      inkSecondary = Color(0xFFA8A9B4),
      canvas = Color(0xFF15161C),
      canvasContrast = Color(0xFF23252E),
      primary = Color(0xFF6BA9F0),
      primaryContainer = Color(0xFF1B2C42),
      onPrimary = Color(0xFF06182B),
      line = Color(0xFF33353F),
      success = Color(0xFF4FC7AA),
      successContainer = Color(0xFF10312B),
      warning = Color(0xFFE9A24B),
      warningContainer = Color(0xFF362514),
      star = Color(0xFFFFB13D),
    )
  }
}

/**
 * The palette in force.
 *
 * A dynamic `compositionLocalOf` rather than a static one, precisely because it changes: a
 * device switching to dark mode mid-session must repaint, and a static local would not
 * invalidate its readers. [DogwoodEnvironment] provides it; the default is light so that a host
 * that never opts into theming still renders.
 */
val LocalPalette = compositionLocalOf { Palette.Light }

/** Shorthand for the palette in force, for the many binding implementations that read it. */
@Composable
@ReadOnlyComposable
fun palette(): Palette = LocalPalette.current

/**
 * The host's named text styles.
 *
 * The same argument as [Palette], one layer up: a guest cannot construct a `TextStyle`, and a
 * literal one could not follow the host's typography. Naming a style is how a guest asks for
 * "the title of a section" and lets the host decide what that looks like -- **including the font
 * family**, which is the design-system-first answer to the font half of the resources subsystem.
 * A payload that shipped its own font would have to ship the file, and no font file crosses this
 * boundary.
 */
class Typography(
  val displayLarge: TextStyle,
  val titleLarge: TextStyle,
  val titleMedium: TextStyle,
  val titleSmall: TextStyle,
  val bodyLarge: TextStyle,
  val bodyMedium: TextStyle,
  val bodySmall: TextStyle,
  val labelLarge: TextStyle,
  val labelMedium: TextStyle,
  val labelSmall: TextStyle,
) {
  /** Null when this client has never heard of the name. Skew degrades; it does not throw. */
  fun token(name: String): TextStyle? = when (name) {
    "displayLarge" -> displayLarge
    "titleLarge" -> titleLarge
    "titleMedium" -> titleMedium
    "titleSmall" -> titleSmall
    "bodyLarge" -> bodyLarge
    "bodyMedium" -> bodyMedium
    "bodySmall" -> bodySmall
    "labelLarge" -> labelLarge
    "labelMedium" -> labelMedium
    "labelSmall" -> labelSmall
    else -> null
  }
}

/**
 * Material 3's scale, as the default set.
 *
 * Ours to replace: a product with its own type ramp provides its own [Typography] and the guest's
 * token names go on meaning what that product says they mean.
 */
@Composable
@ReadOnlyComposable
fun materialTypography(): Typography = androidx.compose.material3.MaterialTheme.typography.let {
  Typography(
    displayLarge = it.displayLarge,
    titleLarge = it.titleLarge,
    titleMedium = it.titleMedium,
    titleSmall = it.titleSmall,
    bodyLarge = it.bodyLarge,
    bodyMedium = it.bodyMedium,
    bodySmall = it.bodySmall,
    labelLarge = it.labelLarge,
    labelMedium = it.labelMedium,
    labelSmall = it.labelSmall,
  )
}

/**
 * The typography in force.
 *
 * `null` means "whatever `MaterialTheme` is providing", resolved at the read site rather than
 * here, because a composition local's default cannot read another composition local.
 */
val LocalTypography = compositionLocalOf<Typography?> { null }

/** The typography in force, falling back to the ambient Material scale. */
@Composable
@ReadOnlyComposable
fun typography(): Typography = LocalTypography.current ?: materialTypography()

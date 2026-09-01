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
 */
package dev.dogwood.host

import androidx.compose.ui.graphics.Color
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

/** Ours, not Backpack's. */
object Palette {
  val Ink = Color(0xFF111236)
  val InkSecondary = Color(0xFF5F6067)
  val Canvas = Color(0xFFFFFFFF)
  val CanvasContrast = Color(0xFFF1F2F8)
  val Primary = Color(0xFF0770E3)
  val PrimaryContainer = Color(0xFFE6F0FC)
  val OnPrimary = Color(0xFFFFFFFF)
  val Line = Color(0xFFDDDDE5)
  val Success = Color(0xFF0C7D63)
  val SuccessContainer = Color(0xFFE0F5F1)
  val Warning = Color(0xFFB35C00)
  val WarningContainer = Color(0xFFFDF2E4)
  val Star = Color(0xFFFF9400)
}

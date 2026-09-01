/*
 * Project Dogwood -- derived views of the host environment.
 *
 * These live in the protocol module, not in the guest or the host, because both sides must agree
 * on them. A guest that lays out for "compact" and a host that measures "medium" against a
 * different breakpoint would disagree about the same screen, and the disagreement would be
 * invisible until somebody reported a layout bug on one device.
 *
 * The breakpoints are Google's published Material window size classes, which Compose
 * Multiplatform's own `WindowSizeClass` uses:
 * https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes
 */
package dev.dogwood.protocol

/**
 * How much horizontal room the experience has, bucketed.
 *
 * Bucketed rather than raw, because a guest that branches on an exact width produces a different
 * composition for every device and no two of them are testable. The raw value remains available
 * on [DogwoodConfiguration] for the cases that genuinely need it.
 */
enum class WidthClass {
  /** Below 600 density-independent pixels: a phone in portrait. */
  Compact,

  /** 600 to 839: a large phone in landscape, a small tablet, a split-screen pane. */
  Medium,

  /** 840 and above: a tablet, a desktop window. */
  Expanded,
}

val DogwoodConfiguration.widthClass: WidthClass
  get() = when {
    viewportWidthDp < 600 -> WidthClass.Compact
    viewportWidthDp < 840 -> WidthClass.Medium
    else -> WidthClass.Expanded
  }

/**
 * The language subtag of the locale, for the common case of choosing a string.
 *
 * The full tag stays on [DogwoodConfiguration] because region matters for formatting -- `en-US`
 * and `en-GB` write dates differently -- but a guest choosing between a Japanese and an English
 * label wants only this half.
 */
val DogwoodConfiguration.language: String
  get() = locale.substringBefore('-')

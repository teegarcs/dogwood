/*
 * Project Dogwood -- the theme is a document.
 *
 * The token *names* are the contract; the *values* behind them are data, and data should not need
 * an application release to change. This file is where that becomes true: a JSON document maps
 * names to values, and everything downstream -- the dynamic palette local, the evaluator's
 * identity-keyed memo, the components reading `palette()` -- was already built for the values to
 * be values.
 *
 * Where the document comes from is deliberately not this file's business. Baked into the
 * application as the default; handed over from whatever configuration channel the product already
 * trusts; or fetched from the same origin the payloads come from (`ThemeDelivery.kt`). One seam,
 * any pipe.
 *
 * The payload is never the pipe. A theme is client-wide -- every experience, and the native
 * chrome around them, must agree -- so screens name colours and the client decides what the names
 * mean. See adrs/layer-5/ADR-026-the-theme-is-a-document.md.
 */
package dev.dogwood.host

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Everything a client can restyle without a release: both palettes, and typography overrides.
 *
 * Spacing and corner radii are deliberately absent from v1. Colours and type are what rebrands
 * and tenant themes actually change; geometry changes tend to need layout review, and holding
 * them back keeps a bad document's blast radius to "looks wrong" rather than "overlaps".
 */
class Theme(
  val name: String,
  val light: Palette,
  val dark: Palette,
  /** Per-style overrides, applied over the host's base typography at provide time. */
  internal val typography: Map<String, TextStyleOverride> = emptyMap(),
) {
  fun palette(darkMode: Boolean): Palette = if (darkMode) dark else light

  /** The host's base typography with this theme's overrides applied. */
  fun applyTo(base: Typography): Typography {
    if (typography.isEmpty()) return base
    fun styled(name: String, style: TextStyle): TextStyle =
      typography[name]?.applyTo(style) ?: style
    return Typography(
      displayLarge = styled("displayLarge", base.displayLarge),
      titleLarge = styled("titleLarge", base.titleLarge),
      titleMedium = styled("titleMedium", base.titleMedium),
      titleSmall = styled("titleSmall", base.titleSmall),
      bodyLarge = styled("bodyLarge", base.bodyLarge),
      bodyMedium = styled("bodyMedium", base.bodyMedium),
      bodySmall = styled("bodySmall", base.bodySmall),
      labelLarge = styled("labelLarge", base.labelLarge),
      labelMedium = styled("labelMedium", base.labelMedium),
      labelSmall = styled("labelSmall", base.labelSmall),
    )
  }

  companion object {
    /** The compiled-in look: what every route degrades to, and what a fresh install shows. */
    val Default = Theme(name = "built-in", light = Palette.Light, dark = Palette.Dark)

    /**
     * Parses a theme document, falling back **field by field** rather than wholesale.
     *
     * A document that renames one colour badly should not throw away its other forty. Every
     * value the parser cannot use falls back to [fallback]'s value for that slot, and the
     * problem is reported through [onProblem] -- a theme arrives from outside the process, so it
     * is treated like every other outside input: wrong-looking beats crashed, and silently
     * wrong beats neither.
     */
    fun fromJson(
      text: String,
      fallback: Theme = Default,
      onProblem: (String) -> Unit = {},
    ): Theme {
      val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse {
        onProblem("not a JSON object: ${it.message}")
        return fallback
      }
      val name = root["name"]?.jsonPrimitive?.content ?: fallback.name
      return Theme(
        name = name,
        light = parsePalette(root["light"] as? JsonObject, fallback.light, "$name/light", onProblem),
        dark = parsePalette(root["dark"] as? JsonObject, fallback.dark, "$name/dark", onProblem),
        typography = parseTypography(root["typography"] as? JsonObject, onProblem),
      )
    }

    private fun parsePalette(
      json: JsonObject?,
      fallback: Palette,
      where: String,
      onProblem: (String) -> Unit,
    ): Palette {
      if (json == null) return fallback
      val parsed = mutableMapOf<String, Color>()
      for ((key, value) in json) {
        val colour = parseColor(value.jsonPrimitive.content)
        if (colour == null) {
          onProblem("$where.$key: not a colour: ${value.jsonPrimitive.content}")
        } else {
          parsed[key] = colour
        }
      }
      fun slot(key: String, absent: Color): Color = parsed.remove(key) ?: absent
      return Palette(
        name = where,
        ink = slot("ink", fallback.ink),
        inkSecondary = slot("inkSecondary", fallback.inkSecondary),
        canvas = slot("canvas", fallback.canvas),
        canvasContrast = slot("canvasContrast", fallback.canvasContrast),
        primary = slot("primary", fallback.primary),
        primaryContainer = slot("primaryContainer", fallback.primaryContainer),
        onPrimary = slot("onPrimary", fallback.onPrimary),
        line = slot("line", fallback.line),
        success = slot("success", fallback.success),
        successContainer = slot("successContainer", fallback.successContainer),
        warning = slot("warning", fallback.warning),
        warningContainer = slot("warningContainer", fallback.warningContainer),
        star = slot("star", fallback.star),
        // Whatever remains is vocabulary this class has no property for. It still resolves.
        extras = fallback.extras + parsed,
      )
    }

    private fun parseTypography(
      json: JsonObject?,
      onProblem: (String) -> Unit,
    ): Map<String, TextStyleOverride> {
      if (json == null) return emptyMap()
      val overrides = mutableMapOf<String, TextStyleOverride>()
      for ((style, raw) in json) {
        val body = raw as? JsonObject ?: run {
          onProblem("typography.$style: not an object")
          continue
        }
        overrides[style] = TextStyleOverride(
          sizeSp = body["sizeSp"]?.jsonPrimitive?.floatOrNull,
          weight = body["weight"]?.jsonPrimitive?.intOrNull,
          lineHeightSp = body["lineHeightSp"]?.jsonPrimitive?.floatOrNull,
        )
      }
      return overrides
    }

    /** `#RRGGBB` or `#AARRGGBB`. Anything else is a problem, not a guess. */
    internal fun parseColor(text: String): Color? {
      val hex = text.removePrefix("#")
      val value = hex.toLongOrNull(16) ?: return null
      return when (hex.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> null
      }
    }
  }
}

/** The parts of a text style a document may change. Font *files* cannot ship this way. */
class TextStyleOverride(
  val sizeSp: Float? = null,
  val weight: Int? = null,
  val lineHeightSp: Float? = null,
) {
  fun applyTo(style: TextStyle): TextStyle = style.copy(
    fontSize = sizeSp?.sp ?: style.fontSize,
    fontWeight = weight?.let(::FontWeight) ?: style.fontWeight,
    lineHeight = lineHeightSp?.sp ?: style.lineHeight,
  )
}

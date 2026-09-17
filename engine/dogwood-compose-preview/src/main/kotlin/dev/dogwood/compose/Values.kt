/*
 * Project Dogwood -- the guest's value types, for the preview path.
 *
 * Every type here has an identical twin in `dogwood-compose`'s `Units.kt` and `Expressions.kt`:
 * the same package, the same name, the same constructors and the same spelling at a call site.
 * The twin on the deployment path holds a **recipe** -- a factory identifier and a list of
 * arguments -- because a colour token, a shape or a formatted number is a question the host
 * answers after the wire has carried it. The twin here holds the answer directly, because on the
 * preview path this process *is* the host.
 *
 * Resolution is `@Composable` wherever the answer depends on the theme, which is the honest shape:
 * `Color.token("primary")` cannot be turned into a number outside a composition, on either path.
 */
@file:Suppress("unused")

package dev.dogwood.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight as UiFontWeight
import androidx.compose.ui.text.style.TextAlign as UiTextAlign
import androidx.compose.ui.text.style.TextDecoration as UiTextDecoration
import androidx.compose.ui.text.style.TextOverflow as UiTextOverflow
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.graphics.Shape as UiShape
import androidx.compose.ui.semantics.Role as UiRole
import androidx.compose.ui.unit.Dp as UiDp
import androidx.compose.ui.unit.TextUnit as UiTextUnit
import androidx.compose.ui.unit.dp as uiDp
import androidx.compose.ui.unit.em as uiEm
import androidx.compose.ui.unit.sp as uiSp
import androidx.compose.foundation.layout.PaddingValues as UiPaddingValues
import androidx.compose.foundation.BorderStroke as UiBorderStroke
import androidx.compose.foundation.layout.Arrangement as UiArrangement
import androidx.compose.ui.Alignment as UiAlignment
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

// ---------------------------------------------------------------------------------------------
// Units
// ---------------------------------------------------------------------------------------------

/** Density-independent pixels. `Dp.Unspecified` is absence, exactly as on the deployment path. */
@JvmInline
value class Dp(val value: Float) {
  companion object {
    val Unspecified: Dp = Dp(Float.NaN)
  }
  val isSpecified: Boolean get() = !value.isNaN()
}

val Int.dp: Dp get() = Dp(toFloat())
val Float.dp: Dp get() = Dp(this)
val Double.dp: Dp get() = Dp(toFloat())

internal fun Dp.real(): UiDp = value.uiDp

/** Scaled or em-relative text size, carrying its unit the way the wire form does. */
class TextUnit private constructor(val value: Float, val unit: String) {
  override fun equals(other: Any?): Boolean = other is TextUnit && other.value == value && other.unit == unit
  override fun hashCode(): Int = 31 * value.hashCode() + unit.hashCode()
  override fun toString(): String = "$value.$unit"

  companion object {
    internal fun sp(value: Float) = TextUnit(value, "sp")
    internal fun em(value: Float) = TextUnit(value, "em")
  }
}

val Int.sp: TextUnit get() = TextUnit.sp(toFloat())
val Float.sp: TextUnit get() = TextUnit.sp(this)
val Double.sp: TextUnit get() = TextUnit.sp(toFloat())
val Float.em: TextUnit get() = TextUnit.em(this)
val Int.em: TextUnit get() = TextUnit.em(toFloat())

internal fun TextUnit.real(): UiTextUnit = if (unit == "em") value.uiEm else value.uiSp

/** Four sides. */
class PaddingValues(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp) {
  override fun equals(other: Any?): Boolean =
    other is PaddingValues && other.start == start && other.top == top && other.end == end && other.bottom == bottom
  override fun hashCode(): Int = listOf(start, top, end, bottom).hashCode()
  override fun toString(): String = "PaddingValues($start, $top, $end, $bottom)"
}

fun PaddingValues(all: Dp = 0.dp): PaddingValues = PaddingValues(all, all, all, all)

fun PaddingValues(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): PaddingValues =
  PaddingValues(horizontal, vertical, horizontal, vertical)

internal fun PaddingValues.real(): UiPaddingValues =
  UiPaddingValues(start.real(), top.real(), end.real(), bottom.real())

/** A width and a colour, which Compose bundles into one parameter. */
class BorderStroke(val width: Dp, val color: Color) {
  override fun equals(other: Any?): Boolean =
    other is BorderStroke && other.width == width && other.color == color
  override fun hashCode(): Int = 31 * width.hashCode() + color.hashCode()
  override fun toString(): String = "BorderStroke($width, $color)"
}

@Composable
internal fun BorderStroke.real(): UiBorderStroke = UiBorderStroke(width.real(), color.resolve())

/** Two numbers, which is how a range crosses a boundary that cannot carry an interface. */
class FloatRange(val start: Float, val end: Float) {
  override fun equals(other: Any?): Boolean = other is FloatRange && other.start == start && other.end == end
  override fun hashCode(): Int = 31 * start.hashCode() + end.hashCode()
  override fun toString(): String = "FloatRange($start, $end)"
}

infix fun Float.rangeTo(other: Float): FloatRange = FloatRange(this, other)

internal fun FloatRange.real(): ClosedFloatingPointRange<Float> = start..end

// ---------------------------------------------------------------------------------------------
// Host-resolved recipes
// ---------------------------------------------------------------------------------------------

/**
 * A shape the host resolves.
 *
 * The deployment path sends a factory number and its arguments; here the same call builds the real
 * `RoundedCornerShape` immediately. Nothing is lost -- every factory the guest can name has an
 * exact real-Compose equivalent, which is why shapes are *not* in this file's stand-in list.
 */
class Shape internal constructor(internal val real: UiShape) {
  override fun equals(other: Any?): Boolean = other is Shape && other.real == real
  override fun hashCode(): Int = real.hashCode()

  companion object {
    fun roundedCorner(dp: Int): Shape = Shape(RoundedCornerShape(dp.uiDp))

    fun roundedCorner(topStart: Int, topEnd: Int, bottomEnd: Int, bottomStart: Int): Shape =
      Shape(RoundedCornerShape(topStart.uiDp, topEnd.uiDp, bottomEnd.uiDp, bottomStart.uiDp))

    val Circle: Shape = Shape(CircleShape)
  }
}

/**
 * A colour, either a literal the guest chose or a **token** the host resolves from its palette.
 *
 * The token branch is the first of this module's three declared stand-ins. On a device the name
 * reaches the client's own `Palette` and comes back as whatever that product's theme says
 * `primaryContainer` means. Here it is resolved against `PreviewPalette`, a fixed reading of the
 * Material 3 colour scheme, and a name the palette does not carry renders **magenta** rather than
 * transparent -- a preview should make a misspelt token impossible to miss.
 */
class Color internal constructor(
  private val literal: UiColor?,
  private val token: String?,
  private val animated: AnimationSpec?,
) {
  override fun equals(other: Any?): Boolean =
    other is Color && other.literal == literal && other.token == token && other.animated == animated
  override fun hashCode(): Int = 31 * (literal?.hashCode() ?: 0) + (token?.hashCode() ?: 0)

  companion object {
    fun token(name: String): Color = Color(null, name, null)
  }

  /** Retargets rather than jumping, which is what the host does with the animated recipe. */
  fun animate(spec: AnimationSpec = Animations.tween()): Color = Color(literal, token, spec)

  @Composable
  internal fun resolve(): UiColor {
    val settled = literal ?: PreviewPalette.token(token ?: "")
    val spec = animated ?: return settled
    val moving by animateColorAsState(settled, spec.color())
    return moving
  }
}

fun Color(argb: Long): Color = Color(UiColor(argb.toInt()), null, null)

/**
 * Text the host produces: a literal, or a number the host formats in its own locale.
 *
 * The second declared stand-in. `Formats.currency(61200, "USD")` crosses as two values precisely
 * because the sandbox has no `Intl` and the client's locale is not the guest's business. Here the
 * formatting is done by `java.text` and `java.time` in **this machine's** default locale, which is
 * one host's answer standing in for the device's.
 */
class TextValue internal constructor(internal val render: () -> String) {
  override fun toString(): String = render()
}

fun TextValue(literal: String): TextValue = TextValue { literal }

object Formats {
  private fun locale(): Locale = Locale.getDefault()

  fun number(value: Double, maximumFractionDigits: Int? = null, pattern: String? = null): TextValue = TextValue {
    val format = NumberFormat.getNumberInstance(locale())
    if (maximumFractionDigits != null) format.maximumFractionDigits = maximumFractionDigits
    format.format(value)
  }

  fun plural(count: Int, templates: Map<String, String>): TextValue = TextValue {
    val key = if (count == 1) "one" else "other"
    (templates[key] ?: templates["other"] ?: "").replace("#", count.toString())
  }

  fun currency(minorUnits: Long, currencyCode: String): TextValue = TextValue {
    val currency = runCatching { Currency.getInstance(currencyCode) }.getOrNull()
    val format = NumberFormat.getCurrencyInstance(locale())
    if (currency != null) format.currency = currency
    val digits = currency?.defaultFractionDigits ?: 2
    // Set explicitly. Assigning the currency does not move the formatter's fraction digits on
    // every Java Virtual Machine, and the first render of the About screen showed it: 61200 minor
    // units of JPY -- a currency with no minor unit at all -- came out as "¥61,200.00".
    format.minimumFractionDigits = digits
    format.maximumFractionDigits = digits
    var units = minorUnits.toDouble()
    repeat(digits) { units /= 10.0 }
    format.format(units)
  }

  fun percent(fraction: Double, maximumFractionDigits: Int? = null): TextValue = TextValue {
    val format = NumberFormat.getPercentInstance(locale())
    if (maximumFractionDigits != null) format.maximumFractionDigits = maximumFractionDigits
    format.format(fraction)
  }

  fun date(epochMillis: Long): TextValue = formatted(epochMillis, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

  fun time(epochMillis: Long): TextValue = formatted(epochMillis, DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

  fun dateTime(epochMillis: Long): TextValue =
    formatted(epochMillis, DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

  fun relativeTime(epochMillis: Long, nowMillis: Long): TextValue = TextValue {
    val deltaSeconds = (epochMillis - nowMillis) / 1000
    val past = deltaSeconds < 0
    val magnitude = abs(deltaSeconds)
    val (amount, unit) = when {
      magnitude < 60 -> magnitude to "second"
      magnitude < 3_600 -> magnitude / 60 to "minute"
      magnitude < 86_400 -> magnitude / 3_600 to "hour"
      magnitude < 2_592_000 -> magnitude / 86_400 to "day"
      magnitude < 31_536_000 -> magnitude / 2_592_000 to "month"
      else -> magnitude / 31_536_000 to "year"
    }
    val plural = if (amount == 1L) unit else "${unit}s"
    if (past) "$amount $plural ago" else "in $amount $plural"
  }

  private fun formatted(epochMillis: Long, formatter: DateTimeFormatter): TextValue = TextValue {
    formatter
      .withLocale(locale())
      .withZone(ZoneId.systemDefault())
      .format(Instant.ofEpochMilli(epochMillis))
  }
}

// ---------------------------------------------------------------------------------------------
// Names the host resolves, which here resolve to Compose's own
// ---------------------------------------------------------------------------------------------

/** How a row or column distributes its children. Compose's vocabulary, crossing as a name. */
class Arrangement private constructor(internal val wire: String) {
  override fun equals(other: Any?): Boolean = other is Arrangement && other.wire == wire
  override fun hashCode(): Int = wire.hashCode()
  override fun toString(): String = "Arrangement($wire)"

  companion object {
    val Start: Arrangement = Arrangement("start")
    val Center: Arrangement = Arrangement("center")
    val End: Arrangement = Arrangement("end")
    val SpaceBetween: Arrangement = Arrangement("spaceBetween")
    val SpaceAround: Arrangement = Arrangement("spaceAround")
    val SpaceEvenly: Arrangement = Arrangement("spaceEvenly")
    val Top: Arrangement get() = Start
    val Bottom: Arrangement get() = End
    fun spacedBy(dp: Int): Arrangement = Arrangement("spacedBy:$dp")
  }
}

internal fun Arrangement.vertical(): UiArrangement.Vertical = when {
  wire.startsWith("spacedBy:") -> UiArrangement.spacedBy(wire.removePrefix("spacedBy:").toInt().uiDp)
  wire == "center" -> UiArrangement.Center
  wire == "end" -> UiArrangement.Bottom
  wire == "spaceBetween" -> UiArrangement.SpaceBetween
  wire == "spaceAround" -> UiArrangement.SpaceAround
  wire == "spaceEvenly" -> UiArrangement.SpaceEvenly
  else -> UiArrangement.Top
}

internal fun Arrangement.horizontal(): UiArrangement.Horizontal = when {
  wire.startsWith("spacedBy:") -> UiArrangement.spacedBy(wire.removePrefix("spacedBy:").toInt().uiDp)
  wire == "center" -> UiArrangement.Center
  wire == "end" -> UiArrangement.End
  wire == "spaceBetween" -> UiArrangement.SpaceBetween
  wire == "spaceAround" -> UiArrangement.SpaceAround
  wire == "spaceEvenly" -> UiArrangement.SpaceEvenly
  else -> UiArrangement.Start
}

/** A font weight: the four names every type ramp has, or a number. */
class FontWeight private constructor(internal val wire: String) {
  override fun equals(other: Any?): Boolean = other is FontWeight && other.wire == wire
  override fun hashCode(): Int = wire.hashCode()

  companion object {
    val Normal: FontWeight = FontWeight("normal")
    val Medium: FontWeight = FontWeight("medium")
    val SemiBold: FontWeight = FontWeight("semibold")
    val Bold: FontWeight = FontWeight("bold")
    fun of(weight: Int): FontWeight = FontWeight(weight.toString())
  }
}

internal fun FontWeight.real(): UiFontWeight = when (wire) {
  "normal" -> UiFontWeight.Normal
  "medium" -> UiFontWeight.Medium
  "semibold" -> UiFontWeight.SemiBold
  "bold" -> UiFontWeight.Bold
  else -> UiFontWeight(wire.toIntOrNull()?.coerceIn(100, 900) ?: 400)
}

enum class TextAlign(internal val wire: String) { Start("start"), Center("center"), End("end"), Justify("justify") }

internal fun TextAlign.real(): UiTextAlign = when (this) {
  TextAlign.Start -> UiTextAlign.Start
  TextAlign.Center -> UiTextAlign.Center
  TextAlign.End -> UiTextAlign.End
  TextAlign.Justify -> UiTextAlign.Justify
}

enum class TextOverflow(internal val wire: String) { Clip("clip"), Ellipsis("ellipsis"), Visible("visible") }

internal fun TextOverflow.real(): UiTextOverflow = when (this) {
  TextOverflow.Clip -> UiTextOverflow.Clip
  TextOverflow.Ellipsis -> UiTextOverflow.Ellipsis
  TextOverflow.Visible -> UiTextOverflow.Visible
}

enum class TextDecoration(internal val wire: String) { Underline("underline"), LineThrough("lineThrough") }

internal fun TextDecoration.real(): UiTextDecoration = when (this) {
  TextDecoration.Underline -> UiTextDecoration.Underline
  TextDecoration.LineThrough -> UiTextDecoration.LineThrough
}

/** What a screen reader calls a tappable node. */
enum class Role(internal val wire: String) {
  Button("button"), Checkbox("checkbox"), Switch("switch"), RadioButton("radioButton"),
  Tab("tab"), Image("image"), DropdownList("dropdownList"),
}

internal fun Role.real(): UiRole? = when (this) {
  Role.Button -> UiRole.Button
  Role.Checkbox -> UiRole.Checkbox
  Role.Switch -> UiRole.Switch
  Role.RadioButton -> UiRole.RadioButton
  Role.Tab -> UiRole.Tab
  Role.Image -> UiRole.Image
  Role.DropdownList -> UiRole.DropdownList
}

enum class VerticalAlignment { Top, CenterVertically, Bottom }

internal fun VerticalAlignment.real(): UiAlignment.Vertical = when (this) {
  VerticalAlignment.Top -> UiAlignment.Top
  VerticalAlignment.CenterVertically -> UiAlignment.CenterVertically
  VerticalAlignment.Bottom -> UiAlignment.Bottom
}

enum class HorizontalAlignment { Start, CenterHorizontally, End }

internal fun HorizontalAlignment.real(): UiAlignment.Horizontal = when (this) {
  HorizontalAlignment.Start -> UiAlignment.Start
  HorizontalAlignment.CenterHorizontally -> UiAlignment.CenterHorizontally
  HorizontalAlignment.End -> UiAlignment.End
}

enum class BoxAlignment {
  TopStart, TopCenter, TopEnd, CenterStart, Center, CenterEnd, BottomStart, BottomCenter, BottomEnd,
}

internal fun BoxAlignment.real(): UiAlignment = when (this) {
  BoxAlignment.TopStart -> UiAlignment.TopStart
  BoxAlignment.TopCenter -> UiAlignment.TopCenter
  BoxAlignment.TopEnd -> UiAlignment.TopEnd
  BoxAlignment.CenterStart -> UiAlignment.CenterStart
  BoxAlignment.Center -> UiAlignment.Center
  BoxAlignment.CenterEnd -> UiAlignment.CenterEnd
  BoxAlignment.BottomStart -> UiAlignment.BottomStart
  BoxAlignment.BottomCenter -> UiAlignment.BottomCenter
  BoxAlignment.BottomEnd -> UiAlignment.BottomEnd
}

/**
 * The preview's palette: the first of the three declared stand-ins, in one place.
 *
 * A device resolves a token against the product's own theme. There is no product here, so the
 * names Dogwood's own design system uses are mapped onto the Material 3 colour scheme this
 * preview runs under -- which means a token *does* follow light and dark, and does *not* follow
 * anybody's brand. Anything unmapped is magenta on purpose.
 */
object PreviewPalette {
  /** What an unmapped token renders as. Loud, because a silent wrong colour is a worse preview. */
  val Unmapped: UiColor = UiColor(0xFFFF00FF)

  @Composable
  @ReadOnlyComposable
  fun token(name: String): UiColor {
    val scheme = MaterialTheme.colorScheme
    return when (name) {
      "primary" -> scheme.primary
      "onPrimary" -> scheme.onPrimary
      "primaryContainer" -> scheme.primaryContainer
      "onPrimaryContainer" -> scheme.onPrimaryContainer
      "secondary" -> scheme.secondary
      "onSecondary" -> scheme.onSecondary
      "secondaryContainer" -> scheme.secondaryContainer
      "onSecondaryContainer" -> scheme.onSecondaryContainer
      "tertiary" -> scheme.tertiary
      "error" -> scheme.error
      "onError" -> scheme.onError
      "errorContainer" -> scheme.errorContainer
      "canvas", "surface", "background" -> scheme.surface
      "canvasContrast", "surfaceVariant" -> scheme.surfaceVariant
      "onCanvas", "onSurface" -> scheme.onSurface
      "onCanvasMuted", "onSurfaceVariant" -> scheme.onSurfaceVariant
      "outline" -> scheme.outline
      "outlineVariant" -> scheme.outlineVariant
      "scrim" -> scheme.scrim
      "inverseSurface" -> scheme.inverseSurface
      "positive" -> UiColor(0xFF2E7D32)
      "negative" -> scheme.error
      "neutral" -> scheme.outline
      else -> Unmapped
    }
  }
}

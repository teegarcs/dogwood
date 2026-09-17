/*
 * Project Dogwood -- segment 0, the primitive tier, for the preview path.
 *
 * These are the nine names every client has carried since the beginning and that a payload can
 * always rely on. On the deployment path each one is a `ComposeNode` in a recording applier that
 * emits a create, some properties and a slot; here each one is a call to the genuine Compose
 * layout of the same meaning. A `Column` is `androidx.compose.foundation.layout.Column`, a `Text`
 * is Material 3's `Text`, and nothing is in between.
 *
 * **Two things are stand-ins and are named where they occur.** A `style` is a *name* the host
 * resolves against its own type ramp, and is resolved here against Material 3's; the lazy
 * containers are not lazy, because laziness across the boundary is a windowing protocol
 * (ADR-035) and there is no window without a wire -- a preview composes every child.
 */
@file:Suppress("unused")

package dev.dogwood.compose

import androidx.compose.foundation.layout.Box as UiBox
import androidx.compose.foundation.layout.BoxScope as UiBoxScope
import androidx.compose.foundation.layout.Column as UiColumn
import androidx.compose.foundation.layout.ColumnScope as UiColumnScope
import androidx.compose.foundation.layout.Row as UiRow
import androidx.compose.foundation.layout.RowScope as UiRowScope
import androidx.compose.foundation.layout.Spacer as UiSpacer
import androidx.compose.foundation.layout.Arrangement as UiArrangement
import androidx.compose.foundation.layout.PaddingValues as UiPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState as rememberUiScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as UiText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow as UiTextOverflow
import androidx.compose.ui.unit.dp as uiDp
import androidx.compose.ui.Modifier as UiModifier

// ---------------------------------------------------------------------------------------------
// Layout scopes
// ---------------------------------------------------------------------------------------------

@DslMarker
annotation class DogwoodLayoutScope

@DogwoodLayoutScope
interface DogwoodRowScope {
  fun Modifier.weight(weight: Float): Modifier
  fun Modifier.align(alignment: VerticalAlignment): Modifier
}

@DogwoodLayoutScope
interface DogwoodColumnScope {
  fun Modifier.weight(weight: Float): Modifier
  fun Modifier.align(alignment: HorizontalAlignment): Modifier
}

@DogwoodLayoutScope
interface DogwoodBoxScope {
  fun Modifier.align(alignment: BoxAlignment): Modifier
}

private class RealRowScope(private val scope: UiRowScope) : DogwoodRowScope {
  override fun Modifier.weight(weight: Float): Modifier = wrap { with(scope) { weight(weight) } }
  override fun Modifier.align(alignment: VerticalAlignment): Modifier =
    wrap { with(scope) { align(alignment.real()) } }
}

private class RealColumnScope(private val scope: UiColumnScope) : DogwoodColumnScope {
  override fun Modifier.weight(weight: Float): Modifier = wrap { with(scope) { weight(weight) } }
  override fun Modifier.align(alignment: HorizontalAlignment): Modifier =
    wrap { with(scope) { align(alignment.real()) } }
}

private class RealBoxScope(private val scope: UiBoxScope) : DogwoodBoxScope {
  override fun Modifier.align(alignment: BoxAlignment): Modifier =
    wrap { with(scope) { align(alignment.real()) } }
}

/**
 * A column scope with no column above it.
 *
 * `VerticalList` gives its children a column scope on both paths, and the preview draws it as an
 * ordinary scrolling `Column`, so `weight` inside one has no column to weigh against. Rather than
 * fail, it is ignored and the child is laid out at its natural size -- the same thing Compose does
 * when a weight has nothing to divide.
 */
private object DetachedColumnScope : DogwoodColumnScope {
  override fun Modifier.weight(weight: Float): Modifier = this
  override fun Modifier.align(alignment: HorizontalAlignment): Modifier = this
}

private object DetachedRowScope : DogwoodRowScope {
  override fun Modifier.weight(weight: Float): Modifier = this
  override fun Modifier.align(alignment: VerticalAlignment): Modifier = this
}

// ---------------------------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------------------------

/**
 * A named style, resolved against Material 3's type ramp.
 *
 * On a device this name reaches the client's own typography. There is no product theme in a
 * preview, so the names Dogwood's design system uses are read as their Material 3 equivalents and
 * anything unrecognised falls back to `bodyMedium` -- quietly, because a type ramp has no
 * equivalent of a magenta colour and a payload that names a style the client lacks gets the
 * client's default there too.
 */
@Composable
private fun styleNamed(name: String?): TextStyle {
  val ramp = MaterialTheme.typography
  return when (name) {
    "display" -> ramp.displaySmall
    "headline", "title" -> ramp.headlineSmall
    "titleLarge" -> ramp.titleLarge
    "titleMedium", "subtitle" -> ramp.titleMedium
    "body" -> ramp.bodyMedium
    "bodyLarge" -> ramp.bodyLarge
    "bodySmall", "caption" -> ramp.bodySmall
    "label" -> ramp.labelMedium
    "labelSmall" -> ramp.labelSmall
    else -> ramp.bodyMedium
  }
}

@Composable
fun Text(
  text: String,
  modifier: Modifier = Modifier,
  maxLines: Int = -1,
  style: String? = null,
  color: Color? = null,
  fontWeight: FontWeight? = null,
  textAlign: TextAlign? = null,
  overflow: TextOverflow? = null,
  sizeSp: Int? = null,
  textDecoration: TextDecoration? = null,
  lineHeightSp: Int? = null,
) {
  UiText(
    text = text,
    modifier = modifier.real,
    color = color?.resolve() ?: androidx.compose.ui.graphics.Color.Unspecified,
    fontSize = sizeSp?.sp?.real() ?: androidx.compose.ui.unit.TextUnit.Unspecified,
    fontWeight = fontWeight?.real(),
    textDecoration = textDecoration?.real(),
    textAlign = textAlign?.real(),
    lineHeight = lineHeightSp?.sp?.real() ?: androidx.compose.ui.unit.TextUnit.Unspecified,
    overflow = overflow?.real() ?: UiTextOverflow.Clip,
    maxLines = if (maxLines >= 0) maxLines else Int.MAX_VALUE,
    style = styleNamed(style),
  )
}

@Composable
fun Text(
  value: TextValue,
  modifier: Modifier = Modifier,
  maxLines: Int = -1,
  style: String? = null,
  color: Color? = null,
  fontWeight: FontWeight? = null,
  textAlign: TextAlign? = null,
  overflow: TextOverflow? = null,
  sizeSp: Int? = null,
  textDecoration: TextDecoration? = null,
  lineHeightSp: Int? = null,
) {
  Text(
    text = value.render(),
    modifier = modifier,
    maxLines = maxLines,
    style = style,
    color = color,
    fontWeight = fontWeight,
    textAlign = textAlign,
    overflow = overflow,
    sizeSp = sizeSp,
    textDecoration = textDecoration,
    lineHeightSp = lineHeightSp,
  )
}

// ---------------------------------------------------------------------------------------------
// Containers
// ---------------------------------------------------------------------------------------------

@Composable
fun Column(
  modifier: Modifier = Modifier,
  verticalArrangement: Arrangement? = null,
  horizontalAlignment: HorizontalAlignment? = null,
  content: @Composable DogwoodColumnScope.() -> Unit,
) {
  UiColumn(
    modifier = modifier.real,
    verticalArrangement = verticalArrangement?.vertical() ?: UiArrangement.Top,
    horizontalAlignment = horizontalAlignment?.real() ?: androidx.compose.ui.Alignment.Start,
  ) {
    RealColumnScope(this).content()
  }
}

@Composable
fun Row(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  horizontalArrangement: Arrangement? = null,
  verticalAlignment: VerticalAlignment? = null,
  content: @Composable DogwoodRowScope.() -> Unit,
) {
  val tappable = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
  UiRow(
    modifier = tappable.real,
    horizontalArrangement = horizontalArrangement?.horizontal() ?: UiArrangement.Start,
    verticalAlignment = verticalAlignment?.real() ?: androidx.compose.ui.Alignment.Top,
  ) {
    RealRowScope(this).content()
  }
}

@Composable
fun Box(
  modifier: Modifier = Modifier,
  contentAlignment: BoxAlignment? = null,
  content: @Composable DogwoodBoxScope.() -> Unit = {},
) {
  UiBox(
    modifier = modifier.real,
    contentAlignment = contentAlignment?.real() ?: androidx.compose.ui.Alignment.TopStart,
  ) {
    RealBoxScope(this).content()
  }
}

@Composable
fun Spacer(modifier: Modifier = Modifier) {
  UiSpacer(modifier = modifier.real)
}

/**
 * A vertical list.
 *
 * Drawn as a scrolling `Column`, not a `LazyColumn`, and the difference is worth stating: the wire
 * form is a windowing protocol in which the host reports a viewport and the guest sends the rows
 * that fall inside it. A preview has no viewport report, so it composes every child. Long lists
 * therefore look right and cost more here than they do on a device.
 */
@Composable
fun VerticalList(
  modifier: Modifier = Modifier,
  spacingDp: Int = 0,
  contentPaddingDp: Int = 0,
  state: LazyListState? = null,
  content: @Composable DogwoodColumnScope.() -> Unit,
) {
  UiColumn(
    modifier = modifier.real.verticalScroll(rememberUiScrollState()).padding(UiPaddingValues(contentPaddingDp.uiDp)),
    verticalArrangement = UiArrangement.spacedBy(spacingDp.uiDp),
  ) {
    DetachedColumnScope.content()
  }
}

@Composable
fun HorizontalList(
  modifier: Modifier = Modifier,
  spacingDp: Int = 0,
  contentPaddingDp: Int = 0,
  state: LazyListState? = null,
  content: @Composable DogwoodRowScope.() -> Unit,
) {
  UiRow(
    modifier = modifier.real.horizontalScroll(rememberUiScrollState()).padding(UiPaddingValues(contentPaddingDp.uiDp)),
    horizontalArrangement = UiArrangement.spacedBy(spacingDp.uiDp),
  ) {
    DetachedRowScope.content()
  }
}

/**
 * The windowed list, composed whole.
 *
 * [placeholder] is what a device draws for a row the guest has not sent yet, which is a state that
 * cannot occur here: the preview has every row already. It is accepted and never drawn, and that
 * absence is one of the things a preview cannot show you.
 */
@Composable
fun <T> LazyVerticalList(
  items: List<T>,
  modifier: Modifier = Modifier,
  state: LazyListState = rememberLazyListState(),
  spacingDp: Int = 0,
  contentPaddingDp: Int = 0,
  overscan: Int = 6,
  placeholder: @Composable () -> Unit,
  item: @Composable (index: Int, value: T) -> Unit,
) {
  UiColumn(
    modifier = modifier.real.verticalScroll(rememberUiScrollState()).padding(UiPaddingValues(contentPaddingDp.uiDp)),
    verticalArrangement = UiArrangement.spacedBy(spacingDp.uiDp),
  ) {
    for (index in items.indices) {
      key(index) { item(index, items[index]) }
    }
  }
}

@Composable
fun <T> LazyHorizontalList(
  items: List<T>,
  modifier: Modifier = Modifier,
  state: LazyListState = rememberLazyListState(),
  spacingDp: Int = 0,
  contentPaddingDp: Int = 0,
  overscan: Int = 6,
  placeholder: @Composable () -> Unit,
  item: @Composable (index: Int, value: T) -> Unit,
) {
  UiRow(
    modifier = modifier.real.horizontalScroll(rememberUiScrollState()).padding(UiPaddingValues(contentPaddingDp.uiDp)),
    horizontalArrangement = UiArrangement.spacedBy(spacingDp.uiDp),
  ) {
    for (index in items.indices) {
      key(index) { item(index, items[index]) }
    }
  }
}

/** A pager, drawn as the page it is on. Swipe is a host gesture and is not offered here. */
@Composable
fun Pager(
  modifier: Modifier = Modifier,
  pager: PagerState? = null,
  content: @Composable () -> Unit,
) {
  UiBox(modifier = modifier.real) { content() }
}

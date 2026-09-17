/*
 * Project Dogwood -- the design-system tier (segment 1), for the preview path.
 *
 * Twenty-one components, and unlike the generated Material 3 tier none of them has a real-Compose
 * function of the same name and signature to forward to: `PrimaryButton`, `Price`, `StarRating`
 * and `SectionHeader` are *Dogwood's* vocabulary, whose implementations live in whichever design
 * system a product registered. A preview has no product, so each one below is a **hand-written
 * delegate**: the plainest Material 3 rendering of what the component means. This is the work the
 * plan predicted -- "for the primitive tier and the design system it is a hand-written delegate
 * per component, which is the work".
 *
 * What that means for a reader of the window: the *layout* a payload composed is exactly right,
 * and the *styling* is Material 3's rather than the product's. A `PrimaryButton` in a preview is a
 * filled Material button; on the device it is whatever the host bound `PrimaryButton` to.
 *
 * Two components draw an explicit placeholder rather than a guess, because guessing would be a
 * fiction: [AsyncImage] has no network and [DatePickerArea]/[TimePickerArea] are a host dialog.
 * Each draws a labelled box saying what the host would have answered.
 */
@file:Suppress("unused")

package dev.dogwood.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement as UiArrangement
import androidx.compose.foundation.layout.Box as UiBox
import androidx.compose.foundation.layout.Column as UiColumn
import androidx.compose.foundation.layout.PaddingValues as UiPaddingValues
import androidx.compose.foundation.layout.Row as UiRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState as rememberUiScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card as UiCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState as UiSnackbarHostState
import androidx.compose.material3.SnackbarResult as UiSnackbarResult
import androidx.compose.material3.Text as UiText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment as UiAlignment
import androidx.compose.ui.focus.FocusRequester as UiFocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight as UiFontWeight
import androidx.compose.ui.text.style.TextDecoration as UiTextDecoration
import androidx.compose.ui.unit.dp as uiDp
import androidx.compose.ui.window.Dialog as UiDialog

// ---------------------------------------------------------------------------------------------
// Buttons, cards and chips
// ---------------------------------------------------------------------------------------------

@Composable
fun PrimaryButton(
  label: TextValue,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Button(onClick = onClick, modifier = modifier.real, enabled = enabled) { UiText(label.render()) }
}

@Composable
fun PrimaryButton(
  label: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onClick: () -> Unit,
) = PrimaryButton(TextValue(label), modifier, enabled, onClick)

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  UiCard(modifier = modifier.real) { UiColumn(modifier = androidx.compose.ui.Modifier.padding(12.uiDp)) { content() } }
}

@Composable
fun Badge(text: TextValue, selected: Boolean = false, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  UiBox(
    modifier = modifier.real
      .border(1.uiDp, if (selected) scheme.primary else scheme.outlineVariant, RoundedCornerShape(50))
      .padding(horizontal = 8.uiDp, vertical = 2.uiDp),
  ) {
    UiText(text.render(), style = MaterialTheme.typography.labelSmall)
  }
}

@Composable
fun Badge(text: String, selected: Boolean = false, modifier: Modifier = Modifier) =
  Badge(TextValue(text), selected, modifier)

@Composable
fun Divider(modifier: Modifier = Modifier) {
  HorizontalDivider(modifier = modifier.real)
}

@Composable
fun Chip(
  text: TextValue,
  selected: Boolean = false,
  modifier: Modifier = Modifier,
  onSelectedChange: (Boolean) -> Unit,
) {
  FilterChip(
    selected = selected,
    onClick = { onSelectedChange(!selected) },
    label = { UiText(text.render()) },
    modifier = modifier.real,
  )
}

@Composable
fun Chip(
  text: String,
  selected: Boolean = false,
  modifier: Modifier = Modifier,
  onSelectedChange: (Boolean) -> Unit,
) = Chip(TextValue(text), selected, modifier, onSelectedChange)

// ---------------------------------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------------------------------

@Composable
fun Price(
  price: TextValue,
  modifier: Modifier = Modifier,
  leadingText: TextValue? = null,
  previousPrice: TextValue? = null,
  trailingText: TextValue? = null,
) {
  UiRow(
    modifier = modifier.real,
    horizontalArrangement = UiArrangement.spacedBy(6.uiDp),
    verticalAlignment = UiAlignment.CenterVertically,
  ) {
    if (leadingText != null) UiText(leadingText.render(), style = MaterialTheme.typography.bodySmall)
    UiText(price.render(), fontWeight = UiFontWeight.SemiBold)
    if (previousPrice != null) {
      UiText(
        previousPrice.render(),
        style = MaterialTheme.typography.bodySmall,
        textDecoration = UiTextDecoration.LineThrough,
      )
    }
    if (trailingText != null) UiText(trailingText.render(), style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
fun Price(
  price: String,
  modifier: Modifier = Modifier,
  leadingText: String? = null,
  previousPrice: String? = null,
  trailingText: String? = null,
) = Price(
  TextValue(price),
  modifier,
  leadingText?.let(::TextValue),
  previousPrice?.let(::TextValue),
  trailingText?.let(::TextValue),
)

@Composable
fun StarRating(rating: Float, modifier: Modifier = Modifier, label: TextValue? = null) {
  val filled = rating.coerceIn(0f, 5f).toInt()
  UiRow(
    modifier = modifier.real,
    horizontalArrangement = UiArrangement.spacedBy(2.uiDp),
    verticalAlignment = UiAlignment.CenterVertically,
  ) {
    UiText(buildString { repeat(5) { append(if (it < filled) '★' else '☆') } })
    if (label != null) UiText(label.render(), style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
fun SectionHeader(title: TextValue, modifier: Modifier = Modifier, description: TextValue? = null) {
  UiColumn(modifier = modifier.real, verticalArrangement = UiArrangement.spacedBy(2.uiDp)) {
    UiText(title.render(), style = MaterialTheme.typography.titleMedium)
    if (description != null) {
      UiText(
        description.render(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, description: String? = null) =
  SectionHeader(TextValue(title), modifier, description?.let(::TextValue))

/**
 * An icon named rather than carried -- no asset crosses this boundary (ADR-003).
 *
 * The host owns the name-to-drawable mapping, so a preview has no drawable to show. It draws the
 * name's initial in a bordered square of the requested size: the layout is right, the glyph is
 * visibly a placeholder, and nobody mistakes it for the icon the device will draw.
 */
@Composable
fun Icon(
  name: String,
  contentDescription: String? = null,
  modifier: Modifier = Modifier,
  sizeDp: Int = 24,
  tint: Color? = null,
) {
  val colour = tint?.resolve() ?: MaterialTheme.colorScheme.onSurfaceVariant
  UiBox(
    modifier = modifier.real.size(sizeDp.uiDp).border(1.uiDp, colour, RoundedCornerShape(4.uiDp)),
    contentAlignment = UiAlignment.Center,
  ) {
    UiText(
      name.take(1).uppercase(),
      color = colour,
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

/**
 * An image fetched by the host, which a preview cannot fetch.
 *
 * A network request from a preview would be a different request from the one the client makes --
 * different cache, different headers, different failure modes -- so none is made. The box names
 * the last path segment of the address so a developer can see *which* image belongs there.
 */
@Composable
fun AsyncImage(
  url: String,
  contentDescription: String? = null,
  modifier: Modifier = Modifier,
  cornerRadiusDp: Int = 8,
) {
  UiBox(
    modifier = modifier.real
      .border(1.uiDp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(cornerRadiusDp.uiDp)),
    contentAlignment = UiAlignment.Center,
  ) {
    UiText(
      "image: " + url.substringAfterLast('/').ifEmpty { url },
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

// ---------------------------------------------------------------------------------------------
// Containers that hold a holder
// ---------------------------------------------------------------------------------------------

/**
 * A scrolling container whose position is mirrored to the guest.
 *
 * The real Compose scroll state is the source of truth and is copied into the Dogwood holder every
 * time it moves -- *every* time, which is where the preview and the device part company:
 * `reportEveryDp` is a threshold that exists so a drag does not become sixty crossings, and there
 * is nothing here for it to throttle.
 */
@Composable
fun ScrollArea(
  modifier: Modifier = Modifier,
  horizontal: Boolean = false,
  scroll: ScrollState? = null,
  content: @Composable () -> Unit,
) {
  val inner = rememberUiScrollState()
  val density = LocalDensity.current

  if (scroll != null) {
    LaunchedEffect(inner, scroll) {
      snapshotFlow { Triple(inner.value, inner.maxValue, inner.isScrollInProgress) }.collect { (value, max, moving) ->
        scroll.offsetDp = with(density) { value.toDp().value.toInt() }
        scroll.maxOffsetDp = if (max == Int.MAX_VALUE) -1 else with(density) { max.toDp().value.toInt() }
        scroll.isScrollInProgress = moving
      }
    }
    LaunchedEffect(scroll.targetSequence) {
      if (scroll.targetSequence == 0) return@LaunchedEffect
      val target = if (scroll.targetDp == SCROLL_TO_END) {
        inner.maxValue
      } else {
        with(density) { scroll.targetDp.uiDp.toPx() }.toInt()
      }
      if (scroll.targetAnimated) inner.animateScrollTo(target) else inner.scrollTo(target)
    }
  }

  if (horizontal) {
    UiRow(modifier = modifier.real.horizontalScroll(inner)) { content() }
  } else {
    UiColumn(modifier = modifier.real.verticalScroll(inner)) { content() }
  }
}

/**
 * A snackbar host, and the suspension that makes it a conversation rather than a notification.
 *
 * The guest's `showSnackbar` suspends until the user acts; the real Material 3 host is what acts,
 * and its answer is reported straight back into the holder.
 */
@Composable
fun SnackbarArea(
  modifier: Modifier = Modifier,
  snackbars: SnackbarHostState? = null,
  content: @Composable () -> Unit,
) {
  val host = remember { UiSnackbarHostState() }
  if (snackbars != null) {
    LaunchedEffect(snackbars.sequence) {
      if (snackbars.sequence == 0) return@LaunchedEffect
      val outcome = host.showSnackbar(snackbars.message, actionLabel = snackbars.actionLabel)
      snackbars.report(snackbars.sequence, outcome == UiSnackbarResult.ActionPerformed)
    }
  }
  UiBox(modifier = modifier.real) {
    content()
    SnackbarHost(host, modifier = androidx.compose.ui.Modifier.align(UiAlignment.BottomCenter))
  }
}

@Composable
fun Presence(
  visible: Boolean,
  modifier: Modifier = Modifier,
  enter: String? = null,
  exit: String? = null,
  onExited: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  AnimatedVisibility(visible = visible, modifier = modifier.real) { content() }
  if (!visible) LaunchedEffect(Unit) { onExited?.invoke() }
}

@Composable
fun Dialog(
  visible: Boolean,
  modifier: Modifier = Modifier,
  dismissOnBackPress: Boolean = true,
  dismissOnClickOutside: Boolean = true,
  onDismissRequest: () -> Unit = {},
  content: @Composable () -> Unit,
) {
  if (!visible) return
  UiDialog(onDismissRequest = onDismissRequest) {
    UiCard(modifier = modifier.real) {
      UiColumn(modifier = androidx.compose.ui.Modifier.padding(16.uiDp)) { content() }
    }
  }
}

/** A bottom sheet. Drawn in place when the holder says it is visible; there is no drag to make. */
@Composable
fun SheetArea(
  modifier: Modifier = Modifier,
  sheet: SheetState? = null,
  content: @Composable () -> Unit,
) {
  if (sheet != null && !sheet.isVisible) return
  UiCard(modifier = modifier.real) {
    UiColumn(modifier = androidx.compose.ui.Modifier.padding(16.uiDp)) { content() }
  }
}

@Composable
fun Menu(
  expanded: Boolean,
  modifier: Modifier = Modifier,
  onDismissRequest: () -> Unit = {},
  anchor: @Composable () -> Unit,
  content: @Composable () -> Unit,
) {
  UiBox(modifier = modifier.real) {
    anchor()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) { content() }
  }
}

@Composable
fun MenuItem(
  label: TextValue,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: String? = null,
  onClick: () -> Unit = {},
) {
  DropdownMenuItem(
    text = { UiText(label.render()) },
    onClick = onClick,
    modifier = modifier.real,
    enabled = enabled,
    leadingIcon = if (icon == null) null else { { Icon(icon, sizeDp = 18) } },
  )
}

@Composable
fun MenuItem(
  label: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: String? = null,
  onClick: () -> Unit = {},
) = MenuItem(TextValue(label), modifier, enabled, icon, onClick)

/**
 * A date picker, which on a device is the *platform's* dialog and not a Compose one.
 *
 * ADR-025: the point of the holder is that the host shows whatever its platform shows -- a
 * Material dialog on Android, a wheel on iOS. There is no platform here to ask, so the area draws
 * a labelled placeholder and never completes the guest's suspension, which is exactly what a
 * preview knows about a platform dialog: nothing.
 */
@Composable
fun DatePickerArea(modifier: Modifier = Modifier, picker: DatePickerState? = null) =
  HostAnswers(modifier, "the host's date picker")

@Composable
fun TimePickerArea(modifier: Modifier = Modifier, picker: TimePickerState? = null) =
  HostAnswers(modifier, "the host's time picker")

@Composable
private fun HostAnswers(modifier: Modifier, what: String) {
  UiBox(
    modifier = modifier.real
      .fillMaxWidth()
      .border(1.uiDp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.uiDp))
      .padding(12.uiDp),
    contentAlignment = UiAlignment.Center,
  ) {
    UiText(
      "$what would answer here",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

// ---------------------------------------------------------------------------------------------
// Text input
// ---------------------------------------------------------------------------------------------

/**
 * The controlled field, with the host's half done here.
 *
 * ADR-019 puts the buffer on the host: the guest sends nothing per keystroke, and what it reads
 * back is the *unmasked* value with an edit count. The preview does the same work in the same
 * order -- the mask is applied for display, the length limit is applied before the value is
 * reported, and the guest sees digits -- so the screen's "guest sees" line says what it says on a
 * device. The keyboard type is a device concept and is accepted and ignored.
 */
@Composable
fun TextInput(
  text: String,
  version: Int = 0,
  modifier: Modifier = Modifier,
  label: TextValue? = null,
  placeholder: TextValue? = null,
  enabled: Boolean = true,
  singleLine: Boolean = true,
  maxLength: Int = -1,
  mask: String? = null,
  keyboard: String? = null,
  showCounter: Boolean = false,
  onValueChange: (String, Int) -> Unit,
  focus: FocusRequester? = null,
) {
  var edits by remember { mutableStateOf(version) }
  val requester = remember { UiFocusRequester() }
  val focusManager = LocalFocusManager.current

  if (focus != null) {
    LaunchedEffect(focus.sequence) {
      if (focus.sequence == 0) return@LaunchedEffect
      if (focus.requested) runCatching { requester.requestFocus() } else focusManager.clearFocus()
    }
  }

  val capacity = when {
    mask != null -> mask.count { it == '#' }
    maxLength >= 0 -> maxLength
    else -> Int.MAX_VALUE
  }

  OutlinedTextField(
    value = if (mask == null) text else applyMask(text, mask),
    onValueChange = { typed ->
      val raw = if (mask == null) typed else typed.filter { it.isLetterOrDigit() }
      val clipped = if (capacity == Int.MAX_VALUE) raw else raw.take(capacity)
      edits += 1
      onValueChange(clipped, edits)
    },
    modifier = modifier.real.focusRequester(requester),
    enabled = enabled,
    singleLine = singleLine,
    label = if (label == null) null else { { UiText(label.render()) } },
    placeholder = if (placeholder == null) null else { { UiText(placeholder.render()) } },
    supportingText = if (!showCounter || capacity == Int.MAX_VALUE) {
      null
    } else {
      { UiText("${text.length}/$capacity") }
    },
  )
}

private fun applyMask(raw: String, mask: String): String {
  val builder = StringBuilder()
  var index = 0
  for (slot in mask) {
    if (index >= raw.length) break
    if (slot == '#') {
      builder.append(raw[index])
      index += 1
    } else {
      builder.append(slot)
    }
  }
  return builder.toString()
}

/** The wrapper ADR-019 points a developer at: a holder on one side, the host's buffer on the other. */
@Composable
fun TextField(
  state: TextFieldState,
  modifier: Modifier = Modifier,
  label: TextValue? = null,
  placeholder: TextValue? = null,
  enabled: Boolean = true,
  singleLine: Boolean = true,
  maxLength: Int = -1,
  mask: String? = null,
  keyboard: String? = null,
  showCounter: Boolean = false,
  focus: FocusRequester? = null,
) {
  TextInput(
    text = state.text,
    version = state.acknowledged,
    modifier = modifier,
    label = label,
    placeholder = placeholder,
    enabled = enabled,
    singleLine = singleLine,
    maxLength = maxLength,
    mask = mask,
    keyboard = keyboard,
    showCounter = showCounter,
    onValueChange = { value, editCount -> state.onHostEdit(value, editCount) },
    focus = focus,
  )
}

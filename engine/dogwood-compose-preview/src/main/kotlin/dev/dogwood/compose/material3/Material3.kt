/*
 * Project Dogwood -- the generated Material 3 tier (segment 255), for the preview path.
 *
 * **This is the half of a preview that should not be hand-written, and today is.**
 *
 * `plans/generator-v2.md` milestone M6 says it plainly: for a generated tier a preview is nearly
 * free, "because the guest stub and the library function have the same signature by construction:
 * the generator emits, from the same parse, a JVM package with the same names whose bodies call
 * `androidx.compose.material3` directly." That is not built. The generator emits two surfaces --
 * the Kotlin/JavaScript guest stub and the host binding -- and a third, this one, is the work that
 * closes M6 properly.
 *
 * What is here instead is the same thing, derived once rather than on every build. Each delegate
 * below was produced mechanically from the pair the generator *does* emit: the guest stub supplies
 * the signature, and the host binding supplies the argument mapping and the library default for
 * every parameter the guest may omit (`ButtonDefaults.shape`, `DividerDefaults.Thickness`, and so
 * on -- the same expressions, read off the same generated file). It is therefore correct for the
 * dictionary it was derived from and **frozen at that dictionary**: a tier regenerated with new
 * components does not update this file, and a payload calling one of them will fail to compile for
 * the preview target with an unresolved reference. That failure is loud, which is the only
 * acceptable property for a file in this position.
 *
 * **Not covered**, because their host bindings resolve a live state holder that has no counterpart
 * on the guest side yet (`plans/close-the-backlog.md` Group 3 is the work that gives them one), or
 * because their content slot takes the layout insets a `Scaffold` computes: `Scaffold`,
 * `BottomSheetScaffold`, `TimePicker`, `TimeInput`, `TopSearchBar`, `SwipeToDismissBox`,
 * `SnackbarHost`, `TriStateCheckbox`, and the state-holder overloads of `Slider` and `RangeSlider`.
 * The two navigation drawers are covered, by hand, at the end of this file.
 *
 * Everything a delegate does is forward. There is no recording, no wire and no host tree: a
 * `Button` here is `androidx.compose.material3.Button`, reached by an ordinary Kotlin call.
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:Suppress("unused", "NAME_SHADOWING", "UNUSED_PARAMETER", "DEPRECATION")

package dev.dogwood.compose.material3

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import dev.dogwood.compose.*

@androidx.compose.runtime.Composable
fun AlertDialog(
  onDismissRequest: () -> Unit,
  confirmButton: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  dismissButton: (@Composable () -> Unit)? = null,
  icon: (@Composable () -> Unit)? = null,
  title: (@Composable () -> Unit)? = null,
  text: (@Composable () -> Unit)? = null,
  shape: Shape? = null,
  containerColor: Color? = null,
  iconContentColor: Color? = null,
  titleContentColor: Color? = null,
  textContentColor: Color? = null,
  tonalElevation: Dp? = null,
) {
  val dwOnDismissRequest = onDismissRequest
  val dwModifier = modifier.real
  val dwShape = shape?.real ?: (AlertDialogDefaults.shape)
  val dwContainerColor = containerColor?.resolve() ?: (AlertDialogDefaults.containerColor)
  val dwIconContentColor = iconContentColor?.resolve() ?: (AlertDialogDefaults.iconContentColor)
  val dwTitleContentColor = titleContentColor?.resolve() ?: (AlertDialogDefaults.titleContentColor)
  val dwTextContentColor = textContentColor?.resolve() ?: (AlertDialogDefaults.textContentColor)
  val dwTonalElevation = tonalElevation?.real() ?: (AlertDialogDefaults.TonalElevation)
  androidx.compose.material3.AlertDialog(
    onDismissRequest = dwOnDismissRequest,
    confirmButton = { confirmButton() },
    modifier = dwModifier,
    dismissButton = if (dismissButton == null) null else ({ dismissButton() }),
    icon = if (icon == null) null else ({ icon() }),
    title = if (title == null) null else ({ title() }),
    text = if (text == null) null else ({ text() }),
    shape = dwShape,
    containerColor = dwContainerColor,
    iconContentColor = dwIconContentColor,
    titleContentColor = dwTitleContentColor,
    textContentColor = dwTextContentColor,
    tonalElevation = dwTonalElevation,
  )
}

@androidx.compose.runtime.Composable
fun BasicAlertDialog(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val dwOnDismissRequest = onDismissRequest
  val dwModifier = modifier.real
  androidx.compose.material3.BasicAlertDialog(
    onDismissRequest = dwOnDismissRequest,
    modifier = dwModifier,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun TopAppBar(
  title: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  navigationIcon: (@Composable () -> Unit)? = null,
  actions: (@Composable () -> Unit)? = null,
  expandedHeight: Dp? = null,
) {
  val dwModifier = modifier.real
  val dwExpandedHeight = expandedHeight?.real() ?: (TopAppBarDefaults.TopAppBarExpandedHeight)
  androidx.compose.material3.TopAppBar(
    title = { title() },
    modifier = dwModifier,
    navigationIcon = { navigationIcon?.invoke() },
    actions = { actions?.invoke() },
    expandedHeight = dwExpandedHeight,
  )
}

@androidx.compose.runtime.Composable
fun CenterAlignedTopAppBar(
  title: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  navigationIcon: (@Composable () -> Unit)? = null,
  actions: (@Composable () -> Unit)? = null,
  expandedHeight: Dp? = null,
) {
  val dwModifier = modifier.real
  val dwExpandedHeight = expandedHeight?.real() ?: (TopAppBarDefaults.TopAppBarExpandedHeight)
  androidx.compose.material3.CenterAlignedTopAppBar(
    title = { title() },
    modifier = dwModifier,
    navigationIcon = { navigationIcon?.invoke() },
    actions = { actions?.invoke() },
    expandedHeight = dwExpandedHeight,
  )
}

@androidx.compose.runtime.Composable
fun MediumTopAppBar(
  title: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  navigationIcon: (@Composable () -> Unit)? = null,
  actions: (@Composable () -> Unit)? = null,
  collapsedHeight: Dp? = null,
  expandedHeight: Dp? = null,
) {
  val dwModifier = modifier.real
  val dwCollapsedHeight = collapsedHeight?.real() ?: (TopAppBarDefaults.MediumAppBarCollapsedHeight)
  val dwExpandedHeight = expandedHeight?.real() ?: (TopAppBarDefaults.MediumAppBarExpandedHeight)
  androidx.compose.material3.MediumTopAppBar(
    title = { title() },
    modifier = dwModifier,
    navigationIcon = { navigationIcon?.invoke() },
    actions = { actions?.invoke() },
    collapsedHeight = dwCollapsedHeight,
    expandedHeight = dwExpandedHeight,
  )
}

@androidx.compose.runtime.Composable
fun LargeTopAppBar(
  title: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  navigationIcon: (@Composable () -> Unit)? = null,
  actions: (@Composable () -> Unit)? = null,
  collapsedHeight: Dp? = null,
  expandedHeight: Dp? = null,
) {
  val dwModifier = modifier.real
  val dwCollapsedHeight = collapsedHeight?.real() ?: (TopAppBarDefaults.LargeAppBarCollapsedHeight)
  val dwExpandedHeight = expandedHeight?.real() ?: (TopAppBarDefaults.LargeAppBarExpandedHeight)
  androidx.compose.material3.LargeTopAppBar(
    title = { title() },
    modifier = dwModifier,
    navigationIcon = { navigationIcon?.invoke() },
    actions = { actions?.invoke() },
    collapsedHeight = dwCollapsedHeight,
    expandedHeight = dwExpandedHeight,
  )
}

@androidx.compose.runtime.Composable
fun BottomAppBar(
  actions: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  floatingActionButton: (@Composable () -> Unit)? = null,
  containerColor: Color? = null,
  contentColor: Color? = null,
  tonalElevation: Dp? = null,
  contentPadding: PaddingValues? = null,
) {
  val dwModifier = modifier.real
  val dwContainerColor = containerColor?.resolve() ?: (BottomAppBarDefaults.containerColor)
  val dwContentColor = contentColor?.resolve() ?: (contentColorFor(dwContainerColor))
  val dwTonalElevation = tonalElevation?.real() ?: (BottomAppBarDefaults.ContainerElevation)
  val dwContentPadding = contentPadding?.real() ?: (BottomAppBarDefaults.ContentPadding)
  androidx.compose.material3.BottomAppBar(
    actions = { actions() },
    modifier = dwModifier,
    floatingActionButton = if (floatingActionButton == null) null else ({ floatingActionButton() }),
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    tonalElevation = dwTonalElevation,
    contentPadding = dwContentPadding,
  )
}

@androidx.compose.runtime.Composable
fun BottomAppBar(
  modifier: Modifier = Modifier,
  containerColor: Color? = null,
  contentColor: Color? = null,
  tonalElevation: Dp? = null,
  contentPadding: PaddingValues? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwContainerColor = containerColor?.resolve() ?: (BottomAppBarDefaults.containerColor)
  val dwContentColor = contentColor?.resolve() ?: (contentColorFor(dwContainerColor))
  val dwTonalElevation = tonalElevation?.real() ?: (BottomAppBarDefaults.ContainerElevation)
  val dwContentPadding = contentPadding?.real() ?: (BottomAppBarDefaults.ContentPadding)
  androidx.compose.material3.BottomAppBar(
    modifier = dwModifier,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    tonalElevation = dwTonalElevation,
    contentPadding = dwContentPadding,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun BadgedBox(
  badge: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  androidx.compose.material3.BadgedBox(
    badge = { badge() },
    modifier = dwModifier,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun Badge(
  modifier: Modifier = Modifier,
  containerColor: Color? = null,
  contentColor: Color? = null,
  content: (@Composable () -> Unit)? = null,
) {
  val dwModifier = modifier.real
  val dwContainerColor = containerColor?.resolve() ?: (BadgeDefaults.containerColor)
  val dwContentColor = contentColor?.resolve() ?: (contentColorFor(dwContainerColor))
  androidx.compose.material3.Badge(
    modifier = dwModifier,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    content = if (content == null) null else ({ content() }),
  )
}

@androidx.compose.runtime.Composable
fun Button(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
  contentPadding: PaddingValues? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (ButtonDefaults.shape)
  val dwBorder = border?.real() ?: (null)
  val dwContentPadding = contentPadding?.real() ?: (ButtonDefaults.ContentPadding)
  androidx.compose.material3.Button(
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    border = dwBorder,
    contentPadding = dwContentPadding,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun ElevatedButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
  contentPadding: PaddingValues? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (ButtonDefaults.elevatedShape)
  val dwBorder = border?.real() ?: (null)
  val dwContentPadding = contentPadding?.real() ?: (ButtonDefaults.ContentPadding)
  androidx.compose.material3.ElevatedButton(
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    border = dwBorder,
    contentPadding = dwContentPadding,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun FilledTonalButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
  contentPadding: PaddingValues? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (ButtonDefaults.filledTonalShape)
  val dwBorder = border?.real() ?: (null)
  val dwContentPadding = contentPadding?.real() ?: (ButtonDefaults.ContentPadding)
  androidx.compose.material3.FilledTonalButton(
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    border = dwBorder,
    contentPadding = dwContentPadding,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun OutlinedButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
  contentPadding: PaddingValues? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (ButtonDefaults.outlinedShape)
  val dwBorder = border?.real() ?: (ButtonDefaults.outlinedButtonBorder(dwEnabled))
  val dwContentPadding = contentPadding?.real() ?: (ButtonDefaults.ContentPadding)
  androidx.compose.material3.OutlinedButton(
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    border = dwBorder,
    contentPadding = dwContentPadding,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun TextButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
  contentPadding: PaddingValues? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (ButtonDefaults.textShape)
  val dwBorder = border?.real() ?: (null)
  val dwContentPadding = contentPadding?.real() ?: (ButtonDefaults.TextButtonContentPadding)
  androidx.compose.material3.TextButton(
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    border = dwBorder,
    contentPadding = dwContentPadding,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun Card(
  modifier: Modifier = Modifier,
  shape: Shape? = null,
  border: BorderStroke? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwShape = shape?.real ?: (CardDefaults.shape)
  val dwBorder = border?.real() ?: (null)
  androidx.compose.material3.Card(
    modifier = dwModifier,
    shape = dwShape,
    border = dwBorder,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun Card(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (CardDefaults.shape)
  val dwBorder = border?.real() ?: (null)
  androidx.compose.material3.Card(
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    border = dwBorder,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun ElevatedCard(
  modifier: Modifier = Modifier,
  shape: Shape? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwShape = shape?.real ?: (CardDefaults.elevatedShape)
  androidx.compose.material3.ElevatedCard(
    modifier = dwModifier,
    shape = dwShape,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun ElevatedCard(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (CardDefaults.elevatedShape)
  androidx.compose.material3.ElevatedCard(
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun OutlinedCard(
  modifier: Modifier = Modifier,
  shape: Shape? = null,
  border: BorderStroke? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwShape = shape?.real ?: (CardDefaults.outlinedShape)
  val dwBorder = border?.real() ?: (CardDefaults.outlinedCardBorder())
  androidx.compose.material3.OutlinedCard(
    modifier = dwModifier,
    shape = dwShape,
    border = dwBorder,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun OutlinedCard(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (CardDefaults.outlinedShape)
  val dwBorder = border?.real() ?: (CardDefaults.outlinedCardBorder(dwEnabled))
  androidx.compose.material3.OutlinedCard(
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    border = dwBorder,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun Checkbox(
  checked: Boolean,
  onCheckedChange: ((Boolean) -> Unit)? = null,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
) {
  val dwChecked = checked
  val dwOnCheckedChange = onCheckedChange
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  androidx.compose.material3.Checkbox(
    checked = dwChecked,
    onCheckedChange = dwOnCheckedChange,
    modifier = dwModifier,
    enabled = dwEnabled,
  )
}

@androidx.compose.runtime.Composable
fun AssistChip(
  onClick: () -> Unit,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  leadingIcon: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (AssistChipDefaults.shape)
  val dwBorder = border?.real() ?: (AssistChipDefaults.assistChipBorder(dwEnabled))
  androidx.compose.material3.AssistChip(
    onClick = dwOnClick,
    label = { label() },
    modifier = dwModifier,
    enabled = dwEnabled,
    leadingIcon = if (leadingIcon == null) null else ({ leadingIcon() }),
    trailingIcon = if (trailingIcon == null) null else ({ trailingIcon() }),
    shape = dwShape,
    border = dwBorder,
  )
}

@androidx.compose.runtime.Composable
fun ElevatedAssistChip(
  onClick: () -> Unit,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  leadingIcon: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (AssistChipDefaults.shape)
  val dwBorder = border?.real() ?: (null)
  androidx.compose.material3.ElevatedAssistChip(
    onClick = dwOnClick,
    label = { label() },
    modifier = dwModifier,
    enabled = dwEnabled,
    leadingIcon = if (leadingIcon == null) null else ({ leadingIcon() }),
    trailingIcon = if (trailingIcon == null) null else ({ trailingIcon() }),
    shape = dwShape,
    border = dwBorder,
  )
}

@androidx.compose.runtime.Composable
fun FilterChip(
  selected: Boolean,
  onClick: () -> Unit,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  leadingIcon: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
) {
  val dwSelected = selected
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (FilterChipDefaults.shape)
  val dwBorder = border?.real() ?: (FilterChipDefaults.filterChipBorder(dwEnabled, dwSelected))
  androidx.compose.material3.FilterChip(
    selected = dwSelected,
    onClick = dwOnClick,
    label = { label() },
    modifier = dwModifier,
    enabled = dwEnabled,
    leadingIcon = if (leadingIcon == null) null else ({ leadingIcon() }),
    trailingIcon = if (trailingIcon == null) null else ({ trailingIcon() }),
    shape = dwShape,
    border = dwBorder,
  )
}

@androidx.compose.runtime.Composable
fun ElevatedFilterChip(
  selected: Boolean,
  onClick: () -> Unit,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  leadingIcon: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
) {
  val dwSelected = selected
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (FilterChipDefaults.shape)
  val dwBorder = border?.real() ?: (null)
  androidx.compose.material3.ElevatedFilterChip(
    selected = dwSelected,
    onClick = dwOnClick,
    label = { label() },
    modifier = dwModifier,
    enabled = dwEnabled,
    leadingIcon = if (leadingIcon == null) null else ({ leadingIcon() }),
    trailingIcon = if (trailingIcon == null) null else ({ trailingIcon() }),
    shape = dwShape,
    border = dwBorder,
  )
}

@androidx.compose.runtime.Composable
fun InputChip(
  selected: Boolean,
  onClick: () -> Unit,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  leadingIcon: (@Composable () -> Unit)? = null,
  avatar: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
) {
  val dwSelected = selected
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (InputChipDefaults.shape)
  val dwBorder = border?.real() ?: (InputChipDefaults.inputChipBorder(dwEnabled, dwSelected))
  androidx.compose.material3.InputChip(
    selected = dwSelected,
    onClick = dwOnClick,
    label = { label() },
    modifier = dwModifier,
    enabled = dwEnabled,
    leadingIcon = if (leadingIcon == null) null else ({ leadingIcon() }),
    avatar = if (avatar == null) null else ({ avatar() }),
    trailingIcon = if (trailingIcon == null) null else ({ trailingIcon() }),
    shape = dwShape,
    border = dwBorder,
  )
}

@androidx.compose.runtime.Composable
fun SuggestionChip(
  onClick: () -> Unit,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  icon: (@Composable () -> Unit)? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (SuggestionChipDefaults.shape)
  val dwBorder = border?.real() ?: (SuggestionChipDefaults.suggestionChipBorder(dwEnabled))
  androidx.compose.material3.SuggestionChip(
    onClick = dwOnClick,
    label = { label() },
    modifier = dwModifier,
    enabled = dwEnabled,
    icon = if (icon == null) null else ({ icon() }),
    shape = dwShape,
    border = dwBorder,
  )
}

@androidx.compose.runtime.Composable
fun ElevatedSuggestionChip(
  onClick: () -> Unit,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  icon: (@Composable () -> Unit)? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (SuggestionChipDefaults.shape)
  val dwBorder = border?.real() ?: (null)
  androidx.compose.material3.ElevatedSuggestionChip(
    onClick = dwOnClick,
    label = { label() },
    modifier = dwModifier,
    enabled = dwEnabled,
    icon = if (icon == null) null else ({ icon() }),
    shape = dwShape,
    border = dwBorder,
  )
}

@androidx.compose.runtime.Composable
fun DatePickerDialog(
  onDismissRequest: () -> Unit,
  confirmButton: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  dismissButton: (@Composable () -> Unit)? = null,
  shape: Shape? = null,
  tonalElevation: Dp? = null,
  content: @Composable () -> Unit,
) {
  val dwOnDismissRequest = onDismissRequest
  val dwModifier = modifier.real
  val dwShape = shape?.real ?: (DatePickerDefaults.shape)
  val dwTonalElevation = tonalElevation?.real() ?: (DatePickerDefaults.TonalElevation)
  androidx.compose.material3.DatePickerDialog(
    onDismissRequest = dwOnDismissRequest,
    confirmButton = { confirmButton() },
    modifier = dwModifier,
    dismissButton = if (dismissButton == null) null else ({ dismissButton() }),
    shape = dwShape,
    tonalElevation = dwTonalElevation,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun HorizontalDivider(
  modifier: Modifier = Modifier,
  thickness: Dp? = null,
  color: Color? = null,
) {
  val dwModifier = modifier.real
  val dwThickness = thickness?.real() ?: (DividerDefaults.Thickness)
  val dwColor = color?.resolve() ?: (DividerDefaults.color)
  androidx.compose.material3.HorizontalDivider(
    modifier = dwModifier,
    thickness = dwThickness,
    color = dwColor,
  )
}

@androidx.compose.runtime.Composable
fun VerticalDivider(
  modifier: Modifier = Modifier,
  thickness: Dp? = null,
  color: Color? = null,
) {
  val dwModifier = modifier.real
  val dwThickness = thickness?.real() ?: (DividerDefaults.Thickness)
  val dwColor = color?.resolve() ?: (DividerDefaults.color)
  androidx.compose.material3.VerticalDivider(
    modifier = dwModifier,
    thickness = dwThickness,
    color = dwColor,
  )
}

@androidx.compose.runtime.Composable
fun VerticalDragHandle(
  modifier: Modifier = Modifier,
) {
  val dwModifier = modifier.real
  androidx.compose.material3.VerticalDragHandle(
    modifier = dwModifier,
  )
}

@androidx.compose.runtime.Composable
fun ExposedDropdownMenuBox(
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val dwExpanded = expanded
  val dwOnExpandedChange = onExpandedChange
  val dwModifier = modifier.real
  androidx.compose.material3.ExposedDropdownMenuBox(
    expanded = dwExpanded,
    onExpandedChange = dwOnExpandedChange,
    modifier = dwModifier,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun FloatingActionButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  shape: Shape? = null,
  containerColor: Color? = null,
  contentColor: Color? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwShape = shape?.real ?: (FloatingActionButtonDefaults.shape)
  val dwContainerColor = containerColor?.resolve() ?: (FloatingActionButtonDefaults.containerColor)
  val dwContentColor = contentColor?.resolve() ?: (contentColorFor(dwContainerColor))
  androidx.compose.material3.FloatingActionButton(
    onClick = dwOnClick,
    modifier = dwModifier,
    shape = dwShape,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun SmallFloatingActionButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  shape: Shape? = null,
  containerColor: Color? = null,
  contentColor: Color? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwShape = shape?.real ?: (FloatingActionButtonDefaults.smallShape)
  val dwContainerColor = containerColor?.resolve() ?: (FloatingActionButtonDefaults.containerColor)
  val dwContentColor = contentColor?.resolve() ?: (contentColorFor(dwContainerColor))
  androidx.compose.material3.SmallFloatingActionButton(
    onClick = dwOnClick,
    modifier = dwModifier,
    shape = dwShape,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun LargeFloatingActionButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  shape: Shape? = null,
  containerColor: Color? = null,
  contentColor: Color? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwShape = shape?.real ?: (FloatingActionButtonDefaults.largeShape)
  val dwContainerColor = containerColor?.resolve() ?: (FloatingActionButtonDefaults.containerColor)
  val dwContentColor = contentColor?.resolve() ?: (contentColorFor(dwContainerColor))
  androidx.compose.material3.LargeFloatingActionButton(
    onClick = dwOnClick,
    modifier = dwModifier,
    shape = dwShape,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun ExtendedFloatingActionButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  shape: Shape? = null,
  containerColor: Color? = null,
  contentColor: Color? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwShape = shape?.real ?: (FloatingActionButtonDefaults.extendedFabShape)
  val dwContainerColor = containerColor?.resolve() ?: (FloatingActionButtonDefaults.containerColor)
  val dwContentColor = contentColor?.resolve() ?: (contentColorFor(dwContainerColor))
  androidx.compose.material3.ExtendedFloatingActionButton(
    onClick = dwOnClick,
    modifier = dwModifier,
    shape = dwShape,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun ExtendedFloatingActionButton(
  text: @Composable () -> Unit,
  icon: @Composable () -> Unit,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  expanded: Boolean? = null,
  shape: Shape? = null,
  containerColor: Color? = null,
  contentColor: Color? = null,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwExpanded = expanded ?: (true)
  val dwShape = shape?.real ?: (FloatingActionButtonDefaults.extendedFabShape)
  val dwContainerColor = containerColor?.resolve() ?: (FloatingActionButtonDefaults.containerColor)
  val dwContentColor = contentColor?.resolve() ?: (contentColorFor(dwContainerColor))
  androidx.compose.material3.ExtendedFloatingActionButton(
    text = { text() },
    icon = { icon() },
    onClick = dwOnClick,
    modifier = dwModifier,
    expanded = dwExpanded,
    shape = dwShape,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
  )
}

@androidx.compose.runtime.Composable
fun FilledIconButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (IconButtonDefaults.filledShape)
  androidx.compose.material3.FilledIconButton(
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun FilledIconToggleButton(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  content: @Composable () -> Unit,
) {
  val dwChecked = checked
  val dwOnCheckedChange = onCheckedChange
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (IconButtonDefaults.filledShape)
  androidx.compose.material3.FilledIconToggleButton(
    checked = dwChecked,
    onCheckedChange = dwOnCheckedChange,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun FilledTonalIconButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (IconButtonDefaults.filledShape)
  androidx.compose.material3.FilledTonalIconButton(
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun FilledTonalIconToggleButton(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  content: @Composable () -> Unit,
) {
  val dwChecked = checked
  val dwOnCheckedChange = onCheckedChange
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (IconButtonDefaults.filledShape)
  androidx.compose.material3.FilledTonalIconToggleButton(
    checked = dwChecked,
    onCheckedChange = dwOnCheckedChange,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun OutlinedIconButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (IconButtonDefaults.outlinedShape)
  val dwBorder = border?.real() ?: (IconButtonDefaults.outlinedIconButtonBorder(dwEnabled))
  androidx.compose.material3.OutlinedIconButton(
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    border = dwBorder,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun OutlinedIconToggleButton(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  border: BorderStroke? = null,
  content: @Composable () -> Unit,
) {
  val dwChecked = checked
  val dwOnCheckedChange = onCheckedChange
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (IconButtonDefaults.outlinedShape)
  val dwBorder = border?.real() ?: (IconButtonDefaults.outlinedIconToggleButtonBorder(dwEnabled, dwChecked))
  androidx.compose.material3.OutlinedIconToggleButton(
    checked = dwChecked,
    onCheckedChange = dwOnCheckedChange,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    border = dwBorder,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun Label(
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  isPersistent: Boolean? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwIsPersistent = isPersistent ?: (false)
  androidx.compose.material3.Label(
    label = { label() },
    modifier = dwModifier,
    isPersistent = dwIsPersistent,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun ListItem(
  headlineContent: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  overlineContent: (@Composable () -> Unit)? = null,
  supportingContent: (@Composable () -> Unit)? = null,
  leadingContent: (@Composable () -> Unit)? = null,
  trailingContent: (@Composable () -> Unit)? = null,
  tonalElevation: Dp? = null,
  shadowElevation: Dp? = null,
) {
  val dwModifier = modifier.real
  val dwTonalElevation = tonalElevation?.real() ?: (ListItemDefaults.Elevation)
  val dwShadowElevation = shadowElevation?.real() ?: (ListItemDefaults.Elevation)
  androidx.compose.material3.ListItem(
    headlineContent = { headlineContent() },
    modifier = dwModifier,
    overlineContent = if (overlineContent == null) null else ({ overlineContent() }),
    supportingContent = if (supportingContent == null) null else ({ supportingContent() }),
    leadingContent = if (leadingContent == null) null else ({ leadingContent() }),
    trailingContent = if (trailingContent == null) null else ({ trailingContent() }),
    tonalElevation = dwTonalElevation,
    shadowElevation = dwShadowElevation,
  )
}

@androidx.compose.runtime.Composable
fun DropdownMenu(
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  shape: Shape? = null,
  containerColor: Color? = null,
  tonalElevation: Dp? = null,
  shadowElevation: Dp? = null,
  border: BorderStroke? = null,
  content: @Composable () -> Unit,
) {
  val dwExpanded = expanded
  val dwOnDismissRequest = onDismissRequest
  val dwModifier = modifier.real
  val dwShape = shape?.real ?: (MenuDefaults.shape)
  val dwContainerColor = containerColor?.resolve() ?: (MenuDefaults.containerColor)
  val dwTonalElevation = tonalElevation?.real() ?: (MenuDefaults.TonalElevation)
  val dwShadowElevation = shadowElevation?.real() ?: (MenuDefaults.ShadowElevation)
  val dwBorder = border?.real() ?: (null)
  androidx.compose.material3.DropdownMenu(
    expanded = dwExpanded,
    onDismissRequest = dwOnDismissRequest,
    modifier = dwModifier,
    shape = dwShape,
    containerColor = dwContainerColor,
    tonalElevation = dwTonalElevation,
    shadowElevation = dwShadowElevation,
    border = dwBorder,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun DropdownMenuItem(
  text: @Composable () -> Unit,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  leadingIcon: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
  enabled: Boolean? = null,
  contentPadding: PaddingValues? = null,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwContentPadding = contentPadding?.real() ?: (MenuDefaults.DropdownMenuItemContentPadding)
  androidx.compose.material3.DropdownMenuItem(
    text = { text() },
    onClick = dwOnClick,
    modifier = dwModifier,
    leadingIcon = if (leadingIcon == null) null else ({ leadingIcon() }),
    trailingIcon = if (trailingIcon == null) null else ({ trailingIcon() }),
    enabled = dwEnabled,
    contentPadding = dwContentPadding,
  )
}

@androidx.compose.runtime.Composable
fun ModalBottomSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  sheetMaxWidth: Dp? = null,
  sheetGesturesEnabled: Boolean? = null,
  shape: Shape? = null,
  containerColor: Color? = null,
  contentColor: Color? = null,
  tonalElevation: Dp? = null,
  scrimColor: Color? = null,
  dragHandle: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  val dwOnDismissRequest = onDismissRequest
  val dwModifier = modifier.real
  val dwSheetMaxWidth = sheetMaxWidth?.real() ?: (BottomSheetDefaults.SheetMaxWidth)
  val dwSheetGesturesEnabled = sheetGesturesEnabled ?: (true)
  val dwShape = shape?.real ?: (BottomSheetDefaults.ExpandedShape)
  val dwContainerColor = containerColor?.resolve() ?: (BottomSheetDefaults.ContainerColor)
  val dwContentColor = contentColor?.resolve() ?: (contentColorFor(dwContainerColor))
  val dwTonalElevation = tonalElevation?.real() ?: (androidx.compose.ui.unit.Dp(0f))
  val dwScrimColor = scrimColor?.resolve() ?: (BottomSheetDefaults.ScrimColor)
  androidx.compose.material3.ModalBottomSheet(
    onDismissRequest = dwOnDismissRequest,
    modifier = dwModifier,
    sheetMaxWidth = dwSheetMaxWidth,
    sheetGesturesEnabled = dwSheetGesturesEnabled,
    shape = dwShape,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    tonalElevation = dwTonalElevation,
    scrimColor = dwScrimColor,
    dragHandle = if (dragHandle == null) null else ({ dragHandle() }),
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun NavigationBar(
  modifier: Modifier = Modifier,
  containerColor: Color? = null,
  contentColor: Color? = null,
  tonalElevation: Dp? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwContainerColor = containerColor?.resolve() ?: (NavigationBarDefaults.containerColor)
  val dwContentColor = contentColor?.resolve() ?: (MaterialTheme.colorScheme.contentColorFor(dwContainerColor))
  val dwTonalElevation = tonalElevation?.real() ?: (NavigationBarDefaults.Elevation)
  androidx.compose.material3.NavigationBar(
    modifier = dwModifier,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    tonalElevation = dwTonalElevation,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun PermanentNavigationDrawer(
  drawerContent: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  androidx.compose.material3.PermanentNavigationDrawer(
    drawerContent = { drawerContent() },
    modifier = dwModifier,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun ModalDrawerSheet(
  modifier: Modifier = Modifier,
  drawerShape: Shape? = null,
  drawerContainerColor: Color? = null,
  drawerContentColor: Color? = null,
  drawerTonalElevation: Dp? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwDrawerShape = drawerShape?.real ?: (DrawerDefaults.shape)
  val dwDrawerContainerColor = drawerContainerColor?.resolve() ?: (DrawerDefaults.modalContainerColor)
  val dwDrawerContentColor = drawerContentColor?.resolve() ?: (contentColorFor(dwDrawerContainerColor))
  val dwDrawerTonalElevation = drawerTonalElevation?.real() ?: (DrawerDefaults.ModalDrawerElevation)
  androidx.compose.material3.ModalDrawerSheet(
    modifier = dwModifier,
    drawerShape = dwDrawerShape,
    drawerContainerColor = dwDrawerContainerColor,
    drawerContentColor = dwDrawerContentColor,
    drawerTonalElevation = dwDrawerTonalElevation,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun DismissibleDrawerSheet(
  modifier: Modifier = Modifier,
  drawerShape: Shape? = null,
  drawerContainerColor: Color? = null,
  drawerContentColor: Color? = null,
  drawerTonalElevation: Dp? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwDrawerShape = drawerShape?.real ?: (androidx.compose.ui.graphics.RectangleShape)
  val dwDrawerContainerColor = drawerContainerColor?.resolve() ?: (DrawerDefaults.standardContainerColor)
  val dwDrawerContentColor = drawerContentColor?.resolve() ?: (contentColorFor(dwDrawerContainerColor))
  val dwDrawerTonalElevation = drawerTonalElevation?.real() ?: (DrawerDefaults.DismissibleDrawerElevation)
  androidx.compose.material3.DismissibleDrawerSheet(
    modifier = dwModifier,
    drawerShape = dwDrawerShape,
    drawerContainerColor = dwDrawerContainerColor,
    drawerContentColor = dwDrawerContentColor,
    drawerTonalElevation = dwDrawerTonalElevation,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun PermanentDrawerSheet(
  modifier: Modifier = Modifier,
  drawerShape: Shape? = null,
  drawerContainerColor: Color? = null,
  drawerContentColor: Color? = null,
  drawerTonalElevation: Dp? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwDrawerShape = drawerShape?.real ?: (androidx.compose.ui.graphics.RectangleShape)
  val dwDrawerContainerColor = drawerContainerColor?.resolve() ?: (DrawerDefaults.standardContainerColor)
  val dwDrawerContentColor = drawerContentColor?.resolve() ?: (contentColorFor(dwDrawerContainerColor))
  val dwDrawerTonalElevation = drawerTonalElevation?.real() ?: (DrawerDefaults.PermanentDrawerElevation)
  androidx.compose.material3.PermanentDrawerSheet(
    modifier = dwModifier,
    drawerShape = dwDrawerShape,
    drawerContainerColor = dwDrawerContainerColor,
    drawerContentColor = dwDrawerContentColor,
    drawerTonalElevation = dwDrawerTonalElevation,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun NavigationRail(
  modifier: Modifier = Modifier,
  containerColor: Color? = null,
  contentColor: Color? = null,
  header: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwContainerColor = containerColor?.resolve() ?: (NavigationRailDefaults.ContainerColor)
  val dwContentColor = contentColor?.resolve() ?: (contentColorFor(dwContainerColor))
  androidx.compose.material3.NavigationRail(
    modifier = dwModifier,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    header = if (header == null) null else ({ header() }),
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun NavigationRailItem(
  selected: Boolean,
  onClick: () -> Unit,
  icon: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  label: (@Composable () -> Unit)? = null,
  alwaysShowLabel: Boolean? = null,
) {
  val dwSelected = selected
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwAlwaysShowLabel = alwaysShowLabel ?: (true)
  androidx.compose.material3.NavigationRailItem(
    selected = dwSelected,
    onClick = dwOnClick,
    icon = { icon() },
    modifier = dwModifier,
    enabled = dwEnabled,
    label = if (label == null) null else ({ label() }),
    alwaysShowLabel = dwAlwaysShowLabel,
  )
}

@androidx.compose.runtime.Composable
fun LinearProgressIndicator(
  modifier: Modifier = Modifier,
  color: Color? = null,
  trackColor: Color? = null,
  gapSize: Dp? = null,
) {
  val dwModifier = modifier.real
  val dwColor = color?.resolve() ?: (ProgressIndicatorDefaults.linearColor)
  val dwTrackColor = trackColor?.resolve() ?: (ProgressIndicatorDefaults.linearTrackColor)
  val dwGapSize = gapSize?.real() ?: (ProgressIndicatorDefaults.LinearIndicatorTrackGapSize)
  androidx.compose.material3.LinearProgressIndicator(
    modifier = dwModifier,
    color = dwColor,
    trackColor = dwTrackColor,
    gapSize = dwGapSize,
  )
}

@androidx.compose.runtime.Composable
fun CircularProgressIndicator(
  modifier: Modifier = Modifier,
  color: Color? = null,
  strokeWidth: Dp? = null,
  trackColor: Color? = null,
  gapSize: Dp? = null,
) {
  val dwModifier = modifier.real
  val dwColor = color?.resolve() ?: (ProgressIndicatorDefaults.circularColor)
  val dwStrokeWidth = strokeWidth?.real() ?: (ProgressIndicatorDefaults.CircularStrokeWidth)
  val dwTrackColor = trackColor?.resolve() ?: (ProgressIndicatorDefaults.circularIndeterminateTrackColor)
  val dwGapSize = gapSize?.real() ?: (ProgressIndicatorDefaults.CircularIndicatorTrackGapSize)
  androidx.compose.material3.CircularProgressIndicator(
    modifier = dwModifier,
    color = dwColor,
    strokeWidth = dwStrokeWidth,
    trackColor = dwTrackColor,
    gapSize = dwGapSize,
  )
}

@androidx.compose.runtime.Composable
fun RadioButton(
  selected: Boolean,
  onClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
) {
  val dwSelected = selected
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  androidx.compose.material3.RadioButton(
    selected = dwSelected,
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
  )
}

@androidx.compose.runtime.Composable
fun SingleChoiceSegmentedButtonRow(
  modifier: Modifier = Modifier,
  space: Dp? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwSpace = space?.real() ?: (SegmentedButtonDefaults.BorderWidth)
  androidx.compose.material3.SingleChoiceSegmentedButtonRow(
    modifier = dwModifier,
    space = dwSpace,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun MultiChoiceSegmentedButtonRow(
  modifier: Modifier = Modifier,
  space: Dp? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwSpace = space?.real() ?: (SegmentedButtonDefaults.BorderWidth)
  androidx.compose.material3.MultiChoiceSegmentedButtonRow(
    modifier = dwModifier,
    space = dwSpace,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun ShortNavigationBar(
  modifier: Modifier = Modifier,
  containerColor: Color? = null,
  contentColor: Color? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwContainerColor = containerColor?.resolve() ?: (ShortNavigationBarDefaults.containerColor)
  val dwContentColor = contentColor?.resolve() ?: (ShortNavigationBarDefaults.contentColor)
  androidx.compose.material3.ShortNavigationBar(
    modifier = dwModifier,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun ShortNavigationBarItem(
  selected: Boolean,
  onClick: () -> Unit,
  icon: @Composable () -> Unit,
  label: (@Composable () -> Unit)? = null,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
) {
  val dwSelected = selected
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  androidx.compose.material3.ShortNavigationBarItem(
    selected = dwSelected,
    onClick = dwOnClick,
    icon = { icon() },
    label = if (label == null) null else ({ label() }),
    modifier = dwModifier,
    enabled = dwEnabled,
  )
}

@androidx.compose.runtime.Composable
fun Slider(
  value: Float,
  onValueChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  valueRange: FloatRange? = null,
  steps: Int? = null,
  onValueChangeFinished: (() -> Unit)? = null,
) {
  val dwValue = value
  val dwOnValueChange = onValueChange
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwValueRange = valueRange?.real() ?: (0f..1f)
  val dwSteps = steps ?: (0)
  val dwOnValueChangeFinished = onValueChangeFinished
  androidx.compose.material3.Slider(
    value = dwValue,
    onValueChange = dwOnValueChange,
    modifier = dwModifier,
    enabled = dwEnabled,
    valueRange = dwValueRange,
    steps = dwSteps,
    onValueChangeFinished = dwOnValueChangeFinished,
  )
}

@androidx.compose.runtime.Composable
fun RangeSlider(
  value: FloatRange,
  onValueChange: (FloatRange) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  valueRange: FloatRange? = null,
  steps: Int? = null,
  onValueChangeFinished: (() -> Unit)? = null,
) {
  val dwValue = value.real()
  val dwOnValueChange: (ClosedFloatingPointRange<Float>) -> Unit = { a0 -> onValueChange(FloatRange(a0.start, a0.endInclusive)) }
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwValueRange = valueRange?.real() ?: (0f..1f)
  val dwSteps = steps ?: (0)
  val dwOnValueChangeFinished = onValueChangeFinished
  androidx.compose.material3.RangeSlider(
    value = dwValue,
    onValueChange = dwOnValueChange,
    modifier = dwModifier,
    enabled = dwEnabled,
    valueRange = dwValueRange,
    steps = dwSteps,
    onValueChangeFinished = dwOnValueChangeFinished,
  )
}

@androidx.compose.runtime.Composable
fun Snackbar(
  modifier: Modifier = Modifier,
  action: (@Composable () -> Unit)? = null,
  dismissAction: (@Composable () -> Unit)? = null,
  actionOnNewLine: Boolean? = null,
  shape: Shape? = null,
  containerColor: Color? = null,
  contentColor: Color? = null,
  actionContentColor: Color? = null,
  dismissActionContentColor: Color? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwActionOnNewLine = actionOnNewLine ?: (false)
  val dwShape = shape?.real ?: (SnackbarDefaults.shape)
  val dwContainerColor = containerColor?.resolve() ?: (SnackbarDefaults.color)
  val dwContentColor = contentColor?.resolve() ?: (SnackbarDefaults.contentColor)
  val dwActionContentColor = actionContentColor?.resolve() ?: (SnackbarDefaults.actionContentColor)
  val dwDismissActionContentColor = dismissActionContentColor?.resolve() ?: (SnackbarDefaults.dismissActionContentColor)
  androidx.compose.material3.Snackbar(
    modifier = dwModifier,
    action = if (action == null) null else ({ action() }),
    dismissAction = if (dismissAction == null) null else ({ dismissAction() }),
    actionOnNewLine = dwActionOnNewLine,
    shape = dwShape,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    actionContentColor = dwActionContentColor,
    dismissActionContentColor = dwDismissActionContentColor,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun Surface(
  modifier: Modifier = Modifier,
  shape: Shape? = null,
  color: Color? = null,
  contentColor: Color? = null,
  tonalElevation: Dp? = null,
  shadowElevation: Dp? = null,
  border: BorderStroke? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwShape = shape?.real ?: (androidx.compose.ui.graphics.RectangleShape)
  val dwColor = color?.resolve() ?: (MaterialTheme.colorScheme.surface)
  val dwContentColor = contentColor?.resolve() ?: (contentColorFor(dwColor))
  val dwTonalElevation = tonalElevation?.real() ?: (androidx.compose.ui.unit.Dp(0f))
  val dwShadowElevation = shadowElevation?.real() ?: (androidx.compose.ui.unit.Dp(0f))
  val dwBorder = border?.real() ?: (null)
  androidx.compose.material3.Surface(
    modifier = dwModifier,
    shape = dwShape,
    color = dwColor,
    contentColor = dwContentColor,
    tonalElevation = dwTonalElevation,
    shadowElevation = dwShadowElevation,
    border = dwBorder,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun Surface(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  color: Color? = null,
  contentColor: Color? = null,
  tonalElevation: Dp? = null,
  shadowElevation: Dp? = null,
  border: BorderStroke? = null,
  content: @Composable () -> Unit,
) {
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (androidx.compose.ui.graphics.RectangleShape)
  val dwColor = color?.resolve() ?: (MaterialTheme.colorScheme.surface)
  val dwContentColor = contentColor?.resolve() ?: (contentColorFor(dwColor))
  val dwTonalElevation = tonalElevation?.real() ?: (androidx.compose.ui.unit.Dp(0f))
  val dwShadowElevation = shadowElevation?.real() ?: (androidx.compose.ui.unit.Dp(0f))
  val dwBorder = border?.real() ?: (null)
  androidx.compose.material3.Surface(
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    color = dwColor,
    contentColor = dwContentColor,
    tonalElevation = dwTonalElevation,
    shadowElevation = dwShadowElevation,
    border = dwBorder,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun Surface(
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  color: Color? = null,
  contentColor: Color? = null,
  tonalElevation: Dp? = null,
  shadowElevation: Dp? = null,
  border: BorderStroke? = null,
  content: @Composable () -> Unit,
) {
  val dwSelected = selected
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (androidx.compose.ui.graphics.RectangleShape)
  val dwColor = color?.resolve() ?: (MaterialTheme.colorScheme.surface)
  val dwContentColor = contentColor?.resolve() ?: (contentColorFor(dwColor))
  val dwTonalElevation = tonalElevation?.real() ?: (androidx.compose.ui.unit.Dp(0f))
  val dwShadowElevation = shadowElevation?.real() ?: (androidx.compose.ui.unit.Dp(0f))
  val dwBorder = border?.real() ?: (null)
  androidx.compose.material3.Surface(
    selected = dwSelected,
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    color = dwColor,
    contentColor = dwContentColor,
    tonalElevation = dwTonalElevation,
    shadowElevation = dwShadowElevation,
    border = dwBorder,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun Surface(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  shape: Shape? = null,
  color: Color? = null,
  contentColor: Color? = null,
  tonalElevation: Dp? = null,
  shadowElevation: Dp? = null,
  border: BorderStroke? = null,
  content: @Composable () -> Unit,
) {
  val dwChecked = checked
  val dwOnCheckedChange = onCheckedChange
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwShape = shape?.real ?: (androidx.compose.ui.graphics.RectangleShape)
  val dwColor = color?.resolve() ?: (MaterialTheme.colorScheme.surface)
  val dwContentColor = contentColor?.resolve() ?: (contentColorFor(dwColor))
  val dwTonalElevation = tonalElevation?.real() ?: (androidx.compose.ui.unit.Dp(0f))
  val dwShadowElevation = shadowElevation?.real() ?: (androidx.compose.ui.unit.Dp(0f))
  val dwBorder = border?.real() ?: (null)
  androidx.compose.material3.Surface(
    checked = dwChecked,
    onCheckedChange = dwOnCheckedChange,
    modifier = dwModifier,
    enabled = dwEnabled,
    shape = dwShape,
    color = dwColor,
    contentColor = dwContentColor,
    tonalElevation = dwTonalElevation,
    shadowElevation = dwShadowElevation,
    border = dwBorder,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun Switch(
  checked: Boolean,
  onCheckedChange: ((Boolean) -> Unit)? = null,
  modifier: Modifier = Modifier,
  thumbContent: (@Composable () -> Unit)? = null,
  enabled: Boolean? = null,
) {
  val dwChecked = checked
  val dwOnCheckedChange = onCheckedChange
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  androidx.compose.material3.Switch(
    checked = dwChecked,
    onCheckedChange = dwOnCheckedChange,
    modifier = dwModifier,
    thumbContent = if (thumbContent == null) null else ({ thumbContent() }),
    enabled = dwEnabled,
  )
}

@androidx.compose.runtime.Composable
fun Tab(
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  text: (@Composable () -> Unit)? = null,
  icon: (@Composable () -> Unit)? = null,
  selectedContentColor: Color? = null,
  unselectedContentColor: Color? = null,
) {
  val dwSelected = selected
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwSelectedContentColor = selectedContentColor?.resolve() ?: (LocalContentColor.current)
  val dwUnselectedContentColor = unselectedContentColor?.resolve() ?: (dwSelectedContentColor)
  androidx.compose.material3.Tab(
    selected = dwSelected,
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    text = if (text == null) null else ({ text() }),
    icon = if (icon == null) null else ({ icon() }),
    selectedContentColor = dwSelectedContentColor,
    unselectedContentColor = dwUnselectedContentColor,
  )
}

@androidx.compose.runtime.Composable
fun LeadingIconTab(
  selected: Boolean,
  onClick: () -> Unit,
  text: @Composable () -> Unit,
  icon: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  selectedContentColor: Color? = null,
  unselectedContentColor: Color? = null,
) {
  val dwSelected = selected
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwSelectedContentColor = selectedContentColor?.resolve() ?: (LocalContentColor.current)
  val dwUnselectedContentColor = unselectedContentColor?.resolve() ?: (dwSelectedContentColor)
  androidx.compose.material3.LeadingIconTab(
    selected = dwSelected,
    onClick = dwOnClick,
    text = { text() },
    icon = { icon() },
    modifier = dwModifier,
    enabled = dwEnabled,
    selectedContentColor = dwSelectedContentColor,
    unselectedContentColor = dwUnselectedContentColor,
  )
}

@androidx.compose.runtime.Composable
fun Tab(
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
  selectedContentColor: Color? = null,
  unselectedContentColor: Color? = null,
  content: @Composable () -> Unit,
) {
  val dwSelected = selected
  val dwOnClick = onClick
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  val dwSelectedContentColor = selectedContentColor?.resolve() ?: (LocalContentColor.current)
  val dwUnselectedContentColor = unselectedContentColor?.resolve() ?: (dwSelectedContentColor)
  androidx.compose.material3.Tab(
    selected = dwSelected,
    onClick = dwOnClick,
    modifier = dwModifier,
    enabled = dwEnabled,
    selectedContentColor = dwSelectedContentColor,
    unselectedContentColor = dwUnselectedContentColor,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun PrimaryTabRow(
  selectedTabIndex: Int,
  modifier: Modifier = Modifier,
  containerColor: Color? = null,
  contentColor: Color? = null,
  indicator: (@Composable () -> Unit)? = null,
  divider: (@Composable () -> Unit)? = null,
  tabs: @Composable () -> Unit,
) {
  val dwSelectedTabIndex = selectedTabIndex
  val dwModifier = modifier.real
  val dwContainerColor = containerColor?.resolve() ?: (TabRowDefaults.primaryContainerColor)
  val dwContentColor = contentColor?.resolve() ?: (TabRowDefaults.primaryContentColor)
  androidx.compose.material3.PrimaryTabRow(
    selectedTabIndex = dwSelectedTabIndex,
    modifier = dwModifier,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    indicator = { indicator?.invoke() },
    divider = { divider?.invoke() },
    tabs = { tabs() },
  )
}

@androidx.compose.runtime.Composable
fun SecondaryTabRow(
  selectedTabIndex: Int,
  modifier: Modifier = Modifier,
  containerColor: Color? = null,
  contentColor: Color? = null,
  indicator: (@Composable () -> Unit)? = null,
  divider: (@Composable () -> Unit)? = null,
  tabs: @Composable () -> Unit,
) {
  val dwSelectedTabIndex = selectedTabIndex
  val dwModifier = modifier.real
  val dwContainerColor = containerColor?.resolve() ?: (TabRowDefaults.secondaryContainerColor)
  val dwContentColor = contentColor?.resolve() ?: (TabRowDefaults.secondaryContentColor)
  androidx.compose.material3.SecondaryTabRow(
    selectedTabIndex = dwSelectedTabIndex,
    modifier = dwModifier,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    indicator = { indicator?.invoke() },
    divider = { divider?.invoke() },
    tabs = { tabs() },
  )
}

@androidx.compose.runtime.Composable
fun PrimaryScrollableTabRow(
  selectedTabIndex: Int,
  modifier: Modifier = Modifier,
  containerColor: Color? = null,
  contentColor: Color? = null,
  edgePadding: Dp? = null,
  indicator: (@Composable () -> Unit)? = null,
  divider: (@Composable () -> Unit)? = null,
  minTabWidth: Dp? = null,
  tabs: @Composable () -> Unit,
) {
  val dwSelectedTabIndex = selectedTabIndex
  val dwModifier = modifier.real
  val dwContainerColor = containerColor?.resolve() ?: (TabRowDefaults.primaryContainerColor)
  val dwContentColor = contentColor?.resolve() ?: (TabRowDefaults.primaryContentColor)
  val dwEdgePadding = edgePadding?.real() ?: (TabRowDefaults.ScrollableTabRowEdgeStartPadding)
  val dwMinTabWidth = minTabWidth?.real() ?: (TabRowDefaults.ScrollableTabRowMinTabWidth)
  androidx.compose.material3.PrimaryScrollableTabRow(
    selectedTabIndex = dwSelectedTabIndex,
    modifier = dwModifier,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    edgePadding = dwEdgePadding,
    indicator = { indicator?.invoke() },
    divider = { divider?.invoke() },
    minTabWidth = dwMinTabWidth,
    tabs = { tabs() },
  )
}

@androidx.compose.runtime.Composable
fun SecondaryScrollableTabRow(
  selectedTabIndex: Int,
  modifier: Modifier = Modifier,
  containerColor: Color? = null,
  contentColor: Color? = null,
  edgePadding: Dp? = null,
  indicator: (@Composable () -> Unit)? = null,
  divider: (@Composable () -> Unit)? = null,
  minTabWidth: Dp? = null,
  tabs: @Composable () -> Unit,
) {
  val dwSelectedTabIndex = selectedTabIndex
  val dwModifier = modifier.real
  val dwContainerColor = containerColor?.resolve() ?: (TabRowDefaults.secondaryContainerColor)
  val dwContentColor = contentColor?.resolve() ?: (TabRowDefaults.secondaryContentColor)
  val dwEdgePadding = edgePadding?.real() ?: (TabRowDefaults.ScrollableTabRowEdgeStartPadding)
  val dwMinTabWidth = minTabWidth?.real() ?: (TabRowDefaults.ScrollableTabRowMinTabWidth)
  androidx.compose.material3.SecondaryScrollableTabRow(
    selectedTabIndex = dwSelectedTabIndex,
    modifier = dwModifier,
    containerColor = dwContainerColor,
    contentColor = dwContentColor,
    edgePadding = dwEdgePadding,
    indicator = { indicator?.invoke() },
    divider = { divider?.invoke() },
    minTabWidth = dwMinTabWidth,
    tabs = { tabs() },
  )
}

@androidx.compose.runtime.Composable
fun Text(
  text: String,
  modifier: Modifier = Modifier,
  color: Color? = null,
  fontSize: TextUnit? = null,
  fontWeight: FontWeight? = null,
  letterSpacing: TextUnit? = null,
  textDecoration: TextDecoration? = null,
  textAlign: TextAlign? = null,
  lineHeight: TextUnit? = null,
  overflow: TextOverflow? = null,
  softWrap: Boolean? = null,
  maxLines: Int? = null,
  minLines: Int? = null,
) {
  val dwText = text
  val dwModifier = modifier.real
  val dwColor = color?.resolve() ?: (androidx.compose.ui.graphics.Color.Unspecified)
  val dwFontSize = fontSize?.real() ?: (androidx.compose.ui.unit.TextUnit.Unspecified)
  val dwFontWeight = fontWeight?.real() ?: (null)
  val dwLetterSpacing = letterSpacing?.real() ?: (androidx.compose.ui.unit.TextUnit.Unspecified)
  val dwTextDecoration = textDecoration?.real() ?: (null)
  val dwTextAlign = textAlign?.real() ?: (null)
  val dwLineHeight = lineHeight?.real() ?: (androidx.compose.ui.unit.TextUnit.Unspecified)
  val dwOverflow = overflow?.real() ?: (androidx.compose.ui.text.style.TextOverflow.Clip)
  val dwSoftWrap = softWrap ?: (true)
  val dwMaxLines = maxLines ?: (Int.MAX_VALUE)
  val dwMinLines = minLines ?: (1)
  androidx.compose.material3.Text(
    text = dwText,
    modifier = dwModifier,
    color = dwColor,
    fontSize = dwFontSize,
    fontWeight = dwFontWeight,
    letterSpacing = dwLetterSpacing,
    textDecoration = dwTextDecoration,
    textAlign = dwTextAlign,
    lineHeight = dwLineHeight,
    overflow = dwOverflow,
    softWrap = dwSoftWrap,
    maxLines = dwMaxLines,
    minLines = dwMinLines,
  )
}

@androidx.compose.runtime.Composable
fun TimePickerDialog(
  onDismissRequest: () -> Unit,
  confirmButton: @Composable () -> Unit,
  title: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  modeToggleButton: (@Composable () -> Unit)? = null,
  dismissButton: (@Composable () -> Unit)? = null,
  shape: Shape? = null,
  containerColor: Color? = null,
  content: @Composable () -> Unit,
) {
  val dwOnDismissRequest = onDismissRequest
  val dwModifier = modifier.real
  val dwShape = shape?.real ?: (TimePickerDialogDefaults.shape)
  val dwContainerColor = containerColor?.resolve() ?: (TimePickerDialogDefaults.containerColor)
  androidx.compose.material3.TimePickerDialog(
    onDismissRequest = dwOnDismissRequest,
    confirmButton = { confirmButton() },
    title = { title() },
    modifier = dwModifier,
    modeToggleButton = if (modeToggleButton == null) null else ({ modeToggleButton() }),
    dismissButton = if (dismissButton == null) null else ({ dismissButton() }),
    shape = dwShape,
    containerColor = dwContainerColor,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun WideNavigationRail(
  modifier: Modifier = Modifier,
  shape: Shape? = null,
  header: (@Composable () -> Unit)? = null,
  arrangement: Arrangement? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwShape = shape?.real ?: (WideNavigationRailDefaults.shape)
  val dwArrangement = arrangement?.vertical() ?: (WideNavigationRailDefaults.arrangement)
  androidx.compose.material3.WideNavigationRail(
    modifier = dwModifier,
    shape = dwShape,
    header = if (header == null) null else ({ header() }),
    arrangement = dwArrangement,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun ModalWideNavigationRail(
  modifier: Modifier = Modifier,
  hideOnCollapse: Boolean? = null,
  collapsedShape: Shape? = null,
  expandedShape: Shape? = null,
  header: (@Composable () -> Unit)? = null,
  expandedHeaderTopPadding: Dp? = null,
  arrangement: Arrangement? = null,
  content: @Composable () -> Unit,
) {
  val dwModifier = modifier.real
  val dwHideOnCollapse = hideOnCollapse ?: (false)
  val dwCollapsedShape = collapsedShape?.real ?: (WideNavigationRailDefaults.modalCollapsedShape)
  val dwExpandedShape = expandedShape?.real ?: (WideNavigationRailDefaults.modalExpandedShape)
  val dwExpandedHeaderTopPadding = expandedHeaderTopPadding?.real() ?: (androidx.compose.ui.unit.Dp(0f))
  val dwArrangement = arrangement?.vertical() ?: (WideNavigationRailDefaults.arrangement)
  androidx.compose.material3.ModalWideNavigationRail(
    modifier = dwModifier,
    hideOnCollapse = dwHideOnCollapse,
    collapsedShape = dwCollapsedShape,
    expandedShape = dwExpandedShape,
    header = if (header == null) null else ({ header() }),
    expandedHeaderTopPadding = dwExpandedHeaderTopPadding,
    arrangement = dwArrangement,
    content = { content() },
  )
}

@androidx.compose.runtime.Composable
fun WideNavigationRailItem(
  selected: Boolean,
  onClick: () -> Unit,
  icon: @Composable () -> Unit,
  label: (@Composable () -> Unit)? = null,
  railExpanded: Boolean,
  modifier: Modifier = Modifier,
  enabled: Boolean? = null,
) {
  val dwSelected = selected
  val dwOnClick = onClick
  val dwRailExpanded = railExpanded
  val dwModifier = modifier.real
  val dwEnabled = enabled ?: (true)
  androidx.compose.material3.WideNavigationRailItem(
    selected = dwSelected,
    onClick = dwOnClick,
    icon = { icon() },
    label = if (label == null) null else ({ label() }),
    railExpanded = dwRailExpanded,
    modifier = dwModifier,
    enabled = dwEnabled,
  )
}


// ---------------------------------------------------------------------------------------------
// The two drawers, by hand
//
// Their host bindings build a `DrawerState` from the holder the guest sent, which is a mirror this
// preview has no wire to fill. A preview therefore gives them Compose's own `rememberDrawerState`:
// the drawer opens and closes by gesture and by its own content, and the payload's holder -- the
// thing a device would report back into -- is not connected to it. A drawer previewed here is a
// drawer you can see and not a drawer whose state a payload can read.
// ---------------------------------------------------------------------------------------------

@Composable
fun ModalNavigationDrawer(
  drawerContent: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  gesturesEnabled: Boolean? = null,
  scrimColor: Color? = null,
  content: @Composable () -> Unit,
) {
  androidx.compose.material3.ModalNavigationDrawer(
    drawerContent = { drawerContent() },
    modifier = modifier.real,
    drawerState = rememberDrawerState(DrawerValue.Closed),
    gesturesEnabled = gesturesEnabled ?: true,
    scrimColor = scrimColor?.resolve() ?: DrawerDefaults.scrimColor,
    content = { content() },
  )
}

@Composable
fun DismissibleNavigationDrawer(
  drawerContent: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  gesturesEnabled: Boolean? = null,
  content: @Composable () -> Unit,
) {
  androidx.compose.material3.DismissibleNavigationDrawer(
    drawerContent = { drawerContent() },
    modifier = modifier.real,
    drawerState = rememberDrawerState(DrawerValue.Open),
    gesturesEnabled = gesturesEnabled ?: true,
    content = { content() },
  )
}

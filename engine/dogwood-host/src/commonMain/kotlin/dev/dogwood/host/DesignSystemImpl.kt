/*
 * Project Dogwood -- the registered design system's implementations.
 *
 * The generator emits the bridge; this is what the bridge calls. Nothing here knows about tags,
 * nodes, batches or events: each function is an ordinary Compose composable taking ordinary
 * parameters, which is exactly the line the architecture draws. The part that grows without bound
 * as a design system grows is generated. The part that requires taste is not.
 *
 * A parameter arriving as null means the guest sent nothing, and absence is the "use host
 * default" sentinel — so a null is this function's cue to choose, not a value to render.
 */
package dev.dogwood.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import coil3.compose.AsyncImage

@Composable
fun PrimaryButtonImpl(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = RoundedCornerShape(Radius.Sm),
    colors = ButtonDefaults.buttonColors(
      containerColor = palette().primary,
      contentColor = palette().onPrimary,
    ),
    contentPadding = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Md),
  ) {
    Text(label, style = MaterialTheme.typography.titleSmall)
  }
}

@Composable
fun AsyncImageImpl(
  url: String,
  contentDescription: String?,
  cornerRadiusDp: Int,
  modifier: Modifier,
) {
  AsyncImage(
    model = url,
    contentDescription = contentDescription,
    contentScale = ContentScale.Crop,
    modifier = modifier.clip(RoundedCornerShape(cornerRadiusDp.dp)).background(palette().canvasContrast),
    onError = { state -> println("dogwood: image failed for $url: ${state.result.throwable}") },
  )
}

@Composable
fun CardImpl(modifier: Modifier, content: @Composable () -> Unit) {
  Card(
    modifier = modifier,
    shape = RoundedCornerShape(Radius.Md),
    colors = CardDefaults.cardColors(containerColor = palette().canvas),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    content()
  }
}

@Composable
fun BadgeImpl(text: String, selected: Boolean, modifier: Modifier) {
  Surface(
    modifier = modifier.clip(RoundedCornerShape(Radius.Xs)),
    color = if (selected) palette().successContainer else palette().canvasContrast,
  ) {
    Text(
      text,
      modifier = Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
      color = if (selected) palette().success else palette().inkSecondary,
      style = MaterialTheme.typography.labelMedium,
    )
  }
}

@Composable
fun DividerImpl(modifier: Modifier) {
  HorizontalDivider(modifier, color = palette().line)
}

@Composable
fun ChipImpl(text: String, selected: Boolean, modifier: Modifier, onSelectedChange: (Boolean) -> Unit) {
  Surface(
    modifier = modifier
      .clip(RoundedCornerShape(Radius.Full))
      .clickable { onSelectedChange(!selected) },
    color = if (selected) palette().primary else palette().canvasContrast,
  ) {
    Text(
      text,
      modifier = Modifier.padding(horizontal = Spacing.Base, vertical = Spacing.Md),
      color = if (selected) palette().onPrimary else palette().ink,
      style = MaterialTheme.typography.labelLarge,
    )
  }
}

@Composable
fun PriceImpl(
  price: String,
  leadingText: String?,
  previousPrice: String?,
  trailingText: String?,
  modifier: Modifier,
) {
  Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
    leadingText?.let { Text(it, color = palette().inkSecondary, style = MaterialTheme.typography.bodySmall) }
    previousPrice?.let {
      Text(
        it,
        color = palette().inkSecondary,
        style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
      )
    }
    Text(price, color = palette().ink, style = MaterialTheme.typography.titleMedium)
    trailingText?.let { Text(it, color = palette().inkSecondary, style = MaterialTheme.typography.bodySmall) }
  }
}

@Composable
fun StarRatingImpl(rating: Float, label: String?, modifier: Modifier) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
    Text("★", color = palette().star, style = MaterialTheme.typography.bodyMedium)
    Text(rating.toString(), color = palette().ink, style = MaterialTheme.typography.labelLarge)
    label?.let { Text(it, color = palette().inkSecondary, style = MaterialTheme.typography.bodySmall) }
  }
}

@Composable
fun SectionHeaderImpl(title: String, description: String?, modifier: Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
    Text(title, color = palette().ink, style = MaterialTheme.typography.titleLarge)
    description?.let { Text(it, color = palette().inkSecondary, style = MaterialTheme.typography.bodyMedium) }
  }
}

/**
 * The icon dictionary made visible.
 *
 * An unknown name is the interesting case and it is handled the same way an unknown widget tag is:
 * something is drawn, and the name is recorded. A payload built against a design system update
 * that has reached servers before it reached devices shows a warning glyph rather than a hole, and
 * the team finds out from telemetry rather than from a screenshot.
 */
@Composable
fun IconImpl(
  name: String,
  contentDescription: String?,
  sizeDp: Int,
  // A resolved `Color`, not a token name. It used to be a `String` that happened to hold one --
  // host-resolved in fact but invisible to the type system, so nothing could check it and nothing
  // could animate it. The binding now resolves the recipe and hands over a colour.
  tint: androidx.compose.ui.graphics.Color?,
  modifier: Modifier,
) {
  val icons = LocalIconSet.current
  val resolved = icons[name]
  if (resolved == null) LocalSkewReport.current.unknownIcons += name

  val color = tint ?: palette().ink

  androidx.compose.material3.Icon(
    imageVector = resolved ?: icons.fallback,
    contentDescription = contentDescription,
    modifier = modifier.size(sizeDp.dp),
    tint = color,
  )
}

/**
 * The host half of text input.
 *
 * **The host is authoritative for the text**, and everything below follows from that one decision.
 * A controlled field -- one that asks the guest what the text should be after every keystroke --
 * is a boundary crossing per character with a composition on the far side of it, racing the next
 * keystroke. When it loses, the caret jumps, a character is swallowed, or the input method's
 * composing region is torn apart mid-word. Layer 5 has forbidden it since the first coverage
 * measurement, and this is what replaces it.
 *
 * The conflict rule is Redwood's, made explicit. This binding counts user edits. Every event
 * carries the raw text and that count. A value arriving from the guest carries the count it last
 * acknowledged, and **a value stamped older than the current count is discarded** -- the user has
 * typed since, and the user wins. A programmatic set is therefore honoured exactly when the guest
 * is up to date, which is exactly when it should be.
 *
 * The mask, the length limit and the counter never round trip at all. The guest declares them; the
 * host applies them where the typing is happening.
 */
@Composable
fun TextInputImpl(
  text: String,
  version: Int,
  label: String?,
  placeholder: String?,
  enabled: Boolean,
  singleLine: Boolean,
  maxLength: Int,
  mask: String?,
  keyboard: String?,
  showCounter: Boolean,
  modifier: Modifier,
  onValueChange: (String, Int) -> Unit,
) {
  // The authoritative value, and the count of edits made to it. Both survive a code update,
  // because this binding keeps its composition group -- which is why the guest's state saver has
  // to carry the count too, or every value it sent afterwards would be discarded as stale.
  var raw by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(text) }
  var editCount by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }

  // The guest speaks. Adopted only when it is answering the newest edit; otherwise dropped,
  // silently and on purpose, because the alternative is undoing what the user just typed.
  androidx.compose.runtime.LaunchedEffect(text, version) {
    if (version >= editCount && text != raw) raw = text
  }

  val capacity = when {
    maxLength >= 0 -> maxLength
    mask != null -> maskCapacity(mask)
    else -> Int.MAX_VALUE
  }

  androidx.compose.material3.OutlinedTextField(
    // The field's value is the **raw** text, always. The mask is a visual transformation over it,
    // so the caret arithmetic stays Compose's -- which knows what the input method is doing.
    value = raw,
    onValueChange = { typed ->
      val next = (if (mask != null) filterForMask(typed, mask) else typed).take(capacity)
      if (next != raw) {
        raw = next
        editCount += 1
        onValueChange(next, editCount)
      }
    },
    // The label is stated twice on purpose: once as the visible `label` composable, and once in
    // semantics.
    //
    // Material 3 draws the label but does not fold it into the field's own semantics, so the
    // element a screen reader lands on has no name -- VoiceOver announces "text field" and stops.
    // The accessibility drill found exactly that on the sample's card-number field, which passes a
    // label from the payload and was reaching the platform anonymous. The typed text stays the
    // element's *value*; this only supplies its *name*.
    modifier = if (label != null) modifier.semantics { contentDescription = label } else modifier,
    enabled = enabled,
    singleLine = singleLine,
    // Drawn, but not announced: the field's own semantics above carry the name, and Android's
    // Material 3 *does* merge a label composable into the field while iOS's does not. Without this
    // the Android announcement stutters -- "Card number Card number 0/16" -- which the Android
    // conformance drill caught the first time both clients were graded on the same claim.
    label = label?.let { { Text(it, modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.bodyMedium) } },
    placeholder = placeholder?.let { { Text(it, color = palette().inkSecondary) } },
    supportingText = if (showCounter && capacity != Int.MAX_VALUE) {
      { Text("${raw.length}/$capacity", color = palette().inkSecondary, style = MaterialTheme.typography.labelSmall) }
    } else {
      null
    },
    keyboardOptions = keyboardOptionsFor(keyboard),
    visualTransformation = when {
      keyboard == "password" -> androidx.compose.ui.text.input.PasswordVisualTransformation()
      mask != null -> MaskTransformation(mask)
      else -> androidx.compose.ui.text.input.VisualTransformation.None
    },
    shape = RoundedCornerShape(Radius.Sm),
  )
}

/** Keyboards are named, not ordinals, so a new one is an addition rather than a renumbering. */
private fun keyboardOptionsFor(name: String?): androidx.compose.foundation.text.KeyboardOptions =
  androidx.compose.foundation.text.KeyboardOptions(
    keyboardType = when (name) {
      "number" -> androidx.compose.ui.text.input.KeyboardType.Number
      "phone" -> androidx.compose.ui.text.input.KeyboardType.Phone
      "email" -> androidx.compose.ui.text.input.KeyboardType.Email
      "password" -> androidx.compose.ui.text.input.KeyboardType.Password
      "decimal" -> androidx.compose.ui.text.input.KeyboardType.Decimal
      else -> androidx.compose.ui.text.input.KeyboardType.Text
    },
  )

/**
 * The host half of enter and exit.
 *
 * The design fork worth recording: animating a node *as it is removed* needs somebody to hold it
 * past the guest's removal, and doing that in the applier would break the protocol. Indices inside
 * a change batch assume removal is immediate, so a retained node would make every subsequent child
 * add or move in that batch address the wrong slot — a correctness failure, not a cosmetic one.
 *
 * So the guest keeps the node and declares visibility, and this reports when the exit has
 * finished. The `MutableTransitionState` is remembered per node, so it survives a code update
 * exactly as a scroll position does, and a payload republished mid-exit does not restart it.
 *
 * `onExited` fires when the transition settles on *not visible*. An exit interrupted by becoming
 * visible again is not an exit and reports nothing, for the same reason a retargeted animation
 * reports no completion: "finished" has to mean one thing.
 */
@Composable
fun PresenceImpl(
  visible: Boolean,
  enter: String?,
  exit: String?,
  modifier: Modifier,
  onExited: () -> Unit,
  content: @Composable () -> Unit,
) {
  val state = androidx.compose.runtime.remember {
    androidx.compose.animation.core.MutableTransitionState(visible)
  }
  state.targetState = visible

  val currentOnExited by androidx.compose.runtime.rememberUpdatedState(onExited)
  androidx.compose.runtime.LaunchedEffect(state) {
    // "Exited" means *was visible and now is not*, which is a narrower claim than "is not
    // visible" and the difference is not academic: a `Presence` that starts hidden satisfies the
    // looser one on its very first frame, so a guest using this to know when removal is safe would
    // have torn down content that had never been shown. Found by the test that asks for exactly
    // that case.
    var everVisible = state.currentState
    androidx.compose.runtime.snapshotFlow { Triple(state.isIdle, state.currentState, state.targetState) }
      .collect { (idle, current, target) ->
        if (current) {
          everVisible = true
        } else if (idle && !target && everVisible) {
          // Reset, so a node shown and hidden again reports again.
          everVisible = false
          currentOnExited()
        }
      }
  }

  androidx.compose.animation.AnimatedVisibility(
    visibleState = state,
    modifier = modifier,
    enter = enterTransitionOf(enter),
    exit = exitTransitionOf(exit),
  ) {
    content()
  }
}

/**
 * Named transition parts, combinable with `+`.
 *
 * Named rather than a structured specification because the set is small, the host owns what each
 * name looks like, and an unknown one can then degrade — which it does, to a fade, with the name
 * recorded as skew. A payload built against a newer design system gets motion that is slightly
 * wrong rather than a screen that throws.
 */
@Composable
private fun enterTransitionOf(names: String?): androidx.compose.animation.EnterTransition {
  if (names.isNullOrBlank()) return androidx.compose.animation.fadeIn()
  val skew = LocalSkewReport.current
  return names.split("+").map { it.trim() }.filter { it.isNotEmpty() }
    .map { name ->
      when (name) {
        "fade" -> androidx.compose.animation.fadeIn()
        "expandVertically" -> androidx.compose.animation.expandVertically()
        "expandHorizontally" -> androidx.compose.animation.expandHorizontally()
        "slideUp" -> androidx.compose.animation.slideInVertically { it }
        "slideDown" -> androidx.compose.animation.slideInVertically { -it }
        "scale" -> androidx.compose.animation.scaleIn()
        else -> {
          skew.unknownTransitions += name
          androidx.compose.animation.fadeIn()
        }
      }
    }
    .reduce { a, b -> a + b }
}

@Composable
private fun exitTransitionOf(names: String?): androidx.compose.animation.ExitTransition {
  if (names.isNullOrBlank()) return androidx.compose.animation.fadeOut()
  val skew = LocalSkewReport.current
  return names.split("+").map { it.trim() }.filter { it.isNotEmpty() }
    .map { name ->
      when (name) {
        "fade" -> androidx.compose.animation.fadeOut()
        "shrinkVertically" -> androidx.compose.animation.shrinkVertically()
        "shrinkHorizontally" -> androidx.compose.animation.shrinkHorizontally()
        "slideUp" -> androidx.compose.animation.slideOutVertically { -it }
        "slideDown" -> androidx.compose.animation.slideOutVertically { it }
        "scale" -> androidx.compose.animation.scaleOut()
        else -> {
          skew.unknownTransitions += name
          androidx.compose.animation.fadeOut()
        }
      }
    }
    .reduce { a, b -> a + b }
}

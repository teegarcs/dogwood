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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun PrimaryButtonImpl(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = RoundedCornerShape(Radius.Sm),
    colors = ButtonDefaults.buttonColors(
      containerColor = Palette.Primary,
      contentColor = Palette.OnPrimary,
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
    modifier = modifier.clip(RoundedCornerShape(cornerRadiusDp.dp)).background(Palette.CanvasContrast),
    onError = { state -> println("dogwood: image failed for $url: ${state.result.throwable}") },
  )
}

@Composable
fun CardImpl(modifier: Modifier, content: @Composable () -> Unit) {
  Card(
    modifier = modifier,
    shape = RoundedCornerShape(Radius.Md),
    colors = CardDefaults.cardColors(containerColor = Palette.Canvas),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    content()
  }
}

@Composable
fun BadgeImpl(text: String, selected: Boolean, modifier: Modifier) {
  Surface(
    modifier = modifier.clip(RoundedCornerShape(Radius.Xs)),
    color = if (selected) Palette.SuccessContainer else Palette.CanvasContrast,
  ) {
    Text(
      text,
      modifier = Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
      color = if (selected) Palette.Success else Palette.InkSecondary,
      style = MaterialTheme.typography.labelMedium,
    )
  }
}

@Composable
fun DividerImpl(modifier: Modifier) {
  HorizontalDivider(modifier, color = Palette.Line)
}

@Composable
fun ChipImpl(text: String, selected: Boolean, modifier: Modifier, onSelectedChange: (Boolean) -> Unit) {
  Surface(
    modifier = modifier
      .clip(RoundedCornerShape(Radius.Full))
      .clickable { onSelectedChange(!selected) },
    color = if (selected) Palette.Primary else Palette.CanvasContrast,
  ) {
    Text(
      text,
      modifier = Modifier.padding(horizontal = Spacing.Base, vertical = Spacing.Md),
      color = if (selected) Palette.OnPrimary else Palette.Ink,
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
    leadingText?.let { Text(it, color = Palette.InkSecondary, style = MaterialTheme.typography.bodySmall) }
    previousPrice?.let {
      Text(
        it,
        color = Palette.InkSecondary,
        style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
      )
    }
    Text(price, color = Palette.Ink, style = MaterialTheme.typography.titleMedium)
    trailingText?.let { Text(it, color = Palette.InkSecondary, style = MaterialTheme.typography.bodySmall) }
  }
}

@Composable
fun StarRatingImpl(rating: Float, label: String?, modifier: Modifier) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
    Text("★", color = Palette.Star, style = MaterialTheme.typography.bodyMedium)
    Text(rating.toString(), color = Palette.Ink, style = MaterialTheme.typography.labelLarge)
    label?.let { Text(it, color = Palette.InkSecondary, style = MaterialTheme.typography.bodySmall) }
  }
}

@Composable
fun SectionHeaderImpl(title: String, description: String?, modifier: Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
    Text(title, color = Palette.Ink, style = MaterialTheme.typography.titleLarge)
    description?.let { Text(it, color = Palette.InkSecondary, style = MaterialTheme.typography.bodyMedium) }
  }
}

/*
 * Acme's components -- the hand-written half of a product's design system.
 *
 * The generator emits the bridge; this is the part that requires taste, and the split is exactly
 * the one Dogwood draws for its own segment
 * ([ADR-011](../../../../../../../../../adrs/layer-5/ADR-011-generator-emits-the-bridge.md)). What
 * a product writes is one `*Impl` per component and nothing else: no dispatch, no property
 * decoding, no tag arithmetic, no skew handling.
 *
 * Nothing here imports anything about the protocol. These are ordinary Compose functions taking
 * ordinary types.
 */
package dev.acme.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Acme's brand colour, so a rendered Acme component is visibly not a Dogwood one. */
private val AcmeInk = Color(0xFF7B1FA2)

@Composable
fun AcmePriceImpl(amount: Int, currency: String, emphasis: String?, modifier: Modifier) {
  val major = amount / 100
  val minor = (amount % 100).toString().padStart(2, '0')
  val symbol = when (currency) {
    "USD" -> "$"
    "EUR" -> "€"
    "JPY" -> "¥"
    // An unknown currency degrades to its code rather than throwing, which is the same rule every
    // named thing in this system follows.
    else -> "$currency "
  }
  Text(
    text = if (currency == "JPY") "$symbol$major" else "$symbol$major.$minor",
    modifier = modifier,
    color = AcmeInk,
    style = MaterialTheme.typography.titleMedium,
    fontWeight = if (emphasis == "strong") FontWeight.Bold else FontWeight.Normal,
  )
}

@Composable
fun AcmeActionImpl(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    colors = ButtonDefaults.buttonColors(containerColor = AcmeInk),
  ) {
    Text(label)
  }
}

@Composable
fun AcmePanelImpl(inset: Int, modifier: Modifier, content: @Composable () -> Unit) {
  OutlinedCard(modifier = modifier, border = BorderStroke(2.dp, AcmeInk)) {
    Column(Modifier.padding(inset.dp)) { content() }
  }
}

/**
 * The generated binding hands this a real `AcmeTone` -- the host-side copy the generator emitted
 * from the surface -- and a lambda that takes one. Nothing here knows the value crossed as a name.
 */
@Composable
fun AcmeTagImpl(label: String, tone: AcmeTone, modifier: Modifier, onToneChange: (AcmeTone) -> Unit) {
  val ink = when (tone) {
    AcmeTone.Neutral -> AcmeInk
    AcmeTone.Positive -> Color(0xFF2E7D32)
    AcmeTone.Negative -> Color(0xFFC62828)
  }
  Text(
    text = "[$label]",
    modifier = modifier.padding(4.dp).clickable {
      // Cycle to the next tone, so a tap proves an enumeration crosses back as well as down.
      onToneChange(AcmeTone.entries[(tone.ordinal + 1) % AcmeTone.entries.size])
    },
    color = ink,
    style = MaterialTheme.typography.labelLarge,
  )
}

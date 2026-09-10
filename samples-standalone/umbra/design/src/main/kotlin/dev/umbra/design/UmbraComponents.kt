/*
 * Umbra's implementations. Ordinary Compose; nothing here imports the protocol.
 */
package dev.umbra.design

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun UmbraBannerImpl(message: String, tone: String?, modifier: Modifier) {
  Text(if (tone == "loud") message.uppercase() else message, modifier)
}

@Composable
fun UmbraStepperImpl(value: Int, enabled: Boolean, modifier: Modifier, onChange: (Int) -> Unit) {
  Row(modifier) {
    TextButton(onClick = { onChange(value - 1) }, enabled = enabled) { Text("−") }
    Text("$value")
    TextButton(onClick = { onChange(value + 1) }, enabled = enabled) { Text("+") }
  }
}

/**
 * Not an `Impl`. This is Umbra's own design-system component, the thing an adopting team already
 * has -- and the surface's `@Implementation` points the generated binding straight at it. If this
 * signature drifts from the surface, the HOST build fails with a named argument mismatch, which is
 * the same compiler-enforced contract a wrapper has.
 */
@Composable
fun UmbraChip(label: String, modifier: Modifier = Modifier) {
  Text(
    text = "[ $label ]",
    fontWeight = FontWeight.Medium,
    modifier = modifier.padding(4.dp),
  )
}


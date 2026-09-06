/*
 * Umbra's implementations. Ordinary Compose; nothing here imports the protocol.
 */
package dev.umbra.design

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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

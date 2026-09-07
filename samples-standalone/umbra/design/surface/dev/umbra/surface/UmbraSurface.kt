/*
 * Umbra's surface. Two components, and nothing about Dogwood beyond the annotations.
 */
package dev.umbra.surface

import androidx.compose.runtime.Composable

@Composable
fun UmbraBanner(
  message: TextValue,
  modifier: Modifier = Modifier,
  tone: String? = null,
) {}

@Composable
fun UmbraStepper(
  @Range(min = 0.0, max = 99.0) value: Int,
  modifier: Modifier = Modifier,
  @Affordance enabled: Boolean = true,
  onChange: (Int) -> Unit,
) {}

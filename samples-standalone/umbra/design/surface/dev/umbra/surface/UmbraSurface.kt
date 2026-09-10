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

/**
 * Bound DIRECTLY to Umbra's own design-system composable -- there is no `UmbraChipImpl` anywhere.
 *
 * This is the adopter path for a team that already owns a design system: when the component's
 * parameter names and wire-side types already line up, the wrapper is ceremony, and
 * `@Implementation` deletes it. `UmbraBanner` and `UmbraStepper` above keep their wrappers on
 * purpose, because a real surface mixes both -- a wrapper earns its keep exactly where a wire type
 * needs mapping before the real component can be called.
 */
@Composable
@Implementation("dev.umbra.design.UmbraChip")
fun UmbraChip(
  label: String,
  modifier: Modifier = Modifier,
) {}


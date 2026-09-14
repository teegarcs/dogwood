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

/**
 * A tone the design system already owns.
 *
 * `@Implementation` on an enumeration is the same bargain as on a component: the surface declares
 * the entries, the guest gets a copy, and the host decodes the name straight into Umbra's own
 * `dev.umbra.design.UmbraTone` -- so no generated copy exists on the host and `UmbraBadge` below
 * is called with the type it was written against. The entry names must match, and the compiler
 * says so if they stop matching.
 */
@Implementation("dev.umbra.design.UmbraTone")
enum class UmbraTone { Calm, Loud }

/**
 * Bound directly, like `UmbraChip`, and taking an enumeration rather than the `String` that
 * `UmbraBanner`'s `tone` had to be before enumerations could cross. The standalone check requires
 * this component in the render transcript for that reason.
 */
@Composable
@Implementation("dev.umbra.design.UmbraBadge")
fun UmbraBadge(
  label: String,
  tone: UmbraTone = UmbraTone.Calm,
  modifier: Modifier = Modifier,
) {}

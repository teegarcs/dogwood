/*
 * Project Dogwood -- the registered design system's surface. THE SOURCE OF TRUTH.
 *
 * This file is not compiled by anything. It is *read* by `dogwood-codegen`, which emits from it:
 * the guest stubs, the host binding layer, and the versioned dictionary. Two ends of a boundary
 * generated from one parse cannot drift, which is the entire point.
 *
 * The signatures are modelled on Skyscanner Backpack, wrapped where the bindability audit found
 * a failure -- `interactionSource` dropped, `Painter` replaced by a Uniform Resource Locator
 * (URL), `contentDescription` taking a finished string rather than a lambda the host would have
 * to call. See adrs/layer-5/ADR-008.
 *
 * **Declaration order is the tag order, and tags are permanent.** Append; never reorder, never
 * delete. A client one dictionary version behind keeps rendering everything it already knew only
 * because that rule holds.
 */
package dev.dogwood.surface

import androidx.compose.runtime.Composable

@Composable
fun PrimaryButton(
  label: String,
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {}

@Composable
fun AsyncImage(
  url: String,
  contentDescription: String? = null,
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  cornerRadiusDp: Int = 8,
) {}

@Composable
fun Card(
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  content: @Composable () -> Unit,
) {}

@Composable
fun Badge(
  text: String,
  selected: Boolean = false,
  modifier: DogwoodModifier = DogwoodModifier.Empty,
) {}

@Composable
fun Divider(
  modifier: DogwoodModifier = DogwoodModifier.Empty,
) {}

@Composable
fun Chip(
  text: String,
  selected: Boolean = false,
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  onSelectedChange: (Boolean) -> Unit,
) {}

@Composable
fun Price(
  price: String,
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  leadingText: String? = null,
  previousPrice: String? = null,
  trailingText: String? = null,
) {}

@Composable
fun StarRating(
  rating: Float,
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  label: String? = null,
) {}

@Composable
fun SectionHeader(
  title: String,
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  description: String? = null,
) {}

/**
 * An icon from the host's icon dictionary.
 *
 * Absent from the first nine components on purpose: Compose's `Icon` takes a required `Painter`,
 * `ImageBitmap` or `ImageVector`, and the sandbox has no filesystem, no network, and no stable
 * host resource identifiers. The icon dictionary is what unblocks it -- the guest names an icon
 * and the host resolves it, exactly as it resolves a colour token. A name this client does not
 * carry renders the icon set's fallback and is reported as skew.
 *
 * @param tint a colour *token* name, not a colour. A literal could not follow dark mode.
 */
@Composable
fun Icon(
  name: String,
  contentDescription: String? = null,
  modifier: DogwoodModifier = DogwoodModifier.Empty,
  sizeDp: Int = 24,
  tint: String? = null,
) {}

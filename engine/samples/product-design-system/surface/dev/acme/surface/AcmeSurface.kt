/*
 * Acme's design system -- a product's own components, not Dogwood's.
 *
 * This file is the point of the whole registration mechanism, and it is deliberately in a package
 * nothing in `dev.dogwood` knows about. `specs/layer-5-host.md` names the gap in one sentence:
 * "without (c) a guest can emit only raw Material 3, which no product team ships." Until a second
 * surface existed, that claim was untested -- the generator ran over exactly one file, in this
 * repository, and every mechanism built around it could have been quietly assuming so.
 *
 * It is small on purpose. Three components are enough to exercise the parts that could differ from
 * Dogwood's own segment: a value parameter, a host-resolved one, an affordance, an event, a content
 * slot, and a `@Range`. If those work in a segment the engine has never heard of, the mechanism is
 * the mechanism rather than a special case with one caller.
 */
package dev.acme.surface

import androidx.compose.runtime.Composable

/**
 * A price with Acme's own emphasis rules.
 *
 * @param amount minor units — cents, yen — because a guest that formats currency itself has
 *   already lost the argument about which locale it is in.
 * @param currency an ISO 4217 code the host resolves against the device's own formatter.
 */
@Composable
fun AcmePrice(
  amount: Int,
  currency: String,
  modifier: Modifier = Modifier,
  emphasis: String? = null,
) {}

/**
 * Acme's call to action.
 *
 * @param enabled marked `@Affordance`, so this widget is **withheld** rather than drawn if a
 *   payload says something about it this client cannot read. A product's controls need that guard
 *   exactly as much as Dogwood's do, and getting it by declaration rather than by remembering is
 *   the reason the marking is on the surface.
 */
@Composable
fun AcmeAction(
  label: TextValue,
  modifier: Modifier = Modifier,
  @Affordance enabled: Boolean = true,
  onClick: () -> Unit,
) {}

/**
 * A bordered region with Acme's own corner and spacing scale.
 *
 * @param inset density-independent pixels. `@Range` because Compose throws on a negative padding
 *   *inside composition*, which on a payload delivered over the air is not a degraded screen but no
 *   screen, on every client at once.
 */
@Composable
fun AcmePanel(
  modifier: Modifier = Modifier,
  @Range(min = 0.0, max = 64.0) inset: Int = 12,
  content: @Composable () -> Unit,
) {}

/**
 * A tone, declared on the surface so a component may take one.
 *
 * The enumeration is the fourth thing this surface exercises that Dogwood's own does not use, and
 * it exists here for the reason the other three do: a design system's most common non-primitive
 * parameter shape is an enumeration, and until a surface outside the engine carried one the
 * generator had silently emitted code for it that did not compile. It crosses as its entry name.
 */
enum class AcmeTone { Neutral, Positive, Negative }

/**
 * A small label whose look is decided by a [tone] -- and the second component here to hand a
 * value back, so the event half of an enumeration is exercised as well as the property half.
 */
@Composable
fun AcmeTag(
  label: String,
  tone: AcmeTone = AcmeTone.Neutral,
  modifier: Modifier = Modifier,
  onToneChange: (AcmeTone) -> Unit = {},
) {}

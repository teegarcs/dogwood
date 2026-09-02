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
 *
 * **`@Affordance` marks a parameter whose absence changes what the user is allowed to do**, rather
 * than how something looks -- `enabled`, `checked`, `readOnly`, `selected`. Section 6 of the
 * technical specification requires the marking, because a widget carrying an affordance a client
 * cannot read must be withheld rather than drawn: every other kind of skew degrades appearance,
 * and this kind degrades into a control that lies about what it will do. The marking is read from
 * here rather than guessed from the parameter's name, since a guess would silently miss
 * `interactive`, `locked` or `isEditable`. Adding one to an existing component is a compatibility
 * event and the lock treats it as one.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Affordance
package dev.dogwood.surface

import androidx.compose.runtime.Composable

@Composable
fun PrimaryButton(
  label: TextValue,
  modifier: Modifier = Modifier,
  @Affordance enabled: Boolean = true,
  onClick: () -> Unit,
) {}

@Composable
fun AsyncImage(
  url: String,
  contentDescription: String? = null,
  modifier: Modifier = Modifier,
  cornerRadiusDp: Int = 8,
) {}

@Composable
fun Card(
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {}

/**
 * A badge is read, not operated, so its `selected` is **deliberately not** an `@Affordance`.
 *
 * The specification names `selected` among the safety-relevant parameters, and for a control that
 * is right. This one carries no event: nothing the user does depends on it, so getting it wrong
 * costs appearance. Withholding the whole badge over cosmetic skew would be a far larger
 * regression than the skew. The rule is what the parameter governs, not what it is called --
 * compare `Chip`, whose `selected` is bound to a handler and is marked.
 */
@Composable
fun Badge(
  text: TextValue,
  selected: Boolean = false,
  modifier: Modifier = Modifier,
) {}

@Composable
fun Divider(
  modifier: Modifier = Modifier,
) {}

@Composable
fun Chip(
  text: TextValue,
  @Affordance selected: Boolean = false,
  modifier: Modifier = Modifier,
  onSelectedChange: (Boolean) -> Unit,
) {}

@Composable
fun Price(
  price: TextValue,
  modifier: Modifier = Modifier,
  leadingText: TextValue? = null,
  previousPrice: TextValue? = null,
  trailingText: TextValue? = null,
) {}

@Composable
fun StarRating(
  rating: Float,
  modifier: Modifier = Modifier,
  label: TextValue? = null,
) {}

@Composable
fun SectionHeader(
  title: TextValue,
  modifier: Modifier = Modifier,
  description: TextValue? = null,
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
 * @param tint a `Color`, which is always a recipe -- `Color.token("primary")` follows the host's
 *   palette including dark mode, and `Color(0xFF…)` is the deliberate opt-out. It used to be a
 *   `String` holding a token name: host-resolved in fact, but invisible to the type system.
 */
@Composable
fun Icon(
  name: String,
  contentDescription: String? = null,
  modifier: Modifier = Modifier,
  sizeDp: Int = 24,
  tint: Color? = null,
) {}

/**
 * A text field whose state the host owns.
 *
 * The low-level shape. Guest code should use `dev.dogwood.compose.TextField`, which wraps this in
 * a `TextFieldState` and hides the version stamping — see
 * `adrs/layer-5/ADR-019-text-input.md` for why the version exists and why a naive controlled text
 * field is forbidden.
 *
 * @param version the host edit count this value is answering. The host **discards** a value
 *   stamped older than its own count, because the user has typed since.
 * @param mask a display pattern: `#` takes a digit, `A` a letter, anything else is a literal. The
 *   guest's [text] is always the raw value.
 * @param maxLength raw characters, enforced host-side. -1 for no limit.
 * @param showCounter drawn and computed by the host; a guest-computed counter would be a crossing
 *   per keystroke.
 */
@Composable
fun TextInput(
  text: String,
  version: Int = 0,
  modifier: Modifier = Modifier,
  label: TextValue? = null,
  placeholder: TextValue? = null,
  @Affordance enabled: Boolean = true,
  singleLine: Boolean = true,
  maxLength: Int = -1,
  mask: String? = null,
  keyboard: String? = null,
  showCounter: Boolean = false,
  onValueChange: (String, Int) -> Unit,
) {}

/**
 * Animates its content in and out.
 *
 * The enter half of this is easy and the exit half is the reason it is a *container*. Animating a
 * node as it is removed needs somebody to keep it alive after the guest has removed it, and doing
 * that in the applier is not an option: indices inside a change batch assume removal is immediate,
 * so every subsequent child add or move in the same batch would address the wrong slot. That is a
 * correctness failure, not a cosmetic one.
 *
 * So the guest keeps the node composed and declares *visibility* instead, and [onExited] tells it
 * when removal is safe. The node the guest is animating away is a node it still owns.
 *
 * @param enter named transition parts, combinable with `+`: `fade`, `expandVertically`,
 *   `expandHorizontally`, `slideUp`, `slideDown`, `scale`. An unknown name is skew and degrades to
 *   a fade rather than throwing.
 * @param exit as [enter], with `shrinkVertically` and `shrinkHorizontally`.
 * @param onExited fired once, when the exit animation has finished and the content is gone. Not
 *   fired when an exit is interrupted by becoming visible again -- that is not an exit.
 */
@Composable
fun Presence(
  visible: Boolean,
  modifier: Modifier = Modifier,
  enter: String? = null,
  exit: String? = null,
  onExited: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {}

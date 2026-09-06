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

/**
 * The range a numeric parameter may take, enforced by the host at the moment it renders.
 *
 * **This exists because Compose enforces some ranges by throwing, and the throw lands inside
 * composition** -- a `maxLines` below one, a negative padding, a weight of zero. A payload is
 * delivered over the air without a store review, so an off-by-one in one property is not a
 * degraded screen but no screen, on every client that receives it, at the same moment
 * ([ADR-035](../../../../adrs/layer-5/ADR-035-hostile-property-values.md)).
 *
 * The generator emits a clamping reader for every parameter marked here, so the protection is a
 * property of the surface rather than of whoever wrote the binding. That was ADR-035's own stated
 * assumption: the hand-written clamps it shipped protect the properties somebody thought of, and
 * the next property with an enforced range and no clamp reintroduces the vector.
 *
 * A clamp is **reported**, not silent: it lands in `SkewReport.clampedValues` with the value that
 * arrived and the range it was forced into, so a designer wondering why their spacing is ignored
 * finds the answer in a report rather than in a debugger.
 *
 * Bounds are inclusive. Declare only what the host genuinely cannot render -- a range invented to
 * look tidy is a payload's legitimate value silently changed.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Range(val min: Double, val max: Double = Double.MAX_VALUE)

/**
 * Marks a parameter as a **live-state holder**: an object the host owns and the guest mirrors.
 *
 * Layer 4 forbids per-frame state in the guest, so a holder is never handed across. What crosses
 * is the mirror's asymmetry, established by
 * [ADR-014](../../../../adrs/layer-5/ADR-014-live-state-holders.md): **targets go down, reports
 * come up, and the host is authoritative**. A guest declares where it wants a list to be, or that
 * it wants a field focused; the host gets there however it gets there, and a stale target cannot
 * arrive because a property carries only its latest value.
 *
 * Without this marking a live-state parameter is **rejected**, and that default is deliberate: the
 * generator cannot tell a holder it knows how to mirror from one nobody has written a mirror for,
 * and plumbing the second would emit properties no host reads. The widget would render, and the
 * holder would be silently inert.
 *
 * So the marking is an assertion the generator checks rather than takes: a `@Holder` on a type
 * with no registered shape fails the build with the type's name in the message. Registering one is
 * an entry in `SurfaceParser.DEFAULT_HOLDER_SHAPES` plus the host-side mirror it names.
 *
 * A holder parameter is always optional. Most call sites do not want one, and the wire form says
 * so: the properties carry the shape's "absent" values, and nothing on the host observes anything.
 *
 * See [ADR-043](../../../../adrs/layer-5/ADR-043-holders-are-declared-on-the-surface.md).
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Holder
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
  @Range(min = 0.0) cornerRadiusDp: Int = 8,
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
  @Range(min = 0.0, max = 5.0) rating: Float,
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
  @Range(min = 1.0) sizeDp: Int = 24,
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
 * @param focus asks for the keyboard. A target with nothing reported back: focus is something the
 *   guest *asks for*, and "is this field focused right now?" is a per-frame question the mirror
 *   deliberately cannot answer. See [Holder].
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
  @Holder focus: FocusRequester? = null,
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

/**
 * A container whose content scrolls.
 *
 * The one layout capability a guest could not express at all: `Column` fills and clips, and a
 * screen taller than the viewport simply lost its bottom. `VerticalList` scrolls, but a list is the
 * wrong shape for a form or an article — it wants items, and this content is one composition.
 *
 * It is **not lazy**. Everything inside is composed, measured and kept, exactly as in a `Column`,
 * which is what makes it the right choice for a bounded page and the wrong one for a feed. Reach
 * for `VerticalList` when the content is long enough that composing all of it would be the problem.
 *
 * @param scroll the position, mirrored. Optional: a container without one still scrolls under the
 *   user's finger, and reports nothing, and cannot be driven by the guest. See [Holder] and
 *   [ADR-044](../../../../adrs/layer-5/ADR-044-scroll-position-is-a-declared-quantum.md).
 * @param horizontal lays the content out in a row and scrolls sideways.
 */
@Composable
fun ScrollArea(
  modifier: Modifier = Modifier,
  horizontal: Boolean = false,
  @Holder scroll: ScrollState? = null,
  content: @Composable () -> Unit,
) {}

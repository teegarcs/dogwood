/*
 * Project Dogwood -- reading the host's environment from guest logic.
 *
 * The second of the two legitimate ways to make an environment-dependent decision, and the one
 * that carries **no contract at all**. The first way is a design-system token: the guest names an
 * intent ("primary") and the host resolves it, which requires the name to mean something on the
 * client. This way, the guest reads the environment the host injected and decides *itself*, in
 * ordinary Kotlin, sending plain literals.
 *
 * The rule for choosing between them is one question: **who owns what this value means?**
 *
 *   - The design system owns it (brand colours, type ramp, spacing) -> use a token. Zero traffic
 *     on a theme change, and the meaning updates when the design system does.
 *   - This screen owns it (a one-off tint, a promo background, a chart palette) -> read the
 *     environment and send a literal. No name to agree on, nothing to version, nothing that can
 *     skew -- at the cost of one small message when the environment changes, because the guest has
 *     to recompose and resend its decision.
 *
 * What is deliberately NOT offered is a third way: sending the *logic* over the wire for the host
 * to evaluate ("if dark then X else Y" as data). It starts as one conditional and grows into an
 * expression language the client must implement and version -- the largest possible contract
 * surface, bought to save a message that costs a tenth of a millisecond. The guest already has a
 * programming language. It is Kotlin, and the environment is right here.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Whether the host is in dark mode. Compose's own name, because it is Compose's concept.
 *
 * Snapshot-backed: only the composables that call this recompose when the theme flips, and the
 * first composition already has the right answer -- the environment is present before it, not
 * shortly after ([Layer 5 ADR-012](../../../../../../adrs/layer-5/ADR-012-host-environment-subsystem.md)).
 */
@Composable
@ReadOnlyComposable
fun isSystemInDarkTheme(): Boolean = LocalHostEnvironment.current.darkMode

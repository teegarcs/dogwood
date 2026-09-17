/*
 * Project Dogwood -- Acme's own components, on the preview path.
 *
 * This is the shape [Layer 1](../../../../../../../../specs/layer-1-authoring.md) Milestone 3
 * describes for a registered design system: "Registered design-system components preview by
 * delegating to the **real** design-system module -- which the host build already depends on".
 * Acme's implementations are ordinary Compose in `:samples:product-design-system`, so the preview
 * delegate for `AcmeAction` is a call to `AcmeActionImpl`, and what the window shows for Acme's
 * components is not an approximation of them. It is them.
 *
 * That makes this file the *least* stand-in-ish part of the preview and the part a product should
 * copy: a design system that already exists as Compose needs no second implementation to preview.
 *
 * It lives in the sample rather than in `dogwood-compose-preview` for the reason the guest stubs
 * live in Acme's module rather than in Dogwood's: the package is Acme's, and the engine has never
 * heard of it. The file is hand-written here because the generator does not yet emit a JVM surface
 * (`plans/generator-v2.md` M6); when it does, it will emit exactly this, from the same
 * registration that emits the stubs.
 */
package dev.acme.guest

import androidx.compose.runtime.Composable
import dev.acme.design.AcmeActionImpl
import dev.acme.design.AcmePanelImpl
import dev.acme.design.AcmePriceImpl
import dev.acme.design.AcmeTagImpl
import dev.dogwood.compose.Modifier
import dev.dogwood.compose.TextValue
import dev.acme.design.AcmeTone as ImplTone

/**
 * The enumeration Acme declared on its surface.
 *
 * Both ends get it, and the entry *name* is what crosses -- so this preview copy translates by
 * name too rather than by ordinal, which is the same rule the wire follows and the reason
 * reordering the declaration breaks nothing.
 */
enum class AcmeTone { Neutral, Positive, Negative }

private fun AcmeTone.impl(): ImplTone = ImplTone.valueOf(name)

private fun ImplTone.guest(): AcmeTone = AcmeTone.valueOf(name)

@Composable
fun AcmePrice(
  amount: Int,
  currency: String,
  modifier: Modifier = Modifier,
  emphasis: String? = null,
) {
  AcmePriceImpl(amount = amount, currency = currency, emphasis = emphasis, modifier = modifier.real)
}

@Composable
fun AcmeAction(
  label: TextValue,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  AcmeActionImpl(label = label.toString(), enabled = enabled, modifier = modifier.real, onClick = onClick)
}

@Composable
fun AcmeAction(
  label: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  AcmeActionImpl(label = label, enabled = enabled, modifier = modifier.real, onClick = onClick)
}

@Composable
fun AcmePanel(
  modifier: Modifier = Modifier,
  inset: Int = 12,
  content: @Composable () -> Unit,
) {
  AcmePanelImpl(inset = inset, modifier = modifier.real, content = content)
}

@Composable
fun AcmeTag(
  label: String,
  tone: AcmeTone = AcmeTone.Neutral,
  modifier: Modifier = Modifier,
  onToneChange: (AcmeTone) -> Unit = {},
) {
  AcmeTagImpl(
    label = label,
    tone = tone.impl(),
    modifier = modifier.real,
    onToneChange = { onToneChange(it.guest()) },
  )
}

# ADR-035: Hostile Property Values Are Clamped and Reported, Not Thrown

**Date:** 2026-09-03
**Status:** Accepted

## 1. Context & Problem Statement

Every containment rule this system had before today is about **names**: a widget tag, an expression
factory, a colour token or a route that the client does not recognise, because the payload was built
against a newer dictionary than the client ships. All of them are handled the same way — the
unrecognised thing is withheld, the rest of the screen renders, and the name lands in the
`SkewReport` so somebody can see the gap.

There is a second class of bad input that none of those rules cover, and it is worse. The client
recognises the property perfectly. It reads the value, it understands the value, and the value is
one that Jetpack Compose refuses:

- `Text(maxLines = 0)` throws `IllegalArgumentException`; Compose requires at least one line.
- `Modifier.padding((-8).dp)` throws; padding may not be negative.
- `Modifier.weight(0f)` throws; weight must be positive.

The throw does not land in the layer that read the value. It lands **inside composition**, which
means it is not a degraded screen — it is *no screen*. And because the payload that carries the
value is delivered over the air, without a store review, an off-by-one in a single property takes
down the screen on **every client that receives it, at the same moment**. The blast radius of a
typo is the whole installed base.

We confirmed the vector rather than reasoning about it. `HostileValueTest` drives real change
batches through the real `HostTree` and the real Compose renderer. Before this change, five of its
seven cases crashed the render: zero `maxLines`, negative `maxLines`, negative padding, zero weight,
and the aggregate case. A negative `size` happened not to crash — which is exactly the problem with
relying on Compose's tolerance, since that tolerance is an implementation detail and not a contract.

The unit test proves the mechanism. What it cannot show is the blast radius, so the fix was also run
end to end on both mobile hosts against a payload fetched over the network — see
[the hostile-value drill](../../tools/hostile-value-drill/README.md). The pre-fix Android build died
with `FATAL EXCEPTION: main` the moment the screen opened; the pre-fix iOS build died with an
uncaught Kotlin exception before its drill could even reach that tab.

## 2. Decision

Numeric values whose range Compose enforces by throwing are **clamped into the legal range at the
reader layer, and the clamp is reported** in `SkewReport.clampedValues`.

Three entry points, in `WidgetView.kt`:

```kotlin
@Composable fun WidgetView.intClamped(tag: Int, default: Int, min: Int, max: Int = Int.MAX_VALUE, what: String): Int
@Composable fun WidgetView.floatClamped(tag: Int, default: Float, min: Float, max: Float = Float.MAX_VALUE, what: String): Float
@Composable fun clampModifierValue(raw: Float, min: Float, max: Float = Float.MAX_VALUE, what: String): Float
```

The first two are property readers, tag-addressed like every other reader in that file. The third
exists because a modifier argument is not a property: it arrives as an element of a chain rather
than under a tag, so there is nothing to read it *by*. All three are `@Composable` so they can reach
`LocalSkewReport.current`; `composeModifier` was already `@Composable`, so nothing had to change to
accommodate them.

Applied at the sites the test proved dangerous, plus the dimension modifiers for consistency:

| Site | Rule |
| --- | --- |
| `Text.maxLines` (`Bindings.kt`) | `min = 1` |
| `padding` (`Modifiers.kt`) | `min = 0f` |
| `weight` (`Modifiers.kt`) | `min = Float.MIN_VALUE` (strictly positive) |
| `size` / `width` / `height` (`Modifiers.kt`) | `min = 0f` |

`SkewReport.clampedValues` is documented as distinct from every other field on that report. The
others say "your payload used a name I do not know." This one says "your payload used a value I
understood and refused." The entry carries the property name, the value that arrived and the range
it was forced into — `Text.maxLines=0 outside 1..2147483647` — because a designer wondering why
their spacing is being ignored should find the answer in the skew report and not in a debugger.

## 3. Rationale & Research

**Why clamp rather than withhold the widget.** Withholding is the right answer for an unknown name,
because an unknown name means the client genuinely cannot render the thing. A value out of range is
different: the client knows exactly what to draw, and drawing it two pixels off is unambiguously
better for the user than drawing nothing. Clamping degrades appearance; withholding degrades
function.

**Why clamp rather than reject the batch.** `ProtocolMismatch` exists for batches whose *grammar* is
wrong, where the tree would be left inconsistent if applied. A hostile value leaves the tree
perfectly consistent — it is well-formed data with a bad number in it. Rejecting the batch would
take down the same screen the throw did, just with a nicer message.

**Why at the reader layer.** The alternative is a guard per binding, which the generator would have
to be taught anyway, and which is wrong the first time somebody adds a binding and forgets. Putting
it in the readers means the clamp is one call away from every property the dictionary has.

**The upstream constraints, verified against Compose sources rather than assumed:**

- `maxLines`: [`BasicText`](https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/BasicText.kt)
  requires `maxLines > 0`.
- `padding`: [`Padding.kt`](https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/foundation/foundation-layout/src/commonMain/kotlin/androidx/compose/foundation/layout/Padding.kt)
  requires all padding values to be non-negative.
- `weight`: [`RowColumnImpl.kt`](https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/foundation/foundation-layout/src/commonMain/kotlin/androidx/compose/foundation/layout/RowColumnImpl.kt)
  requires `weight > 0.0`.

## 4. Unstated Assumptions

- ~~**Compose's list of throwing ranges is not exhaustive here.**~~ **The durable fix is built**
  ([ADR-042](ADR-042-ranges-are-declared-on-the-surface.md)): `@Range(min, max)` on a surface
  parameter makes the generator emit a clamping reader, so protection is a property of the
  declaration rather than of whoever wrote the binding. The original text, because it is still
  what the hand-written clamps do on their own: we clamped the properties the fuzz test proved
  dangerous and the dimension modifiers alongside them, and a property added later with an enforced
  range and no clamp would reintroduce the vector for that one property.

  The hand-written readers remain, and correctly: `Text.maxLines` and the modifier arguments are
  not generated from the surface at all — a modifier argument arrives as an element of a chain
  rather than under a property tag, so there is nothing for a surface annotation to attach to.
- **The clamped value is assumed to be the better of two bad outcomes.** It is, for appearance. If a
  future property's out-of-range value would change *meaning* rather than looks — an affordance, a
  destination — clamping would be the wrong default and the withholding rule from ADR-031 applies
  instead.
- **`SkewReport` is sampled, not observed.** Clamps recorded during composition are readable
  afterwards; nothing recomposes because a clamp was recorded.

## 5. Updated Documents

- [Layer 5: Host Rendering](../../specs/layer-5-host.md)
- `engine/dogwood-host/src/commonMain/kotlin/dev/dogwood/host/WidgetView.kt`
- `engine/dogwood-host/src/commonMain/kotlin/dev/dogwood/host/Bindings.kt`
- `engine/dogwood-host/src/commonMain/kotlin/dev/dogwood/host/Modifiers.kt`
- `engine/dogwood-host/src/commonMain/kotlin/dev/dogwood/host/Skew.kt`
- `engine/dogwood-host/src/jvmTest/kotlin/dev/dogwood/host/HostileValueTest.kt`
- [The hostile-value drill](../../tools/hostile-value-drill/README.md)
- `engine/samples/slice-android/.../TabsActivity.kt` and `engine/samples/slice-ios/.../Main.kt` (both
  now surface `SkewReport`, which is how the drill could report anything at all)

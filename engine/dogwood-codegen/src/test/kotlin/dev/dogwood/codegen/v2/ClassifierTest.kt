/*
 * Project Dogwood -- the v2 bindability rule on a real signature.
 *
 * `Button` as material3 1.9.0 declares it, copied from the fetched source. Every parameter lands in
 * one of the three outcomes the plan names, and the host-default-only ones keep their default's
 * text byte for byte, because that text is what the host binding will pass.
 */
package dev.dogwood.codegen.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BUTTON = """
package androidx.compose.material3

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {}

@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {}

@Composable
fun Icon(painter: Painter, contentDescription: String?) {}

@Composable
fun TextField(value: String, onValueChange: (String) -> Unit) {}

@Composable
fun TextField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit) {}

@Composable
fun Lazy(content: LazyListScope.() -> Unit) {}

/*
 * Material 3 1.9.0's two Slider overloads, reduced to what makes them indistinguishable: the same
 * guest-visible parameters, `steps` and `onValueChangeFinished` swapped. See the ambiguity test.
 */
@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {}

@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    steps: Int = 0,
) {}
"""

class ClassifierTest {

  private val classified = Classifier.classify(
    LibrarySurface("material3", LibrarySurfaceParser().parseFile("Button.kt", BUTTON)),
  )

  private fun verdict(component: String, parameter: String): Verdict =
    classified.first { it.source.name == component }.parameters.first { it.parameter.name == parameter }.verdict

  @Test
  fun buttonIsBoundAndEveryParameterLandsWhereThePlanSaysItDoes() {
    val button = classified.first { it.source.name == "Button" }
    assertTrue(button.isBindable, button.unbindableReason.orEmpty())
    assertEquals(Kind.EVENT, (verdict("Button", "onClick") as Verdict.Settable).kind)
    assertEquals(Kind.MODIFIER, (verdict("Button", "modifier") as Verdict.Settable).kind)
    val enabled = verdict("Button", "enabled") as Verdict.Settable
    assertEquals(Kind.PRIMITIVE, enabled.kind)
    assertTrue(enabled.affordance, "enabled governs what the user may do")
    assertEquals(Kind.SHAPE, (verdict("Button", "shape") as Verdict.Settable).kind)
    assertEquals(Kind.PADDING_VALUES, (verdict("Button", "contentPadding") as Verdict.Settable).kind)
    val content = verdict("Button", "content") as Verdict.Settable
    assertEquals(Kind.SLOT, content.kind)
    assertEquals("RowScope", content.slotReceiver)
  }

  @Test
  fun aParameterTheTableCannotCrossButWhichHasADefaultIsHostDefaultOnlyWithItsTextVerbatim() {
    assertEquals(Verdict.HostDefaultOnly("ButtonColors", "ButtonDefaults.buttonColors()"), verdict("Button", "colors"))
    assertEquals(Verdict.HostDefaultOnly("ButtonElevation?", "ButtonDefaults.buttonElevation()"), verdict("Button", "elevation"))
    assertEquals(Verdict.HostDefaultOnly("BorderStroke?", "null"), verdict("Button", "border"))
    assertEquals(Verdict.HostDefaultOnly("MutableInteractionSource?", "null"), verdict("Button", "interactionSource"))
  }

  @Test
  fun aScaffoldContentSlotThatReceivesPaddingIsStillASlot() {
    val content = verdict("Scaffold", "content") as Verdict.Settable
    assertEquals(Kind.SLOT, content.kind)
    assertTrue(content.slotTakesPadding)
  }

  @Test
  fun aRequiredAssetMakesTheComponentUnbindableWithTheReason() {
    val icon = classified.first { it.source.name == "Icon" }
    assertEquals("painter: asset-backed type Painter", icon.unbindableReason)
  }

  @Test
  fun controlledTextInputIsExcludedByNameAndTwoOverloadsWithTheSameNamesGetDifferentKeys() {
    val fields = classified.filter { it.source.name == "TextField" }
    assertEquals(2, fields.size)
    assertTrue(fields.all { it.unbindableReason?.startsWith("controlled text input") == true })
    assertEquals("TextField", fields[0].dictionaryName)
    assertTrue(fields[1].dictionaryName.startsWith("TextField~"), fields[1].dictionaryName)
    // The names match; only the types differ. The key must still differ, or the lock sees one
    // component with two encodings.
    assertTrue(fields[0].dictionaryName != fields[1].dictionaryName)
  }

  /**
   * The defect plans/material3-proof.md section 0 measured before it was fixed.
   *
   * Both overloads used to bind. Kotlin accepted them, because swapping two parameters of
   * different types is a different signature -- and every call that supplied only `value` and
   * `onValueChange` was an overload-resolution ambiguity, so no payload could call `Slider` at
   * all. Nothing caught it: the tier's tests composed wire trees, not Kotlin calls, and no sample
   * had used it.
   */
  @Test
  fun twoOverloadsAGuestCannotTellApartAreOneComponent() {
    val sliders = classified.filter { it.source.name == "Slider" }
    assertEquals(2, sliders.size)
    assertTrue(sliders[0].isBindable, sliders[0].unbindableReason.orEmpty())
    assertEquals(
      "guest signature identical to Slider after erasing host-default-only parameters",
      sliders[1].unbindableReason,
    )
  }

  @Test
  fun anInFrameScopeIsUnreachableNotBespoke() {
    val lazy = classified.first { it.source.name == "Lazy" }
    assertEquals("content: LazyListScope is invoked inside a frame", lazy.unbindableReason)
    assertEquals("unreachable", bucketOf(lazy.unbindableReason))
  }
}

/*
 * Project Dogwood -- `@Implementation`: the surface names the function the binding calls.
 *
 * The convention -- `${implPackage}.${name}Impl` -- exists for the components whose wire types need
 * mapping before the real component can be called. For an adopter who already owns a design system,
 * most components need no mapping at all, and the wrapper is ceremony: a function whose whole body
 * is a call-through with the same parameter names. `@Implementation("fully.qualified.Name")` on the
 * surface removes it by pointing the generated call at the adopter's composable directly.
 *
 * What these tests pin, and why each line matters:
 *
 *   - the annotated component's binding calls the named target, and the unannotated one beside it
 *     still calls the convention -- because the feature is per component, not per segment, and a
 *     surface will mix both for as long as some components need mapping;
 *   - the target never reaches the dictionary -- it is a host-side detail, two clients may bind the
 *     same component differently, and a payload must not be able to tell;
 *   - a malformed target fails the PARSE, loudly, because the surface is parsed rather than
 *     compiled and a constant reference here would silently generate a call to the wrong name.
 */
package dev.dogwood.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SURFACE = """
@Composable
fun DirectChip(
  label: String,
  modifier: Modifier = Modifier,
) {}

@Composable
@Implementation("dev.acme.ds.Chip")
fun BoundChip(
  label: String,
  modifier: Modifier = Modifier,
) {}
"""

class ImplementationTargetTest {

  private val components = SurfaceParser().parse(SURFACE)
  private val dictionary = buildDictionary("acme", 7, 1, components)
  private val bindings = emitHostBindings("dev.acme.host", "dev.acme.impl", dictionary, components)

  @Test
  fun anAnnotatedComponentCallsItsTargetDirectly() {
    assertTrue("dev.acme.ds.Chip(" in bindings, bindings.lines().filter { "Chip" in it }.toString())
    // And no wrapper name for it is ever emitted -- half a migration would be worse than none.
    assertFalse("BoundChipImpl" in bindings)
  }

  @Test
  fun anUnannotatedComponentStillCallsTheConvention() {
    assertTrue("dev.acme.impl.DirectChipImpl(" in bindings)
  }

  @Test
  fun theTargetStaysOutOfTheDictionary() {
    // Host-side only: the wire format, the lock, and what a payload can observe are all identical
    // whether a client binds through a wrapper or directly.
    assertFalse("dev.acme.ds" in dictionary.encode(), "the implementation target leaked into the dictionary")
    assertEquals(
      dictionary.components.single { it.name == "DirectChip" }.properties.keys,
      dictionary.components.single { it.name == "BoundChip" }.properties.keys,
      "the two components must be wire-identical",
    )
  }

  @Test
  fun aTargetThatIsNotAPlainQualifiedNameIsRefusedAtParse() {
    // A constant reference would parse as its *source text*, and the generator would emit a call
    // to a name that does not exist where it looked. Refusing at parse is the only place the
    // mistake is still cheap.
    assertFailsWith<IllegalArgumentException> {
      SurfaceParser().parse(
        """
        @Composable
        @Implementation(TARGET_CONSTANT)
        fun Broken(label: String, modifier: Modifier = Modifier) {}
        """,
      )
    }
  }
}

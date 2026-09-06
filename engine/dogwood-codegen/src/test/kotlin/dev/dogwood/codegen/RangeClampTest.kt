/*
 * Project Dogwood -- a range declared on the surface becomes a clamp in the generated binding.
 *
 * ADR-035 shipped hand-written clamps and said plainly what they did not cover: "a property added
 * later with an enforced range and no clamp reintroduces the vector for that one property. The
 * durable fix is a range declared on the surface so the generator emits the clamp -- that is not
 * built." This is that, and these are the tests that make it a rule rather than a habit.
 *
 * The failure being prevented is not cosmetic. Compose enforces some numeric ranges by throwing,
 * and the throw lands *inside composition*, so a payload delivered over the air without a store
 * review takes the screen down on every client that receives it at once.
 */
package dev.dogwood.codegen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RangeClampTest {

  private fun emitFor(surface: String): String {
    val components = SurfaceParser().parse(surface, "Range.kt")
    val dictionary = buildDictionary("test", segmentId = 1, version = 1, components = components)
    return emitHostBindings(
      packageName = "dev.dogwood.host",
      implementationPackage = "dev.dogwood.host",
      dictionary = dictionary,
      components = components,
    )
  }

  private val ranged = """
    package dev.dogwood.surface
    import androidx.compose.runtime.Composable
    annotation class Range(val min: Double, val max: Double = Double.MAX_VALUE)
    @Composable
    fun Gauge(
      @Range(min = 0.0, max = 5.0) rating: Float,
      @Range(min = 1.0) lines: Int,
      plain: Int = 3,
      modifier: Modifier = Modifier,
    ) {}
  """.trimIndent()

  @Test
  fun aDeclaredRangeEmitsAClampingReader() {
    val emitted = emitFor(ranged)
    assertTrue(
      "floatClamped" in emitted,
      "the float parameter's range did not reach the binding:\n$emitted",
    )
    assertTrue("intClamped" in emitted, "the int parameter's range did not reach the binding")
  }

  @Test
  fun theBoundsAreTheOnesTheSurfaceDeclared() {
    val emitted = emitFor(ranged)
    assertTrue("min = 0.0f, max = 5.0f" in emitted, "float bounds wrong:\n$emitted")
    assertTrue("min = 1, max = Int.MAX_VALUE" in emitted, "int bounds wrong:\n$emitted")
  }

  /**
   * An unbounded maximum must not be narrowed by cast.
   *
   * `@Range(min = 1.0)` reaches the emitter as `Double.MAX_VALUE`. Casting that to `Int` gives
   * `Int.MAX_VALUE` on the Java Virtual Machine but the intent is "no upper bound", and writing
   * the type's own constant says so in the generated code rather than leaving a reader to work out
   * whether 2147483647 was chosen or fallen into.
   */
  @Test
  fun anUnboundedMaximumEmitsTheTypeConstant() {
    assertTrue("max = Int.MAX_VALUE" in emitFor(ranged))
  }

  @Test
  fun aParameterWithoutARangeIsUnchanged() {
    val emitted = emitFor(ranged)
    // The plain parameter must still use the ordinary reader; a generator that clamped everything
    // would be silently altering values no one declared a bound for.
    assertTrue(
      Regex("""plain = node\.int\(""").containsMatchIn(emitted),
      "the unranged parameter lost its plain reader:\n$emitted",
    )
  }

  /**
   * A nullable parameter is left alone, and that is a decision rather than an oversight.
   *
   * Absence is the "use host default" sentinel. Clamping cannot improve on a value that is not
   * there, and emitting a clamped reader would mean inventing one.
   */
  @Test
  fun aNullableRangedParameterKeepsItsAbsenceReader() {
    val emitted = emitFor(
      """
      package dev.dogwood.surface
      import androidx.compose.runtime.Composable
      annotation class Range(val min: Double, val max: Double = Double.MAX_VALUE)
      @Composable
      fun Gauge(@Range(min = 0.0) rating: Float? = null, modifier: Modifier = Modifier) {}
      """.trimIndent(),
    )
    assertTrue("floatOrNull" in emitted, "absence stopped being readable:\n$emitted")
    assertFalse("floatClamped" in emitted, "a nullable parameter was clamped")
  }

  /** Positional arguments mean the same as named ones; a parser that dropped them drops a clamp. */
  @Test
  fun positionalRangeArgumentsAreRead() {
    val emitted = emitFor(
      """
      package dev.dogwood.surface
      import androidx.compose.runtime.Composable
      annotation class Range(val min: Double, val max: Double = Double.MAX_VALUE)
      @Composable
      fun Gauge(@Range(2.0, 7.0) rating: Float, modifier: Modifier = Modifier) {}
      """.trimIndent(),
    )
    assertTrue("min = 2.0f, max = 7.0f" in emitted, "positional bounds were not read:\n$emitted")
  }
}

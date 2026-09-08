/*
 * Project Dogwood -- a callback carrying an argument is an event, not lazy-layout machinery.
 *
 * The classifier used to reject any lambda containing `(Int)` as "indexed content", which made
 * `onChange: (Int) -> Unit` -- the most ordinary shape a stepper, slider or pager can have --
 * silently unbindable. It was found by the first surface written outside this repository
 * (`samples-standalone/umbra`), whose stepper had never been callable from a payload and whose
 * build was the first thing that tried. See `plans/adoption-audit.md` A2.
 *
 * The distinction the classifier actually needs is already made two branches earlier: an indexed
 * *content* lambda is `@Composable` and becomes a slot before `classifyLambda` runs.
 */
package dev.dogwood.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

class EventArgumentTest {

  private fun kinds(surface: String): Map<String, ParameterKind> =
    SurfaceParser().parse(surface).single().parameters.associate { it.name to it.kind }

  @Test
  fun aCallbackCarryingAnIntIsAnEvent() {
    val kinds = kinds(
      """
      @Composable
      fun Stepper(
        value: Int,
        modifier: Modifier = Modifier,
        onChange: (Int) -> Unit,
      ) {}
      """.trimIndent(),
    )
    assertEquals(ParameterKind.EVENT, kinds["onChange"], "kinds: $kinds")
  }

  @Test
  fun aCallbackCarryingSeveralArgumentsIsAnEventToo() {
    val kinds = kinds(
      """
      @Composable
      fun Slider(
        value: Float,
        modifier: Modifier = Modifier,
        onChangeFinished: (Float, Boolean) -> Unit,
      ) {}
      """.trimIndent(),
    )
    assertEquals(ParameterKind.EVENT, kinds["onChangeFinished"], "kinds: $kinds")
  }

  @Test
  fun anIndexedContentLambdaIsStillNotAnEvent() {
    // The control: the case the old rejection believed it was guarding. `@Composable` wins two
    // branches earlier, so this must classify as a slot -- never as an event.
    val kinds = kinds(
      """
      @Composable
      fun Repeater(
        count: Int,
        modifier: Modifier = Modifier,
        itemContent: @Composable (Int) -> Unit,
      ) {}
      """.trimIndent(),
    )
    assertEquals(ParameterKind.SLOT, kinds["itemContent"], "kinds: $kinds")
  }

  @Test
  fun aLambdaReturningAValueIsStillRefused() {
    // The other control: the neighbouring rule must survive the removal. A lambda the host would
    // await mid-frame cannot cross, arguments or not.
    val kinds = kinds(
      """
      @Composable
      fun Sorted(
        modifier: Modifier = Modifier,
        comparator: (Int) -> Int,
      ) {}
      """.trimIndent(),
    )
    assertEquals(ParameterKind.UNSUPPORTED, kinds["comparator"], "kinds: $kinds")
  }
}

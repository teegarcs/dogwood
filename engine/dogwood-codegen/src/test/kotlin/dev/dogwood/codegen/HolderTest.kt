/*
 * Project Dogwood -- a holder declared on the surface becomes plumbing on both sides.
 *
 * ADR-014 built `LazyListState` by hand, on both sides of the boundary, and stated the cost it was
 * accepting: roughly thirty holder types remain, "so this subsystem's per-holder cost recurs far
 * more often than the five examples suggest". Hand-writing the wire form thirty times is the part
 * that does not scale -- not the holders themselves, which each genuinely need a decision about
 * what a target means and what may be reported.
 *
 * So `@Holder` declares the shape and the generator emits the wire form. These tests pin the three
 * things that makes load-bearing: that the plumbing appears on both sides, that tags are still
 * appended rather than renumbered, and that an unregistered holder is **rejected** rather than
 * plumbed by inference -- which would emit properties no host reads, on a widget that renders
 * perfectly and does nothing.
 */
package dev.dogwood.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HolderTest {

  private val surface = """
    package dev.dogwood.surface
    import androidx.compose.runtime.Composable
    annotation class Holder
    @Composable
    fun Field(
      text: String,
      modifier: Modifier = Modifier,
      onValueChange: (String) -> Unit,
      @Holder focus: FocusRequester? = null,
    ) {}
  """.trimIndent()

  private fun parse(source: String, shapes: List<HolderShape> = SurfaceParser.DEFAULT_HOLDER_SHAPES) =
    SurfaceParser(shapes).parse(source, "Holder.kt")

  private fun dictionaryFor(components: List<ParsedComponent>) =
    buildDictionary("test", segmentId = 1, version = 1, components = components)

  @Test
  fun aHolderExpandsIntoThePropertiesItsShapeDeclares() {
    val entry = dictionaryFor(parse(surface)).components.single()
    // Appended, not interleaved: `text` keeps tag 1, and the holder's two properties take the
    // next free ones. A moved tag does not fail to render -- it renders the wrong thing.
    assertEquals(1, entry.properties["text"])
    assertEquals(2, entry.properties["focusRequested"])
    assertEquals(3, entry.properties["focusSequence"])
    assertEquals("Boolean", entry.propertyTypes["focusRequested"])
  }

  @Test
  fun theGuestReadsTheHoldersFieldsInTheComposableBody() {
    val components = parse(surface)
    val emitted = emitGuestStubs("dev.dogwood.compose", dictionaryFor(components), components)
    // Read in the body, not inside `update`. That is what subscribes the call site to the
    // holder's snapshot state; a read inside `update` happens after the composition has already
    // decided not to recompose, so a target declared between frames would never cross.
    val body = emitted.substringAfter("fun Field(").substringAfter(") {")
    assertTrue(
      body.substringBefore("ComposeNode").contains("val focusSequence = focus?.sequence"),
      "the holder's fields are not read in the composable body:\n$emitted",
    )
  }

  @Test
  fun aHolderThatWasNotPassedSendsNothing() {
    val components = parse(surface)
    val emitted = emitGuestStubs("dev.dogwood.compose", dictionaryFor(components), components)
    // Absence is the sentinel here exactly as it is for an optional value, and the reason is
    // sharper than symmetry. A stub that sent the holder's properties unconditionally would put
    // two new tags on every one of these widgets; a client one dictionary version behind meets
    // two property tags it has never seen on a widget that owns an affordance, and is defined to
    // withhold it. Every field on that client would go blank because a newer guest declined to
    // ask for focus.
    assertTrue(
      "set(focusRequested) { if (it != null)" in emitted,
      "the holder's properties are sent unconditionally:\n$emitted",
    )
  }

  @Test
  fun theMirrorsArgumentsAreNamedRatherThanPositional() {
    // A shape with five properties passed positionally is two `Int` arguments away from a silent
    // transposition: the mirror compiles, the container scrolls to the sequence number, and
    // nothing says so. `ScrollState` has five.
    val components = parse(reportingSurface, listOf(reporting))
    val emitted = emitHostBindings(
      packageName = "dev.dogwood.host",
      implementationPackage = "dev.dogwood.host",
      dictionary = dictionaryFor(components),
      components = components,
    )
    assertTrue("rememberProbeMirror(target = node.int(1, 0)" in emitted, emitted)
  }

  @Test
  fun theHostBindingBuildsTheMirrorAndHandsItOver() {
    val components = parse(surface)
    val emitted = emitHostBindings(
      packageName = "dev.dogwood.host",
      implementationPackage = "dev.dogwood.host",
      dictionary = dictionaryFor(components),
      components = components,
    )
    assertTrue(
      "focus = rememberFocusMirror(requested = node.boolean(2, false), sequence = node.int(3, 0))" in emitted,
      "the binding does not build the mirror the shape names:\n$emitted",
    )
  }

  @Test
  fun anUnregisteredHolderIsRejectedRatherThanPlumbed() {
    // The fail-closed half, and the one that matters most. A guessed shape emits properties
    // nothing on the host reads, which is not a failure anybody sees: the widget renders and the
    // holder is inert. The build stops instead, with the type's name in the message.
    val component = parse(
      """
        package dev.dogwood.surface
        import androidx.compose.runtime.Composable
        annotation class Holder
        @Composable
        fun Sheet(@Holder state: SheetState? = null) {}
      """.trimIndent(),
    ).single()
    assertFalse(component.isBindable)
    assertEquals(
      "no holder shape is registered for SheetState?",
      component.parameters.single().rejection,
    )
  }

  @Test
  fun anUnmarkedHolderIsStillRejectedAsLiveState() {
    // The marking is an assertion the generator checks, not a formality: without it the original
    // bindability rule applies unchanged.
    val component = parse(
      """
        package dev.dogwood.surface
        import androidx.compose.runtime.Composable
        @Composable
        fun Field(focus: FocusRequester? = null) {}
      """.trimIndent(),
    ).single()
    assertFalse(component.isBindable)
    assertTrue(component.parameters.single().rejection!!.startsWith("live-state holder"))
  }

  /**
   * A shape that reports, exercised through an injected table.
   *
   * `FocusRequester` deliberately reports nothing, so the shipping table cannot cover this half.
   * Registering a reporting shape in the shipping table to test it would be worse than not testing
   * it: the entry names a host mirror, and one that does not exist generates a binding that
   * compiles and fails at link time — or worse, one that is written to satisfy a test and never
   * used.
   */
  private val reporting = HolderShape(
    type = "ProbeState",
    mirror = "rememberProbeMirror",
    properties = listOf(HolderProperty("Target", "Int", "target", "0")),
    report = HolderReport(
      method = "report",
      arguments = listOf(HolderArgument("first", "Int", "0"), HolderArgument("moving", "Boolean", "false")),
    ),
  )

  private val reportingSurface = """
    package dev.dogwood.surface
    import androidx.compose.runtime.Composable
    annotation class Holder
    @Composable
    fun Probe(@Holder probe: ProbeState? = null) {}
  """.trimIndent()

  @Test
  fun aReportingHolderNumbersItsEventWithTheDeclaredOnes() {
    val components = parse(reportingSurface, listOf(reporting))
    val entry = dictionaryFor(components).components.single()
    assertEquals(1, entry.events["probeReport"])
    // The signature is synthesised so the lock's type check covers a report exactly as it covers
    // a declared callback. Changing what a report carries moves no tag, so without this it would
    // pass every check in the lock while the two ends disagreed about the argument list.
    assertEquals("(Int, Boolean) -> Unit", entry.eventTypes["probeReport"])
  }

  @Test
  fun aReportIsInstalledOnTheHolderAndClearedWithoutOne() {
    val components = parse(reportingSurface, listOf(reporting))
    val emitted = emitGuestStubs("dev.dogwood.compose", dictionaryFor(components), components)
    assertTrue("recording.lambdas.clear(id, EventTag(1))" in emitted, emitted)
    assertTrue("holder.report(" in emitted, emitted)
    assertTrue("first = args.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 0," in emitted, emitted)
    assertTrue("moving = args.getOrNull(1)?.jsonPrimitive?.booleanOrNull ?: false," in emitted, emitted)
  }

  @Test
  fun theHostSendsTheReportThroughTheSameEventChannel() {
    val components = parse(reportingSurface, listOf(reporting))
    val emitted = emitHostBindings(
      packageName = "dev.dogwood.host",
      implementationPackage = "dev.dogwood.host",
      dictionary = dictionaryFor(components),
      components = components,
    )
    assertTrue(
      "report = { a0, a1 -> events.send(node, EventTag(1), listOf(JsonPrimitive(a0), JsonPrimitive(a1))) }" in emitted,
      "the mirror is not given a way to report:\n$emitted",
    )
  }
}

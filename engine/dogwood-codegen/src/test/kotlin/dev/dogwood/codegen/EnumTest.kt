/*
 * Project Dogwood -- enumerations declared on the surface.
 *
 * Before these existed, `variant: ButtonVariant` was classified as an ordinary value and the
 * generator emitted `JsonPrimitive(it)` for it: code that did not compile, on both sides, in files
 * the author never wrote. A design system's most common non-primitive parameter shape was
 * unusable and the failure said nothing about the surface.
 *
 * What these pin, and why each line matters:
 *
 *   - an enumeration crosses as its entry NAME, on properties and on event arguments, because a
 *     name survives a reordered declaration and reads as itself in a transcript;
 *   - both ends get a copy of the enumeration -- unless the surface says the adopter already owns
 *     one, in which case the host decodes into theirs and nothing is emitted for it;
 *   - the entries are part of the contract: the lock refuses a removed entry and requires the
 *     version to move for an added one, exactly as it does for a component;
 *   - a type the surface did not declare is refused at the parse, with the list of what would have
 *     been accepted -- never silently emitted as something that will not compile.
 */
package dev.dogwood.codegen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SURFACE = """
package acme.surface
import androidx.compose.runtime.Composable

enum class Tone { Neutral, Positive, Negative }

@Implementation("com.acme.ds.Size")
enum class Size { Small, Large }

@Composable
fun Tag(
  label: String,
  tone: Tone = Tone.Neutral,
  size: Size,
  accent: Tone? = null,
  modifier: Modifier = Modifier,
  onToneChange: (Tone) -> Unit = {},
) {}
"""

class EnumTest {

  private val surface = SurfaceParser().parseSurface(SURFACE)
  private val dictionary = buildDictionary("acme", 7, 1, surface.components, enums = surface.enums)
  private val guest = emitGuestStubs("dev.acme.guest", dictionary, surface.components, surface.enums)
  private val host = emitHostBindings("dev.acme.host", "dev.acme.impl", dictionary, surface.components, surface.enums)

  @Test
  fun theSurfaceDeclaresItsEnumerationsInOrder() {
    assertEquals(listOf("Tone", "Size"), surface.enums.map { it.name })
    assertEquals(listOf("Neutral", "Positive", "Negative"), surface.enums.first().entries)
    assertEquals("com.acme.ds.Size", surface.enums[1].implementation)
  }

  @Test
  fun anEnumParameterIsAValueThatKnowsItsEnumeration() {
    val tone = surface.components.single().parameters.first { it.name == "tone" }
    assertEquals(ParameterKind.VALUE, tone.kind)
    assertEquals("Tone", tone.enumType?.name)
    assertTrue(surface.components.single().isBindable)
  }

  @Test
  fun theGuestCarriesTheEnumerationAndSendsTheName() {
    assertTrue("enum class Tone { Neutral, Positive, Negative }" in guest, guest)
    assertTrue("enum class Size { Small, Large }" in guest, guest)
    assertTrue("tone: Tone = Tone.Neutral" in guest, guest)
    // Its name, never its ordinal.
    assertTrue("set(tone) { recording.recorder.property(id, PropertyTag(2), JsonPrimitive(it.name)) }" in guest, guest)
    // Optional: absence is the sentinel, as for every other value.
    assertTrue("set(accent) { if (it != null) recording.recorder.property(id, PropertyTag(4), JsonPrimitive(it.name)) }" in guest, guest)
  }

  @Test
  fun theHostDecodesByNameIntoItsOwnCopyOrTheAdopters() {
    // A copy for the one the adopter did not claim...
    assertTrue("enum class Tone { Neutral, Positive, Negative }" in host, host)
    // ...and nothing for the one they did: their type is named at the reader instead.
    assertFalse("enum class Size" in host, host)
    assertTrue("tone = node.enum(2, Tone.entries, Tone.Neutral, what = \"Tag.tone\")" in host, host)
    assertTrue("size = node.enum(3, com.acme.ds.Size.entries, com.acme.ds.Size.entries.first(), what = \"Tag.size\")" in host, host)
    assertTrue("accent = node.enumOrNull(4, Tone.entries, what = \"Tag.accent\")" in host, host)
  }

  @Test
  fun anEnumEventArgumentCrossesAsItsName() {
    assertTrue("onToneChange = { a0 -> events.send(node, EventTag(1), listOf(JsonPrimitive(a0.name))) }" in host, host)
    // And the guest resolves it back, degrading to the first entry for a name it has never heard of
    // rather than throwing on a tap from a newer host.
    assertTrue(
      "handler(Tone.entries.firstOrNull { e -> e.name == args[0].jsonPrimitive.content } ?: Tone.entries.first())" in guest,
      guest,
    )
  }

  @Test
  fun theDictionaryRecordsTheEntriesSoTheLockCanSeeThem() {
    assertEquals(mapOf("Tone" to listOf("Neutral", "Positive", "Negative"), "Size" to listOf("Small", "Large")), dictionary.enums)
    // The property's declared type is the enumeration's name, so a retype is caught as before.
    assertEquals("Tone", dictionary.components.single().propertyTypes["tone"])
  }

  @Test
  fun theLockRefusesARemovedEntryAndRequiresAVersionForAnAddedOne() {
    val lock = File.createTempFile("dogwood-lock", ".json").also { it.delete() }
    checkAgainstLock(dictionary, lock)

    val narrowed = dictionary.copy(enums = mapOf("Tone" to listOf("Neutral", "Positive"), "Size" to listOf("Small", "Large")))
    val removed = checkAgainstLock(narrowed, lock)
    assertTrue(removed is LockResult.Violated, "expected a violation, got $removed")
    assertTrue((removed as LockResult.Violated).problems.any { "Tone.Negative was removed" in it }, removed.problems.toString())

    val widened = dictionary.copy(enums = mapOf("Tone" to listOf("Neutral", "Positive", "Negative", "Warning"), "Size" to listOf("Small", "Large")))
    val unversioned = checkAgainstLock(widened, lock)
    assertTrue(unversioned is LockResult.Violated, "expected a violation, got $unversioned")
    assertTrue((unversioned as LockResult.Violated).problems.any { "Tone.Warning" in it && "version" in it }, unversioned.problems.toString())

    val versioned = checkAgainstLock(widened.copy(version = 2), lock)
    assertTrue(versioned is LockResult.Updated, "expected the lock to advance, got $versioned")
  }

  @Test
  fun aTypeTheSurfaceDidNotDeclareIsRefusedWithTheListOfWhatWouldHaveBeenAccepted() {
    val parsed = SurfaceParser().parse(
      """
      @Composable fun Undeclared(variant: ButtonVariant, padding: Dp = 8.dp, options: List<String>) {}
      """,
    ).single()
    assertFalse(parsed.isBindable)
    val reasons = parsed.parameters.filter { it.kind == ParameterKind.UNSUPPORTED }.associate { it.name to it.rejection!! }
    assertEquals(setOf("variant", "padding", "options"), reasons.keys)
    // Names the fix: declare it on the surface.
    assertTrue("enum class ButtonVariant { ... }" in reasons.getValue("variant"), reasons.toString())
    // And names what is accepted, so the author is not left to guess.
    assertTrue("TextValue, Color or Shape" in reasons.getValue("padding"), reasons.toString())
    assertTrue("String, Int, Long, Float, Double, Boolean" in reasons.getValue("options"), reasons.toString())
  }

  @Test
  fun anEnumerationDeclaredInAnotherFileStillClassifies() {
    // Two passes over every file, so declaration order across files does not decide anything.
    val files = listOf(
      File.createTempFile("b-components", ".kt").apply { writeText("@Composable fun Pill(tone: Tone) {}") },
      File.createTempFile("a-enums", ".kt").apply { writeText("enum class Tone { Neutral }") },
    )
    val parsed = SurfaceParser().parseSurfaceFiles(files)
    assertTrue(parsed.components.single().isBindable, parsed.components.toString())
  }

  @Test
  fun longAndDoubleReadersAreEmittedRatherThanARawJsonElement() {
    // Both were listed as serializable and neither had a reader: the binding received a
    // `JsonElement` where the implementation declared a `Long`, which does not compile.
    val components = SurfaceParser().parse("@Composable fun Meter(count: Long, ratio: Double = 0.5, since: Long? = null) {}")
    val d = buildDictionary("acme", 7, 1, components)
    val bindings = emitHostBindings("dev.acme.host", "dev.acme.impl", d, components)
    assertTrue("count = node.long(1, 0L)" in bindings, bindings)
    assertTrue("ratio = node.double(2, 0.5)" in bindings, bindings)
    assertTrue("since = node.longOrNull(3)" in bindings, bindings)
  }
}

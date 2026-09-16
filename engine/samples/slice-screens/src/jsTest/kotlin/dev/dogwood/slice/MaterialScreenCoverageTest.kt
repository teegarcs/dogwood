/*
 * Project Dogwood -- what the Material catalogue actually sends.
 *
 * The coverage report (`tools/generator-v2/coverage.md`) counts what the generator *bound*. This
 * counts what a payload *uses*, which is a different number and was, before `MaterialScreen.kt`,
 * six. A tier whose bindings compile and are never composed is the projected-coverage mistake in a
 * new place: plans/generator-v2.md was written because every figure this project quoted was a
 * ceiling nobody had reached, and "eighty components are bound" would be the same sentence again
 * if the samples used five of them.
 *
 * So: compose every section, read the widget tags that reach the wire, keep the ones in segment
 * 255, and fail if the screen has stopped exercising the tier. The threshold is deliberately a
 * floor rather than an equality -- a library upgrade that binds more components should not break
 * this -- and the failure message names what is bound and unused, so the next person knows what
 * the screen owes.
 */
package dev.dogwood.slice

import dev.dogwood.compose.DogwoodComposition
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.DogwoodHost
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.WidgetTag
import dev.dogwood.protocol.decodePositional
import kotlin.test.Test
import kotlin.test.assertTrue

/** The segment the generated Material 3 tier owns (ADR-072, D-B). */
private const val MATERIAL3 = 255

/**
 * The floor. Raised when the screen grows, never lowered to make a run green: lowering it is how
 * a coverage number becomes a decoration again.
 *
 * The catalogue composed 79 of the 79 bound Material 3 components when this was written, against
 * plans/material3-proof.md's target of 60. The floor sits a little below that so a library upgrade
 * that binds something new is not a broken build on the day it lands -- the coverage report is
 * where a widening surface is noticed, and the upgrade procedure says to read it.
 */
private const val AT_LEAST = 75

private class CountingHost : DogwoodHost {
  val batches = mutableListOf<String>()
  override fun sendChanges(positionalBatch: String) { batches += positionalBatch }
  override fun requestFrame() = Unit
  override fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag) = Unit
  override fun onUnknownEventNode(id: Id, tag: EventTag) = Unit
  override fun handleUncaughtException(exception: Throwable): Unit = throw exception
  override fun close() = Unit
}

/**
 * Where the captured batches go, if anywhere.
 *
 * The Gradle test task sets this. When it is unset -- somebody running one test from an editor --
 * nothing is written and nothing fails, because the capture is a side effect of this suite rather
 * than its purpose.
 */
private fun captureDirectory(): String? =
  js("(typeof process !== 'undefined' && process.env && process.env.DOGWOOD_WIRE_OUT) || null") as String?

/**
 * The batches this screen sent, written where the Java Virtual Machine side can replay them.
 *
 * `MaterialReplayTest` in `slice-desktop` renders exactly these through the real host bindings.
 * Passing them as a file rather than regenerating them there is the whole point: the wire a real
 * guest produced is the input, so nothing between the payload and the screen is a stand-in.
 */
private fun capture(section: String, batches: List<String>) {
  val directory = captureDirectory() ?: return
  val fs = js("require('fs')")
  fs.mkdirSync(directory, js("({ recursive: true })"))
  fs.writeFileSync("$directory/$section.wire", batches.joinToString("\n"))
}

private fun batchesOf(section: String, openEverything: Boolean): List<String> {
  val host = CountingHost()
  val composition = DogwoodComposition(
    host = host,
    initialConfiguration = HostEnvironment(),
    segmentVersions = emptyMap(),
    restoredState = null,
    content = { MaterialSection(section, openEverything = openEverything) },
  )
  composition.frame(0)
  composition.dispose()
  return host.batches
}

private fun tagsOf(section: String): Set<Int> {
  // Two captures per section, because they answer two questions. The *open* one composes the
  // dialogs, the sheet and the menu at once and is what the coverage count is taken from: a
  // component nobody opened is still a component this payload can send. The *resting* one is the
  // screen a person actually arrives at, and is what `MaterialReplayTest` renders -- a modal sheet
  // covering the section would make "is it displayed" a question about a scrim.
  capture(section, batchesOf(section, openEverything = false))
  val open = batchesOf(section, openEverything = true)
  capture("$section.open", open)
  return open
    .flatMap { decodePositional(it).g }
    .filterIsInstance<Create>()
    // A widget tag packs the segment into the top byte and the local tag into the rest, which is
    // how a host routes to a binding without a string compare (ADR-003).
    .map { it.w.value }
    .filter { (it ushr 24) == MATERIAL3 }
    .toSet()
}

class MaterialScreenCoverageTest {

  @Test
  fun theCatalogueUsesMostOfWhatTheGeneratorBound() {
    val used = MATERIAL_SECTIONS.flatMap { (id, _) -> tagsOf(id) }.toSet()
    assertTrue(
      used.size >= AT_LEAST,
      "the Material catalogue composes ${used.size} distinct Material 3 components and the floor " +
        "is $AT_LEAST. Either a section stopped composing what it used to, or the floor was " +
        "raised without the screen growing. The local tags it did compose, which " +
        "`engine/dogwood-material3/androidx.material3.lock.json` maps back to names: " +
        used.map { it and 0xFFFFFF }.sorted(),
    )
  }

  /**
   * Every section contributes, which is the part a single total would hide.
   *
   * A section that silently composed nothing -- a `when` branch that stopped matching, a chip whose
   * identifier was renamed in one place -- would leave the total almost unchanged and take a whole
   * family of components out of every device drill with it.
   */
  @Test
  fun everySectionComposesSomethingFromTheTier() {
    val empty = MATERIAL_SECTIONS.filter { (id, _) -> tagsOf(id).isEmpty() }.map { it.first }
    assertTrue(
      empty.isEmpty(),
      "these sections composed no Material 3 component at all: $empty. A section that sends " +
        "nothing is a section the device drills cannot grade.",
    )
  }

  /**
   * The chips are the catalogue's own navigation, so they are asserted separately from the
   * sections they reveal: if `FilterChip` stops reaching the wire, every other claim on this
   * screen becomes unreachable on a device rather than merely wrong.
   */
  @Test
  fun theSectionPickerIsItselfBuiltFromTheTier() {
    val host = CountingHost()
    val composition = DogwoodComposition(
      host = host,
      initialConfiguration = HostEnvironment(),
      segmentVersions = emptyMap(),
      restoredState = null,
      content = { MaterialScreen() },
    )
    composition.frame(0)
    val tags = host.batches
      .flatMap { decodePositional(it).g }
      .filterIsInstance<Create>()
      .map { it.w.value }
      .filter { (it ushr 24) == MATERIAL3 }
    composition.dispose()
    assertTrue(
      tags.size >= MATERIAL_SECTIONS.size,
      "the screen sent ${tags.size} Material 3 widgets, fewer than the ${MATERIAL_SECTIONS.size} " +
        "chips the picker needs",
    )
  }
}

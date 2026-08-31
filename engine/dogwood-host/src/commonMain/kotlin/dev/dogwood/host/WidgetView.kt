/*
 * Project Dogwood -- what a binding is allowed to see.
 *
 * The bindings read the host tree through this interface, and nothing else. That exists so the
 * *strategy* behind the tree can be swapped without touching a single binding, which is what
 * roadmap.md Phase 1 step 7 asks for: decide the host rendering strategy by measurement, with
 * the snapshot mirror on one side and an imperative applier over retained nodes on the other.
 *
 * A comparison you cannot run is not a decision, and a comparison that requires rewriting the
 * dictionary is one nobody runs twice.
 */
package dev.dogwood.host

import androidx.compose.runtime.staticCompositionLocalOf
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.ModifierElem
import dev.dogwood.protocol.WidgetTag
import kotlinx.serialization.json.JsonElement

/** One node, as a binding sees it. Absence is always the "use host default" sentinel. */
interface WidgetView {
  val id: Id
  val tag: WidgetTag
  val modifiers: List<ModifierElem>

  fun property(tag: Int): JsonElement?

  fun children(slot: Int): List<WidgetView>
}

fun WidgetView.string(tag: Int, default: String = ""): String =
  (property(tag) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: default

fun WidgetView.int(tag: Int, default: Int): Int =
  (property(tag) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: default

fun WidgetView.float(tag: Int, default: Float): Float =
  (property(tag) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull() ?: default

fun WidgetView.boolean(tag: Int, default: Boolean): Boolean =
  (property(tag) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
    ?: default

fun WidgetView.has(tag: Int): Boolean = property(tag) != null

/**
 * Counts how many bindings Compose re-executed.
 *
 * This is the number that actually separates the two strategies: fine-grained snapshot state
 * buys small recompositions at the cost of a state object per property, and coarse invalidation
 * buys cheap state at the cost of recomposing everything. Wall-clock alone cannot tell those
 * apart on a noisy host; the count can.
 */
val LocalRenderCounter = staticCompositionLocalOf<RenderCounter?> { null }

/** A plain counter deliberately outside the snapshot system, so counting cannot itself invalidate. */
class RenderCounter {
  var count: Int = 0
    private set

  fun record() {
    count++
  }

  fun reset() {
    count = 0
  }
}

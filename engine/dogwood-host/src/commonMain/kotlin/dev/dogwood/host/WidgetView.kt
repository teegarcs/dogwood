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

/*
 * Absence-reading accessors.
 *
 * A generated binding for an optional parameter must be able to tell "the guest sent nothing" from
 * "the guest sent a value that happens to look empty". Absence is the host-default sentinel, and
 * collapsing it into a default here would take the decision away from the implementation that is
 * supposed to make it.
 */

/**
 * `JsonNull` is a `JsonPrimitive` whose `content` is the four-character string "null", so a
 * reader that forgets to exclude it renders the word on screen. It did, in the price rows.
 */
private fun WidgetView.primitive(tag: Int): kotlinx.serialization.json.JsonPrimitive? =
  (property(tag) as? kotlinx.serialization.json.JsonPrimitive)
    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }

fun WidgetView.stringOrNull(tag: Int): String? = primitive(tag)?.content

fun WidgetView.intOrNull(tag: Int): Int? = primitive(tag)?.content?.toIntOrNull()

fun WidgetView.floatOrNull(tag: Int): Float? = primitive(tag)?.content?.toFloatOrNull()

fun WidgetView.booleanOrNull(tag: Int): Boolean? = primitive(tag)?.content?.toBooleanStrictOrNull()

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

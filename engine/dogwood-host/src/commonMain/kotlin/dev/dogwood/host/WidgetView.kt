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

  /**
   * Every property tag this node actually carries.
   *
   * Needed because "what did the guest send that I do not understand?" is a question no
   * per-property reader can answer: a reader is asked for a tag it already knows. Skew from a
   * newer payload arrives as tags nobody asks about, so it is invisible unless something looks at
   * the whole set. See [unknownProperties].
   */
  fun propertyTags(): Set<Int>

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
 * Property tags this node carries that [known] does not name.
 *
 * A payload built against a newer dictionary sends properties this client has never heard of. For
 * most of them, ignoring the value costs appearance and nothing else. For one that governs
 * affordance -- whether a control is enabled, checked, read-only -- ignoring it renders a control
 * that lies about what it will do, and the client cannot tell the two cases apart, because the tag
 * comes from a dictionary it has never seen.
 *
 * That asymmetry is why generated bindings consult this only for widgets that own an affordance;
 * see section 6 of the technical specification and
 * `adrs/layer-5/ADR-031-safety-relevant-parameters.md`.
 */
fun WidgetView.unknownProperties(known: Set<Int>): Set<Int> =
  propertyTags().filterTo(mutableSetOf()) { it !in known }

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

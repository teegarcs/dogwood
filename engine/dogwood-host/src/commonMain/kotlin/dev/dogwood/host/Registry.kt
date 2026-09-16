/*
 * Project Dogwood -- how a product adds its own components.
 *
 * `specs/layer-5-host.md` names this as item (c) of bespoke subsystem 9 and says why it matters in
 * one sentence: **"without (c) a guest can emit only raw Material 3, which no product team ships."**
 * Redwood's entire model was application-defined schemas, and deleting the schema must not delete
 * the escape hatch.
 *
 * Until now `RenderNode` called `bindDogwoodDesignSystem` by name. That is one segment, decided at
 * compile time, in this repository. A product has its own design system -- its own button, its own
 * card, its own video player -- and could not add one at all.
 *
 * So dispatch goes through a registry. A product runs the generator over its own surface, gets its
 * own segment, and registers the binding the generator emitted. Nothing else changes: the wire
 * format, the skew rules, the affordance guard and the dictionary lock all work on a product
 * segment exactly as they work on Dogwood's own, because they are the same code.
 *
 * **What the registry is not.** It is not a plug-in system and it is not dynamic. Registration
 * happens once, in host code, at application start; a segment cannot arrive over the air, because a
 * binding is native code and the whole architecture rests on native code shipping through a store
 * and payloads not being able to.
 */
package dev.dogwood.host

import androidx.compose.runtime.Composable
import dev.dogwood.protocol.Segments
import dev.dogwood.protocol.WidgetTag
import dev.dogwood.protocol.widgetTag

/**
 * One dictionary segment this client can render.
 *
 * Implemented by generated code -- `dogwood-codegen` emits an object for every segment it produces
 * -- so a product implements this by running the generator, not by writing it.
 */
interface DogwoodSegmentBinding {

  /** The segment's name, as the guest sees it in `LocalSegmentVersions`. */
  val segmentName: String

  /** What this client implements. A guest branches on it to decide what it may use. */
  val segmentVersion: Int

  /** Every widget tag this binding answers to. Used for the skew rules, never for dispatch. */
  val tags: Set<Int>

  /** What each of those tags is called, for diagnostics and for skew telemetry. */
  val names: Map<Int, String>

  /** Renders one node, or returns false if the tag is not this segment's. */
  @Composable
  fun bind(node: WidgetView, scope: LayoutScope, events: EventSink): Boolean
}

/**
 * The segments this client renders.
 *
 * Register at application start, before the first experience is mounted. There is deliberately no
 * unregister: a binding that disappeared while a tree referencing it was on screen would turn
 * rendered widgets into placeholders, which is a worse failure than any it could fix.
 */
object DogwoodRegistry {

  private val registered = mutableListOf<DogwoodSegmentBinding>()

  /** In registration order. Dogwood's own design system is first, and is registered for you. */
  val bindings: List<DogwoodSegmentBinding> get() = registered

  /**
   * Adds a segment.
   *
   * Refuses two things loudly, because both fail *silently* otherwise and both render the wrong
   * widget rather than no widget — the failure the dictionary lock exists to prevent, arriving
   * from a direction the lock cannot see. A lock is per surface; two surfaces that each pass their
   * own lock can still collide with each other, and only this can notice.
   */
  fun register(binding: DogwoodSegmentBinding) {
    val clash = registered.firstOrNull { it.segmentName == binding.segmentName }
    require(clash == null) {
      "segment '${binding.segmentName}' is already registered; a segment is registered once"
    }
    val overlap = registered.flatMap { existing ->
      binding.tags.filter { it in existing.tags }.map { existing to it }
    }
    require(overlap.isEmpty()) {
      val (existing, tag) = overlap.first()
      "segment '${binding.segmentName}' claims tag $tag, which '${existing.segmentName}' already " +
        "binds. Two segments sharing a tag do not fail to render -- whichever registered first " +
        "renders the other's widget. Give this surface its own --segment-id; " +
        "${Segments.LAYOUT} and ${Segments.DESIGN_SYSTEM} are Dogwood's."
    }
    registered += binding
  }

  /** True when any registered segment binds this tag. */
  internal fun knows(tag: WidgetTag): Boolean = registered.any { tag.value in it.tags }

  /** A registered segment's name for this tag, or null. */
  internal fun name(tag: WidgetTag): String? =
    registered.firstNotNullOfOrNull { it.names[tag.value] }

  /** Every registered segment's advertised version, by name. */
  internal fun versions(): Map<String, Int> =
    registered.associate { it.segmentName to it.segmentVersion }

  /** For tests. Restores the registry to just Dogwood's own segment. */
  internal fun resetForTest() {
    registered.clear()
    registerBuiltIns()
  }

  private fun registerBuiltIns() {
    registered += DogwoodDesignSystemBinding
  }

  init {
    registerBuiltIns()
  }
}

/**
 * The first segment identifier a product may use.
 *
 * 0 is the layout tier and 1 is Dogwood's design system. A product picks 2 or above and keeps it
 * forever: a segment identifier is half of every widget tag it will ever ship, so changing one is
 * indistinguishable, on a client one version behind, from every component being replaced at once.
 */
const val FIRST_PRODUCT_SEGMENT: Int = 2

/**
 * The last segment identifier a product may use.
 *
 * Above it are Dogwood's generated library tiers, allocated downward from 255 so the two
 * directions cannot meet (`Segments.MATERIAL3` and its neighbours). A product that picked 255
 * would collide with the Material 3 tier on every client that registers it -- and the collision
 * does not fail to render, it renders the wrong widget, which is why this is refused at the call
 * site rather than discovered on a screen.
 */
const val LAST_PRODUCT_SEGMENT: Int = 200

/** A tag in a product's own segment. Sugar for [widgetTag], so a product need not import both. */
fun productTag(segmentId: Int, localTag: Int): WidgetTag {
  require(segmentId >= FIRST_PRODUCT_SEGMENT) {
    "segment $segmentId is Dogwood's; a product segment starts at $FIRST_PRODUCT_SEGMENT"
  }
  require(segmentId <= LAST_PRODUCT_SEGMENT) {
    "segment $segmentId is reserved for Dogwood's generated library tiers; a product segment " +
      "ends at $LAST_PRODUCT_SEGMENT"
  }
  return widgetTag(segmentId, localTag)
}

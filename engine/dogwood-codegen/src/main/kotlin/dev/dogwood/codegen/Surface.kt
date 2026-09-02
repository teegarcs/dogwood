/*
 * Project Dogwood -- the parsed model of a component surface.
 *
 * One parsed model, four artifacts. Everything the generator emits -- guest stubs, host bindings,
 * the dictionary, and (later) guest-side value-type stand-ins -- comes from this, so the two ends
 * of the boundary cannot drift: they are not two implementations of one schema, they are two
 * renderings of one parse.
 */
package dev.dogwood.codegen

import kotlinx.serialization.Serializable

/** How a parameter crosses the boundary, decided by the bindability rule. */
enum class ParameterKind {
  /** An ordinary serializable value: a `PropertySet`. */
  VALUE,

  /** The modifier chain: a `ModifierSet`. Exactly one per component, and never numbered. */
  MODIFIER,

  /** A composition-time content slot: a `ChildrenTag`. */
  SLOT,

  /** A discrete event: an `EventTag`. Arguments are serializable and positional. */
  EVENT,

  /** A value the host must construct from a recipe. */
  /** A value the host resolves at draw time: `TextValue`, `Color`, `Shape`. */
  HOST_RESOLVED,

  /** Fails the bindability rule. Reported with a reason; never emitted. */
  UNSUPPORTED,
}

@Serializable
data class ParsedParameter(
  val name: String,
  val type: String,
  val kind: ParameterKind,
  val hasDefault: Boolean,
  /** The default's source text, when it has one. Null defaults and `@Composable` ones differ. */
  val defaultExpression: String? = null,
  /** Populated when [kind] is `UNSUPPORTED`. The audit's reason, in the generator's words. */
  val rejection: String? = null,
  /**
   * Whether this parameter's absence changes what the user is *allowed to do*, not how it looks.
   *
   * `enabled`, `checked`, `readOnly`, `selected` and their relatives. Marked with `@Affordance` on
   * the surface. Section 6 of the technical specification requires the marking, and the reason is
   * narrow: every other kind of skew degrades cosmetically, while this kind degrades into a
   * control that lies about what it will do.
   */
  val affordance: Boolean = false,
) {
  /**
   * Whether the host must resolve this parameter's default itself.
   *
   * Absence on the wire is the "use host default" sentinel, and it is the only correct encoding
   * for a default the guest cannot evaluate -- most obviously a `@Composable` one, which has no
   * value until it is composed, on the host, in context.
   */
  val defaultIsHostResolved: Boolean
    get() = hasDefault && (defaultExpression == null || defaultExpression.contains("LocalDogwood") ||
      defaultExpression.contains("MaterialTheme") || defaultExpression.contains(".current"))
}

@Serializable
data class ParsedComponent(
  val name: String,
  val parameters: List<ParsedParameter>,
) {
  val isBindable: Boolean get() = parameters.none { it.kind == ParameterKind.UNSUPPORTED }
  val values: List<ParsedParameter> get() = parameters.filter { it.kind == ParameterKind.VALUE || it.kind == ParameterKind.HOST_RESOLVED }
  val slots: List<ParsedParameter> get() = parameters.filter { it.kind == ParameterKind.SLOT }
  val events: List<ParsedParameter> get() = parameters.filter { it.kind == ParameterKind.EVENT }
  val modifier: ParsedParameter? get() = parameters.firstOrNull { it.kind == ParameterKind.MODIFIER }

  /** Value parameters marked `@Affordance`. Their presence changes how this widget degrades. */
  val affordances: List<ParsedParameter> get() = values.filter { it.affordance }
}

/**
 * One dictionary segment: a named, versioned group of components with stable local tags.
 *
 * Tags are assigned by declaration order and never renumbered, because the protocol's evolution
 * rule is additive. Property, slot and event tags are numbered per component within their own
 * kind, which is why a component can gain an optional parameter without disturbing any other.
 */
@Serializable
data class Dictionary(
  val segmentName: String,
  val segmentId: Int,
  val version: Int,
  val components: List<DictionaryEntry>,
  /**
   * Local tags in this segment that the generator does not own.
   *
   * A segment is not necessarily all generated. Dogwood's design-system segment also carries two
   * hand-written lazy containers, because the generator does not model lazy layouts yet
   * ([ADR-011](../../../../../../adrs/layer-5/ADR-011-generator-emits-the-bridge.md)). Without
   * this, the generator allocates by position and will eventually hand a new component a tag a
   * hand-written binding already answers to -- and the collision does not fail to render, it
   * renders **the wrong widget**, which is the failure this whole locking mechanism exists to
   * prevent. It happened: adding `Icon` as the tenth component collided with `VerticalList`.
   */
  val reservedLocalTags: List<Int> = emptyList(),
)

@Serializable
data class DictionaryEntry(
  val name: String,
  val localTag: Int,
  val properties: Map<String, Int>,
  /**
   * Each property's declared type.
   *
   * Recorded because a tag is not the whole contract. Widening `price: String` to
   * `price: TextValue` moves no tag and adds no component, so the lock had nothing to say about
   * it -- yet a client built before the change reads a recipe with a primitive reader and quietly
   * renders its default. A type change is a compatibility event and the lock now treats it as one.
   */
  val propertyTypes: Map<String, String> = emptyMap(),
  /**
   * Property names whose absence changes safety or affordance rather than appearance.
   *
   * Required by section 6 of the technical specification. Recorded per widget rather than only
   * per property because of what a client can actually observe: a client that meets a property tag
   * it does not know cannot tell whether that property was cosmetic or was the one that turns the
   * control off -- the tag is from a dictionary it has never seen. So the decision has to be made
   * at the level of the widget, and this set is what makes it: a widget that owns any affordance
   * refuses to render at all rather than render an affordance it might have missed.
   */
  val safetyRelevant: Set<String> = emptySet(),
  val slots: Map<String, Int>,
  val events: Map<String, Int>,
  /**
   * Each event's parameter types, in declaration order.
   *
   * Recorded for the same reason [propertyTypes] is, and the gap it closes is identical. An event
   * tag is not the whole contract: changing `onClick: () -> Unit` to `(Boolean) -> Unit` moves no
   * tag, adds no component and retypes no property, so every other check in the lock stays silent
   * -- while the arguments now crossing the wire have a shape the other side does not expect. In
   * one direction the argument is ignored; in the other the generated reader indexes past the end
   * of the list and throws on a tap.
   */
  val eventTypes: Map<String, String> = emptyMap(),
  /** Parameters the rule rejected, kept so the dictionary records what it declined to bind. */
  val rejected: Map<String, String> = emptyMap(),
)

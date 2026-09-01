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
  val slots: Map<String, Int>,
  val events: Map<String, Int>,
  /** Parameters the rule rejected, kept so the dictionary records what it declined to bind. */
  val rejected: Map<String, String> = emptyMap(),
)

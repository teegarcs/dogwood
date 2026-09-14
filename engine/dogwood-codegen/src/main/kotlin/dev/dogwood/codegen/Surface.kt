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

  /**
   * A live-state holder the host owns and the guest mirrors.
   *
   * Not one wire entity but several: a holder expands into the properties its shape declares, and
   * optionally an event carrying the host's report back. The expansion is [HolderShape]'s, so the
   * generator plumbs a holder without knowing what the holder *is* -- the same line
   * [ADR-011](../../../../../../adrs/layer-5/ADR-011-generator-emits-the-bridge.md) already draws
   * between the bridge and the widget.
   */
  HOLDER,

  /** Fails the bindability rule. Reported with a reason; never emitted. */
  UNSUPPORTED,
}

/**
 * One wire property a holder expands into.
 *
 * @param suffix appended to the parameter's own name to make the wire name, so two holders on one
 *   widget cannot collide and the name says which holder it belongs to.
 * @param type the serializable type crossing the boundary.
 * @param field the property to read on the guest-side holder object.
 * @param absent what to send when the guest passed no holder at all. A holder is optional by
 *   construction -- most call sites do not want one -- and this is the value that means "nobody is
 *   driving this".
 */
@Serializable
data class HolderProperty(
  val suffix: String,
  val type: String,
  val field: String,
  val absent: String,
)

/** One argument of a holder's report event. */
@Serializable
data class HolderArgument(val name: String, val type: String, val absent: String)

/**
 * The host's report back into a holder: an event, and the holder method it feeds.
 *
 * Null for a holder that only takes targets. `FocusRequester` is the plain case -- focus is
 * something the guest *asks for*, and there is nothing to read back that the guest could act on
 * without asking for per-frame state.
 */
@Serializable
data class HolderReport(val method: String, val arguments: List<HolderArgument>)

/**
 * How one holder type crosses the boundary.
 *
 * The generator owns the plumbing and this table owns the shape, so adding the next holder is a
 * table entry plus a host-side mirror rather than a second hand-written binding on both sides of
 * the wire. That split is the point: the corrected coverage measurement counts roughly thirty
 * holder types, so anything paid per holder is paid thirty times.
 *
 * @param mirror the host-side factory the generated binding calls. It is hand-written, because
 *   what a holder *does* on the host -- move a list, take focus, open a sheet -- is exactly the
 *   part that requires taste.
 */
@Serializable
data class HolderShape(
  val type: String,
  val mirror: String,
  val properties: List<HolderProperty>,
  val report: HolderReport? = null,
)

/** A property as it appears on the wire, after holders have been expanded. */
data class WireProperty(
  val name: String,
  val type: String,
  val parameter: ParsedParameter,
  /** Null for an ordinary value; the holder field this one carries otherwise. */
  val holder: HolderProperty? = null,
)

/**
 * An event as it appears on the wire.
 *
 * Declared callbacks and holder reports share one numbering, because they share one channel: both
 * are the host speaking to the guest through an [EventTag], and a holder's report is not a
 * different kind of thing merely because the surface did not spell it out as a lambda.
 */
data class WireEvent(
  val name: String,
  val type: String,
  val parameter: ParsedParameter,
  /** Null for a declared callback; the report this one delivers otherwise. */
  val report: HolderReport? = null,
)

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
  /**
   * The range this parameter may take, when the surface declared one with `@Range`.
   *
   * Held as doubles whatever the parameter's own type, because that is how the surface writes it;
   * the emitter narrows at the point where it knows the type, so the literal is parsed once.
   *
   * Its purpose is narrow and worth restating here: Compose enforces some numeric ranges by
   * throwing, inside composition, so a payload delivered over the air can take a screen down on
   * every client at once ([ADR-035](../../../../../../../adrs/layer-5/ADR-035-hostile-property-values.md)).
   * Declaring the range on the surface is what makes the clamp generator-wide instead of
   * per-binding.
   */
  val range: ParsedRange? = null,
  /**
   * The holder shape this parameter crosses as, when [kind] is `HOLDER`.
   *
   * Marked with `@Holder` on the surface and resolved against the generator's shape table. A
   * `@Holder` on a type the table does not know is rejected rather than guessed at: a holder the
   * generator plumbed by inference would produce properties nothing on the host reads, which
   * renders and is silently inert.
   */
  val holderShape: HolderShape? = null,
  /**
   * The enumeration this parameter's type names, when the surface declared one.
   *
   * An enumeration crosses as its entry **name**, never its ordinal. Names survive a reordered
   * declaration, read as themselves in a transcript or a skew report, and let a client one
   * dictionary version behind meet an entry it has never heard of and say so -- an ordinal would
   * silently resolve to whichever entry happened to sit at that index. The bytes cost is a few
   * characters per property and is not worth an ambiguity.
   */
  val enumType: ParsedEnum? = null,
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

/** An inclusive numeric range declared on the surface. See [ParsedParameter.range]. */
@Serializable
data class ParsedRange(val min: Double, val max: Double)

/**
 * An enumeration declared on the surface, so a component may take one as a parameter.
 *
 * Before this existed a parameter typed `variant: ButtonVariant` was classified as an ordinary
 * value and the generator emitted `JsonPrimitive(it)` for it -- code that does not compile, on
 * both sides, pointing at a generated file rather than at the surface. A design system's most
 * common non-primitive parameter shape was therefore unusable and the failure said nothing about
 * why. See [ADR-068](../../../../../../../adrs/layer-5/ADR-068-the-generator-refuses-what-it-cannot-bind.md).
 *
 * @param entries in declaration order. Append-only, like tags: the lock refuses a removed or renamed
 *   entry, because a client one version behind resolves the name it receives against this list.
 * @param implementation the fully qualified host enumeration the binding decodes into, when the
 *   adopter already owns one; null means the generator emits a host-side copy. Host-side only, as
 *   `@Implementation` is on a component: it never reaches the dictionary or the wire.
 */
@Serializable
data class ParsedEnum(
  val name: String,
  val entries: List<String>,
  val implementation: String? = null,
)

/** Everything one parse of a surface produced: the components, and the enumerations they may use. */
data class ParsedSurface(
  val components: List<ParsedComponent>,
  val enums: List<ParsedEnum> = emptyList(),
)

@Serializable
data class ParsedComponent(
  val name: String,
  val parameters: List<ParsedParameter>,
  /**
   * The fully qualified function the host binding calls, or null for the convention.
   *
   * Null means `${implPackage}.${name}Impl` — a wrapper the adopter writes. `@Implementation` on
   * the surface replaces that with the adopter's own composable, called directly, because for a
   * team that already owns a design system the wrapper is pure ceremony whenever the parameter
   * names and wire-side types already line up. The generated call uses named arguments either way,
   * so the contract is identical: the target must exist with these parameter names, and the
   * compiler is what enforces it — an `@Implementation` pointing at nothing fails the host build
   * with an unresolved reference, exactly as a missing `Impl` does today.
   *
   * Host-side only, deliberately: it never touches the dictionary, the lock, or anything on the
   * wire, so two clients may bind the same component to different implementations and a payload
   * cannot tell.
   */
  val implementation: String? = null,
) {
  val isBindable: Boolean get() = parameters.none { it.kind == ParameterKind.UNSUPPORTED }
  val values: List<ParsedParameter> get() = parameters.filter { it.kind == ParameterKind.VALUE || it.kind == ParameterKind.HOST_RESOLVED }
  val holders: List<ParsedParameter> get() = parameters.filter { it.kind == ParameterKind.HOLDER }

  /**
   * Every property this component puts on the wire, in declaration order.
   *
   * Declaration order rather than values-then-holders, because tags are allocated from this list
   * and the protocol's evolution rule is additive: a parameter appended to the surface must append
   * its tags. Grouping by kind would renumber a holder's properties the next time an ordinary
   * value was added after it, and a moved tag does not fail to render -- it renders the wrong
   * thing, which is what the lock exists to catch and what this ordering exists to avoid.
   */
  val wireProperties: List<WireProperty> get() = parameters.flatMap { parameter ->
    when (parameter.kind) {
      ParameterKind.VALUE, ParameterKind.HOST_RESOLVED ->
        listOf(WireProperty(parameter.name, parameter.type, parameter))
      ParameterKind.HOLDER -> {
        val shape = parameter.holderShape ?: error("holder '${parameter.name}' has no shape")
        shape.properties.map {
          WireProperty(parameter.name + it.suffix, it.type, parameter, it)
        }
      }
      else -> emptyList()
    }
  }

  /**
   * Every event this component puts on the wire, in declaration order.
   *
   * Declaration order for the same reason [wireProperties] is in it: tags are allocated from this
   * list and may only ever be appended.
   */
  val wireEvents: List<WireEvent> get() = parameters.flatMap { parameter ->
    when (parameter.kind) {
      ParameterKind.EVENT -> listOf(WireEvent(parameter.name, parameter.type, parameter))
      ParameterKind.HOLDER -> {
        val report = parameter.holderShape?.report ?: return@flatMap emptyList()
        // A synthesised signature, so the lock's type check covers a report exactly as it covers a
        // declared callback: changing what a report carries moves no tag and would otherwise pass
        // every check in the lock while the two ends disagreed about the argument list.
        val signature = report.arguments.joinToString(", ") { it.type }
        listOf(WireEvent("${parameter.name}Report", "($signature) -> Unit", parameter, report))
      }
      else -> emptyList()
    }
  }
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
  /**
   * What this segment is called **on the wire**, where a guest reads it from
   * `LocalSegmentVersions`.
   *
   * Separate from [segmentName] because that one names Kotlin declarations — `bindFooBar`,
   * `FooBarTags` — and must be an identifier, while this one is a string a guest compares and is
   * conventionally dotted. They were the same field, reconciled by a `replace()` in the generator's
   * entry point, and the consequence surfaced the moment a segment had to name itself in two
   * places: the same segment appeared in the version map twice, under both spellings, and a guest
   * branching on either would have been half right.
   */
  val wireName: String = segmentName,
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
  /**
   * Every enumeration this segment's components may carry, with its entries in declaration order.
   *
   * Part of the contract, so the lock can see it: an entry crosses as its name, and a name a client
   * has never heard of degrades to the parameter's default. Adding an entry is therefore a
   * compatibility event -- a client behind the change renders the default where a newer payload
   * meant something specific -- and the lock requires the segment version to move with it, exactly
   * as it does for an added component.
   */
  val enums: Map<String, List<String>> = emptyMap(),
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

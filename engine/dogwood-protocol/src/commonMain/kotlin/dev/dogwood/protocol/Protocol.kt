/*
 * Project Dogwood -- Phase 0 measurement harness.
 *
 * The provisional v0 wire format of the Change/Event protocol, transcribed field for
 * field from adrs/layer-4/ADR-004-change-event-protocol-v0.md. Nothing is invented here:
 * if a field disagrees with that Architecture Decision Record (ADR), the ADR wins and this
 * file is the bug.
 *
 * Experiment 0.3 measures the bytes this file produces, so field names stay one letter
 * long exactly as ADR-004 section 2.2 specifies.
 */
package dev.dogwood.protocol

import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------------------
// Tags (ADR-004 section 2.1)
// ---------------------------------------------------------------------------

/** A node instance. [Id] 0 is the root. Monotonic within a composition, never reused. */
@Serializable
@JvmInline
value class Id(val value: Int)

/**
 * Which bound Compose function a node represents. The dictionary segment lives in the top
 * eight bits: `tag = (segmentId shl 24) or localTag`.
 */
@Serializable
@JvmInline
value class WidgetTag(val value: Int) {
  val segment: Int get() = value ushr 24
  val local: Int get() = value and LOCAL_MASK
}

/** Which modifier is applied. Segment-encoded exactly as [WidgetTag] is. */
@Serializable
@JvmInline
value class ModifierTag(val value: Int) {
  val segment: Int get() = value ushr 24
  val local: Int get() = value and LOCAL_MASK
}

/** Which parameter of a widget is being set. Widget-scoped, so it carries no segment bits. */
@Serializable
@JvmInline
value class PropertyTag(val value: Int)

/** Which content slot children belong to. Widget-scoped. */
@Serializable
@JvmInline
value class ChildrenTag(val value: Int)

/** Which lambda parameter an event targets. Widget-scoped. */
@Serializable
@JvmInline
value class EventTag(val value: Int)

internal const val LOCAL_MASK = 0x00FFFFFF

/** Packs a dictionary segment identifier and a segment-local tag into one wire integer. */
fun widgetTag(segment: Int, local: Int): WidgetTag {
  require(segment in 0..255) { "segment out of range: $segment" }
  require(local in 0..LOCAL_MASK) { "local tag out of range: $local" }
  return WidgetTag((segment shl 24) or local)
}

/** Packs a dictionary segment identifier and a segment-local tag into one wire integer. */
fun modifierTag(segment: Int, local: Int): ModifierTag {
  require(segment in 0..255) { "segment out of range: $segment" }
  require(local in 0..LOCAL_MASK) { "local tag out of range: $local" }
  return ModifierTag((segment shl 24) or local)
}

/** Dictionary segment identifiers used by the Phase 0 and Phase 1 hand-written slice. */
object Segments {
  /** The generated androidx tier. Phase 1 uses it for the five layout primitives. */
  const val LAYOUT = 0

  /** The first registered module -- the design-system slice. */
  const val DESIGN_SYSTEM = 1
}

// ---------------------------------------------------------------------------
// The Change hierarchy (ADR-004 section 2.2)
// ---------------------------------------------------------------------------

@Serializable
sealed interface Change

/** Creates a node. */
@Serializable
@SerialName("c")
data class Create(
  val i: Id,
  val w: WidgetTag,
) : Change

/**
 * Sets one property. Unset optional parameters are ABSENT from the batch -- absence is the
 * "use host default" sentinel. Resetting a set property back to its default sends the
 * default-sentinel object instead.
 */
@Serializable
@SerialName("p")
data class PropertySet(
  val i: Id,
  val p: PropertyTag,
  val v: JsonElement,
) : Change

/** One element of a modifier chain: a segment-encoded tag and its serialized arguments. */
@Serializable
data class ModifierElem(
  val t: ModifierTag,
  val v: JsonElement,
)

/** Replaces a node's whole modifier chain, in order. Per-element diffing is a v1 question. */
@Serializable
@SerialName("m")
data class ModifierSet(
  val i: Id,
  val e: List<ModifierElem>,
) : Change

/** Adds a child to a slot at an index. */
@Serializable
@SerialName("a")
data class ChildAdd(
  val i: Id,
  val s: ChildrenTag,
  val c: Id,
  val x: Int,
) : Change

/** Removes [n] children starting at index [x]. The guest performs the depth-first purge. */
@Serializable
@SerialName("r")
data class ChildRemove(
  val i: Id,
  val s: ChildrenTag,
  val x: Int,
  val n: Int,
) : Change

/** Moves [n] children from index [f] to index [t]. */
@Serializable
@SerialName("v")
data class ChildMove(
  val i: Id,
  val s: ChildrenTag,
  val f: Int,
  val t: Int,
  val n: Int,
) : Change

// ---------------------------------------------------------------------------
// Envelope and event (ADR-004 section 2.3)
// ---------------------------------------------------------------------------

/** All changes from one composition pass. Apply in order. */
@Serializable
data class ChangeBatch(
  val q: Int,
  val g: List<Change>,
)

/**
 * One inbound interaction. [q] is the sequence number of the last batch the host had
 * APPLIED when the interaction occurred, which is what lets the guest drop stale events.
 */
@Serializable
data class Event(
  val i: Id,
  val e: EventTag,
  val q: Int,
  val a: List<JsonElement> = emptyList(),
)

// ---------------------------------------------------------------------------
// Wire encoding
// ---------------------------------------------------------------------------

/**
 * The v0 encoder. The class discriminator is `"k"` per ADR-004 section 2.2, and defaults
 * are NOT encoded so that an absent optional parameter costs zero bytes.
 */
val DogwoodJson: Json = Json {
  classDiscriminator = "k"
  encodeDefaults = false
  ignoreUnknownKeys = true
}

/**
 * The same schema encoded with array polymorphism: a change becomes `["c",{...}]` instead of
 * `{"k":"c",...}`.
 *
 * This is not an invention. It is the encoding Zipline's own `CallChannel` already uses
 * (`Endpoint.json` sets `useArrayPolymorphism = true`), which means the bytes ADR-004
 * section 2.4 draws are NOT the bytes that cross the boundary when the guest calls
 * `sendChanges`. Experiment 0.3 measures both so the v1 revision can choose with numbers
 * rather than with assumption.
 */
val DogwoodJsonArrayPolymorphic: Json = Json {
  useArrayPolymorphism = true
  encodeDefaults = false
  ignoreUnknownKeys = true
}

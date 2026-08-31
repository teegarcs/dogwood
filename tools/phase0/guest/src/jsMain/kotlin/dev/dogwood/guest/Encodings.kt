/*
 * Project Dogwood -- Phase 0.3 encoding bake-off.
 *
 * Every function here encodes the SAME `List<Change>` a different way, so the only variable
 * is the encoding. Cost on this boundary is linear in bytes (measured: ~1.2 microseconds per
 * byte), and the boundary itself is `CallChannel.call(callJson: String): String` -- a string
 * channel with no byte path. That constraint is what makes this a real contest rather than
 * an obvious win for the most compact format.
 *
 * Two facts shape the candidates:
 *
 *  1. On Kotlin/JavaScript, `kotlinx.serialization`'s pure-Kotlin encoder runs as interpreted
 *     bytecode inside QuickJS, while `JSON.stringify` is QuickJS's own C implementation.
 *     Measured, the C path is roughly twice as fast for identical output. Any encoding that
 *     must be built in interpreted Kotlin forfeits that.
 *  2. A binary encoding cannot cross a string channel as bytes. It must be text-encoded,
 *     and Base64 adds a third again to whatever it saved.
 */
package dev.dogwood.guest

import dev.dogwood.protocol.Change
import dev.dogwood.protocol.ChangeBatch
import dev.dogwood.protocol.ChildAdd
import dev.dogwood.protocol.ChildMove
import dev.dogwood.protocol.ChildRemove
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.ModifierElem
import dev.dogwood.protocol.ModifierSet
import dev.dogwood.protocol.PropertySet
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

// ---------------------------------------------------------------------------
// Positional JavaScript Object Notation (JSON)
// ---------------------------------------------------------------------------

/**
 * Change-kind discriminators for the positional encoding. Small integers rather than the
 * one-character strings of ADR-004, because a number costs no quote marks.
 */
private const val K_CREATE = 0
private const val K_PROPERTY = 1
private const val K_MODIFIER = 2
private const val K_CHILD_ADD = 3
private const val K_CHILD_REMOVE = 4
private const val K_CHILD_MOVE = 5

/**
 * Converts a serialized value to a native JavaScript value.
 *
 * This is the step that lets `JSON.stringify` do all the work: once the whole batch is a
 * native array of numbers, strings, and nested arrays, the C implementation walks it with no
 * interpreted Kotlin involved.
 */
private fun nativeValue(value: JsonElement): Any? = when (value) {
  is JsonNull -> null
  is JsonPrimitive -> when {
    value.isString -> value.content
    else -> value.content.toDoubleOrNull() ?: value.content
  }
  // Structured values do not occur in the Phase 0 slice; they will once deferred
  // expressions exist, and they will need their own positional shape then.
  else -> value.toString()
}

private fun positionalChange(change: Change): Array<Any?> = when (change) {
  is Create -> arrayOf(K_CREATE, change.i.value, change.w.value)
  is PropertySet -> arrayOf(K_PROPERTY, change.i.value, change.p.value, nativeValue(change.v))
  is ModifierSet -> arrayOf(K_MODIFIER, change.i.value, positionalChain(change.e))
  is ChildAdd -> arrayOf(K_CHILD_ADD, change.i.value, change.s.value, change.c.value, change.x)
  is ChildRemove -> arrayOf(K_CHILD_REMOVE, change.i.value, change.s.value, change.x, change.n)
  is ChildMove -> arrayOf(K_CHILD_MOVE, change.i.value, change.s.value, change.f, change.t, change.n)
}

private fun positionalChain(elements: List<ModifierElem>): Array<Any?> =
  Array(elements.size) { arrayOf(elements[it].t.value, nativeValue(elements[it].v)) }

/** Positional JSON: `[[0,1,2],[1,3,1,"Total"],...]`, wrapped with the batch sequence number. */
fun encodePositional(batch: ChangeBatch): String {
  val changes = Array<Any?>(batch.g.size) { positionalChange(batch.g[it]) }
  return JSON.stringify(arrayOf<Any?>(batch.q, changes))
}

/**
 * Positional JSON with a modifier-chain table.
 *
 * The reference screen sends 160 modifier chains of which 13 are distinct, so every chain
 * after the first occurrence is a repeat of bytes the host has already seen. Interning sends
 * each distinct chain once and refers to it by index thereafter.
 */
fun encodePositionalInterned(batch: ChangeBatch): String {
  val table = ArrayList<Array<Any?>>()
  val index = HashMap<String, Int>()
  val changes = Array<Any?>(batch.g.size) { i ->
    val change = batch.g[i]
    if (change is ModifierSet) {
      // The key is the chain's identity. Tag-and-value pairs are small and few, so a joined
      // string key is cheaper than a structural comparison.
      val key = buildString {
        for (e in change.e) {
          append(e.t.value); append(':'); append(e.v.toString()); append(';')
        }
      }
      val slot = index.getOrPut(key) {
        table.add(positionalChain(change.e))
        table.size - 1
      }
      arrayOf<Any?>(K_MODIFIER, change.i.value, slot)
    } else {
      positionalChange(change)
    }
  }
  return JSON.stringify(arrayOf<Any?>(batch.q, table.toTypedArray(), changes))
}

// ---------------------------------------------------------------------------
// Binary candidates
// ---------------------------------------------------------------------------

/**
 * A protocol-buffer-shaped mirror of [Change].
 *
 * It exists because ADR-004 types every dynamic value as `JsonElement`, and protocol buffers
 * cannot encode that -- there is no wire representation for "arbitrary JSON". Answering
 * "could we use protocol buffers?" therefore requires a schema change, not just a different
 * encoder, and this mirror is what that schema would look like: one flat message with a kind
 * discriminator and typed optional fields, exactly as a hand-written `.proto` would.
 */
@Serializable
data class ProtoChange(
  @ProtoNumber(1) val k: Int,
  @ProtoNumber(2) val i: Int = 0,
  @ProtoNumber(3) val a: Int = 0,
  @ProtoNumber(4) val b: Int = 0,
  @ProtoNumber(5) val c: Int = 0,
  @ProtoNumber(6) val d: Int = 0,
  @ProtoNumber(7) val s: String? = null,
  @ProtoNumber(8) val n: Double? = null,
  @ProtoNumber(9) val mods: List<ProtoMod> = emptyList(),
)

@Serializable
data class ProtoMod(
  @ProtoNumber(1) val t: Int,
  @ProtoNumber(2) val n: Double? = null,
  @ProtoNumber(3) val s: String? = null,
)

@Serializable
data class ProtoBatch(
  @ProtoNumber(1) val q: Int,
  @ProtoNumber(2) val g: List<ProtoChange>,
)

private fun protoValueString(value: JsonElement): String? =
  (value as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun protoValueNumber(value: JsonElement): Double? =
  (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

fun toProtoBatch(batch: ChangeBatch): ProtoBatch = ProtoBatch(
  q = batch.q,
  g = batch.g.map { change ->
    when (change) {
      is Create -> ProtoChange(k = K_CREATE, i = change.i.value, a = change.w.value)
      is PropertySet -> ProtoChange(
        k = K_PROPERTY,
        i = change.i.value,
        a = change.p.value,
        s = protoValueString(change.v),
        n = protoValueNumber(change.v),
      )
      is ModifierSet -> ProtoChange(
        k = K_MODIFIER,
        i = change.i.value,
        mods = change.e.map { ProtoMod(it.t.value, protoValueNumber(it.v), protoValueString(it.v)) },
      )
      is ChildAdd -> ProtoChange(k = K_CHILD_ADD, i = change.i.value, a = change.s.value, b = change.c.value, c = change.x)
      is ChildRemove -> ProtoChange(k = K_CHILD_REMOVE, i = change.i.value, a = change.s.value, b = change.x, c = change.n)
      is ChildMove -> ProtoChange(k = K_CHILD_MOVE, i = change.i.value, a = change.s.value, b = change.f, c = change.t, d = change.n)
    }
  },
)

@OptIn(ExperimentalSerializationApi::class)
fun encodeProtobufBytes(batch: ChangeBatch): ByteArray =
  ProtoBuf.encodeToByteArray(ProtoBatch.serializer(), toProtoBatch(batch))

@OptIn(ExperimentalSerializationApi::class)
fun encodeCborBytes(batch: ChangeBatch): ByteArray =
  Cbor.encodeToByteArray(ProtoBatch.serializer(), toProtoBatch(batch))

/**
 * Base64 is what a binary encoding must pay to cross a string channel. It costs a third of
 * the payload in size, plus the time to perform it in interpreted Kotlin.
 */
@OptIn(ExperimentalEncodingApi::class)
fun toWireString(bytes: ByteArray): String = Base64.encode(bytes)

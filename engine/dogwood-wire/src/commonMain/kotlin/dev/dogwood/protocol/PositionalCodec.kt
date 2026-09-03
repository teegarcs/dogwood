/*
 * Project Dogwood -- the positional wire format's grammar, in one place.
 *
 * The shape is ADR-007 section 2.1:
 *   batch    = [sequence, [change, ...]]
 *   create   = [0, id, widgetTag]
 *   property = [1, id, propertyTag, value]
 *   modifier = [2, id, [[tag, value], ...]]
 *   add      = [3, parentId, slot, childId, index]
 *   remove   = [4, id, slot, index, count]
 *   move     = [5, id, slot, from, to, count]
 *
 * **Why this lives in the protocol module rather than in the host.** It used to live in the host,
 * and the discriminators were declared a second time in the guest's encoder, a third time in a
 * decoder inside the guest's own tests, and a fourth time in the Phase 0 harness. Nothing compared
 * any of them, and no test in the repository ever fed the real encoder's output to the real
 * decoder -- every host test typed wire strings by hand. Two halves of one grammar, maintained
 * separately, verified separately, and able to drift while every suite stayed green.
 *
 * The protocol module targets JavaScript as well as the Java Virtual Machine and Android, so the
 * decoder can sit beside the guest that produces the bytes. That is what makes a round-trip test
 * possible at all, and the round-trip test is the point.
 *
 * The **encoder** deliberately does not live here. ADR-007's whole finding is that the guest must
 * build native JavaScript arrays and hand them to QuickJS's own `JSON.stringify`, because the cost
 * of a crossing is dominated by how much interpreted Kotlin runs while encoding it. That is
 * irreducibly platform-specific, so `dogwood-compose` keeps it -- but it now imports its
 * discriminators from [ChangeKind] rather than redeclaring them.
 */
package dev.dogwood.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The change-kind discriminators, declared once.
 *
 * These are permanent, like widget tags: a client one protocol revision behind reads a batch only
 * because the numbers never move.
 */
object ChangeKind {
  const val CREATE = 0
  const val PROPERTY = 1
  const val MODIFIER = 2
  const val CHILD_ADD = 3
  const val CHILD_REMOVE = 4
  const val CHILD_MOVE = 5

  /**
   * How many elements each kind's tuple carries, discriminator included.
   *
   * Checked on every change, and the check is the difference between a loud failure and a silent
   * one. Every interesting position in these tuples is an integer, so a payload whose tuples have
   * shifted by one type-checks perfectly: a child identifier is read as an insertion index, a
   * count is read as a position, and the host applies a corrupted tree while believing it applied
   * a correct one. Arity is the only thing that distinguishes "a batch I understand" from "a batch
   * that happens to parse".
   */
  internal val arity = intArrayOf(3, 4, 3, 5, 5, 6)
}

/**
 * A batch this client cannot decode.
 *
 * Typed rather than a bare `error(...)` because the caller has to be able to tell a protocol
 * disagreement from a bug: the first is skew and is contained, the second is not.
 */
class ProtocolMismatch(message: String) : IllegalArgumentException(message)

private val PositionalJson = Json { ignoreUnknownKeys = true }

/**
 * Parses a positional batch back into the same [ChangeBatch] the guest composed.
 *
 * Throws [ProtocolMismatch] rather than returning a partial batch. A half-applied batch is worse
 * than a rejected one: the changes in a batch are ordered and interdependent, so applying the
 * prefix of one leaves the tree in a state the guest never intended and every later batch compounds
 * against it. The delivery layer already settled this shape for the same reason -- a manifest that
 * fails verification is rejected whole and the last validated payload keeps serving.
 */
fun decodePositional(payload: String): ChangeBatch {
  val root = PositionalJson.parseToJsonElement(payload).jsonArray
  if (root.size != 2) {
    throw ProtocolMismatch("a batch is [sequence, changes]; this one has ${root.size} elements")
  }
  val sequence = root[0].jsonPrimitive.intOrNull
    ?: throw ProtocolMismatch("a batch's first element must be a sequence number")
  return ChangeBatch(sequence, root[1].jsonArray.map { decodeChange(it.jsonArray) })
}

/**
 * Parses the interned variant, whose second element is the modifier-chain table.
 *
 * Kept even though ADR-007 rejects interning on cost, because the rejection is a measurement that
 * should stay reproducible rather than a claim that has to be taken on trust. **Nothing on the wire
 * distinguishes this envelope from [decodePositional]'s**, so a caller must already know which one
 * it asked for; it is not reachable from the live path.
 */
fun decodePositionalInterned(payload: String): ChangeBatch {
  val root = PositionalJson.parseToJsonElement(payload).jsonArray
  val sequence = root[0].jsonPrimitive.int
  val table = root[1].jsonArray.map { decodeChain(it.jsonArray) }
  val changes = root[2].jsonArray.map { element ->
    val fields = element.jsonArray
    if (fields[0].jsonPrimitive.int == ChangeKind.MODIFIER) {
      ModifierSet(Id(fields[1].jsonPrimitive.int), table[fields[2].jsonPrimitive.int])
    } else {
      decodeChange(fields)
    }
  }
  return ChangeBatch(sequence, changes)
}

private fun decodeChange(fields: JsonArray): Change {
  val kind = fields.getOrNull(0)?.jsonPrimitive?.intOrNull
    ?: throw ProtocolMismatch("a change must begin with a kind discriminator")
  if (kind !in ChangeKind.arity.indices) {
    throw ProtocolMismatch("unknown change kind $kind; this payload is newer than this client")
  }
  val expected = ChangeKind.arity[kind]
  if (fields.size != expected) {
    throw ProtocolMismatch(
      "change kind $kind carries ${fields.size} elements, expected $expected; the payload's " +
        "tuple shape disagrees with this client's",
    )
  }

  fun int(index: Int): Int = fields[index].jsonPrimitive.int
  return when (kind) {
    ChangeKind.CREATE -> Create(Id(int(1)), WidgetTag(int(2)))
    ChangeKind.PROPERTY -> PropertySet(Id(int(1)), PropertyTag(int(2)), fields[3])
    ChangeKind.MODIFIER -> ModifierSet(Id(int(1)), decodeChain(fields[2].jsonArray))
    ChangeKind.CHILD_ADD -> ChildAdd(Id(int(1)), ChildrenTag(int(2)), Id(int(3)), int(4))
    ChangeKind.CHILD_REMOVE -> ChildRemove(Id(int(1)), ChildrenTag(int(2)), int(3), int(4))
    else -> ChildMove(Id(int(1)), ChildrenTag(int(2)), int(3), int(4), int(5))
  }
}

private fun decodeChain(elements: JsonArray): List<ModifierElem> = elements.map {
  val pair = it.jsonArray
  if (pair.size != 2) {
    throw ProtocolMismatch("a modifier element is [tag, value]; this one has ${pair.size}")
  }
  ModifierElem(ModifierTag(pair[0].jsonPrimitive.int), pair[1] as JsonElement)
}

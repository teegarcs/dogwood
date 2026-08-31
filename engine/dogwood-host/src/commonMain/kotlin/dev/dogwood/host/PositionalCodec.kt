/*
 * Project Dogwood -- the host half of the positional wire format.
 *
 * ADR-007 chose positional JavaScript Object Notation (JSON) for v1 on the strength of guest
 * encode cost alone, and left host decode unmeasured. This is that decoder, and the reason it
 * lives in `host-core` rather than in the harness is that Phase 1's `HostChangeApplier` needs
 * exactly this code: measuring it and shipping it are the same work.
 *
 * The shape is ADR-007 section 2.1:
 *   batch   = [sequence, [change, ...]]
 *   create  = [0, id, widgetTag]
 *   property= [1, id, propertyTag, value]
 *   modifier= [2, id, [[tag, value], ...]]
 *   add     = [3, parentId, slot, childId, index]
 *   remove  = [4, id, slot, index, count]
 *   move    = [5, id, slot, from, to, count]
 */
package dev.dogwood.host

import dev.dogwood.protocol.Change
import dev.dogwood.protocol.ChangeBatch
import dev.dogwood.protocol.ChildAdd
import dev.dogwood.protocol.ChildMove
import dev.dogwood.protocol.ChildRemove
import dev.dogwood.protocol.ChildrenTag
import dev.dogwood.protocol.Create
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.ModifierElem
import dev.dogwood.protocol.ModifierSet
import dev.dogwood.protocol.ModifierTag
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.PropertyTag
import dev.dogwood.protocol.WidgetTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private const val K_CREATE = 0
private const val K_PROPERTY = 1
private const val K_MODIFIER = 2
private const val K_CHILD_ADD = 3
private const val K_CHILD_REMOVE = 4
private const val K_CHILD_MOVE = 5

private val PositionalJson = Json { ignoreUnknownKeys = true }

/** Parses a positional batch back into the same [ChangeBatch] the guest composed. */
fun decodePositional(payload: String): ChangeBatch {
  val root = PositionalJson.parseToJsonElement(payload).jsonArray
  val sequence = root[0].jsonPrimitive.int
  val changes = root[1].jsonArray
  return ChangeBatch(sequence, changes.map { decodeChange(it.jsonArray) })
}

/**
 * Parses the interned variant, whose second element is the modifier-chain table.
 *
 * Kept even though ADR-007 rejects interning on cost, because the rejection is a measurement
 * that should stay reproducible rather than a claim that has to be taken on trust.
 */
fun decodePositionalInterned(payload: String): ChangeBatch {
  val root = PositionalJson.parseToJsonElement(payload).jsonArray
  val sequence = root[0].jsonPrimitive.int
  val table = root[1].jsonArray.map { decodeChain(it.jsonArray) }
  val changes = root[2].jsonArray.map { element ->
    val fields = element.jsonArray
    if (fields[0].jsonPrimitive.int == K_MODIFIER) {
      ModifierSet(Id(fields[1].jsonPrimitive.int), table[fields[2].jsonPrimitive.int])
    } else {
      decodeChange(fields)
    }
  }
  return ChangeBatch(sequence, changes)
}

private fun decodeChange(fields: JsonArray): Change {
  fun int(index: Int): Int = fields[index].jsonPrimitive.int
  return when (val kind = int(0)) {
    K_CREATE -> Create(Id(int(1)), WidgetTag(int(2)))
    K_PROPERTY -> PropertySet(Id(int(1)), PropertyTag(int(2)), fields[3])
    K_MODIFIER -> ModifierSet(Id(int(1)), decodeChain(fields[2].jsonArray))
    K_CHILD_ADD -> ChildAdd(Id(int(1)), ChildrenTag(int(2)), Id(int(3)), int(4))
    K_CHILD_REMOVE -> ChildRemove(Id(int(1)), ChildrenTag(int(2)), int(3), int(4))
    K_CHILD_MOVE -> ChildMove(Id(int(1)), ChildrenTag(int(2)), int(3), int(4), int(5))
    else -> error("unknown change kind on the wire: $kind")
  }
}

private fun decodeChain(elements: JsonArray): List<ModifierElem> = elements.map {
  val pair = it.jsonArray
  ModifierElem(ModifierTag(pair[0].jsonPrimitive.int), pair[1] as JsonElement)
}

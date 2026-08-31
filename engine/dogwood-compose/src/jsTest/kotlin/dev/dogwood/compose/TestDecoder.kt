/*
 * Project Dogwood -- a test-only reader for the positional wire format.
 *
 * The tests decode what actually crossed rather than inspecting the recorder directly. That is
 * the point: a test that asserts on an in-memory structure the encoder never touched would pass
 * happily while the bytes on the wire were wrong.
 *
 * This mirrors `dev.dogwood.host.decodePositional`. It is duplicated rather than shared because
 * the host decoder is Java-Virtual-Machine-side and this runs in the guest's own target; a
 * common decoder is worth building when a third caller appears, not before.
 */
package dev.dogwood.compose

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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

fun decodeForTest(payload: String): ChangeBatch {
  val root = json.parseToJsonElement(payload).jsonArray
  return ChangeBatch(
    q = root[0].jsonPrimitive.int,
    g = root[1].jsonArray.map { decode(it.jsonArray) },
  )
}

private fun decode(fields: JsonArray): Change {
  fun int(index: Int) = fields[index].jsonPrimitive.int
  return when (val kind = int(0)) {
    K_CREATE -> Create(Id(int(1)), WidgetTag(int(2)))
    K_PROPERTY -> PropertySet(Id(int(1)), PropertyTag(int(2)), fields[3])
    K_MODIFIER -> ModifierSet(
      Id(int(1)),
      fields[2].jsonArray.map {
        val pair = it.jsonArray
        ModifierElem(ModifierTag(pair[0].jsonPrimitive.int), pair[1])
      },
    )
    K_CHILD_ADD -> ChildAdd(Id(int(1)), ChildrenTag(int(2)), Id(int(3)), int(4))
    K_CHILD_REMOVE -> ChildRemove(Id(int(1)), ChildrenTag(int(2)), int(3), int(4))
    K_CHILD_MOVE -> ChildMove(Id(int(1)), ChildrenTag(int(2)), int(3), int(4), int(5))
    else -> error("unknown change kind on the wire: $kind")
  }
}

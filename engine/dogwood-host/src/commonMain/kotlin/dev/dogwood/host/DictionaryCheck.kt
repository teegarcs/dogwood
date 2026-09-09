/*
 * Project Dogwood -- the pre-flight dictionary comparison, in one place for every client.
 *
 * `commonMain`, not `ziplineMain`, and the move was worth making. Two clients ask this question --
 * a mobile host before it starts a Zipline guest, and the Web host before it creates a Worker --
 * and each used to answer it with its own copy of the same nine lines. Two copies that "must stay
 * identical" are two copies that drift, and the drift would be invisible: a payload accepted on one
 * platform and refused on the other, discovered by a user rather than by a test. The Web host
 * cannot see Zipline, but it can see this module, so this is where the comparison belongs.
 *
 * It is deliberately pure: no fetching, no manifest, no platform. What it takes is what a payload
 * declared and what a client implements, and what it returns is the difference.
 */
package dev.dogwood.host

/**
 * The pre-flight dictionary check, and why it lives beside the release guard rather than inside it.
 *
 * Both answer "may this payload run?" before a byte of it does, and both are refusals rather than
 * failures — but they refuse for unrelated reasons and a host will want to say different things
 * about them. A quarantined release is *this build* being bad; a payload naming a dictionary this
 * client lacks is *this client* being old, which is the user's next app update rather than a
 * publishing mistake.
 *
 * The comparison is the web's, deliberately identical (`WebDelivery.checkDictionary`): a payload
 * may name **fewer** segments than the client implements — a guest that uses no design-system
 * component says nothing about that segment — so absence is never a refusal. The refusals are the
 * other direction: a segment the client has never heard of, and a segment the client is behind on.
 */
fun checkDeclaredDictionary(
  declared: Map<String, Int>,
  clientVersions: Map<String, Int>,
): DictionarySkew? {
  if (declared.isEmpty()) return null
  val unknown = mutableListOf<String>()
  val tooNew = mutableMapOf<String, Pair<Int, Int>>()
  for ((segment, wanted) in declared) {
    val have = clientVersions[segment]
    when {
      have == null -> unknown += segment
      wanted > have -> tooNew[segment] = wanted to have
    }
  }
  return if (unknown.isEmpty() && tooNew.isEmpty()) null else DictionarySkew(unknown, tooNew)
}

/** What a payload asked for that this client cannot provide. */
data class DictionarySkew(
  /** Segments this client has never heard of. */
  val unknownSegments: List<String>,
  /** Segments this client is behind on, as name to (wanted, have). */
  val outdatedSegments: Map<String, Pair<Int, Int>>,
) {
  val message: String
    get() = buildString {
      append("the payload needs a dictionary this client does not have")
      if (unknownSegments.isNotEmpty()) append("; unknown segments: ${unknownSegments.sorted()}")
      for ((segment, versions) in outdatedSegments) {
        append("; $segment wants ${versions.first}, this client implements ${versions.second}")
      }
    }
}


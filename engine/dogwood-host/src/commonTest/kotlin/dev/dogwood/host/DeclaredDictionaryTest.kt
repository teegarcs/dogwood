/*
 * Project Dogwood -- the mobile pre-flight dictionary check.
 *
 * The web has refused a too-new payload before creating its Worker since ADR-032; the mobile
 * clients had no equivalent, so render-time containment — placeholders, withheld affordances,
 * reported skew — was their only line. The adoption audit recorded that asymmetry and this closes
 * it: the payload declares its dictionary in the manifest's **signed** metadata, and a client that
 * cannot render it refuses before a byte of guest code runs.
 *
 * Most of these tests assert that it does **not** refuse, and that proportion is the point. A
 * refusal is a screen nobody sees; every case where refusing would be wrong costs more than the
 * case it exists for.
 */
package dev.dogwood.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeclaredDictionaryTest {

  private val client = mapOf(
    "androidx.layout" to 1,
    "dogwood.designsystem" to 14,
    "acme.designsystem" to 1,
  )

  @Test
  fun aPayloadBuiltAgainstThisClientRuns() {
    assertNull(checkDeclaredDictionary(client, client))
  }

  @Test
  fun aPayloadDeclaringNothingRuns() {
    // Every payload built before this field existed declares nothing, and they must keep working:
    // this is a second line of defence, not a new requirement. Refusing here would turn an engine
    // upgrade into a fleet-wide outage on the next poll.
    assertNull(checkDeclaredDictionary(emptyMap(), client))
  }

  @Test
  fun aPayloadNamingFewerSegmentsRuns() {
    // A guest that uses no design-system component says nothing about that segment. Absence is not
    // a claim, so it cannot be a refusal — the same rule the web's check follows.
    assertNull(checkDeclaredDictionary(mapOf("androidx.layout" to 1), client))
  }

  @Test
  fun anOlderPayloadRuns() {
    // The whole point of the dictionary's append-only discipline: a client one version ahead
    // renders everything an older payload can name.
    assertNull(checkDeclaredDictionary(mapOf("dogwood.designsystem" to 9), client))
  }

  @Test
  fun aNewerPayloadIsRefusedAndSaysBothNumbers() {
    val skew = checkDeclaredDictionary(mapOf("dogwood.designsystem" to 15), client)
    assertTrue(skew != null)
    // Both numbers, because "this client is behind" is unactionable and "wants 15, implements 14"
    // tells whoever reads it exactly how far.
    assertTrue("15" in skew.message && "14" in skew.message, skew.message)
  }

  @Test
  fun aSegmentThisClientHasNeverHeardOfIsRefused() {
    // A product's own segment, on a client that never registered it. Every widget in it would
    // render as an inert placeholder, so refusing before the guest runs is the kinder failure.
    val skew = checkDeclaredDictionary(mapOf("umbra.designsystem" to 1), client)
    assertTrue(skew != null)
    assertEquals(listOf("umbra.designsystem"), skew.unknownSegments)
  }

  @Test
  fun bothKindsOfSkewAreReportedTogether() {
    // A host shows one screen, so it needs one message with everything wrong in it — not the first
    // problem discovered.
    val skew = checkDeclaredDictionary(
      mapOf("umbra.designsystem" to 1, "dogwood.designsystem" to 99),
      client,
    )
    assertTrue(skew != null)
    assertEquals(listOf("umbra.designsystem"), skew.unknownSegments)
    assertEquals(mapOf("dogwood.designsystem" to (99 to 14)), skew.outdatedSegments)
  }
}

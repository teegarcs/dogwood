/*
 * Project Dogwood -- the two allocation directions cannot meet.
 *
 * Products allocate segment identifiers upward from 2; Dogwood's generated library tiers allocate
 * downward from 255 (plans/generator-v2.md, D-B). A product that picked 255 would collide with the
 * Material 3 tier on every client registering it, and a tag collision does not fail to render -- it
 * renders the wrong widget. So the ceiling is refused at the call site, where the message names it.
 */
package dev.dogwood.host

import dev.dogwood.protocol.Segments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SegmentCeilingTest {

  @Test
  fun aProductSegmentAboveTheCeilingIsRefusedByName() {
    val failure = assertFailsWith<IllegalArgumentException> { productTag(Segments.MATERIAL3, 1) }
    assertTrue("reserved for Dogwood's generated library tiers" in failure.message.orEmpty(), failure.message)
    assertTrue("$LAST_PRODUCT_SEGMENT" in failure.message.orEmpty())
  }

  @Test
  fun theLastProductSegmentItselfIsStillAProductsToTake() {
    assertEquals((LAST_PRODUCT_SEGMENT shl 24) or 1, productTag(LAST_PRODUCT_SEGMENT, 1).value)
  }

  @Test
  fun theTiersSitAboveTheCeilingAndBelowTheRange() {
    for (tier in listOf(Segments.MATERIAL3, Segments.FOUNDATION, Segments.FOUNDATION_LAYOUT, Segments.UI)) {
      assertTrue(tier > LAST_PRODUCT_SEGMENT && tier <= 255, "tier $tier")
    }
  }
}

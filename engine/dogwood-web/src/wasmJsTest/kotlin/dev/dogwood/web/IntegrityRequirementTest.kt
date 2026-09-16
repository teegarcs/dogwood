/*
 * Project Dogwood -- the rule that decides what a missing or present script digest means.
 *
 * The browser half (fetch, `crypto.subtle.digest`, a Worker from a blob) is graded by the web
 * conformance drill as `B5`, against real bytes. This is the decision table on its own, because the
 * refusal for an *absent* digest is the half a drill cannot reach: every fixture the build signs
 * carries one, and a fixture without one would first fail the signature check.
 */
package dev.dogwood.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrityRequirementTest {

  private val digest = "a".repeat(64)

  @Test
  fun aDigestIsVerifiedWhetherOrNotKeysAreHeld() {
    assertEquals(IntegrityRequirement.Verify(digest), integrityRequirement(digest, keysHeld = true))
    assertEquals(IntegrityRequirement.Verify(digest), integrityRequirement(digest, keysHeld = false))
  }

  @Test
  fun theDigestIsNormalisedBeforeItIsCompared() {
    // A build that emitted upper-case hexadecimal, or a hand edit with a stray space, is not a
    // swapped script. The comparison is over the value, not its spelling.
    assertEquals(IntegrityRequirement.Verify(digest), integrityRequirement(" ${digest.uppercase()} ", keysHeld = true))
  }

  @Test
  fun noDigestWithKeysHeldIsARefusalThatNamesTheField() {
    val outcome = integrityRequirement(null, keysHeld = true)
    assertTrue(outcome is IntegrityRequirement.Refuse, outcome.toString())
    assertTrue("guestScriptSha256" in (outcome as IntegrityRequirement.Refuse).reason)
    assertTrue("signWebSidecars" in outcome.reason, "the message says who fills it in")
  }

  @Test
  fun noDigestWithNoKeysIsTheWrittenDecisionToBelieveTheOrigin() {
    assertEquals(IntegrityRequirement.BelieveTheOrigin, integrityRequirement(null, keysHeld = false))
    assertEquals(IntegrityRequirement.BelieveTheOrigin, integrityRequirement("", keysHeld = false))
  }

  @Test
  fun aMalformedDigestIsRefusedRatherThanTrustedOrIgnored() {
    // Sixty-three characters, or a non-hex character, is a manifest somebody edited by hand. It is
    // not treated as "absent", because that would turn a typo into believe-the-origin on a host
    // that holds keys.
    assertTrue(integrityRequirement("abc", keysHeld = false) is IntegrityRequirement.Refuse)
    assertTrue(integrityRequirement("g".repeat(64), keysHeld = true) is IntegrityRequirement.Refuse)
  }
}

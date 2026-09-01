/*
 * Project Dogwood -- what a client offers, and what it does not.
 *
 * The service surface's central claim is that absence is normal: an application should be able to
 * ship Dogwood without wiring analytics, and a guest written against one that has analytics should
 * keep running on one that does not. That claim is only true if the host side reports absence
 * consistently, which is what this pins.
 */
package dev.dogwood.host

import dev.dogwood.protocol.LogLevel
import dev.dogwood.protocol.ServiceNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServiceHostTest {

  @Test
  fun aClientThatOffersNothingSaysSoConsistently() {
    val host = DogwoodServiceHost()
    assertEquals(emptySet(), host.available())
    assertNull(host.log())
    assertNull(host.clock())
    assertNull(host.analytics())
    assertNull(host.featureFlags())
    assertNull(host.network())
  }

  @Test
  fun availableAgreesWithTheAccessors() {
    // Two ways to ask the same question, and a guest may use either. They must not disagree.
    val host = DogwoodServiceHost(
      log = CallbackLog { _, _, _ -> },
      featureFlags = MapFeatureFlags(mapOf("a" to "b")),
    )
    assertEquals(setOf(ServiceNames.LOG, ServiceNames.FEATURE_FLAGS), host.available())
    assertTrue(host.log() != null && host.featureFlags() != null)
    assertNull(host.analytics())
    assertNull(host.clock())
    assertNull(host.network())
  }

  @Test
  fun theSameServiceInstanceIsHandedOutEveryTime() {
    // The guest resolves once and holds for the composition's lifetime; a fresh instance per call
    // would mean the identity a guest captured at startup was not the one the host thought it had
    // given away.
    val log = CallbackLog { _, _, _ -> }
    val host = DogwoodServiceHost(log = log)
    assertSame(log, host.log())
    assertSame(log, host.log())
  }

  @Test
  fun closingTheVendorDoesNotCloseWhatItHandedOut() {
    // The services outlive the vendor: a guest holds them for its whole life, and the vendor is
    // finished the moment start() returns. Closing them here would break every guest immediately
    // after it launched.
    val lines = mutableListOf<String>()
    val log = CallbackLog { level, tag, message -> lines += "$level/$tag: $message" }
    val host = DogwoodServiceHost(log = log)
    val handedOut = host.log()!!
    host.close()
    handedOut.log(LogLevel.Info, "after", "still works")
    assertEquals(listOf("Info/after: still works"), lines)
  }

  @Test
  fun theServiceSurfaceVersionIsPublishedThroughTheDictionary() {
    // Same channel as the widget segments, because a guest has the same question about both.
    assertEquals(
      dev.dogwood.protocol.SERVICES_VERSION,
      DogwoodDictionary.segmentVersions[dev.dogwood.protocol.SERVICES_SEGMENT],
    )
  }
}

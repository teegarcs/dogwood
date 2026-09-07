/*
 * Project Dogwood -- the web profile's start payload is one shape, not two.
 *
 * The host half of that boundary is Kotlin/WebAssembly and the guest half is Kotlin/JavaScript.
 * They do not link, so the envelope's *kind* constants are mirrored by hand in each -- and the
 * payloads deliberately are not. `WebStartPayload` and its two siblings live in `dogwood-wire`,
 * the one module both halves compile, precisely so that a field added on one side cannot go
 * missing on the other.
 *
 * These tests are what makes that claim checkable rather than merely stated. They run on every
 * target, which is the point: the same declaration, the same generated serialiser, the same
 * document.
 */
package dev.dogwood.host

import dev.dogwood.protocol.WebAnalyticsEvent
import dev.dogwood.protocol.WebNavigationRequest
import dev.dogwood.protocol.WebStartPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.dogwood.protocol.DogwoodJson
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The configuration both halves actually use, not one this file invented.
 *
 * A test that declares its own `Json` proves that *some* parser tolerates an unknown field, which
 * is not the claim. The claim is about the parser on the wire.
 */
private val json = DogwoodJson

class WorkerPayloadTest {

  @Test
  fun everythingAHostDeclaresSurvivesTheCrossing() {
    val sent = WebStartPayload(
      entryPoint = "explore",
      launchParams = buildJsonObject {
        put("city", JsonPrimitive("Tokyo"))
        put("apiBaseUrl", JsonPrimitive("https://example.test"))
      },
      featureFlags = mapOf("explore.showWasPrice" to "true"),
      routes = setOf("experience/feed"),
      segmentVersions = mapOf("androidx.layout" to 1, "dogwood.designsystem" to 10),
    )

    val received = json.decodeFromString(
      WebStartPayload.serializer(),
      json.encodeToString(WebStartPayload.serializer(), sent),
    )

    assertEquals(sent, received)
  }

  @Test
  fun aGuestOlderThanTheHostIgnoresAFieldItDoesNotKnow() {
    // The compatibility rule for this boundary, exercised rather than asserted in a comment. A page
    // is updated by a deployment and a guest by a publish, so one is routinely newer than the
    // other; a field the reader has never heard of must be ignored rather than fatal.
    val fromTheFuture = """{"entryPoint":"about","somethingAddedLater":42}"""

    val received = json.decodeFromString(WebStartPayload.serializer(), fromTheFuture)

    // The control. Without it this test passes just as happily against a fixture with no unknown
    // field in it, which is a test of nothing.
    assertFailsWith<SerializationException> {
      Json { ignoreUnknownKeys = false }
        .decodeFromString(WebStartPayload.serializer(), fromTheFuture)
    }

    assertEquals("about", received.entryPoint)
    assertTrue(received.featureFlags.isEmpty())
  }

  @Test
  fun anEmptyDeclarationIsHonestRatherThanAbsent() {
    // The default a page gets when it declares nothing, and it must round-trip as *stated*
    // emptiness: no flags, no routes, no parameters, no versions reported. A guest branches on
    // `segmentVersions` to decide what it may use, and an empty map means "this client reported
    // nothing", which is what the web slice's Diagnostics screen displayed for months.
    val received = json.decodeFromString(
      WebStartPayload.serializer(),
      json.encodeToString(WebStartPayload.serializer(), WebStartPayload()),
    )

    assertEquals(WebStartPayload(), received)
    assertTrue(received.segmentVersions.isEmpty())
  }

  @Test
  fun anAnalyticsEventCarriesItsProperties() {
    val sent = WebAnalyticsEvent("checkout.started", mapOf("cart" to "3", "currency" to "JPY"))

    assertEquals(
      sent,
      json.decodeFromString(
        WebAnalyticsEvent.serializer(),
        json.encodeToString(WebAnalyticsEvent.serializer(), sent),
      ),
    )
  }

  @Test
  fun aNavigationRequestCarriesItsLaunchParameters() {
    // Launch parameters, not a message: `DogwoodNavigation.navigate` is explicit that a host which
    // routes to an experience already running never reads them.
    val sent = WebNavigationRequest(
      route = "experience/feed",
      params = buildJsonObject { put("filter", JsonPrimitive("stays")) },
    )

    assertEquals(
      sent,
      json.decodeFromString(
        WebNavigationRequest.serializer(),
        json.encodeToString(WebNavigationRequest.serializer(), sent),
      ),
    )
  }
}

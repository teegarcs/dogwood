/*
 * Project Dogwood -- entry points and host services, as tests.
 *
 * Two properties matter more than the rest, because both fail *silently* if they are wrong.
 *
 * An unknown entry point must be reported with the names the payload actually offers. A host and a
 * payload ship separately and can disagree about routing; if that disagreement renders an empty
 * screen, nobody finds out where it came from.
 *
 * A service the host does not offer must be an ordinary null, not an exception. An application
 * should be able to ship Dogwood without wiring analytics, and a guest written against an
 * application that has analytics should keep running on one that does not.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dogwood.protocol.DogwoodAnalytics
import dev.dogwood.protocol.DogwoodClock
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.DogwoodFeatureFlags
import dev.dogwood.protocol.DogwoodLog
import dev.dogwood.protocol.DogwoodNavigation
import dev.dogwood.protocol.DogwoodNetwork
import dev.dogwood.protocol.NAVIGATION_MIN_VERSION
import dev.dogwood.protocol.DogwoodServices
import dev.dogwood.protocol.HttpRequest
import dev.dogwood.protocol.HttpResponse
import dev.dogwood.protocol.LogLevel
import dev.dogwood.protocol.PropertySet
import dev.dogwood.protocol.SERVICES_SEGMENT
import dev.dogwood.protocol.ServiceNames
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

// ---------------------------------------------------------------------------
// Fakes. These implement the protocol interfaces directly; nothing crosses Zipline in a unit test.

private class FakeLog : DogwoodLog {
  val lines = mutableListOf<String>()
  override fun log(level: LogLevel, tag: String, message: String) {
    lines += "$level/$tag: $message"
  }
  override fun close() = Unit
}

private class FakeAnalytics : DogwoodAnalytics {
  val events = mutableListOf<Pair<String, Map<String, String>>>()
  override fun track(name: String, properties: Map<String, String>) {
    events += name to properties
  }
  override fun close() = Unit
}

private class FakeClock(private val millis: Long) : DogwoodClock {
  override fun nowEpochMillis(): Long = millis
  override fun timeZoneId(): String = "Asia/Tokyo"
  override fun close() = Unit
}

private class FakeFlags(private val values: Map<String, String>) : DogwoodFeatureFlags {
  var snapshots = 0
    private set
  override fun snapshot(): Map<String, String> {
    snapshots++
    return values
  }
  override fun close() = Unit
}

private class FakeNetwork(private val response: HttpResponse) : DogwoodNetwork {
  val requests = mutableListOf<HttpRequest>()
  override suspend fun fetch(request: HttpRequest): HttpResponse {
    requests += request
    return response
  }
  override fun close() = Unit
}

private class FakeNavigation(
  private val declared: Set<String> = emptySet(),
) : DogwoodNavigation {
  val requests = mutableListOf<Pair<String, JsonObject>>()
  var routeReads = 0
    private set

  override fun routes(): Set<String> {
    routeReads++
    return declared
  }

  override fun navigate(route: String, params: JsonObject) {
    requests += route to params
  }

  override fun close() = Unit
}

/** Counts accessor calls, because resolving per composition would leak a proxy pair each time. */
private class FakeServices(
  val log: FakeLog? = FakeLog(),
  val analytics: FakeAnalytics? = FakeAnalytics(),
  val clock: FakeClock? = FakeClock(1_700_000_000_000L),
  val flags: FakeFlags? = FakeFlags(mapOf("explore.showWasPrice" to "true")),
  val network: FakeNetwork? = null,
  val navigation: FakeNavigation? = null,
) : DogwoodServices {
  var accessorCalls = 0
    private set

  /** Separate from [accessorCalls]: the version gate is about this accessor specifically. */
  var navigationReads = 0
    private set

  override fun available(): Set<String> = buildSet {
    if (log != null) add(ServiceNames.LOG)
    if (analytics != null) add(ServiceNames.ANALYTICS)
    if (clock != null) add(ServiceNames.CLOCK)
    if (flags != null) add(ServiceNames.FEATURE_FLAGS)
    if (network != null) add(ServiceNames.NETWORK)
    if (navigation != null) add(ServiceNames.NAVIGATION)
  }

  override fun log(): DogwoodLog? { accessorCalls++; return log }
  override fun clock(): DogwoodClock? { accessorCalls++; return clock }
  override fun analytics(): DogwoodAnalytics? { accessorCalls++; return analytics }
  override fun featureFlags(): DogwoodFeatureFlags? { accessorCalls++; return flags }
  override fun network(): DogwoodNetwork? { accessorCalls++; return network }
  override fun navigation(): DogwoodNavigation? { navigationReads++; return navigation }
  override fun close() = Unit
}

/**
 * Runs a suspending call that does not actually suspend.
 *
 * There is no `runBlocking` on Kotlin/JavaScript, and the paths under test here — a refusal, a
 * missing service — complete without ever yielding. A path that genuinely suspends is verified end
 * to end against a real host instead, because a fake would prove nothing about Zipline.
 */
private fun <T> runNow(block: suspend () -> T): T {
  var outcome: Result<T>? = null
  block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
  return checkNotNull(outcome) { "the call suspended; this helper only runs calls that do not" }
    .getOrThrow()
}

private fun startGuest(
  guest: DogwoodGuest,
  entryPoint: String,
  services: DogwoodServices = FakeServices(),
  launchParams: JsonObject = JsonObject(emptyMap()),
  segmentVersions: Map<String, Int> = mapOf(SERVICES_SEGMENT to 1),
): RecordingHost {
  val host = RecordingHost()
  guest.start(
    host = host,
    services = services,
    entryPoint = entryPoint,
    configuration = HostEnvironment(),
    launchParams = launchParams,
    segmentVersions = segmentVersions,
    restoredState = null,
  )
  return host
}

// ---------------------------------------------------------------------------

class EntryPointTest {

  private fun payload() = DogwoodGuest(
    "explore" to { params -> Text("explore ${(params as? JsonObject)?.get("city") ?: "?"}") },
    "about" to { _ -> Text("about") },
  )

  @Test
  fun theHostChoosesWhichExperienceRuns() {
    val host = startGuest(payload(), "about")
    val texts = host.decoded().single().g.filterIsInstance<PropertySet>().map { it.v.toString().trim('"') }
    assertTrue("about" in texts, "expected the 'about' entry point; rendered $texts")
  }

  @Test
  fun launchParametersReachTheEntryPoint() {
    // The host cannot construct guest types, so the launch payload is data and the guest decodes
    // it. This asserts the data arrives at all.
    val host = startGuest(
      payload(),
      "explore",
      launchParams = buildJsonObject { put("city", "Kyoto") },
    )
    val texts = host.decoded().single().g.filterIsInstance<PropertySet>().map { it.v.toString().trim('"') }
    assertTrue(texts.any { it.contains("Kyoto") }, "launch parameters did not arrive; rendered $texts")
  }

  @Test
  fun anUnknownEntryPointIsReportedWithTheNamesOnOffer() {
    val host = RecordingHost()
    // RecordingHost rethrows, which is what a host that has not thought about this would do.
    // What matters is the message: a host and a payload ship separately and can disagree about
    // routing, and the only way anyone finds out is if the payload says what it actually offers.
    val failure = runCatching {
      startGuestOn(host, payload(), "checkout")
    }.exceptionOrNull()

    val message = failure?.message.orEmpty()
    assertTrue("checkout" in message, "the message must name what was asked for: $message")
    assertTrue("about" in message && "explore" in message, "and what is on offer: $message")
    assertTrue(host.batches.isEmpty(), "nothing must be composed for an entry point that does not exist")
  }

  private fun startGuestOn(host: RecordingHost, guest: DogwoodGuest, entryPoint: String) {
    guest.start(
      host = host,
      services = FakeServices(),
      entryPoint = entryPoint,
      configuration = HostEnvironment(),
      launchParams = JsonObject(emptyMap()),
      segmentVersions = emptyMap(),
      restoredState = null,
    )
  }

  @Test
  fun aPayloadDeclaresWhatItOffers() {
    assertEquals(setOf("explore", "about"), payload().offers)
  }
}

// ---------------------------------------------------------------------------

class HostServiceTest {

  @Composable
  private fun Probe(body: (HostServices) -> String) {
    Text(body(services()))
  }

  private fun renderedText(host: RecordingHost): List<String> =
    host.decoded().flatMap { it.g }.filterIsInstance<PropertySet>().map { it.v.toString().trim('"') }

  @Test
  fun servicesAreResolvedOncePerExperienceNotPerComposition() {
    // Every accessor call crosses the boundary and allocates a service proxy on both sides.
    // Resolving per composition would leak a pair at the rate the screen recomposes.
    val services = FakeServices()
    val guest = DogwoodGuest("main" to { _ -> Probe { it.available.size.toString() } })
    startGuest(guest, "main", services = services)
    assertEquals(5, services.accessorCalls, "one call per accessor, once")
    assertEquals(1, services.flags!!.snapshots, "flags are snapshotted once, at start")
  }

  @Test
  fun flagsAndTheClockReachGuestCode() {
    val services = FakeServices()
    val guest = DogwoodGuest(
      "main" to { _ ->
        Probe { "${it.flagEnabled("explore.showWasPrice")} ${it.nowEpochMillis()}" }
      },
    )
    val host = startGuest(guest, "main", services = services)
    assertTrue(
      renderedText(host).any { it == "true 1700000000000" },
      "expected the flag and the host clock; rendered ${renderedText(host)}",
    )
  }

  @Test
  fun analyticsAndLoggingReachTheHost() {
    val services = FakeServices()
    val guest = DogwoodGuest(
      "main" to { _ ->
        Probe {
          it.info("probe", "hello")
          it.track("probe.rendered", mapOf("k" to "v"))
          "ok"
        }
      },
    )
    startGuest(guest, "main", services = services)
    assertEquals(listOf("Info/probe: hello"), services.log!!.lines)
    assertEquals(listOf("probe.rendered" to mapOf("k" to "v")), services.analytics!!.events)
  }

  @Test
  fun aServiceThisClientDoesNotOfferIsANullNotACrash() {
    // The point of the whole surface: an application ships without analytics, and a guest written
    // against one that has it keeps running.
    val services = FakeServices(log = null, analytics = null, clock = null, flags = null)
    val guest = DogwoodGuest(
      "main" to { _ ->
        Probe {
          it.info("probe", "goes nowhere")
          it.track("probe.rendered")
          "clock=${it.nowEpochMillis()} flag=${it.flag("anything", "fallback")}"
        }
      },
    )
    val host = startGuest(guest, "main", services = services)
    assertTrue(
      renderedText(host).any { it == "clock=null flag=fallback" },
      "rendered ${renderedText(host)}",
    )
    assertEquals(emptySet(), services.available())
  }

  @Test
  fun aMissingNetworkAnswersLikeARefusalRatherThanThrowing() {
    // One shape for the guest to handle, not two: "this client has no network" and "this client
    // will not let me reach that address" both arrive as a failed response.
    val response = runNow { HostServices.None.fetch(HttpRequest(url = "https://example.com")) }
    assertEquals(0, response.code)
    assertTrue(!response.isSuccessful)
    assertTrue(response.failure!!.contains("no network service"), response.failure!!)
  }

  @Test
  fun theServiceSurfaceVersionIsReportedToTheGuest() {
    // It matters more than a widget version: an unknown widget tag becomes a placeholder, but
    // calling a service method an older host does not implement fails at the boundary.
    val guest = DogwoodGuest("main" to { _ -> Probe { it.version.toString() } })
    val host = startGuest(guest, "main", segmentVersions = mapOf(SERVICES_SEGMENT to 7))
    assertTrue(renderedText(host).any { it == "7" }, "rendered ${renderedText(host)}")
  }

  @Test
  fun aClientThatReportsNoServiceVersionReadsAsZero() {
    val guest = DogwoodGuest("main" to { _ -> Probe { it.version.toString() } })
    val host = startGuest(guest, "main", segmentVersions = emptyMap())
    assertTrue(renderedText(host).any { it == "0" }, "rendered ${renderedText(host)}")
  }

  @Test
  fun theLaunchPayloadIsAlsoAvailableAsACompositionLocal() {
    // Passed to the entry point *and* published as a local, because a screen deep in the tree
    // should not have to be threaded the launch payload by every composable above it.
    val guest = DogwoodGuest(
      "main" to { _ -> Text(LocalLaunchParams.current.toString()) },
    )
    val host = startGuest(guest, "main", launchParams = buildJsonObject { put("city", "Osaka") })
    assertTrue(
      renderedText(host).any { it.contains("Osaka") },
      "rendered ${renderedText(host)}",
    )
  }
}


// ---------------------------------------------------------------------------

/**
 * The frame loop, for state the guest changes on its own.
 *
 * This is the property host services broke and had to restore. Every earlier guest state change
 * began with a host call, so notifying at the end of that call was enough. A suspending service
 * call resumes long after its caller returned: the write happens with nobody left to notice it,
 * and the screen sits on whatever it was showing until something unrelated happens to arrive.
 */
/*
 * Navigation, and the version gate it is the first service to need.
 *
 * `DogwoodNavigation` arrived in service surface version 2. An unknown *widget* tag degrades to a
 * placeholder, but calling a `ZiplineService` method an older host does not implement is an error
 * at the boundary with nothing to fall back to -- so the guest has to decide not to call, before
 * calling. These tests pin that decision, because the failure it prevents only appears on the
 * combination nobody tests by hand: a payload newer than the client running it.
 */
class NavigationTest {

  @Composable
  private fun Probe(body: (HostServices) -> String) {
    Text(body(services()))
  }

  private fun renderedText(host: RecordingHost): List<String> =
    host.decoded().flatMap { it.g }.filterIsInstance<PropertySet>().map { it.v.toString().trim('"') }

  @Test
  fun anOlderHostIsNeverAskedForANavigationServiceItCannotHave() {
    // The whole point of the gate. Not "the call returns null" -- the call must not happen.
    val services = FakeServices(navigation = FakeNavigation(setOf("home")))
    val guest = DogwoodGuest("main" to { _ -> Probe { it.navigate("home").toString() } })
    val host = startGuest(guest, "main", services = services, segmentVersions = mapOf(SERVICES_SEGMENT to 1))

    assertEquals(0, services.navigationReads, "a version 1 host has no navigation accessor to call")
    assertEquals(listOf("false"), renderedText(host), "and the guest degrades rather than throwing")
    assertEquals(emptyList(), services.navigation!!.requests)
  }

  @Test
  fun aCurrentHostRoutesTheRequest() {
    val navigation = FakeNavigation(setOf("stay", "reviews"))
    val services = FakeServices(navigation = navigation)
    val guest = DogwoodGuest(
      "main" to { _ ->
        Probe { it.navigate("stay", buildJsonObject { put("id", "42") }).toString() }
      },
    )
    val host = startGuest(
      guest, "main", services = services,
      segmentVersions = mapOf(SERVICES_SEGMENT to NAVIGATION_MIN_VERSION),
    )

    assertEquals(listOf("true"), renderedText(host))
    assertEquals(1, navigation.requests.size)
    assertEquals("stay", navigation.requests.single().first)
    assertEquals("42", navigation.requests.single().second["id"]?.jsonPrimitive?.content)
    assertEquals(1, navigation.routeReads, "routes are read once at start, not per call")
  }

  @Test
  fun anUndeclaredRouteIsRefusedWithoutCrossingTheBoundary() {
    // A guest asking for a destination this client does not have should learn so locally. Sending
    // it anyway would mean every stale payload's dead button costs a boundary round trip.
    val navigation = FakeNavigation(setOf("stay"))
    val services = FakeServices(navigation = navigation)
    val guest = DogwoodGuest("main" to { _ -> Probe { it.navigate("reviews").toString() } })
    val host = startGuest(
      guest, "main", services = services,
      segmentVersions = mapOf(SERVICES_SEGMENT to NAVIGATION_MIN_VERSION),
    )

    assertEquals(listOf("false"), renderedText(host))
    assertEquals(emptyList(), navigation.requests)
  }

  @Test
  fun aHostThatDoesNotEnumerateItsRoutesIsTriedRatherThanRefused() {
    // An empty set is an absence of information, not a refusal. Reading it as "handles nothing"
    // would hide every navigating control on every host that resolves routes from a deep-link
    // table -- which is most of them.
    val navigation = FakeNavigation(emptySet())
    val services = FakeServices(navigation = navigation)
    val guest = DogwoodGuest("main" to { _ -> Probe { it.canNavigate("anything").toString() } })
    val host = startGuest(
      guest, "main", services = services,
      segmentVersions = mapOf(SERVICES_SEGMENT to NAVIGATION_MIN_VERSION),
    )

    assertEquals(listOf("true"), renderedText(host))
  }

  @Test
  fun aHostWithNoNavigationServiceAtAllIsAnOrdinaryBranch() {
    val services = FakeServices(navigation = null)
    val guest = DogwoodGuest(
      "main" to { _ -> Probe { "${it.canNavigate("stay")} ${it.navigate("stay")}" } },
    )
    val host = startGuest(
      guest, "main", services = services,
      segmentVersions = mapOf(SERVICES_SEGMENT to NAVIGATION_MIN_VERSION),
    )

    assertEquals(listOf("false false"), renderedText(host))
  }
}

class AsynchronousStateTest {

  @Test
  fun aWriteOutsideAnyHostCallAsksForAFrame() {
    var label by mutableStateOf("before")
    val (host, composition) = compose { Text(label) }
    val before = host.frameRequests

    // Exactly what a resumed service call does: write, with no host call in progress.
    label = "after"

    assertTrue(
      host.frameRequests > before,
      "the guest must ask for a frame; nothing else is going to, and the change would sit in the " +
        "composition unseen",
    )

    composition.frame(0L)
    val texts = host.decoded().flatMap { it.g }.filterIsInstance<PropertySet>()
      .map { it.v.toString().trim('"') }
    assertTrue("after" in texts, "and the change must then cross; got $texts")
  }

  @Test
  fun aBurstOfWritesAsksForOneFrameNotOnePerWrite() {
    var a by mutableStateOf(0)
    var b by mutableStateOf(0)
    val (host, composition) = compose { Text("$a/$b") }
    val before = host.frameRequests

    a = 1
    b = 1
    a = 2

    assertEquals(
      before + 1,
      host.frameRequests,
      "the observer fires per write, so an uncoalesced request would be a boundary crossing for " +
        "every field a guest touches",
    )
    composition.frame(0L)
  }
}

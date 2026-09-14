/*
 * Project Dogwood -- the Worker bridge, exercised without a Worker.
 *
 * `runInWorker` installs `onmessage` on `self`, which exists only inside a real Worker; everything
 * it does after that goes through [WorkerGuestBridge], which posts through a function it was
 * handed. These tests hand it a list, so the envelope handling that used to live in a sample file
 * -- and was therefore verified only by the browser conformance drill -- is asserted on Node in the
 * same run as every other guest test.
 *
 * What is pinned is the contract a page depends on rather than the code's shape: a configuration
 * starts the composition and a batch comes out; a request the guest does not understand is answered
 * with an error naming it rather than with silence; the `start` payload reaches the services; and a
 * state request answers on the correlation it was asked with.
 */
package dev.dogwood.compose

import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.WebStartPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

private data class Posted(val kind: String, val correlation: Int, val payload: String)

private fun bridge(
  posted: MutableList<Posted>,
  guest: DogwoodGuest = DogwoodGuest("main" to { _ -> Text("hello from the worker") }),
): WorkerGuestBridge = WorkerGuestBridge(
  guest = guest,
  services = ::defaultWorkerServices,
  post = { kind, correlation, payload -> posted += Posted(kind, correlation, payload) },
  fallbackEntryPoint = { "main" },
)

private val environment = Json.encodeToString(HostEnvironment.serializer(), HostEnvironment())

class WorkerGuestBridgeTest {

  @Test
  fun aConfigurationStartsTheCompositionAndABatchComesOut() {
    val posted = mutableListOf<Posted>()
    bridge(posted).handle(WorkerEnvelope.UPDATE_CONFIGURATION, 0, environment)

    val changes = posted.filter { it.kind == WorkerEnvelope.CHANGES }
    assertEquals(1, changes.size, "one composition, one batch; posted $posted")
    assertTrue("hello from the worker" in changes.single().payload, changes.single().payload)
  }

  @Test
  fun anUnknownMessageKindIsAnsweredWithAnErrorNamingIt() {
    val posted = mutableListOf<Posted>()
    bridge(posted).handle("teleport", 7, "")

    val error = posted.single()
    assertEquals(WorkerEnvelope.ERROR, error.kind)
    assertEquals(7, error.correlation, "the error answers on the request's own correlation")
    assertTrue("teleport" in error.payload, error.payload)
  }

  @Test
  fun theStartPayloadsFeatureFlagsReachTheServices() {
    val posted = mutableListOf<Posted>()
    var available: Set<String> = emptySet()
    val guest = DogwoodGuest("main" to { _ -> available = services().available; Text("probe") })
    val b = bridge(posted, guest)

    val start = WebStartPayload(entryPoint = "main", featureFlags = mapOf("beta" to "on"))
    b.handle(WorkerEnvelope.START, 0, Json.encodeToString(WebStartPayload.serializer(), start))
    b.handle(WorkerEnvelope.UPDATE_CONFIGURATION, 0, environment)

    assertTrue("featureFlags" in available, "featureFlags must be offered once start named some: $available")
    assertTrue("log" in available && "clock" in available, "and the Worker's own services alongside: $available")
  }

  @Test
  fun withoutFeatureFlagsTheServiceIsHonestlyAbsent() {
    val posted = mutableListOf<Posted>()
    var available: Set<String> = emptySet()
    val guest = DogwoodGuest("main" to { _ -> available = services().available; Text("probe") })
    bridge(posted, guest).handle(WorkerEnvelope.UPDATE_CONFIGURATION, 0, environment)

    assertTrue("featureFlags" !in available, "a page that sent no flags offers no flag service: $available")
  }

  @Test
  fun aSnapshotRequestAnswersOnItsOwnCorrelation() {
    val posted = mutableListOf<Posted>()
    val b = bridge(posted)
    b.handle(WorkerEnvelope.UPDATE_CONFIGURATION, 0, environment)
    b.handle(WorkerEnvelope.SNAPSHOT_STATE, 11, "")

    val result = posted.single { it.kind == WorkerEnvelope.RESULT }
    assertEquals(11, result.correlation)
  }

  @Test
  fun readyAnnouncesTheEnvelopeRevision() {
    val posted = mutableListOf<Posted>()
    bridge(posted).announce()
    assertEquals(Posted(WorkerEnvelope.READY, 0, WORKER_ENVELOPE_REVISION.toString()), posted.single())
  }
}

/*
 * Project Dogwood -- a crash in a guest effect reaches the host, with its stack.
 *
 * It did not, for as long as the composition existed. The scope had no exception handler, so a
 * `LaunchedEffect` that threw fell to the platform default and surfaced as ONE line in Zipline's
 * internal log -- minified type name, no frames, and `handleUncaughtException` never called. A
 * host cannot triage a crash it cannot observe, which made this the sharpest edge of the adoption
 * audit's A4: the payload ships without store review, so a bad one ships fast, and its crashes
 * were invisible by construction.
 *
 * Measured on the production pipeline before it was fixed (`plans/adoption-audit.md` A4), and
 * pinned here at the seam: the composition's scope must deliver an effect failure to the host's
 * one exception channel.
 */
package dev.dogwood.compose

import androidx.compose.runtime.LaunchedEffect
import dev.dogwood.protocol.DogwoodHost
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.WidgetTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A host that records rather than rethrows: rethrowing from the handler is the trap under test. */
private class CrashRecordingHost : DogwoodHost {
  val uncaught = mutableListOf<Throwable>()
  override fun sendChanges(positionalBatch: String) = Unit
  override fun requestFrame() = Unit
  override fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag) = Unit
  override fun onUnknownEventNode(id: Id, tag: EventTag) = Unit
  override fun handleUncaughtException(exception: Throwable) {
    uncaught += exception
  }
  override fun close() = Unit
}

class GuestCrashRoutingTest {

  @Test
  fun aCrashInAnEffectReachesTheHostChannel() {
    val host = CrashRecordingHost()
    val composition = DogwoodComposition(
      host = host,
      initialConfiguration = HostEnvironment(),
      segmentVersions = emptyMap(),
      restoredState = null,
      services = HostServices.None,
      launchParams = kotlinx.serialization.json.JsonNull,
      content = {
        LaunchedEffect(Unit) {
          error("CRASH-ROUTING-PROBE")
        }
      },
    )
    // Effects run on the frame after composition, which is why one frame is pumped rather than
    // the failure being expected synchronously.
    composition.frame(0L)

    assertEquals(1, host.uncaught.size, "the host was told ${host.uncaught.size} times")
    assertTrue(
      "CRASH-ROUTING-PROBE" in (host.uncaught.single().message ?: ""),
      "the wrong failure crossed: ${host.uncaught.single()}",
    )
    composition.dispose()
  }

  @Test
  fun aHealthyEffectReportsNothing() {
    // The control: a channel that fires without a crash teaches a team to ignore it.
    val host = CrashRecordingHost()
    val composition = DogwoodComposition(
      host = host,
      initialConfiguration = HostEnvironment(),
      segmentVersions = emptyMap(),
      restoredState = null,
      services = HostServices.None,
      launchParams = kotlinx.serialization.json.JsonNull,
      content = { LaunchedEffect(Unit) { /* completes */ } },
    )
    composition.frame(0L)
    assertEquals(emptyList(), host.uncaught.map { it.message })
    composition.dispose()
  }
}

/*
 * Project Dogwood -- shared scaffolding for the guest tests.
 *
 * Every test here asserts on what actually crossed the boundary rather than on an in-memory
 * structure, which is why [RecordingHost] captures encoded strings and [decoded] reads them back
 * through the same decoder the host uses. A test that inspected the recorder directly would pass
 * with a broken encoder.
 */
package dev.dogwood.compose

import androidx.compose.runtime.Composable
import dev.dogwood.protocol.ChangeBatch
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.DogwoodHost
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.StateSnapshot
import dev.dogwood.protocol.WidgetTag
import dev.dogwood.protocol.decodePositional
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Captures what crossed, so a test can assert on the traffic rather than on the screen. */
internal class RecordingHost : DogwoodHost {
  val batches = mutableListOf<String>()
  var frameRequests = 0

  override fun sendChanges(positionalBatch: String) {
    batches += positionalBatch
  }

  override fun requestFrame() {
    frameRequests++
  }

  override fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag) = Unit
  var unknownNodes = 0
    private set

  override fun onUnknownEventNode(id: Id, tag: EventTag) {
    unknownNodes++
  }
  override fun handleUncaughtException(exception: Throwable) = throw exception
  override fun close() = Unit
}

internal fun compose(
  configuration: HostEnvironment = HostEnvironment(),
  restoredState: StateSnapshot? = null,
  services: HostServices = HostServices.None,
  launchParams: JsonElement = JsonNull,
  content: @Composable () -> Unit,
): Pair<RecordingHost, DogwoodComposition> {
  val host = RecordingHost()
  val composition = DogwoodComposition(
    host = host,
    initialConfiguration = configuration,
    segmentVersions = emptyMap(),
    restoredState = restoredState,
    services = services,
    launchParams = launchParams,
    content = content,
  )
  return host to composition
}

/**
 * Reads the batches back through **the same decoder the host uses** -- literally the same
 * function, out of `dogwood-protocol`, not a copy of it.
 *
 * It used to be a copy, in a file next to this one, on the reasoning that a shared decoder was
 * worth building "when a third caller appears". The cost of that was invisible and total: a change
 * to the encoder plus a matching change to the copy left this suite green while the real host
 * decoder was wrong, and no test anywhere fed the real encoder's output to the real decoder. Every
 * assertion below now crosses the actual grammar.
 */
internal fun RecordingHost.decoded(): List<ChangeBatch> = batches.map { decodePositional(it) }

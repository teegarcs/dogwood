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
import dev.dogwood.protocol.DogwoodConfiguration
import dev.dogwood.protocol.DogwoodHost
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.StateSnapshot
import dev.dogwood.protocol.WidgetTag

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
  configuration: DogwoodConfiguration = DogwoodConfiguration(),
  restoredState: StateSnapshot? = null,
  content: @Composable () -> Unit,
): Pair<RecordingHost, DogwoodComposition> {
  val host = RecordingHost()
  val composition =
    DogwoodComposition(host, configuration, emptyMap(), restoredState, content)
  return host to composition
}

/**
 * Reads the batches back through the same decoder the host uses, so the test asserts on what
 * genuinely crossed rather than on an in-memory structure that never went through the encoder.
 */
internal fun RecordingHost.decoded(): List<ChangeBatch> = batches.map { decodeForTest(it) }

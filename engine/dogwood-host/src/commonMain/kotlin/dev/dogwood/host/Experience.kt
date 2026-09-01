/*
 * Project Dogwood -- the host driver.
 *
 * Owns the Zipline instance, the threading contract, the frame loop, and the event path.
 * specs/layer-4-sandbox.md, "The Frame Loop", is implemented here rather than described.
 */
package dev.dogwood.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import app.cash.zipline.Zipline
import dev.dogwood.protocol.DogwoodConfiguration
import dev.dogwood.protocol.DogwoodGuestUi
import dev.dogwood.protocol.DogwoodHost
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.StateSnapshot
import dev.dogwood.protocol.WidgetTag
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One mounted experience.
 *
 * **Threading contract.** Two dispatchers, and every crossing states which one it is on.
 * Guest work -- `start`, `sendEvent`, `frame` -- runs on the Zipline dispatcher, which is a
 * single thread. `sendChanges` is delivered to the user-interface dispatcher, because it
 * mutates the tree Compose is reading. Both sides assert rather than trust: see [checkUi] and
 * [checkZipline].
 */
class DogwoodExperience(
  private val zipline: Zipline,
  private val ziplineDispatcher: CoroutineDispatcher,
  private val uiScope: CoroutineScope,
  private val onGuestException: (Throwable) -> Unit = { throw it },
) {
  val tree = HostTree()

  /**
   * The threading contract.
   *
   * **This class must be constructed on the user-interface thread.** That is not arbitrary: the
   * very first batch arrives during [start], before any surface has composed, so binding the
   * user-interface thread lazily from a composable is too late. Construction is the earliest
   * moment that is reliably on the right thread, so it is where the binding happens.
   */
  val threads = DogwoodThreads().apply { bindUi() }

  private var guest: DogwoodGuestUi? = null

  /** Coalesces frame requests: the guest may ask many times before one frame is served. */
  private var frameScheduled = false

  private val hostServices = object : DogwoodHost {
    override fun sendChanges(positionalBatch: String) {
      threads.checkZipline()
      // Decoding is cheap enough to do on this thread -- 0.17 ms for a whole-screen batch,
      // measured in Phase 0 -- but applying it touches state Compose reads, so the apply is
      // posted to the user-interface dispatcher.
      val batch = decodePositional(positionalBatch)
      uiScope.launch {
        threads.checkUi()
        tree.apply(batch)
      }
    }

    override fun requestFrame() {
      threads.checkZipline()
      if (frameScheduled) return
      frameScheduled = true
      uiScope.launch {
        // `withFrameNanos` is the host's real display link: Choreographer on Android, the
        // window's frame clock on desktop. Compose Multiplatform provides it on every target,
        // which is why this code is common rather than per-platform.
        val nanos = withFrameNanos { it }
        frameScheduled = false
        uiScope.launch(ziplineDispatcher) {
          threads.checkZipline()
          guest?.frame(nanos)
        }
      }
    }

    override fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag) {
      unknownEvents += widgetTag.value to tag.value
    }

    override fun onUnknownEventNode(id: Id, tag: EventTag) {
      // Expected, not exceptional: the user tapped a node the guest removed on a frame the
      // host had not yet repainted. Telemetry, never a crash.
      staleEvents++
    }

    override fun handleUncaughtException(exception: Throwable) {
      onGuestException(exception)
    }

    override fun close() = Unit
  }

  /** Counted rather than thrown, so the slice can show that the path is exercised. */
  var staleEvents: Int = 0
    private set

  val unknownEvents = mutableSetOf<Pair<Int, Int>>()

  /**
   * Starts the guest. Must be called on the Zipline dispatcher.
   *
   * @param serviceName the name the guest bound its service under, which the manifest's
   *   `mainFunction` is responsible for having done.
   */
  fun start(
    serviceName: String = "dogwood.guest",
    configuration: DogwoodConfiguration = DogwoodConfiguration(),
    launchParams: JsonObject = JsonObject(emptyMap()),
    restoredState: StateSnapshot? = null,
  ) {
    threads.bindZipline()
    val service = zipline.take<DogwoodGuestUi>(serviceName)
    guest = service
    service.start(
      host = hostServices,
      configuration = configuration,
      launchParams = launchParams,
      segmentVersions = DogwoodDictionary.segmentVersions,
      restoredState = restoredState,
    )
  }

  /** Delivers one interaction, stamped with the batch the host had applied when it happened. */
  fun send(node: WidgetView, tag: EventTag, args: List<JsonElement> = emptyList()) {
    val sequence = tree.appliedSequence
    uiScope.launch(ziplineDispatcher) {
      threads.checkZipline()
      guest?.sendEvent(Event(i = node.id, e = tag, q = sequence, a = args))
    }
  }

  /**
   * Captures the guest's saveable state. Must be called on the Zipline dispatcher, and only
   * makes sense immediately before teardown.
   */
  fun snapshotState(): StateSnapshot {
    threads.checkZipline()
    return guest?.snapshotState() ?: StateSnapshot()
  }

  fun configurationChanged(configuration: DogwoodConfiguration) {
    uiScope.launch(ziplineDispatcher) { guest?.updateConfiguration(configuration) }
  }

  fun close() {
    guest?.close()
    guest = null
    zipline.close()
  }
}

/**
 * Renders a mounted experience.
 *
 * The root is node zero with slot one, exactly as the protocol's worked example describes, so
 * this walks the mirror from there and lets Compose do the rest. Nothing here knows what any
 * widget means; that is entirely the dictionary's business.
 */
@Composable
fun DogwoodSurface(experience: DogwoodExperience, modifier: Modifier = Modifier) {
  val sink = EventSink { node, tag, args -> experience.send(node, tag, args) }
  // One evaluator per experience: its cache holds host objects built from this guest's recipes,
  // so its lifetime is this guest's.
  val evaluator = androidx.compose.runtime.remember(experience) { ExpressionEvaluator() }
  androidx.compose.runtime.CompositionLocalProvider(LocalExpressionEvaluator provides evaluator) {
    androidx.compose.foundation.layout.Column(modifier) {
      RenderChildren(experience.tree.root, slot = 1, scope = LayoutScope(column = this), events = sink)
    }
  }
}

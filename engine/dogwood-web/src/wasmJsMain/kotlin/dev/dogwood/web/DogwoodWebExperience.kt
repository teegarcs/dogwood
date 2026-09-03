/*
 * Project Dogwood -- one guest, running.
 *
 * This is the web reading of `dogwood-host`'s `DogwoodExperience`: it owns the mirror of the guest
 * tree, it is what the bridge reports to, and it is what the page composes. Everything
 * asynchronous about the Worker boundary is contained here, so that the bindings above it read an
 * ordinary tree and the page below it calls one composable.
 *
 * ```mermaid
 * sequenceDiagram
 *   participant Page as Page (main thread)
 *   participant Del as WebDelivery
 *   participant Exp as DogwoodWebExperience
 *   participant Br as WorkerBridge
 *   participant W as Guest (Web Worker)
 *   Page->>Del: start(manifestUrl)
 *   Del->>Del: fetch dogwood-manifest.json
 *   Del->>Del: compare segment versions
 *   Note over Del: refuses here, before any guest code runs
 *   Del->>W: new Worker(guestScript)
 *   W-->>Br: {t:"ready", p:"1"}
 *   Br->>Exp: onReady()
 *   Exp->>Br: updateConfiguration(HostEnvironment)
 *   Br->>W: {t:"configuration", p:"{...}"}
 *   W-->>Br: {t:"changes", p:"[1,[...]]"}
 *   Br->>Exp: onChanges(batch)
 *   Exp->>Exp: FastPositionalDecoder -> WebTree.apply
 *   Exp-->>Page: snapshot invalidation, Compose recomposes
 *   Page->>Exp: a tap on a bound Row
 *   Exp->>Br: sendEvent(Event(id, tag, appliedSequence))
 *   Br->>W: {t:"event", p:"{...}"}
 * ```
 *
 * **Every node in that diagram is a type in this module** except the guest, which is JavaScript
 * this host never links: `WebDelivery` fetches and checks, `WorkerBridge` is the envelope,
 * `DogwoodWebExperience` is here, `WebTree` is the mirror, and `FastPositionalDecoder` is the read.
 */
package dev.dogwood.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import dev.dogwood.protocol.DogwoodJson
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.ProtocolMismatch
import dev.dogwood.protocol.StateSnapshot
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonElement

/** The single content slot the root node exposes, matching the layout tier's containers. */
private const val ROOT_CONTENT = 1

/**
 * Everything the page wants to know that is not a pixel.
 *
 * Reported through one callback rather than several, because these are the events an operator
 * cares about and a page that wired up three of four would be missing exactly the one that matters
 * -- a guest that failed is invisible on screen, because a failed guest sends no changes and the
 * last good tree keeps drawing.
 */
fun interface ExperienceReporter {
  /**
   * `operator` so that a reporter reads as a call at every use site.
   *
   * Without it the field would have to be invoked as `report.report(...)`, and the first thing
   * anybody would do is wrap it in a private helper -- which is the same function with one more
   * name.
   */
  operator fun invoke(line: String)
}

/**
 * One guest, from the first batch to the last.
 *
 * [environment] is a value rather than a live source because the page owns the window and knows
 * when it changed; [updateEnvironment] is how it says so.
 */
class DogwoodWebExperience(
  environment: HostEnvironment,
  private val report: ExperienceReporter,
  /**
   * Records what the bindings composed.
   *
   * Null in a shipping page. Non-null in the verification harness, which needs to distinguish
   * "Compose Multiplatform drew" from "Compose Multiplatform drew the guest's tree".
   */
  private val transcript: RenderTranscript? = null,
) : WorkerBridgeListener {

  val tree = WebTree()

  private val decoder = FastPositionalDecoder()

  private var bridge: WorkerBridge? = null

  private var environment: HostEnvironment = environment

  /** Guards against sending the environment twice when readiness and attachment race. */
  private var configurationSent = false

  /**
   * Frame requests waiting for the host's display.
   *
   * A channel rather than a list because the consumer is a coroutine inside the composition with
   * access to Compose's `MonotonicFrameClock`, and that is the only place `withFrameNanos` means
   * anything. Unlimited capacity: a guest that asked for more frames than the display produced
   * must not block its own Worker.
   */
  private val frameRequests = Channel<Int>(Channel.UNLIMITED)

  /** How many batches have been applied, which the harness reads to know something happened. */
  var appliedBatches by mutableStateOf(0)
    private set

  /**
   * Binds a bridge to this experience.
   *
   * Separate from construction because the bridge does not exist until [WebDelivery] has finished
   * the dictionary check, and the experience must exist before then so a refusal has something to
   * report through.
   */
  fun attach(bridge: WorkerBridge) {
    this.bridge = bridge
    if (bridge.isReady) sendConfiguration()
  }

  fun updateEnvironment(environment: HostEnvironment) {
    this.environment = environment
    bridge?.takeIf { it.isReady }?.updateConfiguration(environment)
  }

  /**
   * Asks the guest for its saveable state.
   *
   * Asynchronous, unavoidably. On mobile `snapshotState()` returns a value and `DogwoodSession`
   * and the shell's eviction path call it synchronously; across a Worker it cannot, and blocking
   * the main thread to fake it would reintroduce the exact freeze the Worker was chosen to
   * prevent.
   */
  fun snapshotState(onResult: (StateSnapshot) -> Unit) {
    val bridge = this.bridge
    if (bridge == null) {
      report("snapshotState called before a guest was attached")
      onResult(StateSnapshot())
      return
    }
    bridge.snapshotState(onResult) { failure ->
      report("snapshotState failed: $failure")
      onResult(StateSnapshot())
    }
  }

  fun close() {
    bridge?.close()
    bridge = null
  }

  // ---------------------------------------------------------------------------------------------
  // WorkerBridgeListener.
  // ---------------------------------------------------------------------------------------------

  override fun onReady() {
    report("guest ready")
    sendConfiguration()
  }

  override fun onChanges(batch: String) {
    val decoded = try {
      decoder.decode(batch)
    } catch (mismatch: ProtocolMismatch) {
      // Contained, not fatal. A batch this client cannot decode is skew, and the last tree the
      // host successfully applied keeps drawing -- the same shape the delivery layer settled on
      // for a manifest that fails verification.
      report("rejected a batch: ${mismatch.message}")
      return
    } catch (failure: Throwable) {
      report("failed to decode a batch: $failure")
      return
    }
    try {
      tree.apply(decoded)
    } catch (failure: Throwable) {
      report("failed to apply batch ${decoded.q}: ${failure.message}")
      return
    }
    appliedBatches++
    if (tree.unknownWidgetTags.isNotEmpty()) {
      report("unknown widget tags: ${tree.unknownWidgetTags.sorted()}")
    }
  }

  override fun onFrameRequested(correlation: Int) {
    frameRequests.trySend(correlation)
  }

  override fun onGuestError(correlation: Int, message: String) {
    report(if (correlation == 0) "guest error: $message" else "guest error ($correlation): $message")
  }

  private fun sendConfiguration() {
    if (configurationSent) return
    val bridge = this.bridge ?: return
    configurationSent = true
    bridge.updateConfiguration(environment)
  }

  // ---------------------------------------------------------------------------------------------
  // The composition.
  // ---------------------------------------------------------------------------------------------

  /**
   * Draws the guest's tree.
   *
   * The root node carries widget tag 0, which is not a widget: it is the anchor the guest adds its
   * top-level children to. So the root's content slot is rendered directly rather than dispatched,
   * and a `Box` filling the viewport gives the tree somewhere to be.
   */
  @Composable
  fun Content() {
    // The frame pump. Every request the guest made is answered on a real display frame, in order.
    LaunchedEffect(this) {
      for (correlation in frameRequests) {
        withFrameNanos { nanos -> bridge?.deliverFrame(correlation, nanos) }
      }
    }
    CompositionLocalProvider(LocalRenderTranscript provides transcript) {
      Box(Modifier.fillMaxSize()) {
        RenderChildren(tree.root, ROOT_CONTENT, LayoutScope(), events)
      }
    }
  }

  /**
   * Turns a binding's event into the wire form the guest expects.
   *
   * The sequence number carried is [WebTree.appliedSequence] -- the last batch the host actually
   * applied, not the last one it received -- which is what lets the guest drop an event aimed at a
   * tree it has already replaced. That window is wider here than on mobile, because the event has
   * a Worker hop to make.
   */
  private val events = EventSink { node, tag, args -> dispatchEvent(node, tag, args) }

  /**
   * Sends one interaction to the guest.
   *
   * Public because a host shell has interactions of its own that no binding produces -- a system
   * back gesture, a deep link, a lifecycle transition -- and because a harness has to be able to
   * exercise this path without synthesising a pointer event onto a WebGL canvas.
   */
  fun dispatchEvent(node: WidgetView, tag: EventTag, args: List<JsonElement> = emptyList()) {
    val bridge = this.bridge
    if (bridge == null) {
      report("dropped an event from node ${node.id.value}: no guest attached")
      return
    }
    bridge.sendEvent(DogwoodJson.encodeToString(Event(node.id, tag, tree.appliedSequence, args)))
  }
}

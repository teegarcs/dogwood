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
 *   Exp->>Exp: FastPositionalDecoder -> HostTree.apply
 *   Exp-->>Page: snapshot invalidation, Compose recomposes
 *   Page->>Exp: a tap on a bound Row
 *   Exp->>Br: sendEvent(Event(id, tag, appliedSequence))
 *   Br->>W: {t:"event", p:"{...}"}
 * ```
 *
 * **Every node in that diagram is a type in this module** except the guest, which is JavaScript
 * this host never links: `WebDelivery` fetches and checks, `WorkerBridge` is the envelope,
 * `DogwoodWebExperience` is here, `HostTree` is the mirror, and `FastPositionalDecoder` is the read.
 */
package dev.dogwood.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import dev.dogwood.protocol.DogwoodJson
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.WebAnalyticsEvent
import dev.dogwood.protocol.WebNavigationRequest
import dev.dogwood.protocol.WebStartPayload
import dev.dogwood.protocol.ProtocolMismatch
import dev.dogwood.protocol.StateSnapshot
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonElement
import dev.dogwood.host.WidgetView
import dev.dogwood.host.RenderTranscript
import dev.dogwood.host.RenderChildren
import dev.dogwood.host.ExpressionEvaluator
import dev.dogwood.host.LocalExpressionEvaluator
import dev.dogwood.host.LocalGuestGeneration
import dev.dogwood.host.LocalSkewReport
import dev.dogwood.host.LocalRenderTranscript
import dev.dogwood.host.LayoutScope
import dev.dogwood.host.HostTree
import dev.dogwood.host.EventSink
import dev.dogwood.host.DogwoodLeakWatcher
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

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
  /**
   * Watches detached nodes, when a host asks for it.
   *
   * Off by default, like every other host: watching costs a weak reference per detached node and
   * a timer, and a page that is not investigating a leak should not pay for one.
   * `BrowserLeakWatcher` is what a page that *is* investigating passes here.
   */
  leakWatcher: DogwoodLeakWatcher = DogwoodLeakWatcher.None,
  /**
   * What this page tells the guest about itself: flags, routes, launch parameters, the entry
   * point, and the dictionary versions this client implements.
   *
   * Defaulted to an empty declaration so an existing page keeps working, and an empty declaration
   * is honest rather than convenient -- it says "no flags, no routes, no parameters, and no
   * versions reported", which is exactly what this profile did before there was anything to say
   * it with. `WebStartPayload` documents each field.
   */
  services: WebStartPayload = WebStartPayload(),
  /**
   * Where a guest's analytics events go. Dropped by default, and dropping is a decision the page
   * takes rather than one this class takes for it.
   */
  private val onAnalytics: (WebAnalyticsEvent) -> Unit = {},
  /**
   * What to do when the guest asks to navigate.
   *
   * Returns whether the host handled it, so an unhandled route is recorded as skew here rather
   * than disappearing. The guest is never told either way -- navigation is a request on every
   * platform, and a guest that could observe the answer would start depending on it.
   */
  private val onNavigate: (WebNavigationRequest) -> Boolean = { false },
  /**
   * Told when a release has actually **worked**, which is not the same as loaded.
   *
   * `WebDelivery` records the attempt before the Worker exists; only this class can see the other
   * end — a guest that started, composed, and had its tree applied. A payload that throws on its
   * first composition has loaded, so reporting success at load time would forgive exactly the
   * release the guard exists to catch (ADR-049).
   */
  private val onReleaseSucceeded: () -> Unit = {},
) : WorkerBridgeListener {

  val tree = HostTree(leakDetector = leakWatcher)

  private val decoder = FastPositionalDecoder()

  private var bridge: WorkerBridge? = null

  private var environment: HostEnvironment = environment

  /** Guards against sending the environment twice when readiness and attachment race. */
  /**
   * Whether *this* bridge has been configured.
   *
   * Held against the bridge rather than as a bare flag, because the flag never reset: a host that
   * tore down a crashed guest and attached a fresh one found the replacement's READY arriving,
   * `sendConfiguration` returning at the latch, and the new guest never learning its viewport --
   * composing nothing, forever.
   */
  private var configuredBridge: WorkerBridge? = null

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
    /*
     * The correctness gate runs here, in the module, and refuses.
     *
     * It used to be called from the sample's `main()` and nowhere else, so every consumer of this
     * module had to know to run it. A second product linking `dogwood-web` with Kotlin's stock
     * `wasm-opt` pass list would decode every batch as empty and render a blank screen with no
     * diagnostic whatsoever -- which is precisely the failure the gate exists to prevent, reached
     * by forgetting a step nobody was told about. Enforcement belongs where it cannot be
     * forgotten; the sample keeps its own early call for a louder, earlier message.
     */
    val gate = gateResult ?: BulkCopyGate.check().also { gateResult = it }
    if (gate is GateResult.Failed) {
      report(
        "refusing to render: this build's WebAssembly is miscompiled (${'$'}{gate.reason}). " +
          "Remove --gufa from the wasm-opt pass list; see ADR-032.",
      )
      bridge.close()
      return
    }
    this.bridge = bridge
    // A new bridge is a new guest, which is the only thing that makes a generation change.
    guestGeneration = Any()
    if (bridge.isReady) sendConfiguration()
  }

  /**
   * Identity that changes exactly when a guest is replaced, and never otherwise.
   *
   * Snapshot state rather than a plain field, because the effects that key on it are inside the
   * composition: a generation nothing recomposes on is a generation nothing observes.
   */
  private var guestGeneration by mutableStateOf<Any>(Any())

  /**
   * What this host declares to the guest, which a code update rewrites once.
   *
   * A `var` for exactly one reason: [update] carries the previous guest's state into the next
   * one's start message, and there is no other route between two Workers.
   */
  private var services: WebStartPayload = services

  /**
   * Replaces the running guest with [next], carrying its state across.
   *
   * **This is a code update, which on this architecture is the normal case rather than an
   * exceptional one.** A publish lands while a screen is open; the user is mid-form; nothing about
   * it should feel like a crash.
   *
   * The order is the whole of the design, and each step is doing something:
   *
   *   1. **Snapshot the old guest first.** It is the only thing that knows its own
   *      `rememberSaveable` values, and in a moment it will not exist -- a new Worker is a new
   *      module with a new composition, so nothing survives implicitly.
   *   2. **Close the old bridge**, so a batch still in flight from a guest being replaced cannot
   *      land on a tree that now belongs to its successor.
   *   3. **Clear the tree.** What arrives next is a whole tree rather than a patch -- the new guest
   *      composes from nothing and its sequence numbering starts again -- so applying it onto the
   *      old one would duplicate every node. The screen is blank for one round trip, which is
   *      honest: the host genuinely does not know what should be on it.
   *   4. **Attach**, which bumps the generation and sends the start message carrying the snapshot.
   *
   * Suspending because step 1 is a correlated request across a Worker boundary and there is no
   * synchronous way to ask.
   */
  suspend fun update(next: WorkerBridge) {
    val previous = bridge
    val carried = if (previous == null) {
      null
    } else {
      suspendCoroutine<StateSnapshot?> { continuation ->
        previous.snapshotState(
          onResult = { continuation.resume(it) },
          onFailure = { failure ->
            // Answered as well as reported: a code update that hangs waiting for a snapshot is
            // worse than one that loses state, because the screen never comes back at all.
            report("snapshotState failed during a code update: $failure")
            continuation.resume(null)
          },
        )
      }
    }
    previous?.close()
    bridge = null
    configuredBridge = null
    tree.clear()
    services = services.copy(restoredState = carried)
    // The next guest is a new release, so its success is a fresh claim. `appliedBatches` is reset
    // by the tree's own clear() above only in the sense that the tree is empty; the counter is
    // this class's, so it is reset here -- otherwise the replacement's first batch would not be
    // its first, and a code update could never report success.
    appliedBatches = 0
    report("code update: carrying ${carried?.values?.size ?: 0} saved keys into the next guest")
    attach(next)
  }

  /** Checked once per page, not once per attach. */
  private var gateResult: GateResult? = null

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
    bridge.snapshotState(
      onResult = onResult,
      onFailure = { failure ->
        // Reported AND answered: a caller that waits forever leaks the experience, so the empty
        // snapshot is the least-bad answer -- but it is only ever produced here, where the
        // failure is on the record, never silently inside the decode.
        report("snapshotState failed: $failure")
        onResult(StateSnapshot())
      },
    )
  }

  fun close() {
    bridge?.close()
    bridge = null
    // Cleared with the bridge. Leaving the tree populated meant a re-attach rendered the previous
    // guest's screen until the new one produced its first batch, and the configuration flag was
    // still set against a bridge that no longer existed.
    configuredBridge = null
    tree.clear()
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
    } catch (mismatch: ProtocolMismatch) {
      // Rejected whole, and nothing of it applied: `HostTree` validates before it mutates, so the
      // tree on screen is the last one that applied cleanly rather than a partial of this one.
      report("rejected batch ${decoded.q}: ${mismatch.message}")
      return
    } catch (failure: Throwable) {
      report("failed to apply batch ${decoded.q}: ${failure.message}")
      return
    }
    appliedBatches++
    // The first applied batch is the release working: a guest started, produced a tree, and the
    // host mounted it. Once per attachment, not once per batch.
    if (appliedBatches == 1) onReleaseSucceeded()
    if (tree.skew.unknownWidgetTags.isNotEmpty()) {
      report("unknown widget tags: ${tree.skew.unknownWidgetTags.sorted()}")
    }
  }

  override fun onFrameRequested(correlation: Int) {
    frameRequests.trySend(correlation)
  }

  override fun onGuestError(correlation: Int, message: String) {
    report(if (correlation == 0) "guest error: $message" else "guest error ($correlation): $message")
  }

  override fun onGuestFailure(correlation: Int, message: String, stack: String?) {
    onGuestError(correlation, message)
    // Reported as its own line rather than appended to the message, because a host that shows one
    // line of a failure should show the sentence, and a host collecting them should be able to send
    // the frames somewhere else. Joining them would make both harder.
    if (stack != null) report("guest stack: $stack")
  }

  private fun sendConfiguration() {
    if (configuredBridge === bridge) return
    val bridge = this.bridge ?: return
    configuredBridge = bridge
    // Before the configuration, always: the configuration is what starts the composition, and a
    // guest that composed first would compose without its launch parameters.
    bridge.start(services)
    bridge.updateConfiguration(environment)
  }

  override fun onAnalytics(event: WebAnalyticsEvent) {
    onAnalytics.invoke(event)
  }

  override fun onNavigate(request: WebNavigationRequest) {
    // Recorded as skew when nothing handled it, for the reason every other unknown name here is:
    // the visible symptom is a control that does nothing, and a control that does nothing is
    // indistinguishable from a slow one until somebody reads a report.
    if (!onNavigate.invoke(request)) {
      tree.skew.unknownRoutes += request.route
      report("no host route for '${request.route}'")
    }
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
    /*
     * The same three locals `DogwoodTree` provides on the mobile hosts, and this client went
     * without all three until the skew drill ran against it.
     *
     * The bindings are shared code (Layer 5 ADR-041 stopped the web keeping its own), and they
     * read what they need through composition locals. Every one of those locals has a *default*,
     * so a host that provides none of them renders perfectly and is wrong in three quiet ways:
     *
     *  1. **The skew report is an orphan.** Everything a binding records -- a withheld control, an
     *     unresolved colour token, a clamped value -- landed in the throwaway `SkewReport()` the
     *     composition local defaults to, which nothing reads. `tools/skew-drill/run-web.sh` found
     *     this on its first run: the client withheld a control carrying an unreadable
     *     affordance-bearing property, exactly as it should, and reported nothing. Only the
     *     unknown *widget tag* showed, because `HostTree.apply` writes that one straight onto
     *     [tree] rather than through the local.
     *  2. **The expression cache was not tied to a guest.** A default `ExpressionEvaluator` is
     *     shared and keyed to nothing, so host objects built from one guest's recipes would
     *     outlive it.
     *  3. **Live-state mirrors had no generation to key on.** They report on change, so a
     *     replacement guest -- which starts knowing nothing -- would never be told what it is
     *     looking at. See `LocalGuestGeneration`.
     */
    val evaluator = remember(tree) { ExpressionEvaluator(tree.skew) }
    CompositionLocalProvider(
      LocalRenderTranscript provides transcript,
      LocalSkewReport provides tree.skew,
      LocalExpressionEvaluator provides evaluator,
      LocalGuestGeneration provides guestGeneration,
    ) {
      Box(Modifier.fillMaxSize()) {
        RenderChildren(tree.root, ROOT_CONTENT, LayoutScope(), events)
      }
    }
  }

  /**
   * Turns a binding's event into the wire form the guest expects.
   *
   * The sequence number carried is [HostTree.appliedSequence] -- the last batch the host actually
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

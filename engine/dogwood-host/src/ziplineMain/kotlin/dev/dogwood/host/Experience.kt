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
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.DogwoodGuestUi
import dev.dogwood.protocol.DogwoodHost
import dev.dogwood.protocol.DogwoodServices
import dev.dogwood.protocol.Event
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.StateSnapshot
import dev.dogwood.protocol.WidgetTag
import dev.dogwood.protocol.decodePositional
import dev.dogwood.protocol.ProtocolMismatch
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
  /** Off by default. See `Leaks.kt` for what is worth watching in a host, and why. */
  leakDetector: DogwoodLeakWatcher = DogwoodLeakWatcher.None,
) {
  /**
   * Everything this client failed to recognise while rendering this guest.
   *
   * One report per experience, because skew is a property of the pairing between a payload and a
   * client, not of the client alone. See `Skew.kt`. Declared before [tree] because the tree
   * reports into it.
   */
  val skew = SkewReport()

  val tree = HostTree(leakDetector, skew)

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

  /**
   * Whether a resynchronisation is already in flight, so a failing one cannot loop.
   *
   * A rejected batch asks the guest to re-send the whole tree. If *that* is rejected too the fault
   * is in the protocol rather than in the divergence, and asking again would produce an endless
   * cycle of full re-sends -- the most expensive thing this boundary can do -- while the screen
   * stays broken either way. One attempt, then report and stop.
   */
  private var resynchronising = false

  /**
   * Clears the tree and asks the guest to send the whole thing again.
   *
   * Containment leaves the screen intact but the host's tree older than the guest believes, and
   * every later change is a diff against a tree that no longer exists on the other side
   * ([Layer 4 ADR-011](../../../adrs/layer-4/ADR-011-a-batch-applies-whole-or-not-at-all.md) §4).
   * This is the repair that record said was not built.
   *
   * **The tree is cleared first, on this thread, before the request crosses.** What comes back is
   * a complete tree rather than a patch, so applying it onto the old one would duplicate every
   * node. Clearing here rather than on arrival also means the screen goes blank for the width of
   * one round trip, which is honest: the host genuinely does not know what should be on it.
   *
   * Call from the user-interface thread.
   */
  private fun requestResynchronisation(because: String) {
    threads.checkUi()
    if (resynchronising) {
      // The re-send itself failed. Reported rather than retried -- see `resynchronising`.
      skew.rejectedBatches += "resynchronisation failed as well ($because); the tree is stale"
      return
    }
    resynchronising = true
    skew.rejectedBatches += "resynchronising: $because"
    tree.clear()
    uiScope.launch(ziplineDispatcher) {
      threads.checkZipline()
      guest?.resynchronise()
    }
  }

  /** Coalesces frame requests: the guest may ask many times before one frame is served. */
  private var frameScheduled = false

  private val hostServices = object : DogwoodHost {
    override fun sendChanges(positionalBatch: String) {
      threads.checkZipline()
      // Decoding is cheap enough to do on this thread -- 0.17 ms for a whole-screen batch,
      // measured in Phase 0 -- but applying it touches state Compose reads, so the apply is
      // posted to the user-interface dispatcher.
      //
      // A batch whose grammar this client does not share is skew, and is contained the way every
      // other kind of skew is: reported, and survivable. It is contained by rejecting the batch
      // WHOLE and keeping the tree that is already on screen -- the same shape the delivery layer
      // uses when a manifest fails verification, and for the same reason. Applying the prefix of a
      // batch would leave a tree the guest never composed, and every later batch would compound
      // against it.
      val batch = try {
        decodePositional(positionalBatch)
      } catch (mismatch: ProtocolMismatch) {
        // Posted rather than written here: the report is read on the user-interface thread while
        // this runs on the Zipline thread.
        uiScope.launch {
          skew.rejectedBatches += mismatch.message ?: "undecodable batch"
          requestResynchronisation("a batch could not be decoded")
        }
        return
      }
      uiScope.launch {
        threads.checkUi()
        // The apply is all-or-nothing too, not only the decode. A batch that refers to a node it
        // never created, or removes past the end of a slot, is rejected whole and the tree already
        // on screen keeps drawing -- rather than being left half-applied, which is a tree the guest
        // never composed and every later diff would compound against. See `BatchValidation.kt`.
        try {
          tree.apply(batch)
          // A batch that applied is the evidence the repair worked; without clearing this, one
          // rejection would leave the guard set for the life of the experience and the next
          // genuine divergence would go unrepaired.
          resynchronising = false
        } catch (mismatch: ProtocolMismatch) {
          skew.rejectedBatches += "batch ${batch.q}: ${mismatch.message}"
          requestResynchronisation("batch ${batch.q} was rejected")
        }
      }
    }

    override fun requestFrame() {
      threads.checkZipline()
      frameRequests++
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

  /**
   * How many times the guest has asked for a frame.
   *
   * This is the number that decides whether keeping an experience warm but hidden is free. The
   * frame clock this experience awaits belongs to the *window*, not to any surface, so a hidden
   * guest that keeps asking keeps waking the Zipline thread whether or not anything it draws is
   * on screen. "It is hidden, so it must be idle" is an assumption; this counter is the evidence,
   * and a warm experience whose count climbs while it is off-screen is a bug in the idle story
   * rather than an overhead to tolerate.
   */
  var frameRequests: Int = 0
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
    /** Which of the payload's experiences to run. Unknown names fail loudly, not blankly. */
    entryPoint: String = "main",
    /** What this client lets the guest reach. The default offers nothing at all. */
    services: DogwoodServices = DogwoodServiceHost(),
    configuration: HostEnvironment = HostEnvironment(),
    launchParams: JsonObject = JsonObject(emptyMap()),
    restoredState: StateSnapshot? = null,
  ) {
    threads.bindZipline()
    val service = zipline.take<DogwoodGuestUi>(serviceName)
    guest = service
    service.start(
      host = hostServices,
      services = services,
      entryPoint = entryPoint,
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

  /**
   * Pushes a new host environment into the running guest.
   *
   * Called from the user-interface thread -- it is derived from composition -- and hops to the
   * Zipline dispatcher, because the guest is single-threaded and has no lock. Equal
   * configurations are the caller's problem to suppress, and
   * [rememberHostEnvironment] does exactly that; the guest also dedupes structurally,
   * since the value backing it is snapshot state.
   */
  fun updateConfiguration(configuration: HostEnvironment) {
    uiScope.launch(ziplineDispatcher) {
      // Before the guest exists there is no thread binding to check against, and no one to tell.
      // Ordering the null check first keeps a configuration that arrives during startup from
      // failing the assertion instead of being harmlessly dropped.
      val target = guest ?: return@launch
      threads.checkZipline()
      target.updateConfiguration(configuration)
    }
  }

  /**
   * Tears the guest down. **Must be called on the Zipline dispatcher.**
   *
   * `guest.close()` and `zipline.close()` are calls into the interpreter, and the interpreter is
   * single-threaded: the same rule that governs `frame` and `sendEvent` governs teardown. It was
   * called from the user-interface thread by the shell's eviction path, which the Java Virtual
   * Machine tolerated and Kotlin/Native does not -- the iOS host aborted the moment the warm cap
   * first evicted anything. Tolerated is not the same as correct; the JVM was getting away with a
   * data race on the guest heap.
   */
  fun close() {
    threads.checkZipline()
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
  DogwoodTree(experience.tree, sink, modifier, evaluatorKey = experience, skew = experience.skew)
}

/**
 * Renders a host tree, with no Zipline instance in sight.
 *
 * Split out from [DogwoodSurface] because the tree is the renderable thing and the experience is
 * only where this one came from. A host that gets its tree some other way -- a test, a preview, or
 * the Web profile, where the guest loads into the browser's own engine rather than through
 * `ZiplineLoader` -- renders it here without pretending to have a QuickJS instance.
 *
 * @param evaluatorKey what the expression cache's lifetime is tied to. The cache holds host
 *   objects built from one guest's recipes, so a new guest must not inherit the old one's.
 */
@Composable
fun DogwoodTree(
  tree: HostTree,
  events: EventSink,
  modifier: Modifier = Modifier,
  evaluatorKey: Any? = tree,
  skew: SkewReport = androidx.compose.runtime.remember(evaluatorKey) { SkewReport() },
) {
  val evaluator = androidx.compose.runtime.remember(evaluatorKey, skew) { ExpressionEvaluator(skew) }
  androidx.compose.runtime.CompositionLocalProvider(
    LocalExpressionEvaluator provides evaluator,
    LocalSkewReport provides skew,
    // The same key the expression cache uses, for the same reason: it identifies one guest. A
    // mirror that reports on change needs to know when the thing it reports *to* was replaced.
    LocalGuestGeneration provides (evaluatorKey ?: tree),
  ) {
    androidx.compose.foundation.layout.Column(modifier) {
      RenderChildren(tree.root, slot = 1, scope = LayoutScope(column = this), events = events)
    }
  }
}

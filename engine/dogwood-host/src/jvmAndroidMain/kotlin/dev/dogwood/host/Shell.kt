/*
 * Project Dogwood -- the host shell: several experiences, one of them on screen.
 *
 * Path B -- an experience per surface -- is the right shape when a second team, a second release
 * cadence, or a hard isolation requirement is real. What made it expensive was that leaving a
 * surface destroyed its runtime: coming back meant a fresh QuickJS instance, a fresh composition,
 * and the delivery layer stood up again. Phase 0 measured ~127 ms p50 to first composition on a
 * development machine, and a device is slower.
 *
 * The shell keeps experiences **warm**. An inactive one stays alive and simply stops being
 * composed, so switching back is a composition change and nothing else. That costs nothing at
 * runtime because of a property the architecture already had: a guest asks for a frame only when
 * something is waiting for one (`BroadcastFrameClock`'s `onNewAwaiters`), so an idle experience
 * nobody is composing produces no frames and no traffic at all.
 *
 * Memory is the price, and it is bounded rather than hoped about. Beyond the cap -- or on a
 * memory warning -- the least recently used experience is **snapshotted and closed**, and coming
 * back to it is a cold start that restores. That is the machinery a code update already uses,
 * pointed at a different reason for the guest having gone away: the user finds their scroll
 * position and half-typed text either way, and only the latency differs.
 *
 * One delivery is shared by every session here. The sample's tab switch used to build a second
 * one, which meant a second `ZiplineCache` open on the same directory and the previous session
 * never closed -- a leaked interpreter per switch. See `plans/experience-composition.md`.
 */
package dev.dogwood.host

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import dev.dogwood.protocol.DogwoodServices
import dev.dogwood.protocol.HostEnvironment
import dev.dogwood.protocol.StateSnapshot

/** What the shell knows about one entry point. */
private class ShellEntry(
  val launchParams: JsonObject,
  var session: DogwoodSession? = null,
  var job: Job? = null,
  /** Kept when the session is evicted, handed back when it returns. */
  var snapshot: StateSnapshot? = null,
)

/**
 * Holds several experiences, one active, and switches between them without a cold start.
 *
 * @param capacity how many experiences may be warm at once, the active one included. Each is a
 *   whole interpreter, so this is a memory decision; see [WarmPool].
 */
class DogwoodShell(
  private val delivery: DogwoodDelivery,
  private val applicationName: String,
  private val manifestUrl: String,
  private val ziplineDispatcher: CoroutineDispatcher,
  private val uiScope: CoroutineScope,
  private var environment: HostEnvironment = HostEnvironment(),
  private val services: DogwoodServices = DogwoodServiceHost(),
  private val leakDetector: DogwoodLeakWatcher = DogwoodLeakWatcher.None,
  capacity: Int = 3,
  private val onSwap: (String, SessionStatus) -> Unit = { _, _ -> },
  private val onFailure: (String, Exception) -> Unit = { _, _ -> },
  /** Called when an experience is snapshotted and closed, with how many state keys were kept. */
  private val onEvict: (String, Int) -> Unit = { _, _ -> },
) {
  private val pool = WarmPool(capacity)
  private val entries = mutableMapOf<String, ShellEntry>()

  private val activeKey = mutableStateOf<String?>(null)

  /**
   * The entry point currently selected.
   *
   * This moves the instant [activate] is called, while [active] moves only once that entry point's
   * guest is actually running. The gap between the two is the cold-start cost, so anything trying
   * to measure a switch has to watch both: the selection alone says nothing about what is drawn.
   */
  val activeEntryPoint: State<String?> get() = activeKey

  /** The experience to compose. Changes when [activate] is called or the active one first loads. */
  val active: State<DogwoodExperience?>
    get() = derived

  private val derived = mutableStateOf<DogwoodExperience?>(null)

  /** Which entry points are currently warm, most recently used first. Telemetry, and for tests. */
  val warm: List<String> get() = pool.warm

  /**
   * Frames requested by each warm experience, active and hidden alike.
   *
   * The design claims a hidden experience costs nothing but memory. That claim is only worth
   * making if it can be checked, and it is checked here: sample this, wait, sample again, and any
   * entry other than the active one whose count moved is doing work for a screen nobody is
   * looking at.
   */
  fun frameRequests(): Map<String, Int> =
    entries.mapNotNull { (key, entry) -> entry.session?.let { key to it.frameRequests } }.toMap()

  /**
   * Makes [entryPoint] the active experience, starting or restoring it if needed.
   *
   * Returns immediately; the active experience appears in [active] when it is ready. A warm one is
   * ready on the same frame, which is the whole point.
   */
  fun activate(entryPoint: String, launchParams: JsonObject = JsonObject(emptyMap())) {
    val evicted = pool.touch(entryPoint)
    activeKey.value = entryPoint

    val entry = entries.getOrPut(entryPoint) { ShellEntry(launchParams) }
    if (entry.session == null) start(entryPoint, entry)
    publish()

    // After the new one is running, so a switch never leaves the screen without an experience.
    for (key in evicted) uiScope.launch { evict(key) }
  }

  /**
   * Reduces the warm set to [keep] experiences. For `onTrimMemory` and the like.
   *
   * The active experience is never dropped, whatever [keep] says: answering a memory warning with
   * a blank screen is not an improvement.
   */
  fun trimMemory(keep: Int = 1) {
    for (key in pool.trim(keep)) uiScope.launch { evict(key) }
  }

  /** Pushes a new host environment into every warm experience, not only the visible one. */
  fun updateEnvironment(next: HostEnvironment) {
    environment = next
    for (entry in entries.values) entry.session?.updateConfiguration(next)
  }

  fun close() {
    for (entry in entries.values) {
      entry.job?.cancel()
      entry.session?.close()
    }
    entries.clear()
    pool.clear()
    derived.value = null
    delivery.close()
  }

  private fun start(entryPoint: String, entry: ShellEntry) {
    val session = DogwoodSession(
      delivery = delivery,
      applicationName = applicationName,
      manifestUrl = manifestUrl,
      ziplineDispatcher = ziplineDispatcher,
      uiScope = uiScope,
      initialConfiguration = environment,
      entryPoint = entryPoint,
      launchParams = entry.launchParams,
      services = services,
      leakDetector = leakDetector,
      // The state this entry point had when it was evicted, if it ever was.
      initialState = entry.snapshot,
      // The shell owns the delivery; a session closing it would take its siblings down.
      ownsDelivery = false,
      onSwap = { status ->
        onSwap(entryPoint, status)
        publish()
      },
      onFailure = { failure -> onFailure(entryPoint, failure) },
    )
    entry.session = session
    entry.snapshot = null
    entry.job = uiScope.launch { session.run() }
  }

  /**
   * Snapshots and closes one experience.
   *
   * The snapshot is taken **before** the teardown, because the guest is the only thing that knows
   * its own saveable state and it has to still be alive to be asked -- the same ordering the code
   * update path uses, for the same reason.
   */
  private suspend fun evict(entryPoint: String) {
    val entry = entries[entryPoint] ?: return
    val session = entry.session ?: return
    val captured = runCatching { session.snapshotState() }
    captured.exceptionOrNull()?.let { onFailure(entryPoint, RuntimeException("snapshot failed", it)) }
    entry.snapshot = captured.getOrNull()
    onEvict(entryPoint, entry.snapshot?.values?.size ?: 0)
    entry.job?.cancel()
    // Closing is the point of evicting. Cancelling the job stops the update flow but leaves the
    // guest alive -- the interpreter, its heap, and the composition inside it -- so an eviction
    // without this frees nothing and quietly accumulates exactly what the cap exists to bound.
    session.close()
    leakDetector.watch(session, "experience '$entryPoint', evicted to stay within the warm cap")
    entry.session = null
    entry.job = null
    publish()
  }

  private fun publish() {
    derived.value = activeKey.value?.let { entries[it]?.session?.experience?.value }
  }
}

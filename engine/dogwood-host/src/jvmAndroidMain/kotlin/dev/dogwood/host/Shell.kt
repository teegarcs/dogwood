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

  /** Entry points the host composes as surfaces of their own, beyond the active one. */
  private val hostMounted = mutableSetOf<String>()

  /** Entry points whose environment describes their own slot rather than the window. */
  private val perSurfaceEnvironment = mutableMapOf<String, HostEnvironment>()

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
   * The live experience for [entryPoint], or null if it is not warm.
   *
   * For hosts composing more than one surface. Reading it inside a composition is a snapshot read,
   * so a surface recomposes when its experience finishes loading or is replaced by a code update.
   */
  fun experience(entryPoint: String): DogwoodExperience? = liveByKey.value[entryPoint]

  /**
   * Makes [entryPoint] the active experience, starting or restoring it if needed.
   *
   * Returns immediately; the active experience appears in [active] when it is ready. A warm one is
   * ready on the same frame, which is the whole point.
   */
  fun activate(entryPoint: String, launchParams: JsonObject = JsonObject(emptyMap())) {
    val previous = activeKey.value
    activeKey.value = entryPoint

    val evicted = buildList {
      // The outgoing experience stops being on screen, unless the host mounted it as a surface of
      // its own -- in which case it is still being drawn and taking it off screen would be a lie
      // the cap could act on.
      if (previous != null && previous != entryPoint && previous !in hostMounted) {
        addAll(pool.unmount(previous))
      }
      addAll(pool.mount(entryPoint))
    }
    startAndPublish(entryPoint, launchParams, evicted)
  }

  /**
   * Composes [entryPoint] as an additional surface, alongside whatever is active.
   *
   * This is the side-by-side case: a navigation rail owned by one team beside a content pane owned
   * by another. A mounted experience is protected from eviction for as long as it is mounted,
   * because recency cannot tell "not tapped recently" from "not on screen" and the cap would
   * otherwise tear down a surface the user is looking straight at.
   *
   * Compose it by reading [experience]; call [unmount] when the surface leaves.
   */
  fun mount(entryPoint: String, launchParams: JsonObject = JsonObject(emptyMap())) {
    hostMounted.add(entryPoint)
    startAndPublish(entryPoint, launchParams, pool.mount(entryPoint))
  }

  /** Withdraws a surface added by [mount]. Its experience becomes an eviction candidate again. */
  fun unmount(entryPoint: String) {
    if (!hostMounted.remove(entryPoint)) return
    // Still protected if it happens to be the active one; the shell keeps that mounted itself.
    val evicted = if (entryPoint == activeKey.value) emptyList() else pool.unmount(entryPoint)
    publish()
    for (key in evicted) uiScope.launch { evict(key) }
  }

  private fun startAndPublish(
    entryPoint: String,
    launchParams: JsonObject,
    evicted: List<String>,
  ) {
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
    for ((key, entry) in entries) {
      if (key !in perSurfaceEnvironment) entry.session?.updateConfiguration(next)
    }
  }

  /**
   * Sets the environment for one entry point, overriding the shell-wide one.
   *
   * Needed the moment a host composes more than one surface, because the host environment
   * describes **the slot an experience occupies**, not the window. Two surfaces sharing a screen
   * have different heights, and possibly different width classes and insets; telling both of them
   * the window's size is telling at least one of them something false, and it will lay out for
   * room it does not have. This was found by composing two surfaces and reading what the second
   * one believed about itself.
   *
   * Wrap each surface in its own `DogwoodEnvironment` and route its measurements here. An entry
   * point with an override stops receiving the shell-wide [updateEnvironment] value, so a host
   * that adopts per-surface environments for one experience does not silently keep overwriting it
   * with the window's.
   */
  fun updateEnvironment(entryPoint: String, next: HostEnvironment) {
    perSurfaceEnvironment[entryPoint] = next
    entries[entryPoint]?.session?.updateConfiguration(next)
  }

  /** Returns [entryPoint] to the shell-wide environment. */
  fun clearEnvironmentOverride(entryPoint: String) {
    if (perSurfaceEnvironment.remove(entryPoint) == null) return
    entries[entryPoint]?.session?.updateConfiguration(environment)
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
      initialConfiguration = perSurfaceEnvironment[entryPoint] ?: environment,
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

  private val liveByKey = mutableStateOf<Map<String, DogwoodExperience>>(emptyMap())

  private fun publish() {
    liveByKey.value = entries.mapNotNull { (key, entry) ->
      entry.session?.experience?.value?.let { key to it }
    }.toMap()
    derived.value = activeKey.value?.let { liveByKey.value[it] }
  }
}

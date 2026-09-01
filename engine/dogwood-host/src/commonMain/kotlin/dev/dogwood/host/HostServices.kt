/*
 * Project Dogwood -- the host side of the service surface.
 *
 * The protocol declares what a guest may reach; this decides what this particular client actually
 * offers. Those are different questions, and keeping them apart is the point of the surface: two
 * applications embedding Dogwood will answer the second differently, and neither has to fork the
 * first.
 *
 * Everything here is composition rather than inheritance. [DogwoodServiceHost] holds five nullable
 * services and hands them out; the implementations are small classes an application either uses,
 * wraps, or replaces entirely. See `adrs/layer-5/ADR-013-host-services-and-entry-points.md`.
 */
package dev.dogwood.host

import dev.dogwood.protocol.DogwoodAnalytics
import dev.dogwood.protocol.DogwoodClock
import dev.dogwood.protocol.DogwoodFeatureFlags
import dev.dogwood.protocol.DogwoodLog
import dev.dogwood.protocol.DogwoodNetwork
import dev.dogwood.protocol.DogwoodServices
import dev.dogwood.protocol.LogLevel
import dev.dogwood.protocol.ServiceNames

/**
 * What this client offers a guest.
 *
 * A service left null is genuinely absent: the guest is told so through
 * [DogwoodServices.available] and through a null accessor, and is expected to carry on without it.
 * That is deliberate — an application should be able to ship Dogwood without wiring analytics, and
 * a guest should be able to run on that application unchanged.
 */
class DogwoodServiceHost(
  private val log: DogwoodLog? = null,
  private val clock: DogwoodClock? = null,
  private val analytics: DogwoodAnalytics? = null,
  private val featureFlags: DogwoodFeatureFlags? = null,
  private val network: DogwoodNetwork? = null,
) : DogwoodServices {

  private val names: Set<String> = buildSet {
    if (log != null) add(ServiceNames.LOG)
    if (clock != null) add(ServiceNames.CLOCK)
    if (analytics != null) add(ServiceNames.ANALYTICS)
    if (featureFlags != null) add(ServiceNames.FEATURE_FLAGS)
    if (network != null) add(ServiceNames.NETWORK)
  }

  override fun available(): Set<String> = names

  override fun log(): DogwoodLog? = log

  override fun clock(): DogwoodClock? = clock

  override fun analytics(): DogwoodAnalytics? = analytics

  override fun featureFlags(): DogwoodFeatureFlags? = featureFlags

  override fun network(): DogwoodNetwork? = network

  /**
   * Closing the vendor does **not** close the services it handed out.
   *
   * The services outlive it: a guest resolves them once at startup and holds them for the
   * composition's lifetime, and the vendor is finished the moment `start` returns. Closing them
   * here would break every guest immediately after it launched.
   */
  override fun close() = Unit
}

/** Routes guest logging wherever this application already sends its own. */
class CallbackLog(
  private val sink: (LogLevel, String, String) -> Unit,
) : DogwoodLog {
  override fun log(level: LogLevel, tag: String, message: String) = sink(level, tag, message)

  override fun close() = Unit
}

class CallbackAnalytics(
  private val sink: (String, Map<String, String>) -> Unit,
) : DogwoodAnalytics {
  override fun track(name: String, properties: Map<String, String>) = sink(name, properties)

  override fun close() = Unit
}

/**
 * Flags from an in-memory map.
 *
 * `snapshot` is read when the guest starts, so the map a real application supplies should be the
 * one its own flag system already resolved, not a live view — see the note on
 * [DogwoodFeatureFlags].
 */
class MapFeatureFlags(private val values: Map<String, String>) : DogwoodFeatureFlags {
  override fun snapshot(): Map<String, String> = values

  override fun close() = Unit
}

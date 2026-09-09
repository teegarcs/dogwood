/*
 * Project Dogwood -- surviving a bad publish.
 *
 * The architecture's selling point is shipping a screen without a store review. The other half of
 * that, and the half nothing here had, is **un-shipping one**. A payload that loads and then fails
 * -- throws on its first composition, produces no tree, wedges on a null -- was previously indistinguishable
 * from one that works, because the only failure the delivery path knew about was a failure to
 * *fetch*. `DogwoodDelivery.updates` already keeps the previous guest running when a poll fails.
 * Nothing kept anything running when a poll succeeded and the payload was broken.
 *
 * The worst shape of that is the one this file is built around: **a payload that crashes the
 * application on launch**. It is the worst because it is self-concealing — every recovery mechanism
 * that lives in memory is erased by the very crash it is counting, so the application relaunches,
 * loads the same payload, and crashes again, forever, on every device that fetched it. A store
 * review would not have stopped it and cannot fix it either; the fix has to be on the device.
 *
 * So this is deliberately three small things rather than one clever one:
 *
 *   - **An attempt is recorded before the payload runs, and it is written to disk.** That single
 *     ordering is what makes a crash loop countable. Everything else here is bookkeeping.
 *   - **A version that fails too often is quarantined**, and the last version known to have worked
 *     is run instead.
 *   - **A kill switch rides in the manifest**, so a publisher can stop a payload without waiting
 *     for devices to discover it is broken.
 *
 * What this is *not*: it is not a rollout system. Staging a release to a fraction of devices needs
 * a server that serves different manifests to different clients, and there is no server here.
 * [InstallCohort] is the client's half of that and nothing more — a stable number a server could
 * use, so the absence is a missing server rather than a missing capability on the device.
 */
package dev.dogwood.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/** What the guard decided about a version, and why. */
sealed interface ReleaseVerdict {
  /** Run it. */
  data object Allowed : ReleaseVerdict

  /**
   * Refuse it, and run [fallbackVersion] if there is one.
   *
   * [reason] is meant to be reported. A payload that silently does not run is the same symptom as
   * a payload that silently does not work.
   */
  data class Refused(val reason: String, val fallbackVersion: String?) : ReleaseVerdict
}

/** The guard's memory, as it is written down. */
@Serializable
internal data class ReleaseRecord(
  /** The last version that started and then reported success. */
  val lastKnownGood: String? = null,
  /** Versions refused on sight. A version reaches this by failing [ReleaseGuard.maxFailures] times. */
  val quarantined: Set<String> = emptySet(),
  /** Attempts started but not yet reported good, by version. */
  val attempts: Map<String, Int> = emptyMap(),
)

/**
 * Where the guard's memory lives.
 *
 * An interface because the guard's *policy* is the interesting part and should be testable without
 * a file system, and because the four hosts do not share one — `BrowserFileSystem` on the web is an
 * Okio file system over `localStorage`.
 */
interface ReleaseStore {
  fun read(): String?
  fun write(text: String)
}

/** The ordinary implementation: one small file, rewritten whole. */
class FileReleaseStore(
  private val file: Path,
  private val fileSystem: FileSystem = platformFileSystem(),
  private val onProblem: (String) -> Unit = {},
) : ReleaseStore {

  override fun read(): String? = runCatching {
    if (!fileSystem.exists(file)) return null
    fileSystem.read(file) { readUtf8() }
  }.getOrElse {
    onProblem("release record unreadable: ${it.message}")
    null
  }

  override fun write(text: String) {
    runCatching {
      file.parent?.let { fileSystem.createDirectories(it) }
      fileSystem.write(file) { writeUtf8(text) }
    }.onFailure {
      // A guard that cannot persist is a guard that cannot survive a crash loop, which is the one
      // case it exists for. Reported loudly rather than swallowed.
      onProblem("release record unwritable: ${it.message}")
    }
  }
}

/**
 * Decides whether a payload version may run, and remembers what happened when it did.
 *
 * @param maxFailures how many starts without a success quarantine a version. Two rather than one,
 *   because a single failure can be the device's fault — a process killed while backgrounded during
 *   the first composition looks exactly like a payload that crashed. Two consecutive failures on
 *   one version is a payload.
 */
class ReleaseGuard(
  private val store: ReleaseStore,
  private val maxFailures: Int = 2,
  private val onReport: (String) -> Unit = {},
) {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private var record: ReleaseRecord = load()

  private fun load(): ReleaseRecord {
    val text = store.read() ?: return ReleaseRecord()
    return runCatching { json.decodeFromString(ReleaseRecord.serializer(), text) }
      .getOrElse {
        // An unreadable record starts over rather than refusing everything. The failure mode of
        // the alternative is an application that will not run any payload because a file it wrote
        // itself is corrupt.
        onReport("release record unparseable; starting over")
        ReleaseRecord()
      }
  }

  private fun save() {
    // A store that throws must not take the launch down. The guard's job is to make a bad payload
    // survivable; a guard that turns an unwritable file into a crash has become the outage. It
    // degrades to useless -- no memory, so no quarantine -- and says so, which is the honest
    // failure for something whose whole value is remembering.
    runCatching { store.write(json.encodeToString(ReleaseRecord.serializer(), record)) }
      .onFailure { onReport("release record could not be saved: ${it.message}") }
  }

  /** The last version that started and reported success, or null before any has. */
  val lastKnownGood: String? get() = record.lastKnownGood

  /** Versions this guard will refuse on sight. */
  val quarantined: Set<String> get() = record.quarantined

  /** How many times [version] has started without reporting success. */
  fun attempts(version: String): Int = record.attempts[version] ?: 0

  /**
   * The last release known to have worked, if any.
   *
   * Public because a refusal that is *not* the guard's own — a payload naming a dictionary this
   * client lacks — still wants to name it: the host's screen says "we could not run this" and the
   * one useful thing to add is which release did.
   */
  fun lastGoodVersion(): String? = record.lastKnownGood

  /**
   * Whether [version] may run.
   *
   * [disabledByPublisher] is the kill switch, read from the manifest. It is passed in rather than
   * read here because the guard is transport-free: what a manifest *is* differs between a Zipline
   * payload and a web sidecar, and what "the publisher said stop" means does not.
   */
  fun verdict(version: String, disabledByPublisher: Boolean = false): ReleaseVerdict = when {
    disabledByPublisher -> ReleaseVerdict.Refused(
      "the publisher disabled this release",
      // Deliberately not falling back to the last known good: a publisher that disabled a release
      // may be disabling the feature, not the build. Falling back would run the thing they stopped.
      fallbackVersion = null,
    )

    version in record.quarantined -> ReleaseVerdict.Refused(
      "quarantined after ${maxFailures} failed starts",
      fallbackVersion = record.lastKnownGood?.takeIf { it != version },
    )

    else -> ReleaseVerdict.Allowed
  }

  /**
   * Records that [version] is about to run. **Persisted before it does.**
   *
   * This ordering is the whole design. An attempt counted in memory is erased by the crash it is
   * counting, so the application relaunches, loads the same payload, and crashes again — forever.
   * Writing first costs one small file write per launch and is what makes a crash loop terminate.
   */
  fun starting(version: String) {
    val next = attempts(version) + 1
    record = record.copy(attempts = record.attempts + (version to next))
    // `>=`, not `>`. With `>` the version is quarantined *during* its third start -- after it has
    // already been allowed to run a third time -- so `maxFailures = 2` ran a crashing payload three
    // times. A test written as the crash loop it models is what showed the off-by-one; one written
    // as three calls in a row would have asserted the counter and agreed with itself.
    if (next >= maxFailures) {
      record = record.copy(
        quarantined = record.quarantined + version,
        attempts = record.attempts - version,
      )
      onReport("quarantined $version after $maxFailures failed starts")
    }
    save()
  }

  /**
   * Records that [version] reached the point the host considers working.
   *
   * What that point is belongs to the caller and matters: "the payload loaded" is not evidence,
   * because a payload that loads and then throws on its first composition has loaded. The host
   * calls this when a tree has actually been applied.
   */
  fun succeeded(version: String) {
    if (record.lastKnownGood == version && version !in record.attempts) return
    record = record.copy(
      lastKnownGood = version,
      attempts = record.attempts - version,
      // A version that works is no longer quarantined. That matters for the case where a publisher
      // fixed the *server* rather than the payload -- a truncated download quarantines a version
      // that was never broken, and refusing it forever would be the guard causing the outage.
      quarantined = record.quarantined - version,
    )
    save()
  }

  /** Records that [version] failed after starting. Reported; the count already advanced. */
  fun failed(version: String, reason: String) {
    onReport("release $version failed: $reason")
  }

  /** Forgets everything. For a host that wants a "reset" affordance in a debug menu. */
  fun clear() {
    record = ReleaseRecord()
    save()
  }
}

/**
 * A stable number for this installation, so a server can stage a release to a fraction of devices.
 *
 * The client half of staged rollout, and **only** the client half. Choosing which cohorts get which
 * manifest is a server's decision and there is no server here; what a device can do is have a
 * number that does not change, so that "the first 5%" means the same devices tomorrow as today. A
 * cohort that was re-rolled every launch would move a device in and out of a release repeatedly, which
 * is worse than no staging at all.
 *
 * It is not an identifier. It is one of a hundred buckets, it is derived from a random value
 * generated once, and nothing about a person can be recovered from it.
 */
class InstallCohort(private val store: ReleaseStore, private val randomBucket: () -> Int) {

  /** 0 to 99, stable for the life of the installation. */
  val bucket: Int by lazy {
    val existing = store.read()?.trim()?.toIntOrNull()
    if (existing != null && existing in 0..99) {
      existing
    } else {
      val fresh = randomBucket().mod(100)
      store.write(fresh.toString())
      fresh
    }
  }
}

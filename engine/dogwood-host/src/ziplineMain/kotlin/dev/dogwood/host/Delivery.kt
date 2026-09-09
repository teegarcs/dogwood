/*
 * Project Dogwood -- Layer 3, Over-The-Air delivery and security.
 *
 * The guest arrives over Hypertext Transfer Protocol (HTTP), its manifest's Ed25519 signature
 * is verified against a key compiled into the host, and its modules are cached on disk so a
 * second launch does not refetch them.
 *
 * The signature check is the security boundary that makes downloaded code acceptable at all:
 * without it, anyone who can answer the manifest request can run arbitrary code inside the
 * application. `ManifestVerifier.NO_SIGNATURE_CHECKS` exists in Zipline for tests; it is
 * deliberately not reachable from here.
 *
 * **Common, not Java-Virtual-Machine-only, since Phase 6.** The only thing in here that was ever
 * platform-specific was the Hypertext Transfer Protocol (HTTP) client, and Zipline already has a
 * common abstraction for it: `ZiplineLoader`'s public constructor takes a `ZiplineHttpClient`,
 * and each platform adapts its own stack to that interface -- `OkHttpClient.asZiplineHttpClient()`
 * on a Java Virtual Machine and Android, `NSURLSession.asZiplineHttpClient()` on iOS. So the
 * class below takes the abstraction and each platform supplies a factory that takes the concrete
 * client (see `Delivery.jvmAndroid.kt` and `Delivery.ios.kt`). Nothing an existing caller writes
 * had to change: those factories carry the defaults the constructor used to.
 */
package dev.dogwood.host

import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.FreshnessChecker
import app.cash.zipline.loader.LoadResult
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.ZiplineCache
import app.cash.zipline.loader.ZiplineLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import app.cash.zipline.loader.ZiplineHttpClient
import okio.ByteString.Companion.decodeHex
import okio.Path
import okio.Path.Companion.toPath

/**
 * How long a cached manifest may be reused before the client insists on refetching.
 *
 * **The default is zero, and that is a deliberate choice rather than an oversight.** An earlier
 * version of this file defaulted to twenty-four hours, which is a perfectly ordinary caching
 * policy and completely wrong here: it was measured, and an update published to the server did
 * not reach a restarted client at all, because the cached manifest was still considered fresh.
 * A server-driven user interface whose updates take a day to arrive is not one.
 *
 * Zero means every launch revalidates against the server. The cost is one conditional request
 * per launch; the module bytes still come from cache when the manifest has not changed, so the
 * expensive part of the fetch is skipped either way.
 *
 * A non-zero window is a legitimate product choice -- it trades update latency for requests, and
 * matters on a metered connection -- but it must be chosen, which is why it is a parameter with
 * a safe default rather than a constant.
 */
const val REVALIDATE_EVERY_LAUNCH = 0L

private class MaxAgeFreshnessChecker(
  private val maxAgeMs: Long,
  private val nowEpochMs: () -> Long,
) : FreshnessChecker {
  override fun isFresh(manifest: ZiplineManifest, freshAtEpochMs: Long): Boolean =
    maxAgeMs > 0 && nowEpochMs() - freshAtEpochMs < maxAgeMs
}

/** What Layer 3 hands to Layer 4: a live interpreter, and the provenance of what is in it. */
class DeliveredGuest(
  val zipline: Zipline,
  val manifest: ZiplineManifest,
  /** Which trusted key signed this manifest. Worth logging: it is the audit trail. */
  val verifiedByKey: String?,
) {
  /**
   * What to call this release when remembering whether it worked.
   *
   * The manifest's own version when it has one. A payload published without one is not refused --
   * that would break every build that has not started setting it -- but it cannot be tracked
   * either, so it is named for the module it loads, which at least distinguishes two different
   * payloads from each other.
   */
  val releaseVersion: String
    get() = manifest.version ?: "unversioned:${manifest.mainModuleId}"

  /**
   * The publisher's kill switch, read from the manifest's **signed** metadata.
   *
   * Signed matters: an attacker who can rewrite an unsigned field can disable a competitor's
   * application, which is a denial of service delivered through the update channel the update
   * channel exists to secure. `metadata` is inside the signature payload; `unsigned` is not, and
   * this deliberately does not look there.
   */
  val disabledByPublisher: Boolean
    get() = manifest.metadata[DISABLED_KEY]?.lowercase() == "true"

  /**
   * What dictionary the payload declares it was built against, or empty if it declares nothing.
   *
   * Parsed leniently on purpose: a malformed entry is dropped rather than failing the load, because
   * this check exists to refuse payloads that are *too new*, and a parse error is not evidence of
   * that. Refusing on unparseable metadata would turn a publishing typo into a fleet-wide outage.
   */
  val declaredSegments: Map<String, Int>
    get() = manifest.metadata[SEGMENTS_KEY].orEmpty()
      .split(",")
      .mapNotNull { entry ->
        val name = entry.substringBeforeLast(':', "").trim()
        val version = entry.substringAfterLast(':', "").trim().toIntOrNull()
        if (name.isEmpty() || version == null) null else name to version
      }
      .toMap()

  companion object {
    /**
     * The dictionary versions the payload was built against, as `name:version` pairs.
     *
     * Rides the **signed** metadata beside the kill switch, for the same reason: a client refuses
     * to run on this, and a field an attacker could set unsigned would be a denial of service
     * delivered through the channel that exists to secure updates.
     *
     * Its absence is not a refusal. A payload that declares nothing is the ordinary case for every
     * payload built before this field existed, and render-time containment — placeholders,
     * withheld affordances, reported skew — remains what protects those. This is a *second* line,
     * added because the web profile has had one since ADR-032 and the mobile clients had none: the
     * asymmetry was recorded in `plans/adoption-audit.md` and is closed here.
     */
    const val SEGMENTS_KEY = "dogwood.segments"

    /** Set this to `"true"` in a manifest's metadata to stop devices running that release. */
    const val DISABLED_KEY: String = "dogwood.disabled"
  }
}

/**
 * A release the guard refused, as a host is told about it.
 *
 * [fallbackVersion] names the last release known to have worked, when there is one. Naming it is
 * not running it: resuming a previous payload means fetching a manifest that still serves it, which
 * is a server's job.
 */
data class GuardedRelease(
  val version: String,
  val reason: String,
  val fallbackVersion: String?,
)

/** What [DogwoodDelivery.loadGuarded] decided. */
sealed interface GuardedLoad {
  /** The release may run, and the attempt has been recorded. */
  data class Running(val guest: DeliveredGuest, val version: String) : GuardedLoad

  /**
   * The release must not run.
   *
   * [fallbackVersion] names the last release known to have worked, when there is one. **Naming it
   * is not the same as running it**, and this deliberately does not try: resuming a previous
   * payload means fetching a manifest that still serves it, which is a server's job. What a host
   * can always do without one is refuse, say why, and show something of its own — which is the
   * difference between a bad publish being a bad hour and being a bad week.
   */
  data class Refused(
    val version: String,
    val reason: String,
    val fallbackVersion: String?,
  ) : GuardedLoad
}

/**
 * Fetches, verifies, caches, and loads a guest.
 *
 * @param trustedPublicKeys key name to Ed25519 public key, hex-encoded. More than one entry is
 *   how key rotation works: ship a manifest signed by both, roll clients forward, then retire
 *   the old key.
 * @param cache built by the caller, because building one genuinely differs by platform: the
 *   Android factory needs a `Context` for its SQLite driver and the Java Virtual Machine one
 *   does not. Hiding that behind a common signature would mean inventing an abstraction over a
 *   difference that is real.
 */
class DogwoodDelivery(
  private val dispatcher: CoroutineDispatcher,
  trustedPublicKeys: Map<String, String>,
  private val cache: ZiplineCache,
  /**
   * The platform's Hypertext Transfer Protocol (HTTP) stack, adapted to Zipline's interface.
   *
   * Deliberately has no default here, so that a call written as
   * `DogwoodDelivery(dispatcher, keys, cache)` resolves unambiguously to the platform factory
   * that does have one.
   */
  httpClient: ZiplineHttpClient,
  /** See [REVALIDATE_EVERY_LAUNCH]. Zero revalidates on every launch. */
  private val manifestMaxAgeMs: Long = REVALIDATE_EVERY_LAUNCH,
  private val nowEpochMs: () -> Long = ::hostEpochMillis,
  /**
   * The bounds every loaded guest runs under. On by default -- the same argument as the release
   * guard's missing default (ADR-058): a payload is replaceable over the air, so an unbounded
   * interpreter is a standing invitation. `GuestLimits.none` is the written way out.
   */
  private val guestLimits: GuestLimits? = GuestLimits(),
  /**
   * What dictionary this client implements, for the pre-flight check.
   *
   * Defaulted to the generated vector, because a host that passed its own would be restating a
   * number the generator already emits — and a restated number is one that can disagree. A host
   * with extra registered segments gets them automatically: `DogwoodDictionary.segmentVersions`
   * includes whatever `DogwoodRegistry` holds.
   *
   * **A function, read at check time, not a map captured at construction.** `segmentVersions` is a
   * property with a getter for exactly this reason, and its own documentation says why: a product's
   * segments join it when they register, and a value computed too early "would have told every
   * guest that the product's own components did not exist". Captured here, the same mistake is
   * worse than a wrong capability report — a client that constructed its delivery before
   * registering its own bindings would *refuse* every payload naming them, turning an ordering
   * detail into a blank screen.
   */
  private val clientSegmentVersions: () -> Map<String, Int> = { DogwoodDictionary.segmentVersions },
) {
  init {
    require(trustedPublicKeys.isNotEmpty()) {
      "Layer 3 refuses to load unsigned code; supply at least one trusted public key"
    }
  }

  private val verifier = ManifestVerifier.Builder()
    .apply {
      for ((name, hex) in trustedPublicKeys) addEd25519(name, hex.decodeHex())
    }
    .build()

  private val loader = ZiplineLoader(
    dispatcher = dispatcher,
    manifestVerifier = verifier,
    httpClient = httpClient,
  ).withCache(cache, dispatcher)

  /**
   * Loads once, at start-up.
   *
   * A signature mismatch, a tampered module hash, or an unreachable server all surface as a
   * thrown exception rather than as a silently empty screen.
   *
   * **This is not the whole of Layer 3.** `ZiplineLoader.load` returns a *flow* of results, and
   * Layer 4 says code update while a screen is live is the normal case, not an edge one.
   * Consuming that flow means tearing down a running experience and standing up a replacement
   * with its state preserved, which needs `SaveableStateRegistry` and a host-side state store
   * that now both exist -- `SaveableStateRegistry` on the guest and `DogwoodStateStore` on the
   * host -- so an update carries state rather than landing fresh on the next launch.
   */
  /**
   * Loads, then asks a [ReleaseGuard] whether this release may run.
   *
   * The check is after the load and not before it, and that is forced rather than chosen: the
   * version and the kill switch are *in the manifest*, so nothing can be decided until it has been
   * fetched and its signature verified. That is the right order anyway — loading a payload is not
   * the dangerous part. **Running** it is, and this returns before anything composes.
   *
   * On [GuardedLoad.Running] the attempt has already been recorded and persisted. The caller owes
   * the guard one call to [ReleaseGuard.succeeded] when the release has actually worked, and what
   * counts as working is the caller's judgement: "it loaded" is not evidence, because a payload
   * that throws on its first composition has loaded.
   */
  suspend fun loadGuarded(
    applicationName: String,
    manifestUrl: String,
    guard: ReleaseGuard,
  ): GuardedLoad {
    val guest = load(applicationName, manifestUrl)
    val version = guest.releaseVersion

    /*
     * The dictionary first, before the release verdict, because they answer different questions and
     * this one is the more specific: a payload this client cannot render is refused whether or not
     * its version was ever going to be allowed to run, and telling a host "quarantined" when the
     * truth is "your client is a release behind" sends somebody to look at the wrong thing.
     */
    val skew = checkDeclaredDictionary(guest.declaredSegments, clientSegmentVersions())
    if (skew != null) {
      // Closed rather than left open: a refused guest is a live QuickJS instance and a whole heap.
      guest.zipline.close()
      return GuardedLoad.Refused(version, skew.message, guard.lastGoodVersion())
    }

    return when (val verdict = guard.verdict(version, guest.disabledByPublisher)) {
      is ReleaseVerdict.Refused -> {
        // Closed rather than left open. A refused guest is a live QuickJS instance and an entire
        // heap; keeping one around because it *might* be wanted is the leak this project has
        // already fixed once, from the other direction.
        guest.zipline.close()
        GuardedLoad.Refused(version, verdict.reason, verdict.fallbackVersion)
      }
      ReleaseVerdict.Allowed -> {
        guard.starting(version)
        GuardedLoad.Running(guest, version)
      }
    }
  }

  suspend fun load(applicationName: String, manifestUrl: String): DeliveredGuest {
    val result = loader.loadOnce(
      applicationName = applicationName,
      freshnessChecker = MaxAgeFreshnessChecker(manifestMaxAgeMs, nowEpochMs),
      manifestUrl = manifestUrl,
    )
    return when (result) {
      is LoadResult.Success -> DeliveredGuest(
        zipline = result.zipline,
        manifest = result.manifest,
        verifiedByKey = result.manifest.signatures.keys.firstOrNull(),
      ).also { delivered ->
        // Before any guest code composes: a bound applied after the first slice began is a bound
        // the first slice never had.
        guestLimits?.let { applyGuestLimits(delivered.zipline, it) }
      }
      is LoadResult.Failure -> throw result.exception
    }
  }

  /**
   * Emits a guest on load, and again whenever the published code changes.
   *
   * `ZiplineLoader.load` compares each fetched manifest against the last one and emits only on a
   * difference, so polling costs a conditional request and produces an emission only when there
   * is genuinely something new. This is the flow Layer 4 means when it says a code update while
   * a screen is live is the normal case.
   */
  fun updates(
    applicationName: String,
    manifestUrl: String,
    pollIntervalMs: Long = 5_000,
    /**
     * Called for every failed poll.
     *
     * Not optional, and not defaulted to a no-op. An earlier version dropped failures on the
     * floor with a comment explaining that a failed poll should not tear down a working screen
     * -- which is true, and which turned a mistyped Uniform Resource Locator into a blank screen
     * with no diagnosis anywhere. Continuing quietly and failing quietly are different things.
     */
    onFailure: (Exception) -> Unit,
  ): Flow<DeliveredGuest> = loader.load(
    applicationName = applicationName,
    freshnessChecker = MaxAgeFreshnessChecker(manifestMaxAgeMs, nowEpochMs),
    manifestUrlFlow = flow {
      while (true) {
        emit(manifestUrl)
        delay(pollIntervalMs)
      }
    },
  ).mapNotNull { result ->
    when (result) {
      is LoadResult.Success -> DeliveredGuest(
        zipline = result.zipline,
        manifest = result.manifest,
        verifiedByKey = result.manifest.signatures.keys.firstOrNull(),
      ).also { delivered ->
        // The code-update path gets the same bounds as the first load: a replacement guest is
        // exactly as capable of a runaway loop as the guest it replaces.
        guestLimits?.let { applyGuestLimits(delivered.zipline, it) }
      }
      // A failed poll is not a reason to tear down a working screen: the previous guest keeps
      // running and the next poll tries again. It is every reason to say so.
      is LoadResult.Failure -> {
        onFailure(result.exception)
        null
      }
    }
  }

  fun close() {
    cache.close()
  }
}

/** Convenience so a host does not have to import Okio's path builder to name a directory. */
fun cachePath(directory: String): Path = directory.toPath()

/**
 * Wall-clock milliseconds, for the manifest freshness window.
 *
 * `expect`/`actual` for the same reason `ThreadIdentity` is: the standard library's own
 * `kotlin.time.Clock` is still experimental at the pinned Kotlin version, and a freshness check
 * is not the place to take an experimental opt-in that every consumer would inherit.
 */
internal expect fun hostEpochMillis(): Long

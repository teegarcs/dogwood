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
import okhttp3.OkHttpClient
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
)

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
  httpClient: OkHttpClient = OkHttpClient(),
  /** See [REVALIDATE_EVERY_LAUNCH]. Zero revalidates on every launch. */
  private val manifestMaxAgeMs: Long = REVALIDATE_EVERY_LAUNCH,
  private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
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
   * that do not exist yet. Until they do, an update lands on the next launch.
   */
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
      )
      is LoadResult.Failure -> throw result.exception
    }
  }

  fun close() {
    cache.close()
  }
}

/** Convenience so a host does not have to import Okio's path builder to name a directory. */
fun cachePath(directory: String): Path = directory.toPath()

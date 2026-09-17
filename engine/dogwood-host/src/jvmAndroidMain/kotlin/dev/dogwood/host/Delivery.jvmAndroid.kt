/*
 * Project Dogwood -- the Java-Virtual-Machine and Android half of Layer 3 delivery.
 *
 * `DogwoodDelivery` itself is common (see `Delivery.kt`). What is here is the one thing that
 * genuinely is not: the Hypertext Transfer Protocol (HTTP) client. OkHttp is a Java library and
 * publishes no Kotlin/Native artifact, so on this platform pair it is the client and on iOS it
 * is `NSURLSession`. Zipline supplies the adapter both ways.
 *
 * A function with the same name as the class, so that every call site written before Phase 6 --
 * `DogwoodDelivery(dispatcher = ..., trustedPublicKeys = ..., cache = ...)` -- resolves here
 * unchanged and gets the same default client it always had.
 */
package dev.dogwood.host

import app.cash.zipline.loader.ZiplineCache
import app.cash.zipline.loader.asZiplineHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient

/** See [DogwoodDelivery]. */
fun DogwoodDelivery(
  dispatcher: CoroutineDispatcher,
  trustedPublicKeys: Map<String, String>,
  cache: ZiplineCache,
  httpClient: OkHttpClient = OkHttpClient(),
  manifestMaxAgeMs: Long = REVALIDATE_EVERY_LAUNCH,
  nowEpochMs: () -> Long = { System.currentTimeMillis() },
  // Passed through rather than dropped: a parameter the common class accepts and no platform
  // factory offers is a feature no host on that platform can reach, and every Java-Virtual-Machine
  // and Android host in this repository calls this overload rather than the constructor.
  installCohort: InstallCohort? = null,
): DogwoodDelivery = DogwoodDelivery(
  dispatcher = dispatcher,
  trustedPublicKeys = trustedPublicKeys,
  cache = cache,
  httpClient = httpClient.asZiplineHttpClient(),
  manifestMaxAgeMs = manifestMaxAgeMs,
  nowEpochMs = nowEpochMs,
  installCohort = installCohort,
)

internal actual fun hostEpochMillis(): Long = System.currentTimeMillis()

/**
 * No-op. Android's equivalent is `allowBackup="false"` in the manifest, which is a declaration
 * about the whole application rather than a property of one file, and the sample sets it.
 */
internal actual fun excludeFromBackup(path: okio.Path) = Unit

internal actual fun platformFileSystem(): okio.FileSystem = okio.FileSystem.SYSTEM

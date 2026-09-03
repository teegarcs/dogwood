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
): DogwoodDelivery = DogwoodDelivery(
  dispatcher = dispatcher,
  trustedPublicKeys = trustedPublicKeys,
  cache = cache,
  httpClient = httpClient.asZiplineHttpClient(),
  manifestMaxAgeMs = manifestMaxAgeMs,
  nowEpochMs = nowEpochMs,
)

internal actual fun hostEpochMillis(): Long = System.currentTimeMillis()

internal actual fun platformFileSystem(): okio.FileSystem = okio.FileSystem.SYSTEM

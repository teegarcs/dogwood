/*
 * Project Dogwood -- the iOS half of Layer 3 delivery, and the Zipline thread.
 *
 * Two things iOS needs that neither Java-Virtual-Machine host does.
 *
 * **The Hypertext Transfer Protocol (HTTP) client.** OkHttp is a Java library, so on iOS the
 * client is `NSURLSession`. Zipline ships the adapter -- `NSURLSession.asZiplineHttpClient()` in
 * `zipline-loader`'s `nativeMain` -- so `DogwoodDelivery` itself is unchanged common code.
 *
 * **The thread the guest runs on.** This is not a detail. Apple gives a background thread a
 * default stack of 512 kibibytes, and interpreted Compose composition inside QuickJS is deeply
 * recursive: the desktop host asks the Java Virtual Machine for eight megabytes and the Android
 * host does the same. A default-stack thread here does not fail gracefully -- it overflows the
 * stack somewhere inside the interpreter. [dogwoodZiplineDispatcher] therefore builds an
 * `NSThread` with the stack size set explicitly, which is precisely what Redwood's
 * `IosTreehouseDispatchers` does and for the reason its comment gives.
 */
package dev.dogwood.host

import kotlin.coroutines.CoroutineContext
import kotlinx.cinterop.convert
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import app.cash.zipline.loader.ZiplineCache
import app.cash.zipline.loader.asZiplineHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import platform.Foundation.NSDate
import platform.Foundation.NSThread
import platform.Foundation.NSURLSession
import platform.Foundation.timeIntervalSince1970

/**
 * See [DogwoodDelivery].
 *
 * The parameter shape matches the Java-Virtual-Machine factory exactly, so the only line that
 * differs between a desktop host and an iOS one is which client it does not pass.
 */
fun DogwoodDelivery(
  dispatcher: CoroutineDispatcher,
  trustedPublicKeys: Map<String, String>,
  cache: ZiplineCache,
  urlSession: NSURLSession = NSURLSession.sharedSession,
  manifestMaxAgeMs: Long = REVALIDATE_EVERY_LAUNCH,
  nowEpochMs: () -> Long = ::hostEpochMillis,
): DogwoodDelivery = DogwoodDelivery(
  dispatcher = dispatcher,
  trustedPublicKeys = trustedPublicKeys,
  cache = cache,
  httpClient = urlSession.asZiplineHttpClient(),
  manifestMaxAgeMs = manifestMaxAgeMs,
  nowEpochMs = nowEpochMs,
)

internal actual fun hostEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

internal actual fun platformFileSystem(): okio.FileSystem = okio.FileSystem.SYSTEM

/** Eight megabytes, the same figure the desktop and Android hosts pass to their thread factories. */
const val ZIPLINE_THREAD_STACK_SIZE: Int = 8 * 1024 * 1024

/**
 * The single thread the guest is allowed to run on, with a stack big enough to interpret a
 * composition.
 *
 * Adapted from `IosTreehouseDispatchers.SingleThreadDispatcher` in Cash App's Redwood (Apache
 * 2.0). The shape is not incidental: a channel plus one `NSThread` running `runBlocking` is the
 * only way to get a Kotlin/Native `CoroutineDispatcher` whose thread has a stack size the caller
 * chose. `kotlinx.coroutines.newSingleThreadContext` gives no control over it.
 *
 * @param stackSize bytes of stack. The default is [ZIPLINE_THREAD_STACK_SIZE]; Apple's own default
 *   for a secondary thread is 512 kibibytes, which QuickJS overflows.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class DogwoodZiplineDispatcher(
  name: String = "dogwood-zipline",
  stackSize: Int = ZIPLINE_THREAD_STACK_SIZE,
) : CloseableCoroutineDispatcher() {
  /** Non-null while this dispatcher is accepting work. */
  private var sendChannel: SendChannel<Runnable>?

  /** Non-null while this dispatcher is running work. */
  var thread: NSThread? = null
    private set

  init {
    val channel = Channel<Runnable>(capacity = Channel.UNLIMITED)
    val created = NSThread {
      runBlocking {
        try {
          while (true) {
            try {
              channel.receive().run()
            } catch (e: ClosedReceiveChannelException) {
              break
            }
          }
        } finally {
          // Breaks a reference cycle: the thread's block captures this dispatcher, and the
          // dispatcher holds the thread. Left in place it is exactly the shape of leak Phase 6
          // is looking for, on the object that owns the interpreter.
          this@DogwoodZiplineDispatcher.thread = null
        }
      }
    }.apply {
      this.name = name
      this.stackSize = stackSize.convert()
    }
    created.start()
    this.sendChannel = channel
    this.thread = created
  }

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    sendChannel?.trySend(block)
  }

  override fun close() {
    sendChannel?.close()
    sendChannel = null
  }
}

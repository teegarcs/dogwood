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
import kotlinx.cinterop.ptr
import kotlinx.cinterop.alloc

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
  // See the Java-Virtual-Machine factory: the parameter shape matches exactly, so an iOS host
  // reaches staged rollout with the same line a desktop one writes.
  installCohort: InstallCohort? = null,
): DogwoodDelivery = DogwoodDelivery(
  dispatcher = dispatcher,
  trustedPublicKeys = trustedPublicKeys,
  cache = cache,
  httpClient = urlSession.asZiplineHttpClient(),
  manifestMaxAgeMs = manifestMaxAgeMs,
  nowEpochMs = nowEpochMs,
  installCohort = installCohort,
)

internal actual fun hostEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

internal actual fun platformFileSystem(): okio.FileSystem = okio.FileSystem.SYSTEM

/**
 * Marks the file as excluded from iCloud and iTunes backup.
 *
 * The one place ADR-010's at-rest reasoning does not carry across from Android. Everything outside
 * `Caches/` is backed up by default here, so saved state -- which measurably contains a masked
 * card field's digits in plain text -- would be copied off the device unless this is set. It is a
 * resource value on the URL rather than a filesystem attribute, which is why Okio cannot do it and
 * why this seam exists.
 *
 * Applied after every write: the flag lives on the file, so a file recreated by a later write is a
 * new file without it.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual fun excludeFromBackup(path: okio.Path) {
  val url = platform.Foundation.NSURL.fileURLWithPath(path.toString())
  kotlinx.cinterop.memScoped {
    val error = alloc<kotlinx.cinterop.ObjCObjectVar<platform.Foundation.NSError?>>()
    url.setResourceValue(
      value = platform.Foundation.NSNumber(bool = true),
      forKey = platform.Foundation.NSURLIsExcludedFromBackupKey,
      error = error.ptr,
    )
  }
}

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
  /**
   * Where a block that throws out of the guest thread is reported.
   *
   * The drain loop used to have no catch at all, so one throwing block exited it while leaving the
   * channel **open** -- every later dispatch then succeeded into a queue nobody was reading. That
   * is indistinguishable from a hung guest and produces no diagnostic whatsoever.
   */
  private val onUncaught: (Throwable) -> Unit = {},
) : CloseableCoroutineDispatcher() {
  /** Non-null while this dispatcher is accepting work. */
  private var sendChannel: SendChannel<Runnable>?

  /** Non-null while this dispatcher is running work. */
  private val dispatcherName: String = name

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
            } catch (t: Throwable) {
              // Report and keep draining. A host-service callback that throws must not take the
              // interpreter's thread down with it, silently, leaving every later dispatch to
              // queue into nothing.
              onUncaught(t)
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
    /*
     * Loud, because the silent alternative is unrecoverable.
     *
     * A dropped block is a dropped *continuation*. Everything that reaches the guest crosses here
     * -- the session swap, `snapshotState`, the shell's eviction -- so a block discarded during or
     * after a close leaves that coroutine suspended forever. Cancelling it does not help either:
     * the cancellation resume is dispatched to the same closed channel and dropped in turn, so the
     * job can never complete, and it holds a `DogwoodExperience`, an interpreter and an
     * eight-megabyte stack. Failing the caller is recoverable; hanging it is not.
     */
    val channel = sendChannel
      ?: throw IllegalStateException("$dispatcherName is closed; it cannot accept more work")
    if (channel.trySend(block).isFailure) {
      throw IllegalStateException("$dispatcherName is closed; it cannot accept more work")
    }
  }

  override fun close() {
    // Nulled *before* the channel closes, so a dispatch racing this cannot see a live reference to
    // a channel that is about to refuse it. Either it fails on the null or it fails on the send;
    // both are the loud path.
    val channel = sendChannel
    sendChannel = null
    channel?.close()
  }
}

/*
 * Project Dogwood -- thread identity on iOS.
 *
 * The same assertion the Java Virtual Machine actual makes, against the same kind of evidence:
 * the `NSThread` the work is running on. Redwood's `IosTreehouseDispatchers` checks
 * `NSThread.currentThread == ziplineThread` for exactly this reason.
 *
 * **`==` and not `===`, and the difference is not stylistic.** The Java Virtual Machine actual
 * compares `Thread` references with `===` because a thread name is not unique. Here `===` compares
 * *Kotlin wrapper* identity, and Kotlin/Native is free to hand out a fresh wrapper for the same
 * underlying Objective-C object on each retrieval of `NSThread.currentThread`. It cost a running
 * application to find out: the guest composed, called `sendChanges` from the Zipline thread it had
 * just been started on, and `checkZipline()` threw. `==` reaches Objective-C `isEqual:`, which for
 * `NSThread` is the pointer comparison that was wanted all along.
 */
package dev.dogwood.host

import kotlin.concurrent.Volatile
import platform.Foundation.NSThread

actual class ThreadIdentity actual constructor() {
  @Volatile
  private var thread: NSThread? = null

  actual fun bind() {
    thread = NSThread.currentThread
  }

  actual fun isCurrent(): Boolean = thread == NSThread.currentThread
}

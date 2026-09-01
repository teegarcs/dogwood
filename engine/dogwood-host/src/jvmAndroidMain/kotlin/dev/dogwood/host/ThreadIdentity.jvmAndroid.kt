package dev.dogwood.host

/**
 * Thread identity on a Java Virtual Machine.
 *
 * Compared by reference rather than by name, because thread names are not unique and a pool
 * may reuse one.
 */
actual class ThreadIdentity actual constructor() {
  @Volatile
  private var thread: Thread? = null

  actual fun bind() {
    thread = Thread.currentThread()
  }

  actual fun isCurrent(): Boolean = thread === Thread.currentThread()
}

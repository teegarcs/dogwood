package dev.dogwood.host

import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * `TimeSource.Monotonic` rather than the deprecated `getTimeNanos`, measured from a mark taken
 * once at start-up so the values are a monotonic nanosecond count with a fixed origin -- which is
 * exactly what `System.nanoTime` is on the other side, and all this harness ever takes
 * differences of.
 */
private val origin = kotlin.time.TimeSource.Monotonic.markNow()

actual fun nanoTime(): Long = origin.elapsedNow().inWholeNanoseconds

actual fun readFileBytes(path: String): ByteArray =
  FileSystem.SYSTEM.read(path.toPath()) { readByteArray() }

actual fun writeTextFile(path: String, text: String) {
  val file = path.toPath()
  file.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
  FileSystem.SYSTEM.write(file) { writeUtf8(text) }
}

actual fun platformDescription(): String =
  "${platform.Foundation.NSProcessInfo.processInfo.operatingSystemVersionString}, " +
    "${platform.Foundation.NSProcessInfo.processInfo.processorCount} cores, Kotlin/Native"

/** See the expectation: absent rather than substituted. */
actual fun gzippedSizeOrNull(bytes: ByteArray): Int? = null

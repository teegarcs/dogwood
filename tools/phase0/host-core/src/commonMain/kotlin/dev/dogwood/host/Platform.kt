/*
 * Project Dogwood -- the three things the Phase 0 driver cannot say in common code.
 *
 * Kept deliberately small and deliberately in one file, because every entry here is a place where
 * two platforms' numbers could stop being comparable without anybody noticing. A clock that ticked
 * at a different resolution, or a file read that buffered differently, would move measurements
 * without moving any measurement code.
 */
package dev.dogwood.host

/**
 * A monotonic nanosecond clock, for durations only.
 *
 * `System.nanoTime` on a Java Virtual Machine and `kotlin.system.getTimeNanos` on Kotlin/Native,
 * which is `mach_absolute_time` on Apple platforms. Both are monotonic and both have an arbitrary
 * origin, so only differences mean anything -- which is all this harness ever takes.
 */
expect fun nanoTime(): Long

/** Reads a whole file. Small inputs only: manifests and `.zipline` containers. */
expect fun readFileBytes(path: String): ByteArray

/** Writes a report, creating the directory above it. */
expect fun writeTextFile(path: String, text: String)

/** Names the machine a report was produced on, so two reports cannot be confused. */
expect fun platformDescription(): String

/**
 * The gzipped size of [bytes], or null where the platform has no gzip.
 *
 * Null on Kotlin/Native, which has no compression in its standard library and no okio equivalent
 * -- okio's `GzipSink` is a Java Virtual Machine source set. Reported as absent rather than
 * substituted, because a compressed size is a property of the bytes and not of the machine: the
 * number measured on any other host is the number here too, and inventing a second way to compute
 * it would risk two answers to a question with one.
 */
expect fun gzippedSizeOrNull(bytes: ByteArray): Int?

/**
 * Fixed-point formatting, in common code.
 *
 * `String.format` is a Java Virtual Machine facility and this harness now reports from three
 * platforms. Writing it once matters for a reason beyond portability: a report is only comparable
 * against another report if the two rounded the same way, and two platform-specific formatters
 * agreeing is a coincidence rather than a guarantee.
 *
 * Ties round away from zero, which is what `%.3f` does for the positive durations and byte counts
 * this harness prints.
 */
fun Double.toFixed(decimals: Int): String {
  if (isNaN() || isInfinite()) return toString()
  var scale = 1L
  repeat(decimals) { scale *= 10 }
  val scaled = kotlin.math.round(this * scale).toLong()
  val negative = scaled < 0
  val absolute = if (negative) -scaled else scaled
  val sign = if (negative) "-" else ""
  if (decimals == 0) return "$sign${absolute}"
  return "$sign${absolute / scale}." + (absolute % scale).toString().padStart(decimals, '0')
}

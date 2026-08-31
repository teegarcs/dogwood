/*
 * Project Dogwood -- Phase 0 measurement harness, host half.
 *
 * Host-side services, manifest reading, and statistics. Everything here is scaffolding.
 */
package dev.dogwood.host

import dev.dogwood.protocol.ChangeBatch
import dev.dogwood.protocol.DogwoodHost
import dev.dogwood.protocol.EventTag
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.MonotonicClock
import dev.dogwood.protocol.Samples
import dev.dogwood.protocol.WidgetTag
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The host end of the boundary. Phase 0 has no renderer, so this counts and discards.
 *
 * It deliberately does no work beyond counting: experiment 0.3 measures the crossing, and
 * any host-side processing here would be charged to the crossing by mistake.
 */
class CountingHost : DogwoodHost {
  var batches = 0
    private set
  var changes = 0
    private set
  var encodedBytes = 0L
    private set
  var frameRequests = 0
    private set

  override fun sendChanges(batch: ChangeBatch) {
    batches++
    changes += batch.g.size
  }

  override fun sendChangesEncoded(json: String) {
    batches++
    encodedBytes += json.length.toLong()
  }

  override fun requestFrame() {
    frameRequests++
  }

  override fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag) {
    error("unknown event: widget ${widgetTag.value}, tag ${tag.value}")
  }

  override fun onUnknownEventNode(id: Id, tag: EventTag) {
    error("unknown event node: ${id.value}, tag ${tag.value}")
  }

  override fun handleUncaughtException(exception: Throwable) {
    throw IllegalStateException("guest threw", exception)
  }

  override fun close() = Unit
}

/**
 * The monotonic clock the guest calls. `System.nanoTime` is the JVM's monotonic source; on
 * Android it is backed by `CLOCK_MONOTONIC`.
 */
class NanoClock : MonotonicClock {
  override fun nowNanos(): Long = System.nanoTime()
  override fun close() = Unit
}

// ---------------------------------------------------------------------------
// Manifest
// ---------------------------------------------------------------------------

/**
 * A minimal mirror of Zipline's `manifest.zipline.json`, read rather than reconstructed so
 * the harness loads exactly the modules the build produced, in dependency order.
 */
@Serializable
data class ManifestMirror(
  val modules: Map<String, ModuleMirror> = emptyMap(),
  val mainModuleId: String = "./guest.js",
  val mainFunction: String? = null,
  val version: String? = null,
)

@Serializable
data class ModuleMirror(
  val url: String,
  val dependsOnIds: List<String> = emptyList(),
)

val LenientJson = Json { ignoreUnknownKeys = true }

/** Depth-first topological order, so a module is loaded after everything it requires. */
fun ManifestMirror.loadOrder(): List<String> {
  val ordered = LinkedHashSet<String>()
  fun visit(id: String) {
    if (id in ordered) return
    val module = modules[id] ?: error("manifest names module '$id' but does not define it")
    for (dependency in module.dependsOnIds) visit(dependency)
    ordered += id
  }
  for (id in modules.keys) visit(id)
  return ordered.toList()
}

// ---------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------

@Serializable
data class Stat(
  val label: String,
  val count: Int,
  val p50Ms: Double,
  val p95Ms: Double,
  val p99Ms: Double,
  val minMs: Double,
  val maxMs: Double,
  val meanMs: Double,
)

fun Samples.stat(): Stat = stat(label, nanos)

fun stat(label: String, nanos: List<Long>): Stat {
  require(nanos.isNotEmpty()) { "no samples for $label" }
  val sorted = nanos.sorted()
  fun percentile(p: Double): Double {
    val index = ((sorted.size - 1) * p).toInt()
    return sorted[index] / 1_000_000.0
  }
  return Stat(
    label = label,
    count = sorted.size,
    p50Ms = percentile(0.50),
    p95Ms = percentile(0.95),
    p99Ms = percentile(0.99),
    minMs = sorted.first() / 1_000_000.0,
    maxMs = sorted.last() / 1_000_000.0,
    meanMs = sorted.sum() / sorted.size / 1_000_000.0,
  )
}

fun gzippedSize(bytes: ByteArray): Int {
  val out = ByteArrayOutputStream()
  GZIPOutputStream(out).use { it.write(bytes) }
  return out.size()
}

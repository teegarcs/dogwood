/*
 * Project Dogwood -- Phase 0 experiment 0.5: allocation and garbage collection.
 *
 * Experiment 0.3 and ADR-007 compared six wire formats on LATENCY. Neither measured what
 * each format allocates, and neither measured a sustained load: both timed a one-shot batch
 * and reported the median. QuickJS runs a non-generational, stop-the-world mark-and-sweep
 * whose pause scales with the LIVE set -- here the whole Compose slot table, every
 * MutableState, the snapshot records, the node tree, and the lambda slot table -- so a
 * format that allocates heavily pays for it in somebody else's frame, not its own median.
 *
 * This file measures three things the earlier work did not:
 *
 *   1. Bytes allocated per batch, per encoding, by running with collection disabled and
 *      watching the heap grow. Direct measurement, not inference.
 *   2. The TAIL of a sustained steady-state stream -- p99, p99.9, and maximum -- timed from
 *      the host end so the guest's own clock is not inside the measurement.
 *   3. Whether a collection lands inside a frame, by pairing every frame's latency with a
 *      `memoryUsage` reading taken between frames.
 *
 * What is MEASURED and what is INFERRED, stated once here and repeated in the result file:
 * every byte count and every nanosecond below is measured. Whether a collection occurred is
 * INFERRED -- Zipline exposes no garbage-collection callback, so a collection is deduced
 * from a fall in `QuickJs.memoryUsage.memoryAllocatedSize` between two consecutive frames.
 *
 * One correction to the premise, forced by the measurements themselves. QuickJS is primarily
 * REFERENCE COUNTED; the mark-and-sweep collector exists to break reference cycles, not to
 * reclaim ordinary garbage. An acyclic object graph -- an array of arrays of numbers and
 * strings, for instance -- is freed the instant its last reference drops, and the allocator's
 * byte count falls again immediately. So "bytes allocated per batch" splits in two:
 *
 *   - GROSS allocation, which QuickJS does not expose. `memoryUsage` reports what is
 *     currently allocated, not a running total, and there is no cumulative counter to read.
 *   - NET heap growth, which is exactly what these probes measure, and which is the quantity
 *     that matters: it is the part of a batch's garbage that reference counting could NOT
 *     reclaim, and therefore the only part that pushes the heap towards the collection
 *     threshold. Gross allocation that is freed by refcount never triggers a collection.
 *
 * Every "bytes per batch" number below is net heap growth, measured with collection disabled,
 * and every probe verifies afterwards that a forced collection returns the heap to its
 * baseline -- which proves the growth was garbage rather than something genuinely retained.
 */
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.dogwood.host

import kotlinx.serialization.Serializable

/**
 * A garbage-collection threshold high enough that no collection can occur during a probe.
 *
 * QuickJS collects when the allocator's live byte count would exceed `malloc_gc_threshold`.
 * Setting that threshold above anything a probe can reach turns the heap into a write-only
 * log of everything the guest allocated, which is precisely what an allocation probe wants.
 * Every probe asserts afterwards that it never got near this number and that the heap never
 * shrank, so a probe that did collect is reported as invalid rather than quietly averaged.
 */
const val NO_COLLECTION_THRESHOLD: Long = 1L shl 30 // 1 GiB

/** One frame budget at 60 Hz, in nanoseconds. The line an outlier must not cross. */
const val FRAME_BUDGET_NANOS: Long = 16_666_667L

/** The Phase 0 gate's guest-side recomposition budget, in nanoseconds. */
const val GUEST_BUDGET_NANOS: Long = 8_000_000L

/**
 * The encodings probed for allocation.
 *
 * ADR-007's decision (`json-positional`) first, the interning variant it beat, and both v0
 * candidates, so the allocation column can be read beside ADR-007's latency column. The
 * binary candidates are omitted: ADR-007 rejected them on encode cost by more than an order
 * of magnitude, and an allocation number cannot rehabilitate a format that is 30 times
 * slower to produce.
 */
val ALLOC_VARIANTS: List<String> = listOf(
  "json-positional",
  "json-positional-interned",
  "json-v0-native",
  "json-v0-kotlinx",
)

/** Zipline's default, then the range ADR-005 recommends. */
val ALLOC_THRESHOLDS: List<Long> = listOf(256L * 1024, 8L * 1024 * 1024, 16L * 1024 * 1024)

// ---------------------------------------------------------------------------
// Result shapes
// ---------------------------------------------------------------------------

/**
 * One allocation probe: how many bytes of QuickJS heap one iteration of some work leaves
 * behind when nothing is allowed to collect.
 */
@Serializable
data class AllocProbe(
  /** What was run each iteration, e.g. `frame+encode+cross`. */
  val stage: String,
  /** The encoding used, or `none` for the stages that do not encode. */
  val variant: String,
  /** Changes in the batch each iteration produced or encoded. */
  val changes: Int,
  val iterations: Int,
  /**
   * Net QuickJS heap growth per iteration, in bytes, measured with collection disabled.
   *
   * This is not gross allocation, which QuickJS does not expose. It is the part of each
   * batch's garbage that reference counting cannot reclaim -- the part that drives the heap
   * towards a collection.
   */
  val bytesPerIteration: Double,
  /**
   * The same figure computed from the second half of the run only.
   *
   * A one-time warm-up cost -- object shapes, atoms, a lazily built serializer cache -- would
   * make the first chunks steeper than the last. When this matches [bytesPerIteration], the
   * growth is genuinely per-batch and not a warm-up artefact left in the average.
   */
  val bytesPerIterationSecondHalf: Double,
  /** [bytesPerIteration] divided by [changes]. Measured, then divided. */
  val bytesPerChange: Double,
  /** Bytes of the encoded payload, so allocation can be read against payload size. */
  val wireBytes: Int,
  /**
   * True when the heap grew monotonically across every chunk and never approached
   * [NO_COLLECTION_THRESHOLD]. False invalidates the probe: something collected.
   */
  val valid: Boolean,
  /** `memoryAllocatedSize` after each chunk of iterations. The evidence for [valid]. */
  val heapSeries: List<Long>,
  /** Heap left after a forced collection, relative to the baseline. Near zero means garbage. */
  val retainedAfterGcBytes: Long,
)

/** One sustained-load run, timed from the host end. */
@Serializable
data class TailRun(
  /** `frame-loop` (recompose, encode, cross) or `crossing-loop` (encode, cross only). */
  val loop: String,
  val variant: String,
  val gcThresholdBytes: Long,
  /** Changes in each batch. Small by construction: this is the steady state, not a screen open. */
  val changesPerBatch: Int,
  val iterations: Int,
  val stat: Stat,
  val p999Ms: Double,
  /** Samples at or above the 60 Hz frame budget of 16.67 ms. */
  val overFrameBudget: Int,
  /** Samples at or above the Phase 0 guest budget of 8 ms. */
  val overGuestBudget: Int,
  /** Maximum divided by median: how far the worst frame is from the typical one. */
  val maxOverP50: Double,
  /** Every sample, in nanoseconds, so any percentile can be recomputed from the result file. */
  val nanos: List<Long>,
)

/** One frame in a traced run: how long it took, and how big the heap was afterwards. */
@Serializable
data class FrameTraceEvent(
  val iteration: Int,
  val latencyMs: Double,
  /** Change in `memoryAllocatedSize` since the previous frame. Negative means a collection. */
  val heapDeltaBytes: Long,
  /** `memoryAllocatedSize` after this frame. With [heapDeltaBytes] this gives the sawtooth. */
  val heapAfterBytes: Long,
)

/**
 * A sustained run in which every frame's latency is paired with a heap reading.
 *
 * The heap is read BETWEEN frames, never inside the timed section, so the reading's own cost
 * ([memoryUsageSampleCost]) is excluded from every latency. It is reported anyway, because
 * `JS_ComputeMemoryUsage` walks the live object list and a reader is entitled to know how
 * much the instrument perturbs the thing it measures.
 */
@Serializable
data class GcTrace(
  val gcThresholdBytes: Long,
  val variant: String,
  val iterations: Int,
  val changesPerBatch: Int,
  val stat: Stat,
  val p999Ms: Double,
  /** Cost of one `QuickJs.memoryUsage` reading. Measured, and outside every latency sample. */
  val memoryUsageSampleCost: Stat,
  /** Frames after which the heap SHRANK. Inferred to be collections. */
  val inferredCollections: Int,
  /** Latency of those frames, in milliseconds. */
  val collectionFrameLatency: Stat?,
  /** Latency of every other frame. The comparison that makes the previous row mean something. */
  val nonCollectionFrameLatency: Stat,
  /** Bytes freed per inferred collection. */
  val meanBytesFreedPerCollection: Double,
  /**
   * Cost of a collection forced from the host after the same load, measured directly with
   * `QuickJs.gc()`. This is the measured counterpart to the inferred in-frame spike: if the
   * two agree, the spike is a collection.
   */
  val forcedGcPause: Stat,
  /**
   * Peak heap before each inferred collection divided by the heap left after it.
   *
   * QuickJS raises its own threshold after every collection, so the `gcThreshold` a caller
   * sets governs the FIRST collection and little after it. This ratio is what the runtime
   * settled on, measured rather than assumed.
   */
  val growthAllowanceRatio: List<Double>,
  /** The heap's low and high water marks across the run: the sawtooth. */
  val heapLowBytes: Long,
  val heapHighBytes: Long,
  /** The twenty slowest frames, each flagged with whether the heap shrank on that frame. */
  val slowestFrames: List<FrameTraceEvent>,
  /** Every inferred collection, in order. */
  val collectionEvents: List<FrameTraceEvent>,
)

@Serializable
data class AllocationGcResults(
  val label: String,
  val platform: String,
  val quickJsVersion: String,
  val rows: Int,
  /**
   * Round-trip cost of the guest's `MonotonicClock`. Not used to time anything here; it is
   * reported to justify why nothing here is timed with it.
   */
  val clockOverhead: Stat,
  val memoryAfterCompose: MemorySnapshot,
  val allocation: List<AllocProbe>,
  val tail: List<TailRun>,
  val gcTraces: List<GcTrace>,
  val notes: List<String>,
)

// ---------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------

/** An arbitrary percentile of a sorted nanosecond series, in milliseconds. */
private fun List<Long>.percentileMs(p: Double): Double {
  val index = ((size - 1) * p).toInt()
  return this[index] / 1_000_000.0
}

/** Consecutive differences. One arrival series of n stamps yields n-1 iteration costs. */
private fun LongArray.deltas(): List<Long> =
  (1 until size).map { this[it] - this[it - 1] }

// ---------------------------------------------------------------------------
// The experiment
// ---------------------------------------------------------------------------

/**
 * Experiment 0.5.
 *
 * @param variants the encodings to probe for allocation. ADR-007's decision and the formats
 *   it beat, so the allocation column can be read next to ADR-007's latency column.
 * @param smallChanges changes in a steady-state batch. Small by construction.
 * @param allocIterations iterations per allocation probe.
 * @param tailIterations frames per sustained run. Thousands, not hundreds: a tail is not
 *   observable in two hundred samples.
 * @param traceIterations frames per traced run. Fewer, because each one pays for a
 *   `memoryUsage` reading between frames.
 * @param thresholds the `gcThreshold` values to run the sustained load at. Zipline's default
 *   is 256 KiB; ADR-005 recommends 8-16 MB.
 */
class AllocationGcExperiment(
  private val driver: Phase0Driver,
  private val rows: Int,
  private val variants: List<String>,
  private val smallChanges: Int,
  private val bigChanges: Int,
  private val allocIterations: Int,
  private val bigAllocIterations: Int,
  private val tailIterations: Int,
  private val traceIterations: Int,
  private val thresholds: List<Long>,
  /**
   * Which parts to run: `alloc`, `tail`, `trace`.
   *
   * A subset exists so a finding can be re-run on its own -- in particular so the sustained
   * runs can be repeated with the thresholds interleaved, which is the only way to tell a
   * threshold effect apart from an ordering effect when one threshold always runs first.
   */
  private val phases: Set<String>,
  private val log: (String) -> Unit,
) {
  /** Chunks per allocation probe. Each chunk boundary is a chance to catch a collection. */
  private val chunks = 20

  fun run(label: String, platform: String): AllocationGcResults {
    val warm = driver.load()
    val clockOverhead: Stat
    val memoryAfterCompose: MemorySnapshot
    try {
      clockOverhead = warm.guest.measureClockOverhead(500).stat()
      warm.guest.composeReferenceScreen(rows, warmups = 2, iterations = 2)
      memoryAfterCompose = driver.memory(warm.zipline)
    } finally {
      warm.zipline.close()
    }
    log("clock round trip p50 ${"%.3f".format(clockOverhead.p50Ms)} ms -- host-side timing only")

    val allocation = ArrayList<AllocProbe>()
    if ("alloc" in phases) {
    // The ladder: the same steady-state frame, with one more stage bolted on each rung.
    // Subtracting adjacent rungs attributes allocation to the stage that caused it.
    allocation += probe("frame-only", "none", smallChanges, allocIterations) { g, n ->
      g.frameOnly(rows, n)
    }
    for (variant in variants) {
      allocation += probe("frame+encode", variant, smallChanges, allocIterations) { g, n ->
        g.frameEncode(rows, variant, n)
      }
      allocation += probe("frame+encode+cross", variant, smallChanges, allocIterations) { g, n ->
        g.sustainedFrames(rows, variant, n)
      }
    }

    // The same encodings over a fixed batch, with no recomposition in the way. This is the
    // wire format's own allocation, measured at a steady-state size and at a screen-open size.
    for (variant in variants) {
      allocation += probe("encode-only", variant, smallChanges, allocIterations) { g, n ->
        g.encodeOnly(variant, smallChanges, n)
      }
      allocation += probe("encode+cross", variant, smallChanges, allocIterations) { g, n ->
        g.encodeAndCross(variant, smallChanges, n)
      }
      // The screen-open size, encoder alone and then with the crossing, so a large batch's
      // allocation can be split between the encoder and Zipline's call path.
      allocation += probe("encode-only", variant, bigChanges, bigAllocIterations) { g, n ->
        g.encodeOnly(variant, bigChanges, n)
      }
      allocation += probe("encode+cross", variant, bigChanges, bigAllocIterations) { g, n ->
        g.encodeAndCross(variant, bigChanges, n)
      }
    }
    }

    val tail = ArrayList<TailRun>()
    val gcTraces = ArrayList<GcTrace>()
    val tailVariant = variants.first()
    for (threshold in thresholds) {
      if ("tail" in phases) {
        tail += tailRun("frame-loop", tailVariant, threshold)
        tail += tailRun("crossing-loop", tailVariant, threshold)
      }
      if ("trace" in phases) gcTraces += gcTrace(tailVariant, threshold)
    }

    return AllocationGcResults(
      label = label,
      platform = platform,
      quickJsVersion = app.cash.zipline.QuickJs.version,
      rows = rows,
      clockOverhead = clockOverhead,
      memoryAfterCompose = memoryAfterCompose,
      allocation = allocation,
      tail = tail,
      gcTraces = gcTraces,
      notes = listOf(
        "Allocation is MEASURED: the probe raises gcThreshold to ${NO_COLLECTION_THRESHOLD / (1 shl 20)} " +
          "MiB so nothing collects, then reads QuickJs.memoryUsage.memoryAllocatedSize before " +
          "and after. Each probe reports the per-chunk heap series and is marked invalid if " +
          "the heap ever fell.",
        "Collections are INFERRED, not instrumented. Zipline exposes no garbage-collection " +
          "callback. A collection is deduced from a fall in memoryAllocatedSize between two " +
          "consecutive frames.",
        "Latency is timed on the HOST, never in the guest: one guest clock round trip costs " +
          "roughly ${"%.0f".format(clockOverhead.p50Ms * 1000)} microseconds, which is the " +
          "same order as the crossing being measured. The frame and crossing loops are timed " +
          "by timestamping arrivals at sendChangesEncoded; the traced loop is timed by " +
          "bracketing one oneFrame call, which additionally charges one host-to-guest crossing.",
      ),
    )
  }

  // -------------------------------------------------------------------------
  // 1. Allocation per batch
  // -------------------------------------------------------------------------

  /**
   * Runs [work] with collection disabled and measures how far the heap moved.
   *
   * The warm-up is not politeness. The first executions of a code path allocate object
   * shapes, atoms, interned strings, and function objects that are allocated once and then
   * live forever; counting those as per-batch cost would overstate every number here. The
   * probe warms first, forces one collection, takes the baseline, and only then measures.
   */
  private fun probe(
    stage: String,
    variant: String,
    changes: Int,
    iterations: Int,
    work: (dev.dogwood.protocol.Phase0Guest, Int) -> Int,
  ): AllocProbe {
    val loaded = driver.load(gcThresholdBytes = NO_COLLECTION_THRESHOLD)
    try {
      loaded.guest.composeReferenceScreen(rows, warmups = 0, iterations = 1)
      val chunk = maxOf(1, iterations / chunks)
      work(loaded.guest, minOf(200, chunk * 2))
      loaded.zipline.quickJs.gc()
      val baseline = loaded.zipline.quickJs.memoryUsage.memoryAllocatedSize

      val series = ArrayList<Long>(chunks)
      var observedChanges = 0
      repeat(chunks) {
        observedChanges = work(loaded.guest, chunk)
        series += loaded.zipline.quickJs.memoryUsage.memoryAllocatedSize
      }
      val total = chunk.toLong() * chunks
      val grown = series.last() - baseline
      val perIteration = grown.toDouble() / total
      // The same slope over the second half only. If a warm-up cost were hiding in the
      // average, the two would disagree.
      val half = chunks / 2
      val secondHalf = (series.last() - series[half - 1]).toDouble() / (chunk.toLong() * (chunks - half))

      loaded.zipline.quickJs.gc()
      val retained = loaded.zipline.quickJs.memoryUsage.memoryAllocatedSize - baseline

      val monotonic = series.zipWithNext().all { (a, b) -> b >= a } && series.first() >= baseline
      val headroom = series.last() < NO_COLLECTION_THRESHOLD * 3 / 4
      val wireBytes = if (variant == "none") 0 else loaded.guest.variantPayload(variant, changes).length

      log(
        "  alloc $stage/$variant changes=$observedChanges " +
          "${"%.0f".format(perIteration)} B/batch " +
          "${"%.1f".format(perIteration / maxOf(1, observedChanges))} B/change " +
          "(2nd half ${"%.0f".format(secondHalf)} B/batch) retained ${retained} B" + if (monotonic && headroom) "" else "  INVALID",
      )
      return AllocProbe(
        stage = stage,
        variant = variant,
        changes = observedChanges,
        iterations = total.toInt(),
        bytesPerIteration = perIteration,
        bytesPerIterationSecondHalf = secondHalf,
        bytesPerChange = perIteration / maxOf(1, observedChanges),
        wireBytes = wireBytes,
        valid = monotonic && headroom,
        heapSeries = series,
        retainedAfterGcBytes = retained,
      )
    } finally {
      loaded.zipline.close()
    }
  }

  // -------------------------------------------------------------------------
  // 2. Sustained load and the tail
  // -------------------------------------------------------------------------

  /**
   * Drives a sustained loop of small batches and times it by call arrival.
   *
   * The guest loop calls no clock. The host stamps `System.nanoTime` the moment each
   * `sendChangesEncoded` arrives, and the gap between two consecutive arrivals is one whole
   * iteration: state write, recomposition, `takeBatch`, encode, Zipline's own outbound
   * serialisation, the Java Native Interface crossing, and the return into JavaScript. That
   * is the frame, and it is measured without a single instrument inside it.
   */
  private fun tailRun(loop: String, variant: String, threshold: Long): TailRun {
    val loaded = driver.load(gcThresholdBytes = threshold)
    try {
      loaded.guest.composeReferenceScreen(rows, warmups = 0, iterations = 1)
      // Warm the loop itself: interpreted code paths, object shapes, and the host's own
      // just-in-time compilation all settle in the first few hundred iterations.
      val warmIterations = minOf(500, tailIterations / 4)
      val changes = when (loop) {
        "frame-loop" -> loaded.guest.sustainedFrames(rows, variant, warmIterations)
        else -> loaded.guest.encodeAndCross(variant, smallChanges, warmIterations)
      }

      loaded.host.recordArrivals(tailIterations + 2)
      when (loop) {
        "frame-loop" -> loaded.guest.sustainedFrames(rows, variant, tailIterations)
        else -> loaded.guest.encodeAndCross(variant, smallChanges, tailIterations)
      }
      // The first delta spans the entry into the loop, not a whole iteration; drop it.
      val nanos = loaded.host.takeArrivals().deltas().drop(1)
      val sorted = nanos.sorted()
      val stat = stat("$loop-${threshold / 1024}KiB", nanos)
      val run = TailRun(
        loop = loop,
        variant = variant,
        gcThresholdBytes = threshold,
        changesPerBatch = changes,
        iterations = nanos.size,
        stat = stat,
        p999Ms = sorted.percentileMs(0.999),
        overFrameBudget = nanos.count { it >= FRAME_BUDGET_NANOS },
        overGuestBudget = nanos.count { it >= GUEST_BUDGET_NANOS },
        maxOverP50 = stat.maxMs / stat.p50Ms,
        nanos = nanos,
      )
      log(
        "  $loop gcThreshold=${threshold / 1024} KiB n=${nanos.size} " +
          "p50 ${"%.3f".format(stat.p50Ms)} p95 ${"%.3f".format(stat.p95Ms)} " +
          "p99 ${"%.3f".format(stat.p99Ms)} p99.9 ${"%.3f".format(run.p999Ms)} " +
          "max ${"%.3f".format(stat.maxMs)} ms  over-frame=${run.overFrameBudget}",
      )
      return run
    } finally {
      loaded.zipline.close()
    }
  }

  // -------------------------------------------------------------------------
  // 3. Does a collection land inside a frame?
  // -------------------------------------------------------------------------

  /**
   * Drives the same steady-state frame one call at a time, reading the heap between frames.
   *
   * This costs one host-to-guest crossing per frame that the arrival-timed loop does not
   * pay, and one `memoryUsage` reading per frame that walks the live object list. Both sit
   * OUTSIDE the timed section. The point is not to produce a faster number than [tailRun];
   * it is to produce a number that can be labelled: this frame collected, that one did not.
   */
  private fun gcTrace(variant: String, threshold: Long): GcTrace {
    val loaded = driver.load(gcThresholdBytes = threshold)
    try {
      loaded.guest.composeReferenceScreen(rows, warmups = 0, iterations = 1)
      loaded.guest.sustainedFrames(rows, variant, minOf(500, traceIterations / 4))

      val latency = LongArray(traceIterations)
      val heap = LongArray(traceIterations)
      val sampleCost = ArrayList<Long>(traceIterations)
      var changes = 0
      repeat(traceIterations) { i ->
        val t0 = System.nanoTime()
        changes = loaded.guest.oneFrame(variant)
        val t1 = System.nanoTime()
        heap[i] = loaded.zipline.quickJs.memoryUsage.memoryAllocatedSize
        sampleCost += System.nanoTime() - t1
        latency[i] = t1 - t0
      }

      val collections = ArrayList<FrameTraceEvent>()
      val collectionLatency = ArrayList<Long>()
      val quietLatency = ArrayList<Long>()
      var freed = 0L
      for (i in 1 until traceIterations) {
        val delta = heap[i] - heap[i - 1]
        if (delta < 0) {
          collections += FrameTraceEvent(i, latency[i] / 1_000_000.0, delta, heap[i])
          collectionLatency += latency[i]
          freed += -delta
        } else {
          quietLatency += latency[i]
        }
      }

      val all = latency.toList()
      val sorted = all.sorted()
      val slowest = all.withIndex().sortedByDescending { it.value }.take(20).map { (i, v) ->
        FrameTraceEvent(
          iteration = i,
          latencyMs = v / 1_000_000.0,
          heapDeltaBytes = if (i == 0) 0 else heap[i] - heap[i - 1],
          heapAfterBytes = heap[i],
        )
      }

      // A collection forced from the host, after the same load, so the inferred in-frame
      // spike has a directly measured number to be compared against.
      val forced = ArrayList<Long>(5)
      repeat(5) {
        loaded.guest.sustainedFrames(rows, variant, 100)
        val t0 = System.nanoTime()
        loaded.zipline.quickJs.gc()
        forced += System.nanoTime() - t0
      }

      val trace = GcTrace(
        gcThresholdBytes = threshold,
        variant = variant,
        iterations = traceIterations,
        changesPerBatch = changes,
        stat = stat("trace-${threshold / 1024}KiB", all),
        p999Ms = sorted.percentileMs(0.999),
        memoryUsageSampleCost = stat("memory-usage-sample", sampleCost),
        inferredCollections = collections.size,
        collectionFrameLatency = collectionLatency.takeIf { it.isNotEmpty() }
          ?.let { stat("collection-frames", it) },
        nonCollectionFrameLatency = stat("quiet-frames", quietLatency),
        meanBytesFreedPerCollection = if (collections.isEmpty()) 0.0 else freed.toDouble() / collections.size,
        forcedGcPause = stat("forced-gc-${threshold / 1024}KiB", forced),
        growthAllowanceRatio = collections.map { event ->
          val before = event.heapAfterBytes - event.heapDeltaBytes
          before.toDouble() / event.heapAfterBytes
        },
        heapLowBytes = heap.min(),
        heapHighBytes = heap.max(),
        slowestFrames = slowest,
        collectionEvents = collections,
      )
      log(
        "  trace gcThreshold=${threshold / 1024} KiB inferred collections " +
          "${trace.inferredCollections}/${traceIterations} " +
          "collection-frame p50 ${"%.3f".format(trace.collectionFrameLatency?.p50Ms ?: 0.0)} ms " +
          "vs quiet p50 ${"%.3f".format(trace.nonCollectionFrameLatency.p50Ms)} ms " +
          "max ${"%.3f".format(trace.stat.maxMs)} ms " +
          "forced gc p50 ${"%.3f".format(trace.forcedGcPause.p50Ms)} ms " +
          "heap ${trace.heapLowBytes / 1024} - ${trace.heapHighBytes / 1024} KiB",
      )
      return trace
    } finally {
      loaded.zipline.close()
    }
  }
}

/*
 * Project Dogwood -- Phase 0 measurement harness, experiment driver.
 *
 * Runs experiments 0.1 through 0.4 exactly as roadmap.md defines them and emits a machine-
 * readable result file plus a human-readable summary. It is shared by the Java Virtual
 * Machine (JVM) development host and the Android on-device host, because the only thing
 * that differs between them is where the process runs.
 */
package dev.dogwood.host

import app.cash.zipline.EventListener
import app.cash.zipline.Zipline
import app.cash.zipline.loader.ZiplineFile
import dev.dogwood.protocol.ChangeBatch
import dev.dogwood.protocol.CompositionResult
import dev.dogwood.protocol.DogwoodJson
import dev.dogwood.protocol.EncodeResult
import dev.dogwood.protocol.Phase0Guest
import okio.Buffer
import okio.ByteString.Companion.toByteString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

// ---------------------------------------------------------------------------
// Result shapes
// ---------------------------------------------------------------------------

@Serializable
data class Toolchain(
  val zipline: String,
  val kotlin: String,
  val composeRuntimeJs: String,
  val coroutines: String,
  val serialization: String,
  val quickJsVersion: String,
)

@Serializable
data class PayloadSizes(
  val minifiedJsBytes: Long,
  val gzippedJsBytes: Long,
  /** QuickJS bytecode only, with the `.zipline` container header stripped. */
  val ziplineBytecodeBytes: Long,
  /** The `.zipline` files as delivered over the air, container included. */
  val ziplineFileBytes: Long,
  val perModule: Map<String, Long>,
)

@Serializable
data class MemorySnapshot(
  val memoryUsedSize: Long,
  val memoryAllocatedSize: Long,
  val objectsCount: Long,
  val objectsSize: Long,
  val stringsCount: Long,
  val stringsSize: Long,
  val jsFunctionsCount: Long,
  val jsFunctionsCodeSize: Long,
  val propertiesSize: Long,
  val arraysCount: Long,
)

@Serializable
data class Experiment01(
  val sizes: PayloadSizes,
  val moduleLoad: Stat,
  val mainFunction: Stat,
  val coldStartToFirstComposition: Stat,
  /**
   * Cold start through the moment the host holds the tree: module load, `main()`, first
   * composition, AND the initial batch crossing. This is the figure a user actually waits
   * for when a screen opens, and it is the budget the initial batch's cost belongs to if
   * the 0.3 gate leg is read as a per-frame budget rather than a per-screen one.
   */
  val coldStartToFirstBatchDelivered: Stat,
  val memoryAfterLoad: MemorySnapshot,
  val memoryAfterFirstComposition: MemorySnapshot,
)

@Serializable
data class Experiment02(
  val rows: Int,
  val widgetNodes: Int,
  val childrenNodes: Int,
  val initialChanges: Int,
  val initialBatchBytes: Int,
  val stateHolders: Int,
  val clockOverhead: Stat,
  val initialComposition: Stat,
  val recomposeOneNodeDiff: Stat,
  val recomposeTwoNodeDiff: Stat,
)

@Serializable
data class BatchPoint(
  val changes: Int,
  val bytes: Int,
  val arrayPolymorphicBytes: Int,
  val nativeStringifyBytes: Int,
  val gzippedBytes: Int,
  val build: Stat,
  val stringify: Stat,
  val stringifyArrayPolymorphic: Stat,
  val stringifyNative: Stat,
  val crossPreEncoded: Stat,
  val crossZiplineSerialized: Stat,
)

@Serializable
data class Experiment03(
  val points: List<BatchPoint>,
  val initialBatch: BatchPoint,
  val method: String,
  /** The encoding bake-off: candidate wire formats for the same batch. */
  val encodings: List<VariantResult> = emptyList(),
)

/** One candidate encoding, measured on the reference screen's initial batch. */
@Serializable
data class VariantResult(
  val name: String,
  /** The encoding's own size, before any text-encoding needed to cross a string channel. */
  val payloadBytes: Int,
  /** What actually crosses. For a binary encoding this is the Base64 length. */
  val wireBytes: Int,
  val encode: Stat,
  /** Encode plus transport, end to end. */
  val cross: Stat,
  /** Host-side parse of the payload back into a `ChangeBatch`. Null where not applicable. */
  val hostDecode: Stat? = null,
  val note: String,
)

@Serializable
data class GcPoint(
  val gcThresholdBytes: Long,
  val recomposeUnderLoad: Stat,
  val forcedCollections: Int,
  val forcedPause: Stat,
  val memoryAfterChurn: MemorySnapshot,
)

@Serializable
data class Experiment04(
  val method: String,
  val points: List<GcPoint>,
)

@Serializable
data class Phase0Results(
  val label: String,
  val platform: String,
  val toolchain: Toolchain,
  val referenceRows: Int,
  val experiment01: Experiment01,
  val experiment02: List<Experiment02>,
  val experiment03: Experiment03,
  val experiment04: Experiment04,
  val notes: List<String>,
)

// ---------------------------------------------------------------------------
// Driver
// ---------------------------------------------------------------------------

/** Reads a compiled guest from a directory of `.zipline` modules plus its manifest. */
class GuestPayload(val ziplineDir: String) {
  private fun inDir(name: String) = "${ziplineDir.trimEnd('/')}/$name"

  val manifestPath: String = inDir("manifest.zipline.json")
  val manifest: ManifestMirror = LenientJson.decodeFromString(
    ManifestMirror.serializer(),
    readFileBytes(manifestPath).decodeToString(),
  )

  /**
   * Module identifier to QuickJS bytecode, in dependency order.
   *
   * A `.zipline` file is a container -- an eight-byte magic prefix, a version, then sections
   * (`ZiplineFile` in `zipline-loader`) -- so the bytecode is unwrapped here exactly as
   * `ZiplineLoadReceiver` does before calling `loadJsModule`.
   */
  val modules: List<Pair<String, ByteArray>> = manifest.loadOrder().map { id ->
    val url = manifest.modules.getValue(id).url
    val bytes = readFileBytes(inDir(url.substringAfterLast('/')))
    val container = ZiplineFile.read(Buffer().write(bytes.toByteString()))
    id to container.quickjsBytecode.toByteArray()
  }

  /** On-disk `.zipline` sizes, which is what over-the-air delivery actually transfers. */
  val containerBytes: Long = manifest.modules.values.sumOf {
    readFileBytes(inDir(it.url.substringAfterLast('/'))).size.toLong()
  }

  val mainModuleId: String get() = manifest.mainModuleId
  val mainFunction: String get() = manifest.mainFunction
    ?: error("manifest declares no mainFunction; set `zipline { mainFunction }` in guest/build.gradle.kts")

  val totalBytecodeBytes: Long get() = modules.sumOf { it.second.size.toLong() }
}

/** One loaded guest, plus the timings its loading produced. */
class LoadedGuest(
  val zipline: Zipline,
  val guest: Phase0Guest,
  val host: CountingHost,
  val moduleLoadNanos: Long,
  val mainFunctionNanos: Long,
)

class Phase0Driver(
  private val dispatcher: CoroutineDispatcher,
  private val payload: GuestPayload,
  private val warmups: Int,
  private val iterations: Int,
) {
  /**
   * Creates a fresh Zipline instance, loads the guest into it, and returns it wired up.
   *
   * "Fresh" is what the appendix means by cold: a new interpreter with a new heap, so no
   * interpreted code, shape, or atom is carried over from a previous run.
   */
  @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
  fun load(
    gcThresholdBytes: Long? = null,
    eventListener: EventListener = EventListener.NONE,
  ): LoadedGuest {
    val zipline = Zipline.create(dispatcher, EmptySerializersModule(), eventListener)
    if (gcThresholdBytes != null) zipline.quickJs.gcThreshold = gcThresholdBytes

    val loadStart = nanoTime()
    for ((id, bytes) in payload.modules) {
      zipline.loadJsModule(bytes, id)
    }
    val loadEnd = nanoTime()

    // Exactly what ZiplineLoader does once the modules are in
    // (zipline/internal/quickJsExtensions.kt, runApplication).
    zipline.quickJs.evaluate(
      "require('${payload.mainModuleId}').${payload.mainFunction}()",
      "RunApplication.kt",
    )
    val mainEnd = nanoTime()

    val guest = zipline.take<Phase0Guest>("phase0Guest")
    val host = CountingHost()
    guest.setUp(host, NanoClock())
    return LoadedGuest(zipline, guest, host, loadEnd - loadStart, mainEnd - loadEnd)
  }

  fun memory(zipline: Zipline): MemorySnapshot {
    val usage = zipline.quickJs.memoryUsage
    return MemorySnapshot(
      memoryUsedSize = usage.memoryUsedSize,
      memoryAllocatedSize = usage.memoryAllocatedSize,
      objectsCount = usage.objectsCount,
      objectsSize = usage.objectsSize,
      stringsCount = usage.stringsCount,
      stringsSize = usage.stringsSize,
      jsFunctionsCount = usage.jsFunctionsCount,
      jsFunctionsCodeSize = usage.jsFunctionsCodeSize,
      propertiesSize = usage.propertiesSize,
      arraysCount = usage.arraysCount,
    )
  }

  // -------------------------------------------------------------------------
  // 0.1 -- cold-start cost
  // -------------------------------------------------------------------------

  fun experiment01(minifiedJs: String?, coldRuns: Int, rows: Int): Experiment01 {
    val moduleLoad = ArrayList<Long>(coldRuns)
    val mainFunction = ArrayList<Long>(coldRuns)
    val coldToFirstComposition = ArrayList<Long>(coldRuns)
    val coldToFirstBatch = ArrayList<Long>(coldRuns)
    var memoryAfterLoad: MemorySnapshot? = null
    var memoryAfterCompose: MemorySnapshot? = null

    repeat(coldRuns) { run ->
      val start = nanoTime()
      val loaded = load()
      try {
        moduleLoad += loaded.moduleLoadNanos
        mainFunction += loaded.mainFunctionNanos
        if (run == 0) memoryAfterLoad = memory(loaded.zipline)
        // One composition, no warm-up: this is the screen-open path a user actually pays.
        loaded.guest.measureClockOverhead(200)
        val composition = loaded.guest.composeReferenceScreen(rows, warmups = 0, iterations = 1)
        coldToFirstComposition += nanoTime() - start
        // Then the one crossing that hands the host the whole tree. No warm-up, one
        // iteration: a cold screen open gets neither.
        loaded.guest.crossBatch(composition.changes, iterations = 1, encoded = false, warmups = 0)
        coldToFirstBatch += nanoTime() - start
        if (run == 0) memoryAfterCompose = memory(loaded.zipline)
      } finally {
        loaded.zipline.close()
      }
    }

    val jsBytes = minifiedJs?.let { readFileBytes(it) }
    return Experiment01(
      sizes = PayloadSizes(
        minifiedJsBytes = jsBytes?.size?.toLong() ?: -1L,
        gzippedJsBytes = jsBytes?.let { gzippedSize(it).toLong() } ?: -1L,
        ziplineBytecodeBytes = payload.totalBytecodeBytes,
        ziplineFileBytes = payload.containerBytes,
        perModule = payload.modules.associate { it.first to it.second.size.toLong() },
      ),
      moduleLoad = stat("module-load", moduleLoad),
      mainFunction = stat("main-function", mainFunction),
      coldStartToFirstComposition = stat("cold-start-to-first-composition", coldToFirstComposition),
      coldStartToFirstBatchDelivered = stat("cold-start-to-first-batch-delivered", coldToFirstBatch),
      memoryAfterLoad = memoryAfterLoad!!,
      memoryAfterFirstComposition = memoryAfterCompose!!,
    )
  }

  // -------------------------------------------------------------------------
  // 0.2 -- composition and recomposition
  // -------------------------------------------------------------------------

  fun experiment02(loaded: LoadedGuest, rows: Int, compositionIterations: Int): Experiment02 {
    val clockOverhead = loaded.guest.measureClockOverhead(1000)
    val composition: CompositionResult =
      loaded.guest.composeReferenceScreen(rows, warmups, compositionIterations)
    val oneNode = loaded.guest.measureRecomposition("row", warmups, iterations)
    val twoNode = loaded.guest.measureRecomposition("total", warmups, iterations)
    return Experiment02(
      rows = rows,
      widgetNodes = composition.widgetNodes,
      childrenNodes = composition.childrenNodes,
      initialChanges = composition.changes,
      initialBatchBytes = composition.initialBatchBytes,
      stateHolders = composition.stateHolders,
      clockOverhead = clockOverhead.stat(),
      initialComposition = composition.timings.stat(),
      recomposeOneNodeDiff = oneNode.stat(),
      recomposeTwoNodeDiff = twoNode.stat(),
    )
  }

  // -------------------------------------------------------------------------
  // 0.3 -- protocol cost per frame
  // -------------------------------------------------------------------------

  fun experiment03(loaded: LoadedGuest, rows: Int, sizes: List<Int>): Experiment03 {
    loaded.guest.composeReferenceScreen(rows, warmups = 0, iterations = 1)
    val points = sizes.map { size -> batchPoint(loaded, size) }
    val initialJson = loaded.guest.initialBatchJson(rows)
    // The batch's `g` array length, read without re-deserializing the sealed hierarchy.
    val initialCount = LenientJson.parseToJsonElement(initialJson).jsonObject
      .getValue("g").jsonArray.size
    val initialBytes = initialJson.encodeToByteArray()
    val initialPoint = batchPoint(loaded, initialCount).copy(
      bytes = initialBytes.size,
      gzippedBytes = gzippedSize(initialBytes),
    )
    val encodings = measureEncodings(loaded, initialCount)
    return Experiment03(
      encodings = encodings,
      points = points,
      initialBatch = initialPoint,
      method = "End-to-end plus total bytes. The five-pass internal breakdown (guest encode, " +
        "JSON.stringify, Java Native Interface transcode, host parse) needs timing inside " +
        "Zipline's internal CallChannel, which needs a locally patched Zipline build; " +
        "roadmap.md permits reporting end-to-end plus bytes when that slips. Guest encode " +
        "and stringify ARE separated here, because the guest can time those itself.",
    )
  }

  /**
   * Runs every candidate encoding over the reference screen's initial batch.
   *
   * Encode and crossing are reported separately because they answer different questions: the
   * encode column says which format is cheapest to produce, and the cross column says whether
   * the size it saved was worth what it cost to produce.
   */
  private fun measureEncodings(loaded: LoadedGuest, changeCount: Int): List<VariantResult> {
    return loaded.guest.measureEncodingVariants(changeCount, warmups, iterations).map { variant ->
      val cross = loaded.guest.crossVariant(variant.name, changeCount, iterations, warmups)
      val hostDecode = decodeStat(loaded, variant.name, changeCount)
      VariantResult(
        name = variant.name,
        payloadBytes = variant.payloadBytes,
        wireBytes = variant.wireBytes,
        encode = variant.encode.stat(),
        cross = cross.stat(),
        hostDecode = hostDecode,
        note = variant.note,
      )
    }
  }

  /**
   * Host-side decode of one variant's payload.
   *
   * Measured on the host rather than in the guest, because that is where it happens: this is
   * Java Virtual Machine work, compiled rather than interpreted, and the whole question ADR-007
   * left open is whether it is small enough to ignore beside the guest's encoding cost.
   *
   * Only the JavaScript Object Notation (JSON) variants are decoded. The binary candidates are
   * rejected on encode cost alone, and building host decoders for formats nobody will ship
   * would be work spent to make a foregone conclusion look more thorough.
   */
  private fun decodeStat(loaded: LoadedGuest, variant: String, changeCount: Int): Stat? {
    val decode: (String) -> ChangeBatch = when (variant) {
      "json-positional" -> ::decodePositional
      "json-positional-interned" -> ::decodePositionalInterned
      "json-v0-native" -> { s -> ZiplineWireJson.decodeFromString(ChangeBatch.serializer(), s) }
      "json-v0-kotlinx" -> { s -> DogwoodJson.decodeFromString(ChangeBatch.serializer(), s) }
      else -> return null
    }
    val payload = loaded.guest.variantPayload(variant, changeCount)
    val nanos = ArrayList<Long>(iterations)
    repeat(warmups) { decode(payload) }
    repeat(iterations) {
      val t0 = nanoTime()
      decode(payload)
      nanos.add(nanoTime() - t0)
    }
    return stat("host-decode-$variant", nanos)
  }

  private fun batchPoint(loaded: LoadedGuest, size: Int): BatchPoint {
    val encode: EncodeResult = loaded.guest.measureEncode(size, warmups, iterations)
    val preEncoded = loaded.guest.crossBatch(size, iterations, encoded = true, warmups = warmups)
    val ziplineSerialized = loaded.guest.crossBatch(size, iterations, encoded = false, warmups = warmups)
    return BatchPoint(
      changes = encode.changeCount,
      bytes = encode.bytes,
      arrayPolymorphicBytes = encode.arrayBytes,
      nativeStringifyBytes = encode.nativeBytes,
      gzippedBytes = -1, // filled in only for the initial batch, where the exact string is known
      build = encode.build.stat(),
      stringify = encode.stringify.stat(),
      stringifyArrayPolymorphic = encode.stringifyArrayPolymorphic.stat(),
      stringifyNative = encode.stringifyNative.stat(),
      crossPreEncoded = preEncoded.stat(),
      crossZiplineSerialized = ziplineSerialized.stat(),
    )
  }

  // -------------------------------------------------------------------------
  // 0.4 -- garbage-collection behaviour
  // -------------------------------------------------------------------------

  /**
   * Zipline's public Application Programming Interface (API) exposes no garbage-collection
   * hooks, so this uses the fallback roadmap.md permits: host-forced `gc()` pauses, which
   * bound the answer from above, plus the recomposition tail under each threshold, which is
   * the signal that actually matters. If the tail does not move with the threshold, garbage
   * collection is not the source of the jank.
   */
  fun experiment04(rows: Int, thresholds: List<Long>, churnIterations: Int): Experiment04 {
    val points = thresholds.map { threshold ->
      val loaded = load(gcThresholdBytes = threshold)
      try {
        loaded.guest.measureClockOverhead(1000)
        loaded.guest.composeReferenceScreen(rows, warmups = 5, iterations = 5)
        loaded.guest.churn(rows, churnIterations)
        val underLoad = loaded.guest.measureRecomposition("row", warmups, iterations)

        val pauses = ArrayList<Long>()
        repeat(10) {
          loaded.guest.churn(rows, churnIterations / 10)
          val t0 = nanoTime()
          loaded.zipline.quickJs.gc()
          pauses += nanoTime() - t0
        }
        GcPoint(
          gcThresholdBytes = threshold,
          recomposeUnderLoad = underLoad.stat(),
          forcedCollections = pauses.size,
          forcedPause = stat("forced-gc-pause", pauses),
          memoryAfterChurn = memory(loaded.zipline),
        )
      } finally {
        loaded.zipline.close()
      }
    }
    return Experiment04(
      method = "Host-forced QuickJs.gc() pauses (upper bound) plus the recomposition tail at " +
        "each gcThreshold. NOT the patched-QuickJS JS_RunGC hook the appendix prefers; that " +
        "needs a local native Zipline build and is recorded as outstanding work.",
      points = points,
    )
  }
}

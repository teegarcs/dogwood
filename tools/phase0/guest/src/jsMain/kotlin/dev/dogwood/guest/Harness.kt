/*
 * Project Dogwood -- Phase 0 measurement harness, guest half.
 *
 * Everything in this file is measurement scaffolding. None of it ships.
 *
 * Timing rules, from the Phase 0 harness appendix in roadmap.md:
 *   - Time is read through a host-injected monotonic clock, because the pinned QuickJS has
 *     no `performance.now` and `Date.now` is millisecond-granular.
 *   - The clock's own round-trip cost is measured first and subtracted from every sample.
 *   - Recomposition timers start after event dispatch and stop when the batch is handed to
 *     `sendChanges`, so the bridge cost is excluded here and measured separately in 0.3.
 *   - Two hundred iterations after twenty warm-ups; the host computes p50/p95/p99.
 */
package dev.dogwood.guest

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import app.cash.zipline.Zipline
import dev.dogwood.protocol.Change
import dev.dogwood.protocol.ChangeBatch
import dev.dogwood.protocol.CompositionResult
import dev.dogwood.protocol.DogwoodHost
import dev.dogwood.protocol.DogwoodJson
import dev.dogwood.protocol.DogwoodJsonArrayPolymorphic
import dev.dogwood.protocol.EncodeResult
import dev.dogwood.protocol.EncodingVariant
import dev.dogwood.protocol.Id
import dev.dogwood.protocol.MonotonicClock
import dev.dogwood.protocol.Phase0Guest
import dev.dogwood.protocol.Samples
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.js.ExperimentalJsExport
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToDynamic
import kotlin.js.JsExport
import kotlinx.coroutines.launch

/**
 * One live composition of the reference screen, driven by a frame clock the harness ticks
 * by hand.
 *
 * The recomposer runs on [Dispatchers.Unconfined] so that `sendFrame` resumes it on the
 * caller's own stack. That makes each measured recomposition synchronous and therefore
 * timeable; it does not change what work is done, only when control returns.
 */
private class LiveComposition(rows: Int) {
  val recorder = ChangeRecorder()
  val lambdas = LambdaSlots()
  val state = ReferenceScreenState()

  /** The root is node zero with slot one, exactly as ADR-004 section 2.4 shows. */
  private val root = WidgetNode(Id(0), Tags.Column)
  private val applier = DogwoodApplier(root, recorder, lambdas)
  val frameClock = BroadcastFrameClock()
  private val scope = CoroutineScope(frameClock + Dispatchers.Unconfined + Job())
  private val recomposer = Recomposer(scope.coroutineContext)
  private val composition = Composition(applier, recomposer)

  /** The batch produced by the initial composition, kept for experiment 0.3. */
  val initialBatch: ChangeBatch

  init {
    recording = RecordingContext(recorder, lambdas)
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      recomposer.runRecomposeAndApplyChanges()
    }
    composition.setContent {
      Children(Tags.Content) { ReferenceScreen(state, rows) }
    }
    initialBatch = recorder.takeBatch()
  }

  /** Applies pending state writes and drives exactly one frame, synchronously. */
  fun pumpFrame() {
    Snapshot.sendApplyNotifications()
    frameClock.sendFrame(0L)
  }

  fun widgetNodeCount(): Int = countWidgets(root) - 1 // the synthetic root is not a widget

  fun childrenNodeCount(): Int = countSlots(root)

  private fun countWidgets(node: WidgetNode): Int {
    var total = 1
    for (slot in node.slots) for (child in slot.children) total += countWidgets(child)
    return total
  }

  private fun countSlots(node: WidgetNode): Int {
    var total = node.slots.size
    for (slot in node.slots) for (child in slot.children) total += countSlots(child)
    return total
  }

  fun dispose() {
    composition.dispose()
    recomposer.cancel()
    scope.cancel()
  }
}

class Phase0GuestImpl : Phase0Guest {
  private var host: DogwoodHost? = null
  private var clock: MonotonicClock? = null

  /** Median round-trip cost of one [MonotonicClock.nowNanos] call, subtracted from samples. */
  private var clockOverheadNanos = 0L

  private var live: LiveComposition? = null
  private var liveRows = -1

  override fun setUp(host: DogwoodHost, clock: MonotonicClock) {
    this.host = host
    this.clock = clock
  }

  private fun clock(): MonotonicClock = clock ?: error("setUp was never called")

  private fun host(): DogwoodHost = host ?: error("setUp was never called")

  private fun live(rows: Int): LiveComposition {
    val existing = live
    if (existing != null && liveRows == rows) return existing
    existing?.dispose()
    val fresh = LiveComposition(rows)
    live = fresh
    liveRows = rows
    return fresh
  }

  override fun measureClockOverhead(iterations: Int): Samples {
    val c = clock()
    val nanos = ArrayList<Long>(iterations)
    // Warm the call path before recording; the first crossings pay one-time costs.
    repeat(20) { c.nowNanos() }
    repeat(iterations) {
      val t0 = c.nowNanos()
      val t1 = c.nowNanos()
      nanos.add(t1 - t0)
    }
    clockOverheadNanos = nanos.sorted()[nanos.size / 2]
    return Samples("clock-overhead", nanos)
  }

  override fun composeReferenceScreen(rows: Int, warmups: Int, iterations: Int): CompositionResult {
    val c = clock()
    val nanos = ArrayList<Long>(iterations)
    var last: LiveComposition? = null
    repeat(warmups + iterations) { i ->
      last?.dispose()
      val t0 = c.nowNanos()
      val composition = LiveComposition(rows)
      val t1 = c.nowNanos()
      if (i >= warmups) nanos.add(t1 - t0 - clockOverheadNanos)
      last = composition
    }
    val result = last ?: error("no composition was built")
    // Keep the last one alive as the target for recomposition measurement.
    live?.takeIf { it !== result }?.dispose()
    live = result
    liveRows = rows
    val json = DogwoodJson.encodeToString(ChangeBatch.serializer(), result.initialBatch)
    return CompositionResult(
      widgetNodes = result.widgetNodeCount(),
      childrenNodes = result.childrenNodeCount(),
      changes = result.initialBatch.g.size,
      initialBatchBytes = json.encodeToByteArray().size,
      stateHolders = result.state.holderCount,
      timings = Samples("initial-composition-rows-$rows", nanos),
    )
  }

  override fun measureRecomposition(kind: String, warmups: Int, iterations: Int): Samples {
    val c = clock()
    val composition = live(if (liveRows > 0) liveRows else REFERENCE_ROWS)
    val nanos = ArrayList<Long>(iterations)
    // Starts at one: the screen's initial total is "$0.00", and writing the value it
    // already holds is not a state change, so iteration zero would measure nothing.
    var counter = 1
    repeat(warmups + iterations) { i ->
      val t0 = c.nowNanos()
      when (kind) {
        // The one-node diff: exactly one row-selection holder, read by exactly one Text.
        "row" -> composition.state.toggleRow(19)
        // The two-node diff: the header price Text and the footer button label.
        "total" -> composition.state.total = "$${counter++}.00"
        else -> error("unknown recomposition kind: $kind")
      }
      composition.pumpFrame()
      val batch = composition.recorder.takeBatch()
      val t1 = c.nowNanos()
      if (i >= warmups) nanos.add(t1 - t0 - clockOverheadNanos)
      // Guard against a silent no-op: a measurement of zero work is not a fast measurement.
      check(batch.g.isNotEmpty()) { "recomposition '$kind' produced no changes at iteration $i" }
    }
    return Samples("recompose-$kind", nanos)
  }

  override fun measureEncode(changeCount: Int, warmups: Int, iterations: Int): EncodeResult {
    val c = clock()
    val composition = live(if (liveRows > 0) liveRows else REFERENCE_ROWS)
    val source = composition.initialBatch.g
    val build = ArrayList<Long>(iterations)
    val stringify = ArrayList<Long>(iterations)
    val stringifyArray = ArrayList<Long>(iterations)
    val stringifyNative = ArrayList<Long>(iterations)
    var bytes = 0
    var arrayBytes = 0
    var nativeBytes = 0
    var count = 0
    repeat(warmups + iterations) { i ->
      val t0 = c.nowNanos()
      val changes = ArrayList<Change>(changeCount)
      for (n in 0 until changeCount) changes.add(source[n % source.size])
      val batch = ChangeBatch(1, changes)
      val t1 = c.nowNanos()
      val json = DogwoodJson.encodeToString(ChangeBatch.serializer(), batch)
      val t2 = c.nowNanos()
      val arrayJson = DogwoodJsonArrayPolymorphic.encodeToString(ChangeBatch.serializer(), batch)
      val t3 = c.nowNanos()
      val nativeJson = encodeViaNativeStringify(batch)
      val t4 = c.nowNanos()
      if (i >= warmups) {
        build.add(t1 - t0 - clockOverheadNanos)
        stringify.add(t2 - t1 - clockOverheadNanos)
        stringifyArray.add(t3 - t2 - clockOverheadNanos)
        stringifyNative.add(t4 - t3 - clockOverheadNanos)
      }
      bytes = json.encodeToByteArray().size
      arrayBytes = arrayJson.encodeToByteArray().size
      nativeBytes = nativeJson.encodeToByteArray().size
      count = changes.size
    }
    return EncodeResult(
      changeCount = count,
      bytes = bytes,
      arrayBytes = arrayBytes,
      build = Samples("build-$changeCount", build),
      stringify = Samples("stringify-discriminator-$changeCount", stringify),
      stringifyArrayPolymorphic = Samples("stringify-arraypolymorphic-$changeCount", stringifyArray),
      stringifyNative = Samples("stringify-native-$changeCount", stringifyNative),
      nativeBytes = nativeBytes,
    )
  }

  override fun crossBatch(changeCount: Int, iterations: Int, encoded: Boolean, warmups: Int): Samples {
    val c = clock()
    val h = host()
    val composition = live(if (liveRows > 0) liveRows else REFERENCE_ROWS)
    val source = composition.initialBatch.g
    val changes = ArrayList<Change>(changeCount)
    for (n in 0 until changeCount) changes.add(source[n % source.size])
    val batch = ChangeBatch(1, changes)
    // Encoded only when the caller asked for the pre-encoded path. Building it
    // unconditionally would charge a caller measuring a cold screen open for an encode that
    // a real screen open never performs.
    val json = if (encoded) DogwoodJson.encodeToString(ChangeBatch.serializer(), batch) else ""
    val nanos = ArrayList<Long>(iterations)
    repeat(warmups) { if (encoded) h.sendChangesEncoded(json) else h.sendChanges(batch) }
    repeat(iterations) {
      val t0 = c.nowNanos()
      if (encoded) h.sendChangesEncoded(json) else h.sendChanges(batch)
      val t1 = c.nowNanos()
      nanos.add(t1 - t0 - clockOverheadNanos)
    }
    return Samples("cross-$changeCount-${if (encoded) "preencoded" else "zipline"}", nanos)
  }

  /**
   * The candidate encodings, all of the same batch. Named so the host can cross a chosen one.
   *
   * Each variant is timed end to end from the in-memory `List<Change>` to the string that
   * would cross, which is the only comparison that means anything: an encoding that produces
   * fewer bytes but takes longer to produce them is not a win on a boundary whose cost is
   * dominated by guest-side work.
   */
  private fun buildVariant(name: String, batch: ChangeBatch): Pair<String, Int> = when (name) {
    // Today's real cost: array polymorphism through Zipline's own native path.
    "json-v0-native" -> {
      val json = encodeViaNativeStringify(batch)
      json to json.length
    }
    // ADR-004's documented rendering, through kotlinx's pure-Kotlin encoder.
    "json-v0-kotlinx" -> {
      val json = DogwoodJson.encodeToString(ChangeBatch.serializer(), batch)
      json to json.length
    }
    "json-positional" -> {
      val json = encodePositional(batch)
      json to json.length
    }
    "json-positional-interned" -> {
      val json = encodePositionalInterned(batch)
      json to json.length
    }
    "protobuf-base64" -> {
      val bytes = encodeProtobufBytes(batch)
      toWireString(bytes) to bytes.size
    }
    "cbor-base64" -> {
      val bytes = encodeCborBytes(batch)
      toWireString(bytes) to bytes.size
    }
    else -> error("unknown encoding variant: ${'$'}name")
  }

  private val variantNames = listOf(
    "json-v0-kotlinx",
    "json-v0-native",
    "json-positional",
    "json-positional-interned",
    "protobuf-base64",
    "cbor-base64",
  )

  private val variantNotes = mapOf(
    "json-v0-kotlinx" to "ADR-004 section 2.2 as documented, through kotlinx.serialization's " +
      "pure-Kotlin encoder. Not what ships; included as the baseline the schema was written against.",
    "json-v0-native" to "What ships today: array polymorphism through encodeToDynamic plus " +
      "QuickJS's native JSON.stringify, which is the path Zipline's CallChannel takes.",
    "json-positional" to "Every change becomes a positional array, so no field names cross. " +
      "Built as native JavaScript values and handed straight to JSON.stringify.",
    "json-positional-interned" to "Positional, plus a modifier-chain table: each distinct chain " +
      "crosses once and is referenced by index thereafter.",
    "protobuf-base64" to "Protocol buffers over a schema mirror, because ADR-004's JsonElement " +
      "values have no protocol-buffer representation. Base64 because CallChannel carries a string.",
    "cbor-base64" to "Concise Binary Object Representation over the same schema mirror, same " +
      "Base64 surcharge. Included so the answer covers binary formats generally, not just one.",
  )

  private fun sourceBatch(changeCount: Int): ChangeBatch {
    val composition = live(if (liveRows > 0) liveRows else REFERENCE_ROWS)
    val source = composition.initialBatch.g
    val changes = ArrayList<Change>(changeCount)
    for (n in 0 until changeCount) changes.add(source[n % source.size])
    return ChangeBatch(1, changes)
  }

  override fun measureEncodingVariants(
    changeCount: Int,
    warmups: Int,
    iterations: Int,
  ): List<EncodingVariant> {
    val c = clock()
    val batch = sourceBatch(changeCount)
    return variantNames.map { name ->
      val nanos = ArrayList<Long>(iterations)
      var wire = 0
      var payload = 0
      repeat(warmups + iterations) { i ->
        val t0 = c.nowNanos()
        val (text, payloadBytes) = buildVariant(name, batch)
        val t1 = c.nowNanos()
        if (i >= warmups) nanos.add(t1 - t0 - clockOverheadNanos)
        wire = text.encodeToByteArray().size
        payload = payloadBytes
      }
      EncodingVariant(
        name = name,
        payloadBytes = payload,
        wireBytes = wire,
        encode = Samples("encode-${'$'}name-${'$'}changeCount", nanos),
        note = variantNotes.getValue(name),
      )
    }
  }

  override fun variantPayload(variant: String, changeCount: Int): String =
    buildVariant(variant, sourceBatch(changeCount)).first

  override fun crossVariant(
    variant: String,
    changeCount: Int,
    iterations: Int,
    warmups: Int,
  ): Samples {
    val c = clock()
    val h = host()
    val batch = sourceBatch(changeCount)
    val nanos = ArrayList<Long>(iterations)
    repeat(warmups) {
      val (text, _) = buildVariant(variant, batch)
      h.sendChangesEncoded(text)
    }
    repeat(iterations) {
      val t0 = c.nowNanos()
      val (text, _) = buildVariant(variant, batch)
      h.sendChangesEncoded(text)
      val t1 = c.nowNanos()
      nanos.add(t1 - t0 - clockOverheadNanos)
    }
    return Samples("cross-${'$'}variant-${'$'}changeCount", nanos)
  }

  override fun churn(rows: Int, iterations: Int) {
    val composition = live(rows)
    repeat(iterations) { i ->
      composition.state.toggleRow(i)
      composition.state.total = "$$i.00"
      composition.pumpFrame()
      composition.recorder.takeBatch()
    }
  }

  override fun initialBatchJson(rows: Int): String {
    val composition = live(rows)
    return DogwoodJson.encodeToString(ChangeBatch.serializer(), composition.initialBatch)
  }
}

/**
 * The encoding Zipline itself performs on Kotlin/JavaScript.
 *
 * `Endpoint.json` sets `useArrayPolymorphism = true`, and `encodeToStringFast` on the
 * JavaScript side is literally `JSON.stringify(encodeToDynamic(serializer, value))`
 * (zipline/src/jsMain/kotlin/app/cash/zipline/internal/jsonJs.kt). Building a native
 * JavaScript object graph and letting QuickJS's C implementation of `JSON.stringify` walk
 * it avoids kotlinx.serialization's pure-Kotlin string builder entirely. Experiment 0.3
 * measures this path directly rather than inferring it by subtraction.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun encodeViaNativeStringify(batch: ChangeBatch): String =
  JSON.stringify(
    DogwoodJsonArrayPolymorphic.encodeToDynamic(ChangeBatch.serializer(), batch),
  )

private val zipline by lazy { Zipline.get() }

/**
 * The manifest's `mainFunction`. It must be exported, or the bundle's namespace will not
 * carry it and `require(module).dev.dogwood.guest.main()` finds nothing.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun main() {
  zipline.bind<Phase0Guest>("phase0Guest", Phase0GuestImpl())
}

/*
 * Project Dogwood -- Phase 0 measurement harness.
 *
 * The Zipline service boundary. Services are named for the side that IMPLEMENTS them,
 * following specs/layer-4-sandbox.md section 4.
 *
 * Two of these -- [DogwoodHost] and [DogwoodGuestUi] -- are the real Layer 4 boundary.
 * The rest ([MonotonicClock], [Phase0Guest]) are measurement scaffolding that exists only
 * so Phase 0 can produce numbers; none of it is part of the shipping architecture.
 */
package dev.dogwood.protocol

import app.cash.zipline.ZiplineService
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// The real Layer 4 boundary
// ---------------------------------------------------------------------------

/** Implemented by the host, called by the guest. */
interface DogwoodHost : ZiplineService {
  /** The single egress point. One call per completed composition pass. */
  fun sendChanges(batch: ChangeBatch)

  /**
   * Phase 0 only: the same batch already encoded to a JavaScript Object Notation (JSON)
   * string by the guest. This exists so experiment 0.3 can separate the cost of encoding
   * from the cost of crossing; Zipline would otherwise serialize [sendChanges]'s argument
   * itself and the two costs would be inseparable from the outside.
   */
  fun sendChangesEncoded(json: String)

  /** Asks the host to schedule exactly one frame. Idle experiences produce no traffic. */
  fun requestFrame()

  fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag)

  fun onUnknownEventNode(id: Id, tag: EventTag)

  fun handleUncaughtException(exception: Throwable)
}

/**
 * A monotonic clock the host injects into the guest.
 *
 * Required because the QuickJS build Zipline pins (Bellard QuickJS `2021-03-27`) has no
 * `performance.now` and `Date.now` is millisecond-granular, which is far too coarse to
 * measure a recomposition against an eight-millisecond budget. Its own round-trip cost is
 * measured first and subtracted from every sample -- see the Phase 0 harness appendix in
 * roadmap.md.
 */
interface MonotonicClock : ZiplineService {
  fun nowNanos(): Long
}

// ---------------------------------------------------------------------------
// Measurement scaffolding
// ---------------------------------------------------------------------------

/** One measured series, in nanoseconds. The host computes the percentiles. */
@Serializable
data class Samples(
  val label: String,
  val nanos: List<Long>,
)

/** What one composition of the reference screen produced. */
@Serializable
data class CompositionResult(
  /** Nodes that produced a `Create` change -- the number the gate's "~150 nodes" refers to. */
  val widgetNodes: Int,
  /** Children-slot markers. They route `ChildAdd` and cost no `Create`. */
  val childrenNodes: Int,
  /** Total changes in the initial batch. */
  val changes: Int,
  /** Bytes of the initial batch, encoded in the v0 wire format. */
  val initialBatchBytes: Int,
  /** How many `mutableStateOf` holders the screen declared. */
  val stateHolders: Int,
  val timings: Samples,
)

/** Guest-side encoding costs for experiment 0.3. */
@Serializable
data class EncodeResult(
  val changeCount: Int,
  /** Bytes under ADR-004 section 2.2's class-discriminator encoding. */
  val bytes: Int,
  /** Bytes under array polymorphism, the encoding Zipline's own CallChannel uses. */
  val arrayBytes: Int,
  /** Cost of building the `List<Change>` from the node tree. */
  val build: Samples,
  /** Cost of encoding that list to a JSON string under ADR-004's encoding. */
  val stringify: Samples,
  /** Cost of encoding the same list under array polymorphism. */
  val stringifyArrayPolymorphic: Samples,
  /**
   * Cost of the path Zipline's own `CallChannel` takes on Kotlin/JavaScript: build a native
   * JavaScript object graph with `encodeToDynamic`, then hand it to QuickJS's native
   * `JSON.stringify`. This is what `sendChanges` actually pays.
   */
  val stringifyNative: Samples,
  val nativeBytes: Int,
)

/**
 * Implemented by the guest, called by the host. Every method is measurement scaffolding.
 *
 * All methods are non-suspending so that each call is one synchronous crossing and the
 * host's wall-clock measurement brackets exactly the guest work plus one crossing.
 */
interface Phase0Guest : ZiplineService {
  /** Injects the host services. Must be called first. */
  fun setUp(host: DogwoodHost, clock: MonotonicClock)

  /**
   * Round-trip cost of [MonotonicClock.nowNanos], measured from inside the guest. The
   * median of this series is subtracted from every later guest-timed sample.
   */
  fun measureClockOverhead(iterations: Int): Samples

  /**
   * Experiment 0.2, cold leg: composes the reference screen from scratch [iterations]
   * times, each time on a fresh composition, and reports node counts from the last one.
   */
  fun composeReferenceScreen(rows: Int, warmups: Int, iterations: Int): CompositionResult

  /**
   * Experiment 0.2, warm leg: mutates exactly one state holder and times the resulting
   * recomposition, from after event dispatch to the moment the batch is ready to hand to
   * `sendChanges`.
   *
   * @param kind `"row"` for the one-node diff (row selection) or `"total"` for the
   *   two-node diff, per the harness appendix.
   */
  fun measureRecomposition(kind: String, warmups: Int, iterations: Int): Samples

  /** Experiment 0.3: guest-side build and stringify costs at a given change count. */
  fun measureEncode(changeCount: Int, warmups: Int, iterations: Int): EncodeResult

  /**
   * Experiment 0.3: drives [iterations] crossings of a batch of [changeCount] changes.
   *
   * @param encoded true to call [DogwoodHost.sendChangesEncoded] with a guest-encoded
   *   string, false to call [DogwoodHost.sendChanges] and let Zipline serialize.
   */
  fun crossBatch(changeCount: Int, iterations: Int, encoded: Boolean): Samples

  /** Experiment 0.4: allocation churn under a repeated recomposition load. */
  fun churn(rows: Int, iterations: Int)

  /** The exact byte size of the v0 encoding of the current initial batch. */
  fun initialBatchJson(rows: Int): String
}

/*
 * Project Dogwood -- leak detection in the browser.
 *
 * The other two hosts watch the same two places, for the same reasons (`Leaks.kt`): a detached
 * subtree, and a replaced guest generation. The second is the one peculiar to this architecture --
 * a code update while a screen is live is the *normal* case, so a retained experience is not one
 * stale object but a whole interpreter with a composition in it. A tab is as capable of holding
 * one as a phone is.
 *
 * `redwood-leak-detector` publishes no WebAssembly artifact, so this is Dogwood's own. It is
 * thirty lines because the browser supplies the hard part: `WeakRef` holds a reference the garbage
 * collector is free to break, and a Kotlin object reaches it through [toJsReference].
 *
 * **That last step was the open question, and it is answered by running it.** Kotlin/Wasm objects
 * live in the WebAssembly garbage-collected heap and are not JavaScript values, so whether their
 * liveness was observable from JavaScript at all was unknown; `WeakRefBridgeProbeTest` shows it is,
 * and pins the two things that make the observation meaningful rather than always-alive:
 *
 *   1. **Yield before asking.** A `WeakRef` is specified to keep its target alive for the whole of
 *      the current job. Anything created and checked in one synchronous turn reports alive, on a
 *      plain JavaScript object exactly as on a Kotlin one -- which is what three earlier probe
 *      runs were measuring before the boundary was added.
 *   2. **Do not hold the subject in an inlining caller's frame.** The same discipline `LeakTest.kt`
 *      and `CrossLanguageLeakTest.kt` document: a `run { }` block is inline, so the local survives
 *      into the caller and the object is genuinely still reachable.
 *
 * **What this cannot do, and neither can the others: force a collection.** There is no standard
 * way to ask a browser to collect, and this never tries -- the tests get one through Chrome's
 * `--js-flags=--expose-gc`, which is a test-browser flag and reaches nothing that ships. So a
 * report here means "still reachable when asked, after the threshold", which is a weaker statement
 * than the Java Virtual Machine detector's and is the same weaker statement the iOS one would make
 * without `GC.collect()`. It is enough for the thing it is for: a generation leaked per publish
 * accumulates, and accumulation is visible however lazily the collector runs.
 */
package dev.dogwood.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private fun weakRefOf(value: JsAny): JsAny = js("new WeakRef(value)")

private fun stillReachable(ref: JsAny): Boolean = js("ref.deref() !== undefined")

/**
 * Watches references in a browser and reports the ones still reachable after [leakThreshold].
 *
 * @param leakThreshold how long to wait before asking. It has to outlast a plausible *collection*,
 *   not merely a plausible leak: nothing here can force one, so a reference asked about before the
 *   collector has next run reports as reachable, and reports correctly -- it is. Too short a
 *   threshold produces false positives, which is the failure that teaches a team to ignore the
 *   detector. The default is deliberately generous for that reason.
 * @param onLeak what to do with a note that outlived its threshold. Reporting rather than throwing:
 *   a leak is a diagnosis, and taking a screen down over one would be worse than the leak.
 */
class BrowserLeakWatcher(
  private val scope: CoroutineScope,
  private val leakThreshold: Duration = 5.seconds,
  private val onLeak: (String) -> Unit,
) : DogwoodLeakWatcher {

  override fun watch(reference: Any, note: String) {
    val ref = weakRefOf(reference.toJsReference())
    scope.launch {
      // The threshold is also the yield: a `WeakRef` keeps its target alive for the current job,
      // so asking any sooner than the next one would report every reference as live.
      delay(leakThreshold)
      if (stillReachable(ref)) onLeak(note)
    }
  }
}

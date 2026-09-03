/*
 * Project Dogwood -- the leak suite across the language boundary (roadmap Phase 6, step 2).
 *
 * Phase 4 adopted `app.cash.redwood:redwood-leak-detector` and aimed it at the two places this
 * architecture leaks: a detached subtree and a replaced guest generation. Those tests run on a
 * Java Virtual Machine (`LeakTest.kt`). This file asks the question Phase 6 exists to ask: does
 * the same instrument work where the hazard is different?
 *
 * **The hazard on iOS is a cycle that spans two memory managers.** Kotlin/Native collects with a
 * tracing garbage collector; Objective-C and Swift free with reference counting. Each is complete
 * on its own side and neither can see the other's graph. A Kotlin object held by an Objective-C
 * object that the Kotlin object also holds is therefore uncollectable *by construction* -- the
 * garbage collector sees an external retain it must honour, and the reference count never reaches
 * zero. This is a documented limitation of Kotlin/Native's memory manager, not a defect to fix,
 * which is exactly why it needs an instrument rather than an argument.
 *
 * The good news these tests record: `redwood-leak-detector` publishes Kotlin/Native artifacts, and
 * its native implementation is `kotlin.native.ref.WeakReference` over `kotlin.native.runtime.GC`
 * -- so [dogwoodLeakDetector] runs here with no Dogwood-specific code, and a cross-language cycle
 * shows up in it as an ordinary leak report.
 *
 * The limit these tests also record: what it sees is a *Kotlin* reference that failed to die. It
 * cannot see a leak that lives entirely on the Objective-C side, it cannot name the retainer, and
 * it cannot tell a cross-language cycle apart from any other object that outlived its threshold.
 * Finding *which* reference caused it is still Instruments' job.
 */
@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package dev.dogwood.host

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSMutableArray

/** A Kotlin object that holds an Objective-C one. Nothing exotic: a host service does this. */
private class HoldsObjectiveC(val array: NSMutableArray)

/**
 * Builds the cycle and returns only a weak handle to it.
 *
 * In a function rather than inline in the test body so that no strong reference survives on the
 * stack -- the same discipline `LeakTest.kt` documents for the Java Virtual Machine, and the same
 * mistake is just as easy to make here.
 *
 * `NSMutableArray.addObject` retains through Objective-C reference counting, and retaining a
 * Kotlin object from Objective-C pins it against the garbage collector. So after this returns:
 * the array holds the Kotlin object, the Kotlin object holds the array, and neither manager can
 * see the whole loop.
 */
@OptIn(ExperimentalNativeApi::class)
private fun makeCrossLanguageCycle(watch: (Any, String) -> Unit): WeakReference<HoldsObjectiveC> {
  val array = NSMutableArray()
  val holder = HoldsObjectiveC(array)
  array.addObject(holder)
  watch(holder, "cross-language cycle")
  return WeakReference(holder)
}

/** The same shape with the Objective-C half not retaining back. This one must be collectable. */
@OptIn(ExperimentalNativeApi::class)
private fun makeAcyclicPair(watch: (Any, String) -> Unit): WeakReference<HoldsObjectiveC> {
  val holder = HoldsObjectiveC(NSMutableArray())
  watch(holder, "no cycle")
  return WeakReference(holder)
}

class CrossLanguageLeakTest {

  /**
   * The instrument runs on Kotlin/Native at all.
   *
   * `DogwoodLeakWatcher` is Dogwood's own interface precisely so the implementation behind it can
   * change; this asserts that the one it currently has -- Redwood's, whose native actuals are
   * `kotlin.native.ref.WeakReference` and `GC.collect()` -- reports a deliberately retained object
   * here exactly as it does on a Java Virtual Machine.
   */
  @Test
  fun aRetainedReferenceIsReportedOnKotlinNative() {
    val retained = mutableListOf<Any>()
    val reported = mutableListOf<String>()
    runBlocking {
      val detector = dogwoodLeakDetector(this, leakThreshold = 200.milliseconds) { _, note ->
        reported += note
      }
      val leaked = Any()
      retained += leaked
      detector.watch(leaked, "deliberately retained")
      detector.awaitAllSettled()
    }
    assertEquals(listOf("deliberately retained"), reported)
    assertNotNull(retained.single())
  }

  /**
   * A cycle spanning Kotlin's collector and Objective-C's reference counting is reported.
   *
   * This is the Phase 6 claim, run rather than asserted. If Kotlin/Native ever gains a collector
   * that can break these cycles this test will start failing, and that failure would be good news
   * worth reading rather than a flake -- which is why the negative control below exists to prove
   * the detector is not simply reporting everything.
   */
  @Test
  fun aCrossLanguageCycleIsReported() {
    val reported = mutableListOf<String>()
    val handle = runBlocking {
      val detector = dogwoodLeakDetector(this, leakThreshold = 200.milliseconds) { _, note ->
        reported += note
      }
      val handle = makeCrossLanguageCycle(detector::watch)
      detector.awaitAllSettled()
      handle
    }
    assertEquals(
      listOf("cross-language cycle"),
      reported,
      "a Kotlin object retained by an Objective-C object it also retains cannot be collected by " +
        "either memory manager; a detector that missed it would be useless on this platform",
    )
    assertNotNull(handle.get(), "and it really is still alive, not merely reported late")
  }

  /**
   * The negative control: the same two objects without the loop are collected and not reported.
   *
   * Without this the test above would pass on a detector that called everything a leak.
   */
  @OptIn(NativeRuntimeApi::class)
  @Test
  fun aKotlinObjectHoldingAnObjectiveCObjectIsNotReported() {
    val reported = mutableListOf<String>()
    val handle = runBlocking {
      val detector = dogwoodLeakDetector(this, leakThreshold = 400.milliseconds) { _, note ->
        reported += note
      }
      val handle = makeAcyclicPair(detector::watch)
      detector.awaitAllSettled()
      handle
    }
    assertEquals(
      emptyList(),
      reported,
      "holding an Objective-C object from Kotlin is ordinary and must not be called a leak",
    )
    GC.collect()
    assertNull(handle.get())
  }

  /**
   * The Zipline thread's own cycle is broken.
   *
   * `DogwoodZiplineDispatcher` captures itself in the block its `NSThread` runs, and holds that
   * thread -- a loop that would hold an eight-megabyte stack and, in a real host, the interpreter
   * dispatched onto it. Closing the dispatcher must end it. This is the one cycle Dogwood's own
   * iOS code creates on purpose, and it is the shape this whole file is about.
   */
  @Test
  fun closingTheZiplineDispatcherReleasesItsThread() {
    val dispatcher = DogwoodZiplineDispatcher(name = "leak-test-zipline")
    assertNotNull(dispatcher.thread, "the thread exists while the dispatcher accepts work")
    dispatcher.close()
    // The thread's loop breaks out of `channel.receive()` on close and nulls the reference in its
    // `finally`. Give it a moment to notice; this is a real thread, not a fake.
    val cleared = runBlocking {
      repeat(200) {
        if (dispatcher.thread == null) return@runBlocking true
        kotlinx.coroutines.delay(10)
      }
      dispatcher.thread == null
    }
    assertTrue(cleared, "the dispatcher still holds its NSThread after close(); the reference cycle it creates was not broken")
  }
}

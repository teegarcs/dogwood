/*
 * Project Dogwood -- leak detection, and the two things it is pointed at.
 *
 * roadmap.md puts this before iOS rather than after, because cross-language reference cycles span
 * Kotlin/Native garbage collection and Swift reference counting and are miserable to find once a
 * platform is already shipping. There is no iOS host yet, so what can be done now is: adopt the
 * instrument, aim it at the two places this architecture can leak, and prove both that the
 * instrument works and that those places currently do not leak.
 */
package dev.dogwood.host

import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking

private val TEXT_TAG = DogwoodDictionary.Text.value
private val COLUMN_TAG = DogwoodDictionary.Column.value

/** Runs collections until [reference] clears, or gives up. Deterministic enough to assert on. */
private fun collectedWithin(reference: WeakReference<*>, attempts: Int = 50): Boolean {
  repeat(attempts) {
    if (reference.get() == null) return true
    System.gc()
    Thread.sleep(10)
  }
  return reference.get() == null
}

class DetachedNodeTest {

  private fun treeWithChild(): HostTree = HostTree().also {
    it.apply(
      decodePositional(
        "[1,[[0,1,$COLUMN_TAG],[3,0,1,1,0],[0,2,$TEXT_TAG],[1,2,1,\"row\"],[3,1,1,2,0]]]",
      ),
    )
  }

  /**
   * Returns a weak handle and keeps no strong one.
   *
   * The strong reference has to go out of scope before the collection, and a local `val` in the
   * test body would still be on the stack when `System.gc()` ran.
   */
  private fun watchGrandchild(tree: HostTree): WeakReference<Any> {
    val column = tree.root.children(1).single()
    return WeakReference(column.children(1).single())
  }

  @Test
  fun aDetachedSubtreeBecomesCollectable() {
    val tree = treeWithChild()
    val child = watchGrandchild(tree)
    assertNotNull(child.get(), "the node must exist before it is removed")

    // Remove the column, which takes its child with it. The depth-first purge is what makes the
    // grandchild collectable: forgetting only the column would leave the child in `byId`,
    // reachable and invisible.
    tree.apply(decodePositional("[2,[[4,0,1,0,1]]]"))

    assertTrue(
      collectedWithin(child),
      "a removed subtree must become collectable; a feed that creates and destroys ten thousand " +
        "rows would otherwise retain ten thousand nodes and every property map in them",
    )
  }

  @Test
  fun negativeControlAnAttachedNodeIsNotCollected() {
    // Without this, the test above would pass on a tree that never held the node at all.
    val tree = treeWithChild()
    val child = watchGrandchild(tree)
    assertTrue(!collectedWithin(child, attempts = 10), "an attached node must stay reachable")
    assertNotNull(tree.root.children(1).single().children(1).single())
  }
}

class LeakDetectorTest {

  /** Holds references the test wants kept alive past a collection. */
  private val retained = mutableListOf<Any>()

  @Test
  fun aReferenceThatSurvivesIsReported() {
    val reported = mutableListOf<String>()
    runBlocking {
      val detector = dogwoodLeakDetector(this, leakThreshold = 200.milliseconds) { _, note ->
        reported += note
      }

      // Deliberately kept alive, which is what a leak looks like from the detector's side.
      val leaked = Any()
      retained += leaked
      detector.watch(leaked, "deliberately retained")
      detector.awaitAllSettled()
    }
    assertEquals(listOf("deliberately retained"), reported)
    assertNotNull(retained.single())
  }

  /**
   * The real wiring, end to end: a node detached from the mirror must not be reported.
   *
   * This started as a bare `detector.watch(Any(), ...)`, and it reported a leak on every run. The
   * object was still live in a stack slot for the whole test, so the detector was right and the
   * test was wrong. Watching something that becomes unreachable *the way the production code makes
   * things unreachable* is both a truer test and a collectable one.
   */
  @Test
  fun aDetachedNodeIsNotReportedAsALeak() {
    val reported = mutableListOf<String>()
    runBlocking {
      val detector = dogwoodLeakDetector(this, leakThreshold = 400.milliseconds) { _, note ->
        reported += note
      }
      val tree = HostTree(detector)
      tree.apply(decodePositional("[1,[[0,1,$TEXT_TAG],[3,0,1,1,0]]]"))
      tree.apply(decodePositional("[2,[[4,0,1,0,1]]]"))
      detector.awaitAllSettled()
    }
    assertEquals(
      emptyList(),
      reported,
      "the detached node was still reachable. A detector that reports healthy code gets switched " +
        "off, and then it is not detecting anything at all",
    )
  }

  @Test
  fun theDefaultWatcherWatchesNothing() {
    // Watching costs a weak reference per detached node and a periodic collection. A host that is
    // not investigating a leak must not pay for one, and this is the default everywhere.
    var watched = 0
    val counting = DogwoodLeakWatcher { _, _ -> watched++ }
    HostTree(counting).apply(
      decodePositional("[1,[[0,1,$TEXT_TAG],[3,0,1,1,0]]]"),
    )
    assertEquals(0, watched, "nothing was detached yet")

    val silent = HostTree()
    silent.apply(decodePositional("[1,[[0,1,$TEXT_TAG],[3,0,1,1,0]]]"))
    silent.apply(decodePositional("[2,[[4,0,1,0,1]]]"))
    // No assertion on `silent` beyond it not throwing: the point is that the default watcher is a
    // no-op, so a removal costs nothing at all.
    assertNull(silent.skew.unknownWidgetTags.firstOrNull())
  }
}

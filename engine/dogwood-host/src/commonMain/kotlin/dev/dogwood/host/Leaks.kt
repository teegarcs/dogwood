/*
 * Project Dogwood -- leak detection.
 *
 * roadmap.md Phase 4 says "Adopt `redwood-leak-detector`. **Before iOS, not after** --
 * cross-language reference cycles span Kotlin/Native garbage collection and Swift reference
 * counting." Adopted rather than reinvented, exactly as written:
 * `app.cash.redwood:redwood-leak-detector` (Apache 2.0) publishes Java Virtual Machine,
 * JavaScript, WebAssembly and iOS targets, and an Android consumer resolves its Java Virtual
 * Machine variant.
 *
 * **Where Dogwood leaks, if it leaks, is not where an ordinary application leaks.** Two places,
 * and both are watched:
 *
 *   1. **A detached subtree.** Removing children from the mirror must forget them, or a feed that
 *      creates and destroys ten thousand rows retains ten thousand nodes and every property map
 *      in them.
 *   2. **A replaced guest generation.** This is the one that matters, and it is peculiar to this
 *      architecture. A code update while a screen is live is the *normal* case, so a retained
 *      `DogwoodExperience` is not one stale object -- it holds the Zipline instance, and through
 *      it an entire QuickJS heap with a whole composition in it. Leak a generation per publish and
 *      a long-lived screen accumulates interpreters.
 *
 * **This file is the interface only.** The implementation is `Leaks.zipline.kt`, one layer down,
 * because `redwood-leak-detector` publishes no WebAssembly artifact and the host core compiles for
 * the browser (Layer 5 ADR-041). Nothing in the core references the implementation -- the tree
 * takes a `DogwoodLeakWatcher` and calls `watch`, which is the whole contract.
 *
 * The guest's own heap is deliberately **not** watched. Its retention hazard -- an event closure
 * outliving the node that registered it -- is structural rather than collectible, and a test that
 * counts `lambdaSlotCount` after a removal is a deterministic assertion where a garbage-collection
 * probe would be a flaky one. That test already exists.
 */
package dev.dogwood.host

/**
 * Where Dogwood hands a reference it expects to become unreachable.
 *
 * Dogwood's own type rather than Redwood's, and the reason is on the tin: `LeakDetector` is marked
 * `@RedwoodLeakApi`, "unstable and for Redwood internal use only". Putting an explicitly-internal
 * third-party type in this project's public signatures would make every host that watches for
 * leaks depend on it directly, and Redwood is a discontinued project. The opt-in lives in this one
 * file, and the implementation behind it can be replaced without touching a caller.
 */
fun interface DogwoodLeakWatcher {
  fun watch(reference: Any, note: String)

  companion object {
    /** What a host that is not investigating a leak uses, which is most hosts most of the time. */
    val None: DogwoodLeakWatcher = DogwoodLeakWatcher { _, _ -> }
  }
}

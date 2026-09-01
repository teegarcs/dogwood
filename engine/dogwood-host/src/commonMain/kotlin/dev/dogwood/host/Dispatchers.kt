/*
 * Project Dogwood -- the threading contract, asserted rather than assumed.
 *
 * specs/layer-4-sandbox.md is explicit that `frame`, `sendEvent` and all guest work run on the
 * Zipline dispatcher while `sendChanges` is delivered to the user-interface dispatcher, and
 * that "both sides should assert with check functions rather than rely on convention."
 * Redwood's `AndroidTreehouseDispatchers` demonstrates the same shape with `checkUi()` and
 * `checkZipline()` on every protocol entry point.
 *
 * These are cheap identity comparisons, not debug-only scaffolding: a violation is a data race
 * on the tree Compose is reading, and it will not announce itself any other way.
 */
package dev.dogwood.host

/** Records which thread is which, so a crossing can prove it is on the right one. */
expect class ThreadIdentity() {
  /** Binds the calling thread as the one this identity names. Call once, from that thread. */
  fun bind()

  /** True when the calling thread is the bound one. */
  fun isCurrent(): Boolean
}

/** The two dispatchers Layer 4 names, with the checks that keep them honest. */
class DogwoodThreads {
  private val ui = ThreadIdentity()
  private val zipline = ThreadIdentity()

  fun bindUi() = ui.bind()

  fun bindZipline() = zipline.bind()

  fun checkUi() {
    check(ui.isCurrent()) {
      "expected the user-interface thread; the host tree may only be mutated where Compose reads it"
    }
  }

  fun checkZipline() {
    check(zipline.isCurrent()) {
      "expected the Zipline thread; the guest is single-threaded and has no lock to save us"
    }
  }
}

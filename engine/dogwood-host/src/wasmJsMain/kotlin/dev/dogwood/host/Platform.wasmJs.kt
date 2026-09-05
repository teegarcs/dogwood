/*
 * Project Dogwood -- the two seams the browser answers differently.
 */
package dev.dogwood.host

import okio.FileSystem
import okio.Path
import okio.fakefilesystem.FakeFileSystem

/**
 * There is one thread, so the two identities are the same one and both checks pass.
 *
 * That is a real answer rather than a stub. `DogwoodThreads` exists because QuickJS is
 * single-threaded and has no lock, so guest work must not wander off the interpreter thread. On
 * this platform the guest is in a Web Worker and the host is on the main thread; neither can touch
 * the other's memory, and the browser enforces that rather than a check function. The contract
 * holds vacuously.
 */
actual class ThreadIdentity {
  private var bound = false

  actual fun bind() {
    bound = true
  }

  actual fun isCurrent(): Boolean = bound
}

/**
 * No backup service to exclude a file from. The browser's storage is origin-scoped and is not
 * synchronised to a vendor cloud, which is the hazard ADR-010 named for iOS.
 */
internal actual fun excludeFromBackup(path: Path) {}

/**
 * **Not persistent yet, and it must not pretend to be.**
 *
 * Okio publishes no browser-backed `FileSystem`, so persisting saved state on the web needs an
 * Okio `FileSystem` over `localStorage` or the Origin Private File System -- real work, and the
 * remaining half of ADR-041's step 2. Until it exists this is an in-memory file system: a store
 * built on it accepts writes and loses them when the tab closes.
 *
 * In memory rather than throwing, because `DogwoodStateStore` is constructed on paths a host may
 * never write to, and a constructor that throws would take down a screen that was not going to
 * save anything. The honest signal is that the conformance claims `E1` and `E2` stay red for web
 * until a durable implementation lands -- which is exactly what the matrix is for.
 */
internal actual fun platformFileSystem(): FileSystem = FakeFileSystem()

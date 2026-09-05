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
 * Saved state, in browser storage, durable across a tab close.
 *
 * `BrowserFileSystem` is a small Okio file system over `localStorage`; see that file for why it is
 * `localStorage` rather than the Origin Private File System (Okio's interface is synchronous and
 * the Origin Private File System's is not, and a write-behind cache would defeat the one property
 * this exists to provide).
 *
 * Where storage is unreachable -- a private-browsing window that blocks it, or a Worker, which has
 * no `localStorage` at all -- this degrades to an in-memory file system rather than failing every
 * write. A host in that situation gets a store that accepts writes and loses them, which is what a
 * browser refusing storage means; the alternative is a screen that will not open.
 */
internal actual fun platformFileSystem(): FileSystem =
  if (browserStorageAvailable()) BrowserFileSystem() else FakeFileSystem()

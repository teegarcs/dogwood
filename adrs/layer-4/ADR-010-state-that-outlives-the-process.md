# ADR-010: State That Outlives the Process

**Date:** 2026-09-02
**Status:** Accepted

## 1. Context & Problem Statement

Dogwood had two mechanisms for carrying guest state across a discontinuity, and both lived entirely
in memory. A code update snapshots the outgoing guest and restores into its replacement. An eviction
snapshots an experience and hands the snapshot back when the user returns. Both are correct for what
they do, and both die with the process.

The case a user actually notices is the one neither covered. Android reclaims a backgrounded
application whenever it likes, and `specs/layer-4-sandbox.md` said so plainly: *"Backgrounding,
process death, and memory pressure remain unaddressed: the same snapshot mechanism is what they will
use, but nothing persists a snapshot beyond the process today."*

Demonstrated before anything was built: set a counter to 1, press home, `adb shell am kill`,
relaunch — the counter reads 0. Nothing about that is distinguishable from a bug.

## 2. Decision

**Persist the snapshot to the application's private storage on `onStop`, consume it once on the next
launch, and let a guest keep a field out of it.**

1. **`DogwoodShell.snapshotAll()` / `restoreAll()`** are the seam. Suspending on the way out, because
   a live guest's state can only be read on the Zipline thread.
2. **`DogwoodStateStore`** writes it. Bounded in size and in age, deleted the moment it is consumed,
   and written to private storage rather than anywhere shared.
3. **`rememberTextFieldState(sensitive = true)`** saves a field's shape but not its contents.
4. **The host persists it; the library does not.** The store is a seam an application wires into its
   own lifecycle, because where state belongs is a product decision.

## 3. Rationale & Research

### Persisting a snapshot changes what a snapshot is

This is the finding that shaped the design, and it came from looking at one. A realistic screen's
snapshot is small — **71 bytes** measured, for a counter and a text field — so size was never the
constraint. This is what was in it:

```json
{"values":{"saved":[{"s":2}],"-dlsj1r8nzlxh":[["4242424242424242",1]]}}
```

That is the raw text of a **masked card-number field, in plain text**. Until now that was harmless:
a snapshot lived microseconds and never left memory. The moment one survives a process it is user
data at rest, and writing it is a decision requiring an argument rather than a default.

So the store writes only to private application storage, bounds age at a day, deletes on read, and
refuses an oversized snapshot **whole rather than truncating** — a partial restore puts a screen into
a state its guest never composed. And because nothing in the host can know which fields are too
sensitive to survive, `sensitive = true` lets the code that declared a field decline. It still saves
the acknowledged edit count: a field restored stamped zero would silently discard everything the user
typed next, for the life of the screen.

### Why `onStop` and not `onSaveInstanceState`

`onSaveInstanceState` is the platform's own mechanism for exactly this, and it cannot be used.
Reading a live guest's state means crossing to the Zipline thread, which is suspending; that callback
is synchronous on the main thread. `onStop` is the last hook guaranteed to run before the process may
be reclaimed, and it can launch. The consequence is that the snapshot is taken slightly earlier than
the platform would take it, which costs nothing here because guest state changes only in response to
events the user is no longer generating.

### Three bugs found by verifying on a device rather than in a log

The first pass reported complete success in logcat — *"saved state for [explore, app] on stop"* and
*"restored state for [explore, app] from a previous process"* — while the screen showed a counter of
zero. Each of the three would have been invisible to any assertion on the save and restore calls.

**The host forgot which tab it was on.** Restoring the guest's state accomplishes nothing if the host
returns on a different screen: the state is there, correctly, and the user is looking elsewhere. The
tab was `remember` rather than `rememberSaveable`. **Which tab is open is host state, and the host has
to save it** — the guest's snapshot cannot carry it.

**The store was consumed inside a composition.** `consume` deletes what it returns, so it must run
once per process, and a `LaunchedEffect(Unit)` cannot promise that: the effect that builds the shell
sits inside `DogwoodEnvironment`, whose `BoxWithConstraints` subcomposes its content and can dispose
and restart it. Reading process-scoped state now happens once, in the activity.

**And the shell was closed immediately after being built.** `DisposableEffect(shell)` ran
`onDispose { shell?.close() }`, which reads the variable *at dispose time* — and on the null-to-built
transition that is the shell that was just built. `close()` clears the shell's entries and with them
the restored snapshots, seconds before anything was activated. Binding the effect's own value fixes
it. **The same latent bug was present in `SliceActivity`**, where it had never mattered because
nothing there had state to lose; it was fixed too.

That is now the third bug of this exact shape in this file — an effect reading a mutable variable at
dispose time instead of the value it was keyed on. The first cost an `onTrimMemory` that silently
evicted nothing.

## 4. Unstated Assumptions

- **Private application storage is a sufficient bar.** It is isolated per application, covered by
  device encryption, and the sample sets `allowBackup="false"`. A product with a higher bar should
  wrap the store or mark more fields sensitive; a rooted device is outside what this defends against.
- **A day is the right staleness bound.** Chosen, not measured. Returning to a form a day later and
  finding it half-filled is stranger than finding it empty.
- **The store is not encrypted.** Deliberate, given the above, and the reason `sensitive` exists.
- **iOS is now wired too, and it changed one of the assumptions above.** The store lives in
  `Library/Application Support`, and everything on iOS outside `Caches/` is **iCloud-backed by
  default** — so the plain-text saved state this record refuses to let leave the device would have
  left it, on a platform this record did not consider. `excludeFromBackup` is a platform seam
  (`NSURLIsExcludedFromBackupKey`, which Okio cannot express) applied after every write, because the
  flag belongs to the file and a recreated file is a new one. Verified on the simulator by reading
  the `com.apple.metadata:com_apple_backup_excludeItem` attribute off the written file. Backgrounding
  is `UIApplicationDidEnterBackgroundNotification` and memory pressure is the matching notification —
  the same reasoning as `onStop` and `onTrimMemory`, arriving by a different mechanism.
- **Desktop and Web are still unwired.** Both have their own lifecycles and neither is done.
- **Concurrent processes are not considered.** One activity, one store, last write wins.

## 5. Updated Documents

- [Layer 4: The Guest Runtime](../../specs/layer-4-sandbox.md) — lifecycle events, no longer "partly
  designed".

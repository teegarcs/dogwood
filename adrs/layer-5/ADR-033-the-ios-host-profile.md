# ADR-033: The iOS Host Profile

**Date:** 2026-09-03
**Status:** Accepted

## 1. Context & Problem Statement

Phase 6 brought the full path up on iOS and made several decisions along the way — the threading
model, the network transliteration, moving four files into `commonMain`, a second image stack — and
recorded them in a commit message. `AGENTS.md` §3 is explicit that maintenance-phase decisions
require a record, and the reason is traceability: a reader should be able to start at any line in a
specification and find the decision that put it there. The web profile got
[ADR-032](ADR-032-the-web-profile.md); iOS got a git log.

This is that record, written after the adversarial review and the R1/R3/R7 work, so it describes
what iOS actually does rather than what the first attempt did.

## 2. Decision

**iOS runs the same host as Android, on Kotlin/Native, with four platform-shaped differences: its
own thread, its own network implementation, its own clock and formatting, and its own storage
rules.**

1. **The guest runs on a dedicated `NSThread` with an 8 MB stack**, owned by
   `DogwoodZiplineDispatcher`.
2. **`UrlSessionNetwork` implements the same policy as `OkHttpNetwork`**, enforced with a session
   delegate rather than a completion handler.
3. **Four files moved to `commonMain`** — `Session`, `Shell`, `StateStore`, `Delivery` — with a
   `jvmAndroid` factory shim so no existing call site changed.
4. **Images use Ktor's Darwin engine**, because `coil-network-okhttp` has no iOS artifact.
5. **Teardown crosses to the Zipline thread**, and `DogwoodExperience.close()` asserts it.

## 3. Rationale & Research

### The thread, and why 512 KiB is not a detail

Apple gives a background `NSThread` 512 KiB of stack by default. QuickJS composition overflows it —
not marginally, but on the reference screen. The dispatcher therefore constructs its thread
explicitly at 8 MB, the same construction and reasoning as Redwood's `IosTreehouseDispatchers`
(Apache 2.0, attributed in place), and the same figure Layer 4's memory notes already require of
host threads on every platform.

Two defects in that dispatcher were found by the review rather than by running it, and both are
fixed ([R3](../../plans/platform-review.md)):

- `dispatch` discarded the result of `trySend`, so work dispatched after `close()` vanished. **A
  vanished block is a vanished continuation** — anything suspended in `withContext(ziplineDispatcher)`
  hung forever, and cancelling could not help because the cancel resume went to the same dead
  channel. It now throws.
- The drain loop had no `catch`, so one throwing block exited it while leaving the channel open;
  every later dispatch queued into silence.

`ThreadIdentity` compares with `==` rather than `===` on this platform, because `NSThread.currentThread`
can return a different Kotlin wrapper for the same Objective-C object. `NSThread` does not override
`isEqual:`, so `==` reaches pointer identity — two distinct threads can never compare equal, and the
failure direction is loud rather than silently permissive.

### The network, and the transliteration that was not one

`UrlSessionNetwork` was written to mirror `OkHttpNetwork` clause by clause. The review found it had
kept the shape and lost the teeth, in three ways that all mattered:

**The scheme was not constrained.** On the Java Virtual Machine, `toHttpUrlOrNull()` returns null for
anything that is not `http(s)`, so other schemes are structurally unreachable. `NSURL` parses any
scheme and NSURLSession serves `file:` natively — so a rule that waived "must be HTTPS" for a
cleartext host passed `file://localhost/<the app container>`. The application's own caches, cookies
and saved state were readable through the *network* policy and exfiltrable to any allowed host, in
the shipped sample's exact configuration. **Cleartext opt-in now names `http`** rather than excusing
"not HTTPS".

**The body cap was advisory.** The completion-handler API hands back a finished `NSData`, so
Foundation had buffered the whole response before any check ran: the refusal bounded what the guest
saw, not what the host allocated. A delegate now refuses on the declared length before reading a
byte and cancels mid-stream on a running count.

**Redirects were unpoliced** — on both platforms, faithfully copied. The allow rule ran once,
pre-flight, while both clients followed 302s by default, so any allowed host with an open-redirect
endpoint was a way out of the allow list. Both now re-apply the rule per hop.

The lesson generalises beyond iOS: **a security boundary ported without its tests is a boundary
ported without its guarantees.** The JVM policy had contract tests; the port had none, which is
exactly how the scheme hole survived. It has them now.

### What moved to `commonMain`, and what that cost

`Session`, `Shell`, `StateStore` and `Delivery` contained no Java Virtual Machine API and moved
unchanged. `Delivery` now takes Zipline's own `ZiplineHttpClient` abstraction, with a same-named
`jvmAndroid` factory function taking an `OkHttpClient` and the old defaults — so **every pre-Phase-6
call site compiles unchanged**. Two new internal `expect` declarations cover what is genuinely
platform-shaped: `hostEpochMillis`, and `platformFileSystem`.

Those files compiled for iOS long before they ran there, and the gap between those two facts held a
defect. `Shell.evict()` closed a guest from the user-interface thread; the interpreter is
single-threaded, the JVM tolerated it, and Kotlin/Native aborts. **Tolerated is not correct** —
Android had been racing on the guest heap during every eviction since the shell shipped. Teardown is
now suspending and crosses to the Zipline thread, and `DogwoodExperience.close()` asserts
`checkZipline()` so the next violation fails at its source.

### Storage, which reopened ADR-010

[ADR-010](../layer-4/ADR-010-state-that-outlives-the-process.md) reasons about saved state in
Android's terms: private storage, device encryption, `allowBackup="false"`. On iOS everything outside
`Caches/` is **iCloud-backed by default**, so the plain-text saved state that ADR refuses to let
leave the device would have left it. `excludeFromBackup` is a new platform seam
(`NSURLIsExcludedFromBackupKey` — a resource value on an `NSURL`, which Okio cannot express), applied
after every write because the flag belongs to the file and a recreated file is a new one.

The payload cache goes in `Caches/` and the state store in `Library/Application Support`, which is
the right split: signed code is re-downloadable and purgeable, user state is neither.

### What the simulator establishes, and what it does not

Per [ADR-004](ADR-004-compose-multiplatform-sole-host-target.md), this is Skiko over Metal — native
*code*, not native *widgets*. The accessibility probe finds a `MetalView` where the interface is.

**Established:** the full path runs — payload fetched over `NSURLSession`, Ed25519 manifest verified,
cached in SQLite, loaded into QuickJS on Kotlin/Native, rendered through `DogwoodSurface`; the shell
switches warm, evicts at the cap, and restores across process death; 63 accessibility nodes are
produced by Compose from the guest's semantics with **zero** Dogwood-specific code; and a genuine
Objective-C↔Kotlin reference cycle is detected by the leak instrument, with a negative control that
is collected and not reported.

**Not established:** any accessibility *interaction*. `simctl` cannot tap or type, so typing through
the input method editor, the keyboard appearing, selection handles, focus order and whether the
spoken result makes sense are all unverified and need a person with VoiceOver on. The tree reaches
the platform correctly shaped, which is the part that would have been Dogwood's fault.

**Since superseded in part** ([ADR-039](ADR-039-accessibility-is-asserted-not-inspected.md)). The
premise above — that interaction needs a person because `simctl` cannot tap — was too narrow.
VoiceOver does not tap; it calls `accessibilityActivate`, `accessibilityScroll` and the rotor's
custom actions, and those are public methods a test can call too. Activation through the
accessibility layer is now asserted end to end, and it found a defect the probe could not see: the
sample's card-number field reached the platform with **no name at all**, because Material 3 draws a
label without folding it into semantics. What still needs a person is narrower than this paragraph
claims: the *quality* of the speech, reading order as experienced, and typing and selection.

## 4. Unstated Assumptions

- **`-lsqlite3` must be linked explicitly.** A framework consumer inherits it from its Xcode target;
  a Kotlin/Native executable has no umbrella and fails on ~20 `_sqlite3_*` symbols.
- **`CADisableMinimumFrameDurationOnPhone`** is required in `Info.plist` or Compose refuses to start.
- **There is no Xcode project.** Kotlin/Native produces the executable and a `Sync` task assembles
  the bundle. A product embedding Dogwood will have its own project and should treat the sample's
  `main` as a reference rather than a template.
- **The simulator is not a device.** No number here is a performance measurement; the Phase 0 suite
  on iOS hardware remains owed.
- **Plural rules are English-only on iOS**, where Android reaches real Unicode rules. One payload,
  two answers, reported through `SkewReport.untranslatedPlurals` rather than left silent.

## 5. Updated Documents

- [Layer 4: The Guest Runtime](../../specs/layer-4-sandbox.md) — the threading contract on a third
  platform, and the storage rules ADR-010 did not anticipate.
- [Layer 5: The Native Host & Generated Binding Layer](../../specs/layer-5-host.md) — the iOS host
  in the platform list.
- [ADR-010](../layer-4/ADR-010-state-that-outlives-the-process.md) — assumptions amended for iOS.
- [Platform review plan](../../plans/platform-review.md) — R1, R2, R3, R7.

# Platform review: what Phases 5 and 6 left to harden

## Part 0 — Why this plan exists, and how to read it

Phases 5 and 6 were built fast and verified behaviourally — a browser renders, a simulator renders,
the assertions are on pixels and glyph boxes rather than logs. What neither phase got was an
adversarial read of its code, which is this project's own bar for "done". Two reviewers (one per
phase) plus a cross-cutting sweep produced the items below; every accepted finding was **verified
in source before it was accepted**.

One finding did not wait: the iOS network policy passed `file://localhost/...` for any
cleartext-waived host, making the app's own container readable through the "network" policy.
**Fixed immediately** (cleartext opt-in now names `http` rather than excusing "not https"), with
`NetworkPolicyIosTest` adding the contract tests whose absence let it survive.

Every remaining item carries three parts. **Problem** — what is wrong, verified. **Implementation**
— the specific change, named down to files and functions, so executing it is transcription rather
than re-investigation. **Gate** — what proves it done. A gate here follows the house rule: a test
that *fails without the fix* (verified by reverting), or a measurement, or evidence on a real
device or browser. "The code looks right" is never a gate; five of nine Phase 4 subsystems and
three findings in ADR-010 were invisible until the code ran.

## Part 1 — The work, in priority order

---

### R1. Network policy parity on iOS — ✅ **done** (PR #5)

*Body cap now streaming and bounded; resource timeout set. The gate landed differently than planned and
the difference is recorded in the ADR: the tests drive `PolicedSessionDelegate` directly rather than
through a loopback server, which covers our logic exhaustively and does not cover Foundation's
wiring — that is exercised for real by the sample on every launch. A stubbed `NSURLProtocol` was
built and abandoned as fragile scaffolding for coverage of Apple's code rather than ours.*

#### Original entry

**Problem.** `UrlSessionNetwork.fetch` (`dogwood-host/src/iosMain/.../PlatformServices.ios.kt`)
uses the completion-handler API — `session.dataTaskWithRequest(built) { data, response, error -> }`
— so Foundation buffers the **entire** response in memory before the `length > maxBodyBytes` check
runs. A 500 MB body from an allowed host jetsams the app before the cap is evaluated. The JVM has
two teeth iOS lost: refuse on declared `Content-Length` before reading, and read at most
`maxBodyBytes + 1` bytes off the wire. Separately, `timeoutInterval = 60.0` is an *inter-byte*
timer; the resource timeout stays at Foundation's default of seven days, so a one-byte-per-59-s
drip holds the request (and its growing buffer) indefinitely.

**Implementation.**
1. Replace the completion-handler task with a **delegate-based** session:
   `NSURLSession.sessionWithConfiguration(config, delegate, delegateQueue = null)` and an
   `NSObject() : NSURLSessionDataDelegateProtocol` that implements:
   - `didReceiveResponse`: read `response.expectedContentLength`; if `> maxBodyBytes`, invoke the
     completion handler with `NSURLSessionResponseCancel` and resume the continuation with the
     same refusal string the JVM uses ("declares N bytes; this client's limit is M").
   - `didReceiveData`: append to an `NSMutableData`, keep a running count, and
     `task.cancel()` the moment the count exceeds `maxBodyBytes` — resume with the
     "exceeds limit" refusal. This bounds host memory exactly as the JVM's
     `source.request(maxBodyBytes + 1)` does.
   - `didCompleteWithError`: distinguish our own cancellation (flag set before `cancel()`) from a
     transport error, resume accordingly.
2. On the session configuration set `timeoutIntervalForResource` (60 s total is consistent with
   the JVM's behaviour under its read timeout) alongside the existing per-byte interval.
3. One delegate instance per request (it carries per-request state); the session may be shared if
   the delegate is passed per-task via `dataTaskWithRequest` + task-specific delegate (iOS 15+) —
   the sample targets 17.5, so use task-level delegates and keep `sharedSession` out of this path.

**Gate.** Extend `NetworkPolicyIosTest` (or a new `UrlSessionNetworkTest`) with a loopback
`NSURLSession`-reachable server started from the test (the web-slice harness proves loopback binds
work in this sandbox; if `iosTest` cannot bind, run against the dev server the samples already
use and mark the test simulator-only):
- a response declaring `Content-Length: cap+1` is refused **without** the body arriving (server
  asserts the connection died before it finished writing);
- a chunked response is cancelled after at most `cap + one chunk` bytes have been sent — asserted
  from the server's write count, which is the memory-boundedness proof;
- a drip-feed response (1 byte/s) fails within the resource timeout, not after seven days.
Each gate test must **fail against the current completion-handler implementation** before the fix
lands — run them first, watch them fail, then fix.

---

### R2. Redirects re-checked against the allow rule, both platforms — ✅ **done** (PR #5)

*Both platforms now re-apply the allow rule per hop. On the JVM the client no longer follows
redirects at all; a bounded manual loop does, so the policy lives in the same file as the rule. The
JVM gate is the planned one — a loopback server asserting the disallowed target records **zero**
connections — and it failed before the fix, as required.*

#### Original entry

**Problem.** The allow rule runs once, pre-flight, on the original URL. Both defaults follow
redirects (`NSURLSession.sharedSession`; `OkHttpClient()` has `followRedirects = true`), so any
allowed host with an open-redirect endpoint lets a guest reach — and read up to 1 MiB from —
arbitrary hosts, with guest-chosen headers. The hole is identical on both platforms; the iOS
review is merely where it was noticed.

**Implementation.**
- **iOS**: on the R1 delegate, implement `willPerformHTTPRedirection`: apply the same `allow`
  lambda to the proposed `newRequest.URL`; if it fails, call the completion handler with `null`
  (refuse to follow) and let the 3xx response surface — the existing code path then returns it as
  an ordinary response the guest can see. Log/report through the same refusal string family.
- **JVM** (`OkHttpNetwork` in `PlatformServices.kt`): add a network interceptor is not enough
  (interceptors see the redirect *response*, not the follow decision); instead build the client as
  `client.newBuilder().followRedirects(false).followSslRedirects(false).build()` in the
  constructor, and implement one manual redirect loop (bounded, e.g. 5 hops) in `fetch` that
  re-applies `allow(url)` per hop. Manual-loop rather than interceptor keeps the policy decision
  in the same file as the policy.
- Redirect behaviour must stay **equivalent across platforms**: same hop bound, same refusal
  wording, both preserving the JVM's existing "refusal is a value, never an exception" contract.

**Gate.** On each platform, a test with a loopback server exposing `/redirect ->
http://disallowed-host/`: the fetch returns a refusal (or the raw 3xx), and the server for the
disallowed target **records zero connections**. Plus a control: `/redirect -> same-host/ok`
succeeds within the hop bound. Both new tests must fail against today's code first.

---

### R3. The iOS dispatcher must not silently drop work — ✅ **done** (PR #6)

*All three planned gates landed, plus the ordering/reentrancy regression guard. One harness note
worth carrying forward: `runTest` drives virtual time, which fast-forwards past timeouts while the
work being awaited runs on a real thread — the awaits must sit inside `withContext(Dispatchers.Default)`
or the test passes instantly and proves nothing. Cost two failing runs to notice.*

#### Original entry

**Problem.** `DogwoodZiplineDispatcher.dispatch` (`Delivery.ios.kt`) is
`sendChannel?.trySend(block)` with the result discarded. After `close()` — or racing it — a
dispatched block vanishes; a vanished block is a vanished *continuation*, so anything suspended in
`withContext(ziplineDispatcher)` (session swap `Session.kt:134/148`, `snapshotState()`,
`Shell.evict`) hangs forever, and cancellation cannot complete either, because the cancel resume
dispatches into the same dead channel. Additionally, a block that throws out of
`channel.receive().run()` exits the drain loop while the channel stays **open**: every later
`trySend` succeeds into a queue nobody drains.

**Implementation.**
1. `dispatch`: capture the `trySend` result. On failure (closed or null channel), do what
   kotlinx's own closed dispatchers do — throw `RejectedExecutionException`-equivalent
   (`IllegalStateException` on Native with a message naming the dispatcher and that it is closed).
   Loud beats hung: the caller's coroutine fails immediately and visibly instead of leaking.
2. Wrap the drain body: `try { channel.receive().run() } catch (c: ClosedReceiveChannelException)
   { break } catch (t: Throwable) { /* report via a pluggable handler, keep draining */ }`. A
   host-service callback that throws must not kill the interpreter's thread invisibly.
3. `close()`: after `channel.close()`, keep the existing drain-then-exit semantics (already
   correct), but null `sendChannel` *before* closing so the dispatch-side check and the close
   cannot interleave into a successful send to a channel about to close (recheck-after-set).

**Gate.** Three `iosTest` cases in a new `ZiplineDispatcherTest`:
- dispatch after `close()` throws (not hangs) — must fail (hang → add a timeout harness) today;
- a block that throws does not stop subsequent blocks from running;
- `withContext(dispatcher)` from a coroutine, with `close()` racing it 100 iterations, never
  leaves an uncompleted job (join with timeout).
Plus the existing four leak tests keep passing — the cycle-break in `close()` must not regress.

---

### R4. Web decoder grammar parity — ✅ **done** (PR #7)

*All five sub-items landed. The grammar question was decided by tightening the reference (nothing
that ships emits quoted structural numbers), and the new parity suite immediately caught a position
the tightening had missed — the kind discriminator itself. `dogwood-web` now has a test source set.*

#### Original entry

**Problem.** `FastPositionalDecoder.readInt` (`dogwood-web/.../FastPositionalDecoder.kt:261-284`)
does `value = value * 10 + digit` with no range check: an id of 2^32+1 wraps to 1 and the change
is applied to a **different live node** — the silent-corruption class ADR-009 abolished, back. The
arity table at line 494 is a re-typed copy of `ChangeKind.arity` (forced by `internal`). The two
decoders already disagree on quoted integers (kotlinx accepts `["7",...]`; the fast path rejects)
and on lexical form of value-position numbers. `dogwood-web` has no test source set. And on
mobile, kotlinx throws `NumberFormatException` (not `ProtocolMismatch`) for overflow, which
escapes `sendChanges`' typed catch (`Experience.kt`) — mobile may crash on input web now contains.

**Implementation.**
1. `readInt`: track digit count; on the 10th digit, or on a final value outside `Int` range
   (accumulate in `Long`), throw `ProtocolMismatch("integer out of range at ...")` — same typed
   refusal as the reference.
2. `PositionalCodec.kt`: make `ChangeKind.arity` public with a doc line saying the web fast path
   reads it; delete `ChangeArity` from `FastPositionalDecoder` and import.
3. **Decide the quoted-integer question** (this is a grammar decision, record it in the ADR-009
   family): the guest encoder never emits quoted integers, so the strict reading is correct —
   tighten the *reference* by replacing `jsonPrimitive.int` with a check that the primitive is
   unquoted (`!isString`) in structural positions, so both decoders refuse. Rejecting in the
   reference is safe: nothing that ever shipped emits them.
4. Mobile symmetry: in `dogwood-wire`'s `decodePositional`, catch `NumberFormatException` /
   `SerializationException` at the envelope level and rethrow as `ProtocolMismatch`, so the typed
   contract ("undecodable batches are refusals, never raw throws") holds for every input, not
   just shapely ones.
5. Create `dogwood-web/src/wasmJsTest` (or, if the wasm test runner fights back, put the
   differential test in `dogwood-wire`'s common tests and run the fast decoder there via a small
   `expect`/`actual` — the decoder is pure string-in, batch-out) with a **differential test**:
   ~200 generated batches (all six kinds, boundary integers ±2^31, deep modifier chains, unicode
   strings, malformed variants) through both decoders; assert agreement on accept/reject AND on
   the decoded `ChangeBatch` equality for accepts.

**Gate.** The differential test passes; reverting fix 1 makes it fail on the overflow cases
specifically (verify by reverting). `GrammarTest` on mobile gains an overflow case asserting
`ProtocolMismatch` — which fails today with `NumberFormatException`. Grep gate:
`intArrayOf(3, 4, 3, 5, 5, 6)` appears exactly once in the repo.

---

### R5. Worker lifecycle — ✅ **done** (PR #8)

*`onerror` now fails everything outstanding; correlated requests time out; a malformed snapshot is a
reported failure rather than an empty success; configuration is tracked per bridge; `close()` clears
the tree. `onmessageerror` is not on Kotlin/Wasm's `Worker` type, so that half is unimplemented and
said so here rather than claimed.*

#### Original entry

**Problem.** `WorkerBridge` installs only `onmessage`. No `onerror`, no `onmessageerror`, no
timeout on correlated requests: a 404'd guest script reports `Started` and renders silence;
`snapshotState` on a crashed guest suspends forever (the sample's 2-second poll loop is the only
thing that saves it, in the wrong layer). A cross-origin `guestScript` in the manifest makes
`new Worker(url)` throw a synchronous `SecurityError` that escapes `WebDelivery.start()`'s
try/catch (only the fetch is guarded). `configurationSent` never resets, so a second `attach()`
leaves the replacement guest unconfigured forever. A malformed snapshot payload is swallowed into
`StateSnapshot()` — the eviction path would *persist* the empty snapshot, wiping user state with
no signal.

**Implementation.**
1. `WorkerBridge`: install `worker.onerror` and `worker.onmessageerror`; both fail every entry in
   `pending` with a typed `WorkerFailure(reason)` and invoke a new constructor-injected
   `onWorkerFailure: (String) -> Unit` so the host can tear down and report.
2. Add a per-request timeout to correlated requests (`snapshotState`): default 10 s via
   `window.setTimeout` captured alongside the pending entry; timeout resolves the pending entry
   with the failure path, never an empty success.
3. `WebDelivery.start()`: before `Worker(...)`, resolve the guest URL against
   `window.location.origin` and refuse `DeliveryRefusal.CrossOriginGuest` if the origins differ;
   wrap the constructor itself in the existing try/catch so a `SecurityError` becomes a typed
   refusal, not an escaped exception.
4. `DogwoodWebExperience`: reset `configurationSent = false` in `close()` AND key it per-bridge
   (move the flag onto the bridge or index it by bridge identity) so re-attach reconfigures.
   `close()` also clears the tree (today it stays populated).
5. Snapshot decode failure (`WorkerBridge.kt:143-148`): replace `getOrElse { StateSnapshot() }`
   with routing to the pending entry's failure continuation. Callers must distinguish "guest says
   empty" from "could not read the guest".

**Gate.** Extend the web-slice harness (`run.sh`) with three new asserted scenarios, each of which
fails today: **404 guest** — the page shows a refusal line and `workerCreated:true, ready:false`
is reported within 3 s (no silent empty box); **cross-origin guest** — typed refusal, no worker,
no uncaught error in the console log the harness already captures; **snapshot timeout** — a guest
that deliberately never answers `snapshotState` produces a typed failure in ≤ the timeout, not a
hang (harness asserts elapsed time). The existing render/refusal/screenshot runs stay green.

---

### R6. Make the `--gufa` gate real — ✅ **done** (PR #8)

*The tautological check now asserts the outcome; enforcement moved into `attach()` so consumers
cannot forget it; and the detector is validated against the one known-miscompiled binary — the
smoke alarm has now been held under a match. Scope: that validates the gate's **first stage**; its
decoder-agreement stages remain unexercised by a real miscompilation, because this defect does not
reach them.*

#### Original entry

**Problem.** Three layers, all soft. The build-time `check` in `samples/web-slice/build.gradle.kts`
is tautological — `without.size < binaryenArgs.size || !binaryenArgs.contains("--gufa")` cannot be
false. The pass-list filter and the runtime `BulkCopyGate` are opt-in per consumer: a second
product linking `dogwood-web` with Kotlin's stock pass list gets blank screens with zero
diagnostics. And the gate has never been observed to fire — ADR-032's own open item.

**Implementation.**
1. Fix the check to assert the *outcome*: after configuring, `check(!binaryenArgs.contains("--gufa"))`.
2. Move enforcement into the module: `DogwoodWebExperience.attach()` (or its factory) runs
   `BulkCopyGate.check()` once per page lifetime and **refuses to attach** on failure with a
   report line — consumers cannot forget what they never had to remember. Keep the sample's early
   loud call as belt-and-braces.
3. Hold the match under the smoke alarm: in `tools/web-weight/bridge/run.sh` (which reproduces
   the miscompilation), load the gate's own check function into the *default-pipeline* build and
   assert it FAILS there, and passes on the gufa-free build. That validates the detector against
   the one known-bad binary in existence.
4. Extend the gate's reference batch: long tuples per kind, an unknown kind, a truncated tuple —
   so the gate also covers the R4 rejection paths in the shipped binary.

**Gate.** (3) is the gate for the gate: `run.sh` output shows `gate: FAIL` on the default
pipeline and `gate: pass` on gufa-safe, both asserted. A new consumer test: a tiny module linking
`dogwood-web` with the stock pass list must fail attach loudly (can be simulated by calling
attach with a stubbed failing gate). The tautology is gone by inspection + the fixed check
actually failing when `--gufa` is injected back into the args in a scratch build.

---

### R7. Run the moved machinery on iOS — ✅ **done** (PR #9)

*All gates met on the simulator. Running it found what compiling it could not: `Shell.evict()` closed
a guest from the user-interface thread, which the Java Virtual Machine tolerated and Kotlin/Native
aborts on — teardown now crosses to the Zipline thread and `DogwoodExperience.close()` asserts it.
The gates are driven by a `--dogwood-drill` launch argument, because `simctl` cannot tap.*

#### Original entry

**Problem.** `Session`, `Shell`, `StateStore` and the code-update flow moved to `commonMain`,
compile for iOS, and have never executed there — `slice-ios/Main.kt` drives a raw
`DogwoodExperience`. Everything R3 threatens lives on exactly those paths. And iOS reopens
ADR-010: everything outside `Caches/` is iCloud-backed by default, so the plain-text saveable
state ADR-010 refuses to let leave the device *would leave the device*; Okio cannot set
`NSURLIsExcludedFromBackupKey`.

**Implementation.**
1. Rewrite `slice-ios/Main.kt`'s `Experience()` on the `DogwoodShell` pattern from
   `TabsActivity.kt`: one shell, `ENTRY_POINTS` tabs, `activate()` per tab, `onEvict`/`onSwap`
   logging — the same observability that caught the Android bugs.
2. Wire `DogwoodStateStore`: file under
   `NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory,...)/dogwood-saved-state.json`;
   add `internal expect fun excludeFromBackup(path: String)` with an iOS actual using
   `NSURL.setResourceValue(true, NSURLIsExcludedFromBackupKey)` (called after each write) and a
   no-op JVM/Android actual. Snapshot from the scene-lifecycle equivalent of `onStop`
   (`UIApplication.didEnterBackgroundNotification` observer), restore-once in the app delegate —
   mirroring ADR-010's read-once-per-process rule, which a `remember` cannot promise (that exact
   bug, third occurrence, is already documented).
3. Memory pressure: observe `UIApplication.didReceiveMemoryWarningNotification` →
   `shell.trimMemory(keep = 1)` — the `onTrimMemory` analogue.
4. Amend ADR-010's assumptions section: iOS location, backup exclusion, and that `NSFileProtection`
   defaults (complete-until-first-auth) are accepted.

**Gate.** The Phase 4.5/ADR-010 drills, re-run on the simulator and evidenced the same way:
- warm tab switch with **zero** `load #` lines; eviction at the cap with `kept N state keys`;
- state survives process death: set state → background → `xcrun simctl terminate` → relaunch →
  same tab, same state, `restored N keys` (screenshot pair as evidence);
- after a store write, `xcrun simctl spawn booted` reads the file's
  `NSURLIsExcludedFromBackupKey == true` (scripted check);
- memory-warning simulation (`simctl` can't send one; use the debug button path or call the
  handler directly from a test hook) evicts all but the visible experience.

---

### R8. The parity ledger — ✅ **mostly done** (PR #10)

*Trust anchor consolidated; the byte cap fixed with a test that failed first; the plural comment now
names Android as the peer and the divergence reports through `SkewReport.untranslatedPlurals`; the
ATS comment, sample dispatcher disposal and stale kdoc all corrected. **Two items deferred, not
done**: the web `SkewReport` analogue, which wants `WireSkew` in `dogwood-wire` and is really the
first step of moving the host onto Wasm rather than a ledger item. (iOS plural rules were the other
one and are now done — [ADR-037](../adrs/layer-5/ADR-037-plural-rules-are-vendored.md).)*

#### Original entry

Each item: problem → implementation → gate, one line each.

- **`TRUSTED_KEYS` ×3 hand-typed** (Android `Slice.kt`, desktop `Main.kt`, iOS `Main.kt`).
  → Move the map into `dogwood-wire` as `DogwoodTrust.DEVELOPMENT_KEYS` (they are throwaway dev
  keys, already public by design) with the rotation commentary attached; samples import it.
  → Gate: grep shows the hex pair exactly once outside lock/manifest files; `SignatureTest` and
  `KeyRotationTest` unchanged and green.
- **Plural rules diverge** (Android ICU vs iOS English-only, comment names the wrong peer).
  → Short term: fix the comment and record the divergence in `SkewReport`-style telemetry
  (`untranslatedPlurals` already exists — report category-fallback on iOS). Real fix: back
  `pluralCategory` with `NSString.localizedStringWithFormat` plural rules or vendored CLDR data —
  investigate cost first (Part 2).
  → Gate: a common test with a Polish `few` case, `expect`ed per platform, documents today's
  divergence explicitly; the comment names desktop *and* iOS.
- **`StateStore.maxBytes` counts chars** (`encoded.length` vs `writeUtf8`).
  → Check `encoded.encodeUtf8().size` (Okio, already on classpath).
  → Gate: a test with a 3-byte-per-char payload sized to pass by chars and fail by bytes — fails
  today, passes fixed.
- **Web `SkewReport` analogue.** → Give `WebTree`/`DogwoodWebExperience` a real `SkewReport`
  (the class is in `dogwood-host`... which web can't link — put a minimal `WireSkew` in
  `dogwood-wire` and have `SkewReport` embed/alias it; this is also the first concrete step of
  the "host on wasm" migration). Sample surfaces it like `TabsActivity` does (sampled, not
  observed). → Gate: web-slice skew scenario (serve a batch with an unknown tag) shows an
  aggregated report line once, not per batch.
- **ATS comment** overstates scope; **sample dispatcher never disposed**; **`Delivery.kt` stale
  kdoc**. → Fix comment (or drop `NSAllowsLocalNetworking`), add `DisposableEffect { onDispose {
  dispatcher.close() } }` to `slice-ios`, update kdoc. → Gate: review-by-diff; the dispatcher
  disposal also gets covered by R3's tests.

---

### R9. Documentation debt — ✅ **done** (PR #11)

*ADR-033 written and indexed; Milestone 14 corrected and Milestone 15 added; the upstream reports
drafted in `tools/upstream-reports/`. **Filing them is deliberately left to a person** — publishing
this project's name and a reproduction against a vendor's product is an outward-facing act, so
ADR-032 now says "drafted, not filed", which is the honest tense.*

#### Original entry

**Problem.** AGENTS.md requires ADRs for maintenance-phase decisions; Phase 6 made several with
none (NSURLSession policy, the 8 MB `NSThread`, the `commonMain` moves, Ktor images). Layer 5's
Milestone 14 doesn't know the web host exists. Two promised upstream reports are tracked nowhere.

**Implementation.**
1. **ADR: the iOS host profile** (layer-5): the dispatcher design and its R3 contract; the network
   transliteration including the scheme lesson and R1/R2 outcomes; the commonMain moves and the
   factory-shim compatibility rule; Ktor-Darwin for images; what the simulator verification does
   and does not establish (per ADR-004's "native code, not native widgets"). List every spec
   updated.
2. Layer-5 Milestone 14: mark partially done with honest scope (five bindings, no services, no
   design system) and a pointer to `dogwood-web` + the four-step `dogwood-host`-on-wasm path.
3. File the two upstream reports and record their URLs in the ADRs that promised them:
   - JetBrains/Binaryen: GUFA + `wasm:js-string intoCharCodeArray` miscompilation, with the
     `tools/web-weight/bridge/` clean reproduction and the context-sensitivity note.
   - JetBrains: incremental Kotlin/Wasm klib `ArrayIndexOutOfBoundsException` in
     `WasmIrFileMetadata.fromByteArray` (workaround: `kotlin.incremental.js.klib=false`).

**Gate.** ADR indexed in `adrs/README.md` with its "Updated Documents" list real (the AGENTS.md
validity rule); both report URLs present in ADR-032 §toolchain and the iOS ADR; `grep -r "reported
upstream"` finds no unbacked claim.

---

### R10. Measurements still owed *(the standing list; each is its own gate)*

- **Phase 0 performance suite on iOS** (Phase 6 step 3). Implementation: port `tools/phase0`'s
  host driver to a Kotlin/Native runner (host-core is common; the driver needs an iOS `main`),
  run 0.2/0.3/0.5 on the simulator AND note it is not gate-valid hardware; publish
  `results/ios-simulator.md` against the Phase 0 baseline table. Gate: the results file, with the
  collection-pause row filled in — the M3 number (5.2–7.0 ms vs the 8 ms budget) is the one with
  a plausible route to failing on a phone.
- ✅ **Web time-to-first-frame — done (PR #17), [the harness and results](../tools/web-ttff/README.md).**
  Ten cold loads per preset, Chrome's own `Network.emulateNetworkConditions`, the shipped
  `index.html` rather than an instrumented copy, brotli at quality 11 to match ADR-030's table (the
  runner refuses to measure if the server is not actually serving `Content-Encoding: br`).

  | preset | p50 | p95 |
  |---|---|---|
  | unthrottled | 135 ms | 215 ms |
  | 4G (9 Mbit/s, 85 ms) | 3,185 ms | 3,206 ms |
  | Fast 3G (1.6 Mbit/s, 562.5 ms) | **17,415 ms** | 17,469 ms |

  3,103,293 bytes transferred, identical in every load of every preset — which is the check that
  the throttle changed only how fast the bytes arrived. **Transfer dominates and it is not close**:
  3.03 MiB at 1.6 Mbit/s is 15.5 s of pure transfer against a 17.4 s measurement, so everything the
  host does after the bytes land costs on the order of a hundred milliseconds. That confirms from
  the other direction what ADR-030 could only assume — page weight is the only lever here — and it
  makes seventeen seconds a **product constraint** rather than a benchmark result. p95 within 0.4%
  of p50 on both throttled presets: no tail, because the bottleneck is a constant-rate pipe.
  *Original entry:* the page already carries
  `#dogwood-first-frame`; serve the brotli'd distribution with throttling (Chrome DevTools
  protocol `Network.emulateNetworkConditions`, Fast-3G and 4G presets) from the existing harness.
  Gate: p50/p95 across ≥10 cold loads per preset, published next to ADR-030's byte table.
- **Accessibility interaction on iOS** needs a human: typing via the IME, selection handles,
  rotor, spoken order. Gate: a filled-in checklist in the iOS ADR; the automated tree dump is
  already evidence for structure, not experience.
- **The Phase 0 gate device** remains unacquired (Layer 4 ADR-008 stands; nothing here reopens it,
  but R10's iOS numbers make the case for finally buying the phone).

### R11. Deferred deliberately, and why *(carried, not forgotten)*

Three things this review chose **not** to do. They are recorded here so the choice stays visible
rather than turning into an omission nobody can date.

**Upstream bug reports stay drafted and unfiled — a standing decision, not a pending task.**
`tools/upstream-reports/` holds finished text and reproductions for both toolchain defects: the
GUFA miscompilation of `String.toCharArray()`, and the incremental Kotlin/Wasm klib crash. Filing
them publishes this project's name and a reproduction against a vendor's product, and the owner has
decided that is not something to do from here. The drafts stay current so anyone who *does* file
them has the work already done; ADR-032 and ADR-033 say "drafted, not filed", which is the honest
tense and should stay that way rather than drifting back to "reported".

**iOS plural rules stay English-only until the cost is known.** ✅ **Resolved** — vendored CLDR
rules, checked against ICU4J ([ADR-037](../adrs/layer-5/ADR-037-plural-rules-are-vendored.md)). The
original entry, kept because it is what the ledger recorded at the time: Android reaches real Unicode plural
rules by reflection; iOS does not, so one payload gets correct Polish `few` on Android and the wrong
category on iOS. It is not silent — `SkewReport.untranslatedPlurals` reports the fallback — but it is
a genuine divergence in what a user reads. Fixing it means either `NSString` plural formatting or
vendoring CLDR category data for the locales a product actually ships, and that is a sizing exercise
before it is a code change. Cost it out; then decide. Do not "just add Polish".

**The web `SkewReport` analogue waits for the host migration.** Web skew is freeform report lines
rather than the aggregated structure mobile has. The fix wants a `WireSkew` in `dogwood-wire` that
`SkewReport` embeds — which is genuinely the *first step of moving `dogwood-host` onto WebAssembly*,
not a ledger item. Doing it standalone would build a second reporting type that the migration then
has to reconcile. Sequence it with that work (Layer 5 Milestone 14), not before.

## Part 2 — Investigate before deciding (outcome is a decision + ADR, not code)

- ✅ **Coil as a second, unpoliced network channel — done (PR #12), [ADR-034](../adrs/layer-5/ADR-034-images-are-a-network-channel.md).** Images got their own default-deny rule rather than sharing the data one, because a content delivery network should serve pictures without also being allowed to answer data requests. Verified on device that the interceptor is genuinely installed, which no unit test can show. iOS and desktop samples are still unwired. *Original entry:* Guest image URLs bypass the
  allow rule entirely. Investigate: can Coil 3's `ImageLoader` take an interceptor applying the
  same `allow` lambda (it can — `components { add(Interceptor) }`) and what should image policy
  *be* — same allowlist as data, a separate one, or host-app-owned? Gate: an ADR deciding it,
  plus (if adopted) an interceptor test proving a disallowed image URL never opens a connection.
- ✅ **Non-transactional apply — done (PR #14), [ADR-011](../adrs/layer-4/ADR-011-a-batch-applies-whole-or-not-at-all.md).**
  Validate-then-apply, chosen over the other two options for reasons the review had not surfaced:
  snapshot rollback is not merely expensive but **incorrect here**, because `byId`, the per-node
  `slots` map and `appliedSequence` are plain fields and would keep the failed batch's edits; an
  undo journal duplicates the applier's semantics on the path least likely to be exercised, and
  mutates first, so the leak detector would report undone removals as leaks. The shared rule lives
  in `dogwood-wire` (`BatchValidation.kt`, one `TreeShape` interface) so all three appliers — mobile,
  the plain-tree comparison, and web — cannot drift on what "will apply cleanly" means.
  **Measured:** validation costs 59–68 µs on a 600-change batch against 181–257 µs to decode the
  same batch, roughly a quarter of decode and well inside a frame; the test asserts a ceiling
  relative to decode rather than a fixed number, and prints the measurement. Nine mobile tests and
  four web tests, including the case a size-only validator would miss (a reference into a subtree
  the same batch removed). Run on all three hosts against real payloads, because the risk is
  false *rejection*, not false acceptance: Android every tab twice plus list flings, iOS the full
  drill, web 2 batches and an event round-trip — no rejections anywhere. **Still open:** containment
  is not repair. A rejected batch leaves the host's tree older than the guest believes and nothing
  resynchronises them; a resynchronisation protocol is named in the ADR and not built.
  *Original entry:* Options: snapshot-global-write rollback around
  `tree.apply` (Compose `Snapshot.takeMutableSnapshot` — plausible on host, measure cost),
  validate-then-apply (two passes over the batch; cheap given 0.17 ms decode), or document the
  narrower guarantee. Gate: decision ADR with the measured cost of the chosen option; if
  validate-then-apply, a test where a mid-batch dangling reference leaves the tree untouched.
- ✅ **Guest-controlled crash values — done (PR #13), [ADR-035](../adrs/layer-5/ADR-035-hostile-property-values.md).**
  Confirmed first, then fixed. `HostileValueTest` drives real batches through the real tree and the
  real renderer: five of its seven cases crashed the render before the change. Clamp-and-report
  now lives in the readers (`intClamped`, `floatClamped`, `clampModifierValue`), applied to
  `Text.maxLines`, `padding`, `weight`, `size`, `width` and `height`, with every clamp recorded in
  `SkewReport.clampedValues` carrying the value that arrived and the range it was forced into.
  Run end to end on both mobile hosts against a real payload —
  [the hostile-value drill](../tools/hostile-value-drill/README.md) — where the pre-fix builds both
  died (`FATAL EXCEPTION` on Android, `Uncaught Kotlin exception` on iOS) and the post-fix builds
  rendered all three widgets and reported all three clamps. The drill also found that the Tabs
  sample's `skew` variable was dead — declared, never written, never shown — so containment was
  again invisible; both samples now poll the report. **Still open:** the clamp is hand-written per
  site. A range declared on the surface, so the generator emits it for every property whose bounds
  it knows, is the durable fix and is named as an unstated assumption in ADR-035. *Original entry:*
  (`maxLines ≤ 0`, negative padding, `weight ≤ 0` throw inside
  composition on both hosts). Investigate the full surface by fuzzing property values through the
  generated bindings in the JVM host tests. Likely fix: clamp-and-report at the reader layer
  (`WidgetView.int(tag, default, min, max)`) so it is generator-wide, not per-binding. Gate: the
  fuzz test passes; hostile values land in `SkewReport` rather than in a stack trace.
- ✅ **Kotlin/Native leak-test flake risk — done (PR #16), [the soak](../tools/leak-soak/README.md).**
  **50 of 50 passed**, so the control needed no restructuring — the outcome the review hoped for and
  did not assume. `--rerun-tasks` on every iteration is not incidental: without it Gradle answers 49
  of the 50 from its up-to-date cache and the soak proves only that caching works. The script is
  kept rather than deleted after one green run, because this is a soak on one Kotlin/Native release
  and the question has to be re-asked when the toolchain moves. A first attempt was thrown away
  rather than reported: an unrelated compile error in the iOS test sources turned runs 15-50 red for
  a reason that had nothing to do with leaks, and a soak that counts build failures as flakes
  measures nothing. *Original entry:* (conservative stack scanning vs the negative control).
  Gate: 50 consecutive green runs of `iosSimulatorArm64Test` (scripted); if flaky, restructure
  the control per the reviewer's note before trusting CI.
- ✅ **iOS plural rules — done (PR #15), [ADR-037](../adrs/layer-5/ADR-037-plural-rules-are-vendored.md).**
  Both options were costed and one was *measured out*: `FoundationPluralProbeTest` builds a
  `.stringsdict` at run time whose every category maps to its own name and renders it for a matrix
  of counts on the simulator. Every language answers identically — Arabic two is `other`, Polish
  three is never `few`, and with the literal-count keys withheld nothing is ever `few` or `many` —
  with a per-language marker proving the right table was loaded. Foundation matches literal counts,
  it does not select Unicode categories, and it would have been the wrong shape anyway: the table
  would have to live in the application bundle, and the languages a payload carries are not known
  when the application is compiled. So the rules are vendored in common code for ~180 languages and
  **checked against ICU4J** (a test-only dependency) across sixty boundary counts rather than
  trusted — which immediately found European Portuguese, sharing Brazilian's round-million `many`
  clause while differing on zero. Fixes desktop as well as iOS, which had the same gap. The
  divergence stops being silent twice over: for covered languages it is gone, and for an uncovered
  one `SkewReport` now distinguishes "the payload had no words for this category" from "the host
  had no rules for this language", which used to be the same line. Verified on Kotlin/Native
  through the shipped function, not only against the table on a Java Virtual Machine.
  *Original entry:* (see R8) cost out `NSString` plural formatting vs vendoring CLDR
  category data for the locales products actually ship. Gate: decision recorded; either way the
  Android/iOS divergence stops being silent.

## Part 3 — What the reviews confirmed is solid

Recorded so the findings above read as targeted rather than damning: the web delivery ordering is
real (exactly one `Worker(` construction, after all checks; the refusal harness proves the guest
is never fetched); the web mirror is genuinely snapshot-backed with `key(child.id)` and correct
`ChildMove` arithmetic; unknown web widgets placehold and keep index arithmetic; decode failures
are typed refusals into a report; the iOS `ThreadIdentity` fix is correct and fails loudly in the
safe direction; the iOS dispatcher's FIFO ordering, reentrancy fast path and tested cycle-break
are right (R3 narrows its close semantics without touching those); the cross-language leak tests
are non-vacuous in both directions; and the payload cache is correctly in `Caches/`.

## Sequencing

| Order | Item | Why this position |
|---|---|---|
| 1 | R1 + R2 (one PR) | The security boundary; R2's iOS half lands on R1's delegate |
| 2 | R3 | Unblocks R7 — the shell on iOS is untrustworthy until dispatch-after-close is loud |
| 3 | R4 | Grammar parity; small, closes the corruption class everywhere |
| 4 | R5 + R6 (one PR) | Worker lifecycle and gate enforcement touch the same attach path |
| 5 | R7 | The big iOS verification pass, on top of 2 |
| 6 | R8 + R9 | Ledger and paper, any time after the code settles |
| 7 | R10 + Part 2 | Measurements and decisions; parallelisable throughout |

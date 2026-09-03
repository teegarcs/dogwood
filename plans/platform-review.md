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

### R3. The iOS dispatcher must not silently drop work *(high; ~0.5 day)*

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

### R4. Web decoder grammar parity *(high; ~1 day)*

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

### R5. Worker lifecycle *(high; ~1 day)*

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

### R6. Make the `--gufa` gate real *(medium; ~0.5 day)*

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

### R7. Run the moved machinery on iOS *(medium; ~1.5 days)*

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

### R8. The parity ledger *(medium; ~1 day total, independent small items)*

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

### R9. Documentation debt *(medium; ~1 day)*

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
- **Web time-to-first-frame, realistic.** Implementation: the page already carries
  `#dogwood-first-frame`; serve the brotli'd distribution with throttling (Chrome DevTools
  protocol `Network.emulateNetworkConditions`, Fast-3G and 4G presets) from the existing harness.
  Gate: p50/p95 across ≥10 cold loads per preset, published next to ADR-030's byte table.
- **Accessibility interaction on iOS** needs a human: typing via the IME, selection handles,
  rotor, spoken order. Gate: a filled-in checklist in the iOS ADR; the automated tree dump is
  already evidence for structure, not experience.
- **The Phase 0 gate device** remains unacquired (Layer 4 ADR-008 stands; nothing here reopens it,
  but R10's iOS numbers make the case for finally buying the phone).

## Part 2 — Investigate before deciding (outcome is a decision + ADR, not code)

- **Coil as a second, unpoliced network channel (all platforms).** Guest image URLs bypass the
  allow rule entirely. Investigate: can Coil 3's `ImageLoader` take an interceptor applying the
  same `allow` lambda (it can — `components { add(Interceptor) }`) and what should image policy
  *be* — same allowlist as data, a separate one, or host-app-owned? Gate: an ADR deciding it,
  plus (if adopted) an interceptor test proving a disallowed image URL never opens a connection.
- **Non-transactional apply, both platforms.** Options: snapshot-global-write rollback around
  `tree.apply` (Compose `Snapshot.takeMutableSnapshot` — plausible on host, measure cost),
  validate-then-apply (two passes over the batch; cheap given 0.17 ms decode), or document the
  narrower guarantee. Gate: decision ADR with the measured cost of the chosen option; if
  validate-then-apply, a test where a mid-batch dangling reference leaves the tree untouched.
- **Guest-controlled crash values** (`maxLines ≤ 0`, negative padding, `weight ≤ 0` throw inside
  composition on both hosts). Investigate the full surface by fuzzing property values through the
  generated bindings in the JVM host tests. Likely fix: clamp-and-report at the reader layer
  (`WidgetView.int(tag, default, min, max)`) so it is generator-wide, not per-binding. Gate: the
  fuzz test passes; hostile values land in `SkewReport` rather than in a stack trace.
- **Kotlin/Native leak-test flake risk** (conservative stack scanning vs the negative control).
  Gate: 50 consecutive green runs of `iosSimulatorArm64Test` (scripted); if flaky, restructure
  the control per the reviewer's note before trusting CI.
- **iOS plural rules properly** (see R8): cost out `NSString` plural formatting vs vendoring CLDR
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

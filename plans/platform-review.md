# Platform review: what Phases 5 and 6 left to harden

## Part 0 — Why this plan exists

Phases 5 and 6 were built fast and verified behaviourally — a browser renders, a simulator renders,
the assertions are on pixels and glyph boxes rather than logs. What neither phase got was an
adversarial read of its code, which is this project's own bar for "done". Two reviewers (one per
phase) plus a cross-cutting sweep produced the findings below. Each was **verified in source before
it was accepted**; the reviews also confirmed a good deal that is genuinely solid, recorded at the
bottom so the picture is honest in both directions.

One finding did not wait for this plan: the iOS network policy passed `file://localhost/...` for any
cleartext-waived host — NSURL parses any scheme and NSURLSession serves `file:` natively, so the
app's own container was readable through the "network" policy and exfiltrable to any allowed host.
**Fixed immediately** (the waiver now names `http` instead of excusing "not https"), with the iOS
policy test suite whose absence let it survive. The lesson is itself a plan item: a security
boundary ported without its tests is a boundary ported without its guarantees.

## Part 1 — The work, in priority order

### R1. Network policy parity on iOS *(high; ~1 day)*

The `UrlSessionNetwork` transliteration kept the JVM's shape and lost its teeth.

- **The body cap is advisory.** The completion-handler API buffers the whole response before the
  check runs; a 500 MB body from an allowed host jetsams the app before the cap is evaluated. The
  JVM refuses on declared `Content-Length` *and* reads at most `cap + 1` bytes off the wire. Fix is
  a delegate-based session: `didReceiveResponse` checks the declared length, `didReceiveData` keeps
  a running count and cancels over cap.
- **The idle timeout is the only timeout.** `timeoutInterval` is inter-byte; the resource timeout is
  Foundation's default of seven days. A one-byte-per-59-seconds drip holds the request — and, until
  the cap fix, an ever-growing buffer — indefinitely.
- **Port the policy test suite.** `NetworkPolicyIosTest` now covers the allow rule; the fetch-path
  contracts (refusal as value, cap against declared and actual size) still have no iOS tests.

### R2. Redirects are unpoliced on BOTH platforms *(high; ~0.5 day)*

The allow rule runs once, pre-flight. `NSURLSession.sharedSession` and the default `OkHttpClient`
both follow redirects, so any allowed host with an open-redirect endpoint lets a guest reach — and
read up to 1 MiB from — arbitrary hosts. The iOS review found it; the JVM has the identical hole,
faithfully transliterated. Fix: re-apply the allow rule per redirect (a session delegate on iOS, an
interceptor or `followRedirects(false)` on the JVM), plus tests on both.

### R3. The iOS dispatcher drops work after close *(high; ~0.5 day)*

`DogwoodZiplineDispatcher.dispatch` is `sendChannel?.trySend(block)` with the result discarded.
After `close()` — or racing it — a dispatched block vanishes, and a vanished block is a vanished
continuation: anything suspended in `withContext(ziplineDispatcher)` (session swap, eviction,
`snapshotState`) hangs forever, and *cancellation cannot complete either*, because the cancel
resume dispatches to the same dead dispatcher. That leaks exactly what Phase 6's leak tests exist
to catch. Also: a block that throws kills the drain loop while the channel stays open, so later
sends queue into silence. Fix: reject-after-close loudly, catch around the drain, and tests for
both. What is *solid* and must not regress: FIFO ordering, the undispatched fast path preventing
self-deadlock, and the tested thread-cycle break in `close()`.

### R4. Web decoder grammar parity *(high; ~1 day)*

`FastPositionalDecoder` reintroduces, in miniature, what ADR-009 abolished:

- **Integer overflow wraps silently.** `value * 10 + digit` with no range check, so an id of
  2^32+1 becomes 1 and the change applies to a *different live node* — the reference decoder
  refuses the same batch. This is the silent-corruption class, back.
- **The arity table is a re-typed copy**, forced by `ChangeKind.arity` being `internal`. Make it
  public; delete the copy.
- **The two decoders already disagree** on quoted integers (`["7",...]` — kotlinx accepts, fast
  path rejects) and on lexical form of value-position numbers. Decide the grammar, then add a
  **differential test**: generated batches through both decoders, agreement required on
  accept/reject *and* on the decoded value. `dogwood-web` currently has no test source set at all.
- **Mobile symmetry check while there**: kotlinx throws `NumberFormatException`, not
  `ProtocolMismatch`, for overflow — which would *escape* `sendChanges`' catch and crash the
  mobile host on input the web host now contains. Verify and fix the catch.

### R5. Worker lifecycle *(high; ~1 day)*

No `onerror`, no `onmessageerror`, no timeouts. `new Worker(url)` succeeds even when the script
404s, so a broken deployment reports `Started` and renders silence; `snapshotState` on a crashed
guest waits forever (the sample survives only because its own poll loop times out — the mitigation
lives in the wrong layer). Also: a cross-origin `guestScript` in the manifest throws a synchronous
`SecurityError` that escapes `start()` uncaught — check resolved-origin before construction and
refuse typed; `configurationSent` never resets, so a second `attach()` leaves the new guest
composing nothing forever; a malformed snapshot payload silently becomes an *empty* snapshot where
the eviction path would persist it — that must be the reported-failure path, never `StateSnapshot()`.

### R6. Make the `--gufa` gate real *(medium; ~0.5 day)*

Three separate problems: the build-time `check` is **tautological** (one disjunct is always true —
it can never fail, while its comment insists it is "not decoration"); the pass-list filter and the
runtime gate are both opt-in per consumer, so a second product linking `dogwood-web` with the stock
pass list gets blank screens with zero diagnostics; and the gate has still never been observed to
fire — ADR-032's own open item, validating it against the known reproduction in
`tools/web-weight/bridge/`, is undone. Fix the check, move enforcement into `dogwood-web` (refuse
to attach ungated), hold the match under the smoke alarm.

### R7. Run the moved machinery on iOS *(medium; ~1.5 days)*

`Session`, `Shell`, `StateStore` and the code-update flow moved to `commonMain`, compile for iOS,
and **have never executed there** — the sample drives a raw `DogwoodExperience`. Everything R3
threatens lives on exactly those paths. Wire `slice-ios` through `DogwoodSession` and the shell
(tabs, warm switching, eviction), then the state store — which forces the ADR-010 question iOS
reopens: everything outside `Caches/` is **iCloud-backed by default**, so the plain-text saveable
state ADR-010 refuses to let leave the device would leave the device. Decide location + backup
exclusion (Okio cannot set `NSURLIsExcludedFromBackupKey`; that needs a platform call), and amend
ADR-010's "private storage" reasoning for iOS.

### R8. The parity ledger *(medium; ~1 day, mostly small)*

Differences between platforms that are individually small and collectively the drift this project
keeps re-fixing:

- **`TRUSTED_KEYS` now has three hand-typed copies** (Android, desktop, iOS) plus the web manifest
  story. Byte-identical today; generate or share before rotation makes them disagree.
- **Plural rules**: Android uses real ICU via reflection; iOS is English-only *and its comment
  compares itself to the wrong peer*. One payload, correct Polish on Android, wrong on iOS.
- **`StateStore.maxBytes` counts UTF-16 chars, not bytes** — non-ASCII state can exceed the cap
  ~3–4×. Pre-existing; the move put it on more platforms.
- **Web has no `SkewReport` analogue** — skew is freeform report lines, unaggregated and repeated.
  Not silent (good), but the fidelity drop is stated nowhere.
- **NSURL vs HttpUrl normalisation**: case and IDN handling differ; all divergences fail closed,
  but a payload author will eventually hit the asymmetry.
- Small singles: ATS comment overstates its scope (`NSAllowsLocalNetworking` is broader than
  "localhost"); the iOS sample never disposes its dispatcher (wrong as the template it offers
  itself as); `Delivery.kt`'s kdoc still says the state store "does not exist yet".

### R9. Documentation debt the phases created *(medium; ~1 day)*

AGENTS.md is unambiguous that maintenance-phase decisions require ADRs, and Phase 6 made several
with none: the NSURLSession policy transliteration, the 8 MB `NSThread` dispatcher, the
`commonMain` moves, the Ktor image path. One ADR covering the iOS host profile (the web profile
got ADR-032; iOS got a commit message). Also: Layer 5's Milestone 14 doesn't know the web host
exists; and the two promised upstream reports are tracked nowhere — the `wasm-opt`/GUFA
miscompilation (clean reproduction in `tools/web-weight/bridge/`) and the Kotlin incremental-klib
crash (worked around globally in `gradle.properties`).

### R10. Measurements still owed *(the standing list)*

- **Phase 0 performance suite on iOS** against the Phase 0 baseline — Phase 6 step 3, untouched.
  It matters more than it looks: collection frames cost 5.2–7.0 ms against an 8 ms guest budget on
  an M3, and no iOS hardware has been measured.
- **Time to first frame on web** under a realistic network and hardware — 331–468 ms is loopback
  under SwiftShader.
- **Accessibility interaction** on iOS needs a person: typing, the keyboard, selection handles,
  focus order, whether the speech makes sense. The tree shape is verified; the experience is not.
- **The Phase 0 gate device** remains unacquired (Layer 4 ADR-008 stands).

## Part 2 — Investigate before deciding (genuinely open)

- **Coil is an unpoliced second network channel on every platform.** Guest-supplied image URLs are
  fetched by the host with no allow rule — the default-deny story covers `DogwoodNetwork` only.
  Pre-existing on Android; Phase 6 extended it to iOS without comment. Needs a decision, not just
  a fix: images may *want* a different policy than data.
- **Non-transactional apply, both platforms.** A batch that decodes but fails mid-apply leaves a
  half-applied tree (web catches after the fact; mobile doesn't catch at all). "Rejected whole"
  currently holds for decode failures only. Options: snapshot-rollback around apply, validate-
  then-apply, or document the narrower guarantee.
- **Guest-controlled crash vectors shared by both hosts**: `maxLines ≤ 0`, negative padding,
  `weight ≤ 0` all throw inside composition. The skew story catches unknown *names*; it does not
  catch hostile *values*. Probably wants range-clamping at the binding layer with skew reporting.
- **Leak-test flake risk on Kotlin/Native**: conservative stack scanning could make the negative
  control flaky. Run the iOS suite ~50× before trusting it in CI.

## Part 3 — What the reviews confirmed is solid

Worth recording so the findings above read as targeted rather than damning: the web delivery
ordering is real (exactly one `Worker(` construction, after all checks; the refusal harness proves
the guest is never fetched); the web mirror is genuinely snapshot-backed with `key(child.id)` and
correct ChildMove arithmetic; unknown web widgets placehold and keep index arithmetic; decode
failures are typed refusals into a report; the iOS `ThreadIdentity` fix is correct and fails
loudly in the safe direction; the iOS dispatcher's ordering, reentrancy fast path and tested
cycle-break are right; the cross-language leak tests are non-vacuous in both directions; and the
payload cache is correctly in `Caches/`.

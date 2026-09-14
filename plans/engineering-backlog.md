# The remaining engineering, planned

Adopted 2026-09-08. This is the successor to [`plans/score-improvement.md`](score-improvement.md)'s
Track E, and its relationship to the score is stated up front so nobody mistakes the purpose:
**almost nothing here moves the grade** — run 2 confirmed production readiness and performance sit
at their rubric caps and adoption at its anchor, all owner-gated. What this list moves is what a
real adopter hits: the catalogue floor, the safety asymmetries between clients, and the cells of
the conformance matrix that still read `—`. The exception is C-group, which the grader named as
the only engineering path back to A-range flexibility.

Everything here follows the house rule that closed the audit: **done means run**, with the defect
each first run finds recorded where it was found.

---

## P — the performance question, answered before it is planned

**A lower-quality simulator or emulator does not substitute for the gate device, and this is a
finding, not a preference.** The iOS Simulator executes host-native code on the development
machine's cores — functionally accurate, performance-meaningless by construction. The Android
emulator already produced Phase 0 numbers (`PauseWatcher` ran there), and they cleared every budget
*because* the emulator borrows the host's single-core speed — the exact dimension a 2022-tier
Cortex-A53 lacks. The available knobs go the wrong way: an Audio Video Device (AVD) profile can
shed cores and memory but cannot honestly slow a core, and process-level throttling produces
numbers that are neither the fleet's nor reproducible — unattributable numbers being precisely what
the Phase 0 harness appendix exists to refuse, and why [Layer 4 ADR-008](../adrs/layer-4/ADR-008-gate-device-not-available.md)
defined the gate on named hardware in the first place. The rubric's performance cap says
*representative*; a fast core pretending to be slow is neither representative nor stable.

**Approved by the owner, 2026-09-08**: the sensitivity-experiment framing below is the accepted
answer — an emulator run stands in for nothing, and the curve it measures informs the §4 device
decision rather than substituting for it.

| # | Item | Done means |
|---|---|---|
| P1 | ✅ **The scaling-sensitivity experiment** — done 2026-09-09 ([ADR-064](../adrs/layer-4/ADR-064-the-tail-budgets-headroom-was-parallelism.md)) | The Phase 0 harness run on the same emulator at 4 cores and at 1 core, same pinned toolchain, results committed beside the existing ones with the core count named. The deliverable is the **shape of the degradation curve** — whether the budgets carry 5× headroom or 1.2× — explicitly labelled as sensitivity, never as gate evidence. It sharpens the owner's §4 decision; it does not replace it.<br><br>**The answer moved the decision.** Median ratio 1.93×, but the median is not the story: `p50` recomposition is flat (0.81×–1.10×, in one case *faster* at one core, which is what a single-threaded interpreter losing cross-core scheduling looks like) while every `p95`/`p99` degrades two- to six-fold. Concretely, **`G1` (recompose p95 ≤ 8 ms) is met at four cores and missed at one** — 1.65–3.98 ms becomes 5.64–15.13 ms, two of five over budget, with nothing about the payload changed. The steady-state margin is real; the tail margin was four cores. **So the gate device is more urgent, not less** — the opposite of what an emulator run is usually reached for |

## C — the catalogue (audit B1's remainder; the grader's flexibility path)

Each component follows the pattern `Dialog` and `SheetArea` established: surface entry → segment
version bump (the lock will insist) → impl → generated binding → render tests with controls on
three targets → docs regenerate themselves. Holder shapes add the guest holder + host mirror pair,
at ADR-043's now-five-times-verified cost.

| # | Item | Done means |
|---|---|---|
| C1 | ~~Menu / dropdown~~ ✅ | A `Menu` anchored to its invoking control; openness is guest state (the `Dialog` finding — no holder for a value the guest owns); dismissal is an event; items are a slot. Render tests: open shows items, close *removes* them, an item tap crosses as its event |
| C2 | ~~Pager + `PagerState`~~ ✅ (shape six) | Scroll's pattern on pages: declared target page + sequence, continuous report of the settled page and whether the **user** swiped it (the sheet's `byUser` field, for the same reason). Tests include the ask-twice-after-user-swipe case that makes the sequence necessary |
| C3 | ~~Date and time pickers~~ ✅ (shapes seven and eight) | Snackbar's pattern: a request that answers with what the user chose, carrying the sequence it answers. The value crosses as ISO-8601 text, because a client's locale must not be baked into the wire |

**Landed 2026-09-08: the count is 20**, against a product's fifty to two hundred — the audit's B1
stays open past this plan and says so. Two findings came out of building them. `Pager` **cannot be a
generated component**: Compose's pager composes one page at a time by index, which is exactly the
indexed-content lambda the parser refuses to bind, so it joins the two lazy lists as a hand-written
binding on a reserved tag — while its *holder* stays a generated shape, so a guest cannot tell which
side of that line its component falls on. And the pickers needed **two** shapes rather than one with
a mode, because the host draws a calendar grid and a clock face, and a mode flag would put a
conditional inside a mirror with no business branching on what the guest meant.

## S — the safety asymmetries between clients

| # | Item | Done means |
|---|---|---|
| S1 | ✅ **Mobile pre-flight dictionary check** — done 2026-09-08 ([ADR-061](../adrs/layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md)) | The web refuses a too-new payload **before any guest code runs** (claim `B3`); the mobile manifests carry no segment versions, so render-time containment is their only line. The payload's segment-version vector rides Zipline's **signed** metadata (where the kill switch already rides), `DogwoodDelivery` compares it before `start`, and `B3` is graded on Android and iOS by a skew-drill variant serving a version-declared payload. Skew containment stays, for the payload that declares nothing.<br><br>**Graded:** `B3 PASS` on a real emulator and a real simulator — *"dogwood.designsystem wants 15, this client implements 14"*, with no widget from the refused payload on screen. `tools/skew-drill/run-preflight.sh` and `run-preflight-ios.sh`; containment (`A2`–`A4`) re-run on both and still green against a payload that declares nothing. **Two defects found on the way**, both by running it: the declaration resolved at Gradle *configuration* time and so described the previous build's payload (a manifest went out saying 14 for a payload built at 15 — the exact failure the field exists to prevent), and the client's own version map was captured at construction, which would have made a host that registered its bindings late refuse every payload naming them |
| S2 | ✅ **A signed web sidecar** — done 2026-09-08 ([ADR-062](../adrs/layer-3/ADR-062-a-signed-web-sidecar.md)) | The web kill switch is only as trustworthy as its origin, and the manifest field says so. A detached Ed25519 signature over the sidecar, verified against the same trusted keys before the sidecar is believed; unsigned sidecars remain accepted only when the host passes no keys — a written decision, per the ADR-058 pattern. Done means the drill flips a byte and watches refusal.<br><br>**Graded** on a real browser, five ways: the signed sidecar starts (`B1-control`), a sidecar whose `guestScript` was rewritten after signing is refused with `SignatureRefused` and no Worker (`B1`), a sidecar with **no** signature beside it is refused too (`B1-absent`), a sidecar signed only by the rotation key is accepted (`B2`), and a host passing `emptyMap()` still runs the altered one (`B1-unsigned-posture`) — which pins the refusals to the signature check rather than to the fixture. The web cell for `B1`/`B2` moved off `dev.dogwood.host.SignatureTest`, a Java Virtual Machine test of a verifier the web does not compile. **Half of ADR-032's gap remains and is stated:** the guest script itself is still fetched without an integrity check |
| S3 | ✅ **Web crash stacks** — done 2026-09-08 ([ADR-063](../adrs/layer-5/ADR-063-a-web-crash-carries-its-frames.md)) | The Worker error path carries a message where the Zipline path carries a source-mapped stack (ADR-059's recorded remainder). Establish what a webpack production build can preserve, route whatever that is through `onGuestError`, and crash a real guest in a real browser to read what arrives — the claim goes whichever way the evidence does, as A4's did.<br><br>**Measured first.** A production webpack build mangles names (`bn.p8`) and keeps **exact offsets**, which is what a source map resolves — so the frames are worth carrying and the resolution belongs offline. `CrashScreen.kt` is a payload that renders, applies a frame, then throws from an effect; the stack rides the existing envelope as a second paragraph, so no revision and no host has to change. Graded on a real browser: `A4-web` (the message crosses intact), `A4-web-stack` (**10 frames**), `A4-web-control` (it rendered before it threw). `tools/symbolicate/resolve.py` resolves every frame against the map the build keeps and deliberately does **not** serve — a public map hands out the source. Top frame: `CrashScreen.kt:68`, the `error(...)` call. **Found by looking:** the map itself contains a position past the end of the file it names; the tool reports it and names the nearest real mapping rather than inventing one. Upstream draft #4, unfiled |
| S4 | ✅ **Multi-team payloads** (audit B3) — done 2026-09-09 | Not new machinery until a need is demonstrated: a sample with **two** payloads in two shells, the real costs measured and written down (separate caches, separate guards, no shared warm pool), and the surface-lock merge workflow documented with an actual two-branch conflict resolved in the text. If the measurements demand engine work, that becomes its own plan with its own ADR.<br><br>**They did not.** [`docs/multi-team.md`](../docs/multi-team.md) carries both halves. The lock conflict was **run**: two branches each appending a component, both claiming tag 24, the real `git merge` output, the safe resolution (reset the lock to the *merge base*, never hand-merge generated JavaScript Object Notation), and what the generator says when you get it wrong — `dictionary lock violated; tags are permanent: FilterChip moved from tag 24 to 25`. It also found a hazard the lock does **not** catch: the version number merges cleanly, so a payload published from an unmerged surface branch declares a version that will mean something else once it lands. The rule that follows — publish from the integration branch, never from a surface branch — is written down rather than built, because the enforcement point is a deployment pipeline. `samples/two-payloads` + `samples/second-guest` host two independently signed and versioned payloads; the cost is **a second copy of the runtime on disk per payload** (1.15 MB for a three-node payload), not memory or latency. No engine work needed |

## V — evidence-widening: cells that read `—` today

| # | Item | Done means |
|---|---|---|
| V1 | ✅ **K1/K2 on Android and iOS** — done 2026-09-08 | The frozen fixture served to the installed release build (manifest address overridable by intent extra / launch argument, as the desktop's now is); the cross-version drill grades load-verify-render per mobile client.<br><br>Android takes `--es manifest`, iOS takes `--dogwood-manifest`, mirroring the desktop's `-Ddogwood.manifest`. Both mobile samples now report the version **and the verifying key** on swap — they reported neither, which is why `K1` ("loads *and verifies*") could only ever be graded on the desktop. `tools/conformance/cross-version-mobile.sh <android\|ios>`; both green against the frozen `payload-2026-09-08` |
| V2 | ✅ **A desktop skew drill** — done 2026-09-08 | The one client where containment has never met a real skewed payload. The two-build shape is portable; the instrument is the render transcript (the desktop has no `uiautomator`), read through a `--check`-style mode as Umbra's is.<br><br>`A2`–`A4` green on the desktop. **Two things had to be got right and the first attempt got both wrong.** Gradle had to leave the loop entirely: `./gradlew run` after the patch recompiles the *engine* against the patched surface and fails to build, so the drill resolves the runtime classpath before patching and launches a bare `java` — the desktop equivalent of not reinstalling the application package. And `A4` had to read `withheld`, not absence: a withheld widget draws an empty box **by design**, so its binding runs and the transcript records it, and the first version asserted the absence of a label the transcript never carries — **passing while testing nothing**. The generator now records `withheld` in the node's own transcript line, which makes the transcript a faithful witness rather than an approximate one |
| V3 | ✅ **H2's device half** — done 2026-09-08 | A committed fixture payload that crashes on launch, served twice through the reference server; quarantine observed on a device, recovery observed through `resume`. The negative control is a healthy payload under the same procedure.<br><br>`tools/reference-server/quarantine-drill.sh`, all five green on a real emulator: the healthy release runs twice and is never refused (`H2-control`, run **first**, because every other assertion here is satisfied by a client that cannot reach the server), two launches of the bad release burn the guard's attempts, the third is refused **and produces no guest output at all** (`H2`, `H2-absent`), the refusal names the last known-good version (`H3`), and `resume` brings the client back on its own (`H2-recovered`). `CrashOnLaunchScreen` throws from the composable body — `CrashScreen` renders and *then* throws from an effect, which the guard correctly counts a success, because quarantining a payload that worked and then broke would strand a fleet for a bug a user might never hit. The drill's first run **failed its own control**: it stamped `version` into an already-signed manifest, so every launch failed signature verification. The signature working exactly as designed, and the reason `-PdogwoodVersion` exists |
| V4 | ✅ **Fixture cadence** — done 2026-09-08 | A rule, not a run: every dictionary-version bump freezes that day's signed payload beside the existing fixtures, so the cross-version window widens as the toolchain moves. Recorded in `docs/checks.md` and in `tools/conformance/fixtures/README.md`, with the freezing command written out. The trigger is mechanical on purpose: "freeze one when something significant changes" is a rule nobody executes, and a dictionary bump is already a deliberate act with a lock file in the same commit. Add, never replace — each fixture widens the window and replacing one narrows it back to a point |

## Order

**C1 → C2 → C3** first (the only score-relevant group, and the first thing any adopter hits), then
**S1** (the largest real safety asymmetry), then **V1–V3** (cheap, each converts a `—`), then
**P1**, **S2–S4**, **V4**. Re-run the grading instrument after C3 and after S1.

## Not in this plan

Everything owner-gated, priced in [`OPEN-DECISIONS.md`](../OPEN-DECISIONS.md):
publishing, keys and hosting, representative hardware (P1 sharpens that decision; only §4 makes
it), the Apple ruling, the merge gate, a public home and a second maintainer. And the three
upstream reports stay drafted and unfiled, as standing instructed.

---

## Deferred, with a decision behind it

| # | Item | Why it is deferred rather than dropped |
|---|---|---|
| D1 | **Per-component binding: let a host register part of a segment** | A host registers a whole dictionary segment or none of it, so every client links every component's implementation whether or not it ever composes one. On the web that is bytes every visitor downloads; the two Material 3 pickers alone are 96,924 of them. Per-component binding would let a profile drop what it does not use.<br><br>**Deferred 2026-09-09 by owner decision** ([ADR-066](../adrs/layer-5/ADR-066-the-pickers-cost-half-a-second.md)): a design system's components cost every client globally, which is what mobile already does, and keeping the two profiles aligned is worth more than the bytes. The `G5` ceiling moves with the catalogue instead, under the attribution rule in `budgets.tsv`.<br><br>**What it would cost if taken up**, so the next reader does not have to rediscover it: it introduces a *third* outcome when a client meets a widget tag — today there is "known, render it" and "never heard of it, placehold and report skew", and this adds "known, deliberately not linked", which is neither. That breaks the pre-flight dictionary check ([ADR-061](../adrs/layer-3/ADR-061-a-payload-declares-the-dictionary-it-needs.md)) as written, because it compares one version number per segment: the numbers would match, the payload would run, and it would draw placeholders — the exact failure that check exists to prevent, arriving through a different door. So it needs finer declarations on both sides, a per-component comparison, a named third skew category, and it makes a new misconfiguration possible one component at a time. Its own plan and its own ADR.<br><br>**And one premise is unverified:** that dead-code elimination actually drops an unreferenced component. Plausible, and this repository has been bitten once by a WebAssembly optimizer pass doing something other than the obvious. Ten minutes of stubbing the two picker implementations and rebuilding would settle it, and that measurement is the first step if this is ever picked up |

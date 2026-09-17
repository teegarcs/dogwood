# The four open items, planned to done

Drafted 2026-09-17. Status: **plan.** These are the items left after
[`plans/close-the-backlog.md`](close-the-backlog.md) merged at `471167e`, excluding the ones that
are the owner's to decide and the ones blocked by platform behaviour. Every fact in §0 was read off
the repository at `471167e`, and the Zipline behaviour in Group 1 was read out of the shipped
bytecode rather than assumed.

The house rule holds: **done means run.** A claim is closed when the drill that would catch its
absence has been watched to fail without the fix.

---

## 0. The inventory

| # | Item | How it was found | Group |
|---|---|---|---|
| 1 | A module request carries no release identity, so with two releases live the server answers it by guessing | `tools/reference-server/cohort-drill.sh:166`; `server.py:311`; the drill watched a pinned client fetch the wrong release's bytes | 1 |
| 2 | Three drills are invoked by no runner: iOS Material, Material pre-flight (`B6`), engine skew (`K3`/`K4`) | the family tables grade them; `run-all.sh` and `tier-c.yml` do not call them | 2 |
| 3 | Part 3's generated matrix is dated 2026-09-14 and carries no `M`, `K3`, `K4`, `B6` or `G6` row | `run-all.sh` calls `aggregate.py` without `--update-plan`, so the local gate never writes back | 3 |
| 4 | `tier-c.yml` and `publish-payload.yml` have never executed | validated by parsing the YAML and by mirroring their commands locally | 4 |

Items 2 and 3 are one defect seen from two ends: a drill nobody runs cannot reach a matrix nobody
regenerates. They are planned separately because the fixes are in different files.

**What is deliberately not here.** iOS `M4`/`M5` and the web `M3-announced`/`M7` skips are blocked
by platform behaviour and are drafted upstream. `G1`–`G4` need the gate device. The colour-bundle
subsystem, grid cell specifications, subcompose layout state and the Android Studio preview pane
are recorded as designed-and-not-taken. The seven upstream reports stay drafted and unfiled on the
owner's standing instruction.

---

## Group 1 — A module address names its bytes

### 1.1 The defect, stated as what a user would see

Two releases are live: `1.0.0-good` for most installations and `1.1.0-bad` pinned to a canary
cohort. A canary device fetches the manifest for `1.1.0-bad`, verifies its signature, and asks for
the module the manifest names. That name is `slice-guest.zipline`, which is the name in **every**
release this project produces. Nothing in the request says which release it belongs to, so the
server answers with the newest bytes it holds. The client compares the SHA-256 against the one its
verified manifest named, they differ, and the load is refused.

The signature caught it, which is the right direction. But the outcome is a device that cannot
start, and it happens to whichever cohort is unlucky. `cohort-drill.sh` has been in this exact
situation and passes anyway, because it grades the manifest half of the claim and stops at
`verified` rather than `updated`.

### 1.2 What the shipped Zipline actually does, read rather than assumed

Three facts decide the design, and each was read out of `zipline-loader-jvm-1.27.0.jar` and
`zipline-jvm-1.27.0.jar` rather than inferred:

- **The module `url` is inside the signed region.** `ZiplineManifest` holds `modules` alongside
  `unsigned`, and `getSignaturePayload()` covers everything but `unsigned`. So a publishing step
  that rewrote module addresses would invalidate every signature it touched.
- **The loader resolves module addresses against the URL it asked for, and nothing else can change
  that.** `HttpFetcher.withBaseUrl` **removes** any `unsigned.baseUrl` the server sent and replaces
  it with the manifest URL the client requested. So the server cannot declare a base, and a redirect
  cannot move one either: `ZiplineHttpClient.download` returns bytes, not a final URL.
- **Signing is public API.** `ManifestSigner.Builder().addEd25519(name, key).build().sign(manifest)`
  and `ZiplineManifest.decodeJson` / `encodeJson` are all public, so a build step can rewrite a
  manifest and re-sign it with the same keys the plugin used.

Together these say the fix belongs **at build time, before signing** — which is the one place the
earlier comment in `server.py` guessed wrong when it said "the fix belongs in publishing".

### 1.3 The decision

**A module file is named for its own content, and the name is written into the manifest before it
is signed.** `slice-guest.zipline` becomes `slice-guest-<first 16 hex of its SHA-256>.zipline`.

Content addressing rather than a per-release path prefix, for three reasons:

- The address is unique **by construction**, with no version to thread through the build. A release
  prefix has to be told the release; a digest already knows.
- `Cache-Control: immutable` becomes true rather than aspirational. Two releases that share an
  unchanged module share its address, so a canary that changes one module re-downloads one module.
  A release prefix re-downloads everything.
- A collision is then impossible rather than warned about. Equal names mean equal bytes.

### 1.4 The work

1. **A Gradle task, shared by every guest that signs a manifest.** After the Zipline compile task
   and in place of the plugin's signing, read `manifest.zipline.json`, rename each module file to
   its content address, set `modules[].url` to the new name, and re-sign with `ManifestSigner` using
   the same two keys the `signingKeys` block holds. The task must be `upToDateWhen { false }` for
   the reason `signWebSidecars` already is: the skew drill swaps files in behind Gradle's back.
   Affected: `samples/slice-guest`, `samples/second-guest`, `samples-standalone/umbra/guest`, and
   `tools/phase0/guest` if it signs.
2. **The reference server keeps a module pool.** `publish` copies the manifest into the release
   directory as now, and modules into a shared `modules/` pool. A module already in the pool is
   asserted byte-equal rather than warned about; unequal bytes at one address is now a hard error,
   because it means something bypassed the addressing. Serving a module reads the pool, and the
   "newest release wins" loop that guessed is deleted along with the comment explaining it.
3. **A new claim, `B7`.** *With two releases live, a client in each cohort loads its own release's
   modules and reaches `updated`.* Graded in `cohort-drill.sh` by advancing the real client past
   `verified`, which is exactly the assertion that comment says is not well posed today.
4. **A test that keeps the premise honest.** A JVM test that mutates a module `url` in a signed
   manifest and asserts the signature no longer verifies. Without it, the next person to read
   "rewrite the addresses at publish time" has nothing telling them why that fails.
5. **The web profile, checked not assumed.** The web guest is signed with detached sidecars over
   `guest-kotlin.js` rather than by a Zipline manifest, so it may or may not have the same
   ambiguity. Establish which by reading `WebDelivery` and the sidecar format, then fix it the same
   way or record why it cannot happen.
6. **ADR-077**, and the corrections: `server.py`'s comment, `cohort-drill.sh`'s, `docs/operating.md`.

### 1.5 Done means

`cohort-drill.sh` grades `B7` PASS with two releases live, and grades it FAIL when the
content-addressing task is disabled. That second half is the gate being watched to fail.

---

## Group 2 — Every drill has a runner

Three drills grade claims that the family tables in `plans/conformance.md` already show as met, and
no runner invokes them. Their only evidence is a hand run in a session that is over.

| Drill | Claims | In `run-all.sh` | In `tier-c.yml` |
|---|---|---|---|
| `tools/a11y-drill/run-material.sh` | iOS `M1`–`M7` | no | yes |
| `tools/skew-drill/run-material-preflight.sh` | `B6` on Android and iOS | no | no |
| `tools/conformance/engine-skew.sh` | `K3`, `K4` | no | no |

Android's Material claims are **not** in this table, and the reason is worth recording so nobody
adds them twice: `run-android.sh` runs `connectedReleaseAndroidTest`, which runs every instrumented
class in `slice-android`, and `MaterialConformanceTest` is one of them. Web's are not either:
`run-web.sh` calls `web_material.py` as its third grader.

**The work.** Add the three to `run-all.sh` in the sections they belong to, each refusing rather
than failing when its prerequisite is missing, which is that script's standing rule. Add the
pre-flight drill to `tier-c.yml`'s Android job and the engine-skew drill to the matrix job, which
needs no device and only a git worktree.

**Done means** a full `run-all.sh` produces `M`, `B6`, `K3` and `K4` rows for the clients that
grade them, and `docs/checks.md` lists every drill with the runner that invokes it.

---

## Group 3 — The matrix writes itself back

`aggregate.py` has taken `--update-plan` since it was written, and `tier-c.yml` passes it. The local
gate never has, so Part 3 is whatever a person last remembered to paste. It is dated 2026-09-14 and
describes a smaller project than the one in the repository.

**The work.** `run-all.sh` passes `--update-plan plans/conformance.md`, so the run that produced the
results is the run that writes them down. A partial run must not write a matrix full of gaps, so
the write-back is conditional on the run having graded every client it could reach, and the banner
above the table says which machine and which commit produced it.

**Tier C still does not commit.** It uploads the regenerated matrix and puts it in the job summary.
Giving a nightly workflow write access to `main` to keep a table fresh is a larger grant than the
problem needs, and with the local gate writing back, the table is refreshed by the person who ran
the gate. This is a decision taken here, not an item deferred.

**Done means** Part 3 is regenerated by a full local run and committed, and its banner names
`471167e` or later.

---

## Group 4 — The two workflows run for real

Neither `tier-c.yml` nor `publish-payload.yml` has ever executed. They were validated by parsing the
YAML and by running the same commands locally, which proves the commands and not the workflow: the
runner images, the caches, the emulator action, the secret handling and the artifact upload are all
untested.

**The work.** Dispatch `tier-c` on `main` and read it end to end; a nightly job that fails at step
three has been a lie in `docs/checks.md` since it was written. Then dispatch `publish-payload` with
`dry_run: true`, which builds, signs with the committed throwaway keys, runs `publish-check.sh` and
uploads a bundle without publishing anything. Fix what breaks, re-dispatch, repeat until both are
green, then record the run identifiers so the next reader can see them.

**Done means** a green run of each, linked from `docs/checks.md`, and every claim `tier-c`
contributed present in the matrix.

---

## Order

Group 4's first dispatch goes out before anything else, because a forty-minute device run should be
failing while other work happens rather than after it. Groups 2 and 3 land next: they are small, and
they are what makes Group 1's new claim visible in the matrix when it arrives. Group 1 is last and
largest, and its own drill grades it.

---

## What landed, 2026-09-17

Filled in as each group closed, including the parts where the plan above was wrong. The house rule
is that a plan is a prediction and the record is what happened.

### Group 1 — the module address

| What the plan said | What happened |
|---|---|
| The fix belongs at build time, before signing | Held. `SignatureTest.movingTheModuleAddressIsRejected` is the assertion behind it. |
| Content-address the module and re-sign | Done, as a `doLast` on the Zipline task so every existing `dependsOn` gets the right bytes. |
| `publish` asserts equality instead of warning | Went further: it **refuses**, because a warning is what `quarantine-drill.sh` used to publish straight past. |
| `B7` graded in `cohort-drill.sh` | Done, and it **passed hollow on its first run**. See below. |

**Three things the plan did not predict.**

1. **`B7` was meaningless when first written.** `-PdogwoodVersion` reaches the manifest and not the
   bundle, so the drill's two releases had byte-identical modules and therefore one shared content
   address. `B7` passed while exercising nothing. It was caught by `B7-distinct`, added at the same
   time out of the same suspicion, which failed printing the same digest twice. The canary is now
   built from genuinely different source. The lesson is the one AGENTS.md §1.5 states from the other
   direction: a claim that passes is also a hypothesis.
2. **Five skew drills fetched the module by its old literal name.** They poll `:8080` for the
   rebuilt payload, and a literal address would have 404'd forever while the drill reported "the
   skewed payload never reached the server" — a product failure that did not happen. They read the
   address out of the manifest now.
3. **The local gate has never run the publish check.** `run-all.sh` passed a positional output path
   to a script that takes named arguments, so `publish-check.sh` exited 64 with "unknown argument"
   into `/dev/null`, and `P1`–`P7` were graded nowhere. Found while reading the script's own
   argument parser to wire it up. Fixing the invocation was not enough: the payload has to be
   **rebuilt at the version being checked**, or `P1` asserts that a number equals itself.

**Watched to fail.** With the content-addressing step commented out, `publish` refuses both releases
by name and the drill stops. The first time that was watched, the drill blamed "the reference server
never answered on :8475" — the symptom three steps downstream — because `build` ignored `publish`'s
exit status. It no longer does.

**Not applied to `samples-standalone/umbra` or `tools/phase0/guest`**, with a reason rather than an
omission: umbra is a standalone build that deliberately shares no files with `engine/`, and phase0 is
a benchmark harness. Neither is ever served by a server holding two releases. The right long-term
home for the step is the published `io.github.teegarcs.dogwood.guest` plugin, where a product would
get it by applying the plugin rather than by copying a script.

### Group 2 — every drill has a runner

The three orphans are wired: the iOS Material drill, the Material pre-flight drill and the engine
skew drill. Engine skew became **its own nightly job** rather than living in the matrix job, which
was about to turn a fifteen-minute report into an hour-long one.

The nine per-copy `CONF RESULT` guards were replaced by **one sweep per job**, which also reaches
`android-a11y.conf` and `web-a11y.conf` that no per-copy guard could. It is loud rather than tidy: a
dropped result file means a drill died, and the run says so in the job output and the summary.

### Group 3 — the matrix writes itself back

`run-all.sh` passes `--update-plan`, guarded so a partial run cannot overwrite a fuller table, with
`SKIP_MATRIX_WRITE=1` and `FORCE_MATRIX_WRITE=1` as the explicit escapes.

**Tier C still does not commit the matrix, and that is a decision taken here rather than deferred.**
Giving a nightly workflow write access to `main` to keep a table fresh is a larger grant than the
problem needs. With the local gate writing back, the table is refreshed by the person who ran it.

### Group 4 — the two workflows run for real

**`publish-payload.yml` is green on its first ever execution**, run 35175358230 on this branch with
`dry_run: true` and version `0.1.1-dryrun`. All seven claims pass, including `P5` (brotli, which the
workflow installs and this development machine cannot) and `P4` showing
`slice-guest-de2a545af99f06ba.zipline` — Group 1's content address, graded in continuous
integration rather than only here. The secret-handling step took the dry-run branch and said so.

Nothing in that workflow needed fixing, which is worth recording as much as a failure would be: the
steps are thin wrappers around commands a person runs, and that discipline is what made a
first-ever run boring.

### What was re-verified here, after Group 1 changed the address

Every drill that fetches a payload had to be re-run, because Group 1 changed the name of the file
they fetch. All on this machine, 2026-09-17:

| Drill | Result |
|---|---|
| `tools/reference-server/check.sh` | 11 of 11, including the new refusal control |
| `tools/reference-server/cohort-drill.sh` | 10 of 10, `B7` and its two companions included |
| `tools/reference-server/rotation-drill.sh` | pass; two releases whose modules are byte-identical share one pool address, which is the intended behaviour and not a collision |
| `tools/reference-server/publish-check.sh` | 6 of 7; `P5` fails for want of the Python `brotli` module on this machine, which the nightly workflow installs and where it passes |
| `tools/skew-drill/run-desktop.sh` | 5 of 5, including the wait loop that now reads the module address out of the manifest |
| `tools/conformance/run-android.sh` | 27 of 27 on an emulator, the whole `M` family included |

`check.sh` failed once on the way, on its real-client leg, while an Android instrumented test was
running on the same machine. Re-run on a quiet machine it passes. Recorded because the first reading
of that failure was "the pool broke the desktop client", which it was not.

### Group 1, item 5 — the web profile had the same defect

The plan asked whether it did, and said to fix it or record why it could not happen. It can, and it
was **reproduced before it was fixed**: one sidecar address answering with different content per
cohort, two releases both naming `guest-kotlin.js`, and headless Chrome reporting `IntegrityRefused`
against a control that started cleanly. ADR-078 is the decision; `signWebSidecars` renames the script
for its own digest and writes that address into every sidecar before signing, for the same reason
the Zipline path had to — claim `B1` already proves a client refuses a sidecar whose `guestScript`
was rewritten after signing.

The integrity fixture keeps its deliberately wrong digest and gains the right address, because a
fixture refused for a 404 grades nothing. New claims `B8`, `B8-distinct` and `B8-canary`, watched to
fail with the addressing disabled — where `B8-canary` passes, which is the defect in one line: the
cohort that keeps working is whichever release published last.

### Two drills were passing for the wrong reason, and both were found on the way

Neither was in the plan. Both are the same shape as `B7`'s hollow pass, which is why they are
recorded together.

1. **`B3` on web was graded by the signature check, not the dictionary check.** The skew drill wrote
   its skewed sidecar *after* the only signing pass, so the page refused it for a missing signature
   — which satisfies `B3`'s "no Worker was created". The repository has the receipt:
   `result-web-2026-09-08.conf` says `refused=DictionarySkew`, and both
   `result-web-2026-09-09.conf` and `result-web-2026-09-14.conf` say `refused=SignatureRefused`. For
   two recorded runs the dictionary claim was passing on the wrong evidence. The drill re-signs that
   sidecar now and `B3` reads `DictionarySkew` again.
2. **`M3` on web searched for *unnamed* control nodes.** It was written when Compose published
   Material 3's selection controls with no name at all. The catalogue then gave each control a
   `contentDescription` — recorded in `plans/material3-proof.md` §5 at the time as the fix for a real
   user as well as for the drill — and nobody came back to the lookup. So the claim failed with "0
   unnamed controls on the section", a red cell reporting that the catalogue had been **fixed**. It
   finds the controls by name now, the way the Android drill always has, and grades three of them
   instead of two. The `M3-announced` skip is narrower and more useful as a result: these publish a
   name but still no role and no checked state, so a user hears "Background refresh, button" where
   they should hear "Background refresh, switch, on". The upstream draft says that now.

The general lesson, which is AGENTS.md §1.5 read from the other end: **a claim that passes is also a
hypothesis.** Three of them were wrong in one day, and each was caught by asking what the drill was
actually looking at rather than whether it was green.

### Group 3's last step is a machine constraint, and the answer is the mechanism already built

The write-back is implemented and rehearsed: fed the four committed runs, it regenerates Part 3
byte-identical to the committed table apart from the provenance banner, and the guard refuses a
partial set by name. What has not happened is a **full local run** to regenerate it from today's
evidence, and the reason is memory rather than correctness.

The gate needs a booted simulator, an attached emulator, Chrome, a served payload and Gradle at
once. On this machine that is over the ceiling: the run was killed twice in the Android section,
with the emulator alone holding a gigabyte and roughly three free. `docs/checks.md` and the machine
notes already record `--max-workers=2` for the same reason, and this is the same wall one step
further out.

**This is what `tier-c.yml` is for**, and it is the shape Group 2 just gave it: one job per device
family on its own runner, artifacts collected, one fold. So the matrix comes from a nightly run
against this branch, downloaded and committed by a person — which is exactly the arrangement Group 3
chose when it decided the workflow must not commit to `main` itself. The decision and the constraint
point the same way, which is the only reason this is a sequencing note rather than an open item.

Individually, every claim was re-verified here against the new addresses; the table above lists each
drill and its result. What the nightly adds is one matrix over all four clients at one commit.

### Group 4, second run: the plumbing holds and the findings keep coming

Dispatched against this branch after the first run's four fixes. Run 35179177548.

**What was fixed and stayed fixed.** Every Android step succeeds and the job produces results; only
the verdict fails, which is the verdict working. `J1`, `M3` and `M5` are gone from the failure list.
The `engine-skew` job passed on a hosted ubuntu runner, which settles the one thing its author said
was unprovable from a development machine: Skiko does render under Xvfb.

**Four more findings, none of them guessed at.**

1. **Six graded iOS claims were being thrown away.** The Material drill cannot always finish —
   activating a Material 3 overlay blocks the application, which is the recorded limit behind `M4`
   and `M5` — so it never reaches its own result line. The fold step correctly drops a file that
   never named its client, and the run said so: `dropped ios-material.conf: 6 claim lines and no
   CONF RESULT line`. Those six were graded and watched; a gap says nobody looked. The harness
   synthesises the line now, the way `run-android.sh` already did, appended to the log because the
   log is what every collector reads.
2. **`M2` on iOS failed because it is the drill's first activation, and only for that reason.**
   `M3`, `M6` and `M7` all passed three seconds later. Tripling the patience moved it from `[20s]`
   to `[50s]` and changed nothing, which is the evidence that it was never slowness: a tap that
   landed before the guest was listening is already gone, and waiting does not bring it back. It
   activates again now, up to three rounds, and the witness decides rather than
   `accessibilityActivate` reporting that the platform delivered it — a proxy, and exactly the kind
   §1.5 warns about.
3. **`D1` on web read the tree once after a fixed sleep** and found only the page title, while `D2`
   through `D5` passed on the very next reads. A first frame is not an accessibility tree; Compose
   publishes that separately and afterwards. It waits for content now, which also returns sooner
   than the old sleep on a quick machine.
4. **The Android drills read empty screens on a hosted emulator**, one of them behind a "Pixel
   Launcher isn't responding" system dialog. They wait for a populated screen rather than sleeping
   eighteen seconds, the instrumented tests take the patience multiplier as an instrumentation
   argument, and a failed drill now captures the screen, the activity stack and the log — because
   the first run left nobody able to tell whether the application had crashed, never drawn, or drawn
   something else.

**The pattern across both runs.** Of the nine distinct problems these two dispatches surfaced, one
was a workflow that had never worked, two were drills passing on the wrong evidence, three were
assertions that encoded a machine rather than a behaviour, and three were real races. None of them
were visible from reading, and the workflow had been "validated" by mirroring its commands locally.
That is the argument for Group 4 in one paragraph.

### Group 4, third run: Android green, web green, two iOS findings left

Run 35206208331. **The Android job passes**, having produced nothing at all on the first run and
three red drills on the second. `engine-skew` passes. **Every web drill passes**, including the
accessibility one whose `D1` had been reading the tree before Compose published it.

The merge gate is green on the same commit: tier S and the web skew job both pass.

Two iOS findings, and each is a variant of one already fixed elsewhere that day.

1. **`J1` on iOS is the web's time-zone bug, in Kotlin.** `[host clock 1789640592534, time zone
   GMT]` — both values crossed the boundary, and the claim rejected one of them for how it was
   spelt, because it required a `/`. A hosted runner's clock is UTC and this platform answers `GMT`.
   The same assertion, the same proxy and the same correction as the web client, found on the same
   day by the same runner.
2. **`M2` on iOS was never a dead button.** Three activations and fifty seconds in, with
   `section=true, activated=true`, the witness read `null` while `M3`, `M6` and `M7` passed two
   seconds later. `reach` scrolls the view to bring its target on screen, and on a shorter viewport
   that pushes the witness line off the bottom; `witnessOf` reads the current viewport only, so it
   answered `null` forever. The payload had responded. The drill was looking at the wrong part of
   the screen, and reported that as a product failure.

   The Android drill's counterpart has scrolled to find its witness since it was written. The iOS
   one now does too. Note what the earlier fix did here: adding a retry to `M2` was right for the
   general case and did nothing for this one, and the *shape* of its failure after the retry —
   three activations, none observed — is what pointed at the reading rather than the writing.

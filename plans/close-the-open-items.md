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

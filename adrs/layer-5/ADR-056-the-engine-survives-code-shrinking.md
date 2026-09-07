# ADR-056: The engine survives code shrinking, watched rather than hoped

**Date:** 2026-09-07
**Status:** Accepted

## 1. Context & Problem Statement

The adoption audit ([`plans/adoption-audit.md`](../../plans/adoption-audit.md) finding A1) observed
that **no build in this repository had ever run under R8** — no `consumerProguardFiles`, no rules
file, no sample with `isMinifyEnabled`. Production Android applications ship shrunk, and this stack
is the shape a shrinker plausibly breaks: Zipline binds services through generated adapters, and
`kotlinx.serialization` through generated serializers, either of which a shrinker strips when
nothing keeps it — with "renders nothing, says nothing" as the likely first symptom.

## 2. Decision

**The Android sample's release build shrinks, and the instrumented conformance drills run against
that build — permanently.** `testBuildType = "release"` points `connectedAndroidTest` at the
minified APK, so every Android cell in the conformance matrix is graded against what a user would
actually install, on every local run. Continuous integration gets the build-time half for free:
`./gradlew build` now assembles the shrunk release, so an R8 configuration error fails every pull
request.

**No consumer rules ship in Dogwood's artifacts, and that absence is a verified finding rather than
an omission.** The engine's reflective surfaces are its dependencies' — Zipline's adapters,
kotlinx-serialization's serializers — and those libraries carry their own consumer rules. Dogwood's
own code has nothing reflective. The first minified run rendered the Diagnostics screen, crossed a
service round trip, and recorded skew, with **zero** Dogwood-specific rules:
24 MB → 5.3 MB, a 278 000-line mapping, and `SkewReport(routes=[experience/nowhere])` on screen.

## 3. Rationale & Research

**Everything that did break was the test harness meeting a shrunk app, and each fix is documented
at the rule that makes it** (`samples/slice-android/proguard-rules.pro`, `proguard-test.pro`). The
sequence, one failure per run, none of them an engine defect:

1. **ErrorProne annotations** — compile-only classes androidx.test references; `-dontwarn` in the
   *test* APK's rules, a statement of fact.
2. **`androidx.tracing.Trace`** — a circular hole: the Android Gradle Plugin excludes it from the
   test APK because the app's dependency graph provides it, and R8 strips it from the app because
   no app code reaches it. Each half correct, the composition broken. Kept in the app; adding it
   to the test APK instead was **watched to be silently dropped**, not assumed.
3. **Kotlin stdlib facades** (`LazyKt`, then `TextStreamsKt`) — the test APK carries no stdlib of
   its own and resolves the app's by name, while R8 inlines the app's call sites and deletes the
   facades. A facade-per-round tail with no principled end; one `-keep class kotlin.**` ends it,
   and masks nothing under `dev.dogwood.**`.
4. **The same tail in `kotlinx.coroutines`** (`runBlocking` from the network drill).
5. **Engine API the drill uses and the app does not** — a facade (`PlatformServicesKt`), then
   default-argument constructor overloads (`OkHttpNetwork`, `HttpRequest`), then data-class
   accessors (`HttpResponse.getBody`). Resolved as three graded keeps: one named facade,
   constructors-only across `dev.dogwood.**`, and the wire types of `dev.dogwood.protocol.**`
   whole — because the drill treats them as *data*, and member-stripping of a plain accessor the
   consumer never reads is R8 working correctly, not the defect class this build drills for.
6. **Renaming is off in the drilled build** (`-dontobfuscate`), because the test APK compiles
   against original names and trusts the app to provide them — obfuscation fails the harness in
   `AndroidJUnitRunner.onCreate`, before a single test runs. Shrinking and optimization — the
   halves that eat serializers and adapters — stay on, which is what A1 is about. A product
   obfuscating its release tests the same way: shrink in the tested build, rename in the shipped
   one.

**Why the drills-against-release design instead of a one-off smoke check:** a smoke check decays;
a test target does not. Every future claim added to the Android column is automatically a claim
about the shrunk build, and a regression in any keep rule fails a drill rather than a note.

**Evidence:** `CONF RESULT client=android passed=19 failed=0 skipped=0`, all against the minified
APK; full engine build green.

## 4. Unstated Assumptions

- **The keep rules in the sample are for the drill harness, not for adopters.** An adopter enabling
  R8 needs *none of them* — that is the finding. Copying `proguard-rules.pro` into a product would
  keep the stdlib whole for no reason.
- **Obfuscated builds are exercised by nothing.** The drilled build shrinks and optimizes without
  renaming; a product that also renames is trusting the same consumer rules to survive renaming,
  which is those libraries' documented contract and not re-verified here.
- **Resource shrinking (`isShrinkResources`) rode along untested in any targeted way** beyond the
  drills passing; the sample has few resources.

## 5. Updated Documents

- [`plans/adoption-audit.md`](../../plans/adoption-audit.md) — A1 closed.
- [`engine/samples/slice-android/`](../../engine/samples/slice-android/) — the release build type,
  `testBuildType`, and the two annotated rules files.
- [`tools/conformance/run-android.sh`](../../tools/conformance/run-android.sh) — runs
  `connectedReleaseAndroidTest`.
- [`plans/conformance.md`](../../plans/conformance.md) and [`docs/checks.md`](../../docs/checks.md)
  — the Android column is graded against the minified build.
- [`docs/getting-started.md`](../../docs/getting-started.md) — adopter guidance: enable shrinking;
  no Dogwood-specific rules are required.

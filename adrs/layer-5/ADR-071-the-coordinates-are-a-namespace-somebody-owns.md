# ADR-071: The coordinates are a namespace somebody owns

**Date:** 2026-09-14
**Status:** Accepted

## 1. Context & Problem Statement

Every artifact this project publishes has been coordinated `dev.dogwood:<module>:0.1.0` since
[ADR-047](ADR-047-the-generator-ships-as-a-plugin.md) made the generator consumable, and the two
Gradle plugins have been `dev.dogwood.codegen` and `dev.dogwood.guest`. That group was chosen
because it reads well. Nobody owns it.

Maven Central verifies namespace ownership before it will accept a publication. `dev.dogwood`
requires registering and verifying the domain `dogwood.dev`, which nobody has bought. So the
coordinates in every build file, in `docs/getting-started.md`, and in the worked example were
coordinates that could not be published under, and the situation was survivable only because the
repository was private and the sole consumer resolved from `mavenLocal()`.

Making the repository public ended that. `docs/getting-started.md` tells a reader to depend on
`dogwood-host:0.1.0`; a stranger following it resolves nothing. The choice had to be taken before
anyone depended on the old coordinates, because a group rename after that point breaks every
consumer's build rather than nobody's.

## 2. Decision

**The group is `io.github.teegarcs`, and the plugin identifiers move under it:
`io.github.teegarcs.dogwood.codegen` and `io.github.teegarcs.dogwood.guest`.**

Maven Central grants the `io.github.<user>` namespace on GitHub identity alone, with no domain to
buy, no renewal, and no second thing to keep paying for. A Gradle plugin identifier is also a
namespace claim — the marker artifact's group *is* the plugin identifier — so the plugins move with
the group rather than being left in a namespace the project cannot publish to either.

**Three things deliberately do not change.**

- **The artifact identifiers.** `dogwood-host`, `dogwood-compose`, `dogwood-wire`,
  `dogwood-protocol`, `dogwood-web`, `dogwood-codegen` keep their names, so every platform variant
  a build resolves — `dogwood-host-android`, `dogwood-web-wasm-js` — keeps its name too, and the
  standalone check's variant assertions needed only their group updated.
- **The Kotlin package names.** Everything stays in `dev.dogwood.*`. A package name is not a
  coordinate: it is what an adopter's source imports, it appears in the generated bindings, the
  ProGuard rules, the Android namespaces and the iOS bundle identifiers, and renaming it would be a
  large mechanical change to buy nothing. Publishing `io.github.teegarcs:dogwood-host` containing
  `dev.dogwood.host` is ordinary and legal. It looks inconsistent for about ten seconds, which is
  why this paragraph exists.
- **The project's name.** Dogwood is still Dogwood. The group names the account that publishes it,
  not the thing.

**The version stays `0.1.0` and still promises nothing.** Renaming the group is exactly the kind of
change `0.1.0` exists to permit, and doing it now rather than later is the point.

## 3. Rationale & Research

**Why not buy the domain.** `dogwood.dev` is a recurring cost, a renewal somebody must remember,
and a second credential to hold, in exchange for coordinates that read slightly better. For a
project with one maintainer and no production consumer, the identity Central already trusts is the
one that costs nothing to keep. If the project ever acquires an organisation and a domain, moving
*to* `dev.dogwood` later is the same mechanical change as this one and can be taken then, with the
same rule: before anyone depends on it.

**Why the plugin identifiers had to move too.** A `plugins { id("…") }` request resolves a marker
artifact whose group is the identifier itself. Leaving the plugins as `dev.dogwood.codegen` would
have meant publishing a marker under `dev.dogwood` — the namespace this decision exists to stop
using. The Gradle Plugin Portal applies the same ownership reasoning.

**What the rename actually cost, measured.** Thirty-five files, all of them build scripts,
documents and one shell script; no Kotlin source but comments. Artifact identifiers were never
derived from the group, so the variant names the standalone check asserts on are unchanged.

**And one thing that was expected to be free and was not.** A Maven group is not only a coordinate:
a Kotlin klib records `unique_name=<group>:<project>` in its manifest, and *that* is what `internal`
declarations are name-mangled against. Moving the group re-mangles every internal in every
Kotlin/JavaScript and Kotlin/WebAssembly module. That is harmless on its own — the whole module
re-mangles consistently and a clean build passes — but **Gradle's build cache does not notice**.
The Kotlin/JavaScript compile task's cache key does not capture `unique_name`, so the first build
after the change restored cached output mangled under the old group, put it beside freshly compiled
callers, and twenty-two `dogwood-compose` tests died on
`TypeError: _this__u8e3s4.then_vuiwuj_k$ is not a function` — the exact symptom
[ADR-047](ADR-047-the-generator-ships-as-a-plugin.md) records for a different cause.

The sequence that established it, because the first two answers were both wrong:

| Attempt | Result |
|---|---|
| Clean the module's `build/` and rebuild | still 22 failures — so "stale output", the obvious answer, was wrong |
| Revert only the group, clean, rebuild | passes — so the group is the cause, and it is not a coincidence |
| Pin `compilerOptions.moduleName` | `unique_name` unchanged; the option does not reach it, and the line was removed rather than left inert |
| `--no-build-cache` | passes |

**`outputModuleName` does not protect this, and the comment in `dogwood-compose` that said it
would has been corrected in place.** That pin covers the emitted JavaScript module's *name*, which
is what ADR-047's loader failure needed; it does not reach the klib's `unique_name`. Two different
names, one symptom, and believing the pin covered both is what made this take four attempts instead
of one.

**What an adopter needs to know:** none of this reaches a consumer. The published artifacts are
internally consistent, and `tools/standalone-check/run.sh` built Umbra against them from a separate
build with the old group deleted from the local repository first. It is a build-cache hazard for
anyone working *in* this repository who pulls a group change onto a warm cache, and the fix is one
`--no-build-cache` run.

**Verified by consuming it, not by reading it.** `tools/standalone-check/run.sh` publishes the new
coordinates to a local repository and builds `samples-standalone/umbra` against them with no path
into this repository: the plugin resolved by its new identifier, the generator as a dependency, the
runtime as artifacts, an Android build resolving `io.github.teegarcs:dogwood-host-android`, a
WebAssembly build resolving `io.github.teegarcs:dogwood-web-wasm-js`, a signed payload rendered in
a desktop host, a Worker bundle, and a linked iOS framework. A green build would not have been
evidence; a resolved variant and a render are.

## 4. Unstated Assumptions

- **Assumes `teegarcs` remains the publishing identity.** The namespace is tied to that GitHub
  account. If the project moves to an organisation, the group moves with it, and the rule above
  applies again: before anyone depends on it.
- **Assumes nobody has already depended on `dev.dogwood`.** True at the time of writing: nothing
  was ever published to any repository beyond a developer's own machine. This assumption expires
  the moment the first real publication happens, which is the whole reason the decision was taken
  today rather than after.
- **Assumes a package name and a coordinate may honestly disagree.** They may, and the split is
  deliberate; anyone who finds it surprising should find §2's third bullet before they find the
  build files.
- **Assumes Central's namespace-ownership check still works on GitHub identity.** It is the
  documented path for `io.github.*`, and nothing here has exercised it yet — §5 of
  [`OPEN-DECISIONS.md`](../../OPEN-DECISIONS.md) records the account work that remains.

## 5. Updated Documents

- [`OPEN-DECISIONS.md`](../../OPEN-DECISIONS.md) §5 — the coordinates are decided; the deployment
  is not.
- [`docs/getting-started.md`](../../docs/getting-started.md) — the coordinates a reader copies.
- [`developer-experience.md`](../../developer-experience.md) §4b — the build file a product writes.
- [`docs/checks.md`](../../docs/checks.md) — the standalone check's resolved-variant assertions.
- [ADR-047](ADR-047-the-generator-ships-as-a-plugin.md) — annotated: the reasoning stands, the
  group moved.
- [`adrs/README.md`](../README.md) — index entry.

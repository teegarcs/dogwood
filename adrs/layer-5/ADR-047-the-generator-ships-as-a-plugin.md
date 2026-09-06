# ADR-047: The Generator Ships as a Gradle Plugin, and Consumption Is Proved From Outside

**Date:** 2026-09-06
**Status:** Accepted

## 1. Context & Problem Statement

[ADR-046](ADR-046-a-product-registers-its-own-segment.md) made a product's own dictionary segment
work, and proved it with `engine/samples/product-design-system` — Acme's components, in a package
nothing in `dev.dogwood` knows about, rendering on a device.

Its own §4 named what that did not prove:

> The generator is still an internal Gradle project. Acme consumes it as
> `project(":dogwood-codegen")`, which a real product cannot do.

A sibling project resolves by path, sees internal source sets, and needs nothing published. Every
real consumer resolves from a repository. So the sample demonstrated the *mechanism* and left the
*claim* — that a product can use this — untested.

There was a second thing in the way, smaller-sounding and more real: **fourteen command-line
arguments.** A product had to know that a lock path goes in `--lock`, that `--wire-out` must be
**omitted** or the engine's version vector is silently overwritten, and which of three package
options means which half of the boundary. None of that is a decision a product should make. Two
things are: what the segment is called, and which identifier it owns.

## 2. Decision

**The generator ships as a Gradle plugin, and a separate build proves it.**

```kotlin
plugins { id("dev.dogwood.codegen") version "0.1.0" }

dogwood {
  segment("umbraDesignSystem") {
    wireName.set("umbra.designsystem")
    segmentId.set(3)          // permanent, and the only decision that is
    version.set(1)
    guestPackage.set("dev.umbra.guest")
    hostPackage.set("dev.umbra.design")
  }
}
```

Everything else is derived: output paths, the dictionary path, the lock path — beside the surface,
committed, exactly as Dogwood's own — and `--wire-out` is not passed at all, because a product
segment has no business writing the engine's version vector.

**The command-line entry point stays**, and both consumers are real: the engine's own build invokes
it directly, because it is one project away and has no need of a plugin; a product applies the
plugin, because fourteen arguments are not an interface.

**The generated host bindings are attached to the project's compilations; the guest stubs are
not.** They belong to a different artifact — a Kotlin/JavaScript library the guest depends on — and
a plugin that guessed which compilation wanted them would be wrong for every product that keeps its
guest in its own module, which is all of them.

**`dogwood-wire`, `dogwood-protocol`, `dogwood-compose`, `dogwood-host` and `dogwood-codegen` are
published** under `dev.dogwood` at `0.1.0`. Where the artifacts actually go is a deployment
decision and is tracked in `DECISIONS-FOR-THE-OWNER.md`; `publishToMavenLocal` is what the proof
below consumes.

## 3. Rationale & Research

**Why a separate build is the only proof that counts.** `samples-standalone/umbra` has no
`includeBuild`, no project dependency and no path into `engine/`. It has its own settings file, its
own repository declarations, and resolves the plugin by identifier, the generator as a dependency,
and the runtime as artifacts. `tools/standalone-check/run.sh` publishes and builds it, and asserts
four things a green build does not imply — that host bindings were generated, that guest stubs were,
that the binding **compiled**, and that a lock was written beside the surface. A product's own
implementations compile perfectly well whether or not the bindings exist, so "it built" is not the
same as "it worked".

### Publishing broke the browser tests, and the cause is worth writing down

Adding `group = "dev.dogwood"` to `dogwood-compose` — three words, for publishing coordinates —
made every browser test in that module die at runtime:

```
TypeError: _this__u8e3s4.then_babg2s_k$ is not a function
```

That is `Modifier.then`, whose second overload is `internal`. **The Kotlin/JavaScript module name
defaults to something derived from the project's `group`, and `internal` declarations are
name-mangled against it** — so a publishing coordinate renamed the module, and the new name
contained a **dot**.

Two things about finding it are worth keeping:

- **A clean build did not fix it**, which is what said it was a naming problem rather than a stale
  one. That distinction was the whole diagnosis; without it the next step would have been to keep
  cleaning.
- **It was found by reverting to a clean tree and re-running**, per `AGENTS.md` §1.5 — the failure
  was not pre-existing, so it was mine, and the bisection was between a build-file change and three
  source changes rather than a guess about which looked more dangerous.

The fix is `outputModuleName`, which makes the emitted module independent of where the artifact is
published — a relationship that should have held all along. `dogwood-wire` and `dogwood-protocol`
were pinned too: they had not been bitten, and the hazard is identical.

**Publishing also found a build defect that had never been reachable.** `dogwood-host` and
`dogwood-wire` add another project's generated directory as a source directory, with `dependsOn` on
the compilations. That was enough until publishing asked for a **sources jar**, which *packages* the
directory rather than compiling it — and Gradle refuses a task that reads another task's output
without declaring it, which is the right refusal. Adding the jars to the `dependsOn` list would have
fixed that one and left the next consumer to find the same wall; the source directory is now wired
through its producing task, so every consumer inherits the dependency.

## 4. Unstated Assumptions

- **`0.1.0` is not a stability promise.** Nothing here is API-frozen, and the dictionary lock
  governs *tags*, not Kotlin signatures.
- **The standalone proof is a Kotlin/JVM product.** It exercises the plugin, the generator, the
  published runtime and the generated bindings compiling against them. It does not exercise Android
  or iOS consumption, and the multiplatform publications those need are published but untried.
- **The proof does not render anything.** It compiles a segment; `product-design-system` is what
  renders one, on a device. Neither sample does both, and the pair is the argument.
- **`mavenLocal()` is a developer's repository, not a deployment.** Where these artifacts actually
  go, and under whose account, is a decision nobody here can take.
- **The in-repository sample still uses the command-line entry point.** Applying the plugin by
  identifier inside the build that *defines* it needs a composite build or a prior publish, and
  either would make the engine's build depend on its own artifacts. That is the right trade for a
  repository and the wrong one to document as the way to consume Dogwood.

## 5. Updated Documents

- [`developer-experience.md`](../../developer-experience.md) — the plugin, in the section on adding
  your own components.
- [`plans/production-readiness.md`](../../plans/production-readiness.md) — Part 1 and the order.
- [`docs/checks.md`](../../docs/checks.md) — the standalone check.
- [`adrs/README.md`](../README.md) — index entry.
